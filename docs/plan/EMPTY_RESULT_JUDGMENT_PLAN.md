# 空结果误判"数据缺失"修复计划

## 背景

用户验收发现（incidentId=25，MG01 服务）：LLM 在故障发生时间窗口查最慢 10 个调用返回 0 条、**未补查基线窗口**的情况下，判断逻辑直接输出"[调用链] 调用链数据缺失，无法评估业务影响"。

按兜底手册（`Fallback.md` 双窗口策略）的正确行为是：

- 告警窗口查空 → **必须先用基线窗口（故障发生前 30 分钟）补查**；
- 双窗口均空 → 才可记"数据缺失，不下结论"；
- 单窗口查空 → 最多只能写"未发现慢调用"，禁止写"数据缺失"。

排查结论（问题不在展示层，`ToolCallSummarizer` 对 0 条已正确显示"未查询到慢调用"）：

1. **`AnalysisPrompts.SYSTEM_PROMPT`「可观测数据缺失」条款混淆了两个概念**：把"查询窗口内指标/日志/调用链均为空"直接等同"数据缺失"，没有区分"查询成功但 total=0"与"数据缺失"，也没有要求先补查基线窗口。模型拿到一次空结果即可援引该条款下"XX数据缺失"结论，与手册双窗口策略冲突。
2. **`HighMemoryUsage.md` 第 3 节本身有硬伤**：让模型调用不存在的 `getTrace` 工具（注册表只有 `getSlowSpans`）且"无参数"；第 1 条"窗口内无 trace → 记调用链缺失"会先于 2.1 的基线对比命中，**与双窗口策略直接矛盾**。
3. **语义缺口（用户拍板）**：慢调用查空 ≠ 调用链数据缺失——`getSlowSpans` 查的是慢调用 top-N，查空只能写"未发现慢调用"；判定"调用链缺失"需要 trace 总量手段（**双窗口 span 总数均为 0 才是缺失**），而注册表目前没有能查 span 总数的工具。
4. **后端无硬约束**：工具返回空结果时，回填给模型的结果文本没有任何提示。执行器已有向工具结果消息附加系统提示的成熟机制（`DUPLICATE_CALL_HINT`、止损 `STOP_LOSS_HINT`，见 `buildToolResultContent`），可以复用。

## 目标 / 不做

**目标**：模型在单窗口查空时按手册规则补查（是否补基线由手册决定）；结论区分"未发现 XX"（单窗口查空）与"XX数据缺失"（双窗口均空），消除"0 条慢调用 → 调用链数据缺失"这类误判；补齐 trace 总量查询手段，让"调用链缺失"有明确判定依据。

**不做**：不给 `getSlowSpans` 加慢调用阈值（保持现状：按 duration 降序 top-N）；不改 `ToolCallSummarizer` 的 slowSpans 文案（"未查询到慢调用"符合"未发现"口径）；不改前端；不改 Mock 执行器。

**前置**：无（基于 LLM_LOOP_PLAN 既有执行器链路）。

## 信息索引

| 要查什么 | 看哪里 |
|---|---|
| 空结果判定与止损机制 | `backend/.../service/LlmAnalysisExecutor.java` `buildToolResultContent()`/`isEmptyResult()` |
| 工具结果回填约束（提示必须写在工具结果消息内） | `LlmAnalysisExecutor` `DUPLICATE_CALL_HINT` 注释 |
| System prompt 约定（文档先行） | `docs/tech-design-LLM-prompt.md`、`backend/.../service/AnalysisPrompts.java` |
| 双窗口策略原文 | `backend/src/main/resources/mock-data/runbooks/Fallback.md` 第 0 节 |
| 出问题的手册 | `backend/src/main/resources/mock-data/runbooks/HighMemoryUsage.md` 第 1、3 节 |
| 上一轮同类计划 | [CALCULATOR_TOOL_PLAN.md](./CALCULATOR_TOOL_PLAN.md) |

## 已拍板的技术选择

| 点 | 落地选择 |
|---|---|
| 概念界定（prompt 层） | **"查询成功但 total=0"= 查询窗口内没有记录，不是数据缺失**；"数据缺失"仅当手册判定双窗口（故障发生时间窗口 + 基线窗口）均无数据时才可使用。单窗口查空只能写"未发现 XX"表述 |
| 补查规则（prompt 层） | 查空后的后续动作（是否补查基线窗口等）**按手册对该类数据空结果的处理规则执行**，prompt 不一刀切强制补查 |
| getSlowSpans 语义（用户拍板） | 慢调用查空 ≠ 调用链缺失：`getSlowSpans` 查空只能写"未发现慢调用"；判定"调用链缺失"以 **trace span 总量双窗口均为 0** 为准 |
| 新工具 getTraceCount | `getTraceCount(service?, startTime?, endTime?)`：窗口内 span 总数，镜像 `countLogs`（裸数字返回，total=0 自然计入止损计数）；SQL `COUNT(*)` 查 traces 表；规格放 getSlowSpans 之后 |
| 硬约束（代码层） | `LlmAnalysisExecutor` 复用 `buildToolResultContent` 附加提示机制：**首次**全空结果一次性附加 `EMPTY_RESULT_HINT`（写在工具结果消息内，理由同 `DUPLICATE_CALL_HINT`），点明 total=0≠数据缺失、后续动作按手册对该类数据空结果的处理规则执行（是否补查基线由手册决定）。`StopLossState` 加 `emptyHintInjected` 标记，与止损 `injected`（≥8 次）相互独立、互不影响计数 |
| 提示注入时机 | 只在空结果上注入且每次 run/resume 至多一次——避免重复注入膨胀上下文 |
| 手册修复 | `HighMemoryUsage.md` 第 3 节重写：`getTrace` → `getSlowSpans`；判定顺序改为"窗口有数据 → 慢调用查空只写未发现 → getTraceCount 双窗口均为 0 才记缺失"；`Fallback.md` 第 3 节同步 getTraceCount 判缺失规则 |
| judgmentLogic 表述示例 | 单窗口查空："故障发生时间窗口未发现慢调用"；双窗口均空："故障发生时间窗口（含故障发生前 30 分钟）均无调用链数据，无法评估" |

## 不改的文件（调研结论）

- `ToolCallSummarizer.java` 的 slowSpans case：0 条时折叠头"查阅 调用链"、证据"未查询到慢调用"，符合"未发现"口径 ✓（但需**新增** getTraceCount case）。
- `AnalysisToolRegistry.java` / `DataAnalyticsService.java` 的 getSlowSpans：查询本身正确返回 {total:0, items:[]}，不加阈值 ✓（但需**新增** getTraceCount）。
- `frontend/`：无代码改动。

## Todo

- [x] **T1. 文档先行**：`docs/tech-design-LLM-prompt.md`「可观测数据缺失」条款改为"空结果 vs 数据缺失"双条目（含按手册补查规则与两种表述示例）；工具清单加 getTraceCount；「执行器提示注入约定」节补记 `EMPTY_RESULT_HINT`。
- [x] **T2. 新工具 getTraceCount**：`DataAnalyticsService.getTraceCount`（COUNT(*) 查 traces，裸数字返回）；`AnalysisToolRegistry` specifications + execute case；`LlmAnalysisExecutor.evidenceSource` 加 "trace"；`ToolCallSummarizer` 加 case（镜像 countLogs 文案）。
- [x] **T3. 提示词**：`AnalysisPrompts.SYSTEM_PROMPT` 同步 T1 内容（Workflow Rules 增补空结果规则、工具清单加 getTraceCount、改写 Output Format 的数据缺失条款）。
- [x] **T4. 执行器硬提示**：`LlmAnalysisExecutor` 加 `EMPTY_RESULT_HINT` 常量与 `StopLossState.emptyHintInjected`，`buildToolResultContent` 首次全空结果注入一次；`LlmAnalysisExecutorTest` 补用例。
- [x] **T5. 手册修复**：`HighMemoryUsage.md` 第 3 节重写（getSlowSpans 查空只写"未发现"，getTraceCount 双窗口均为 0 才记"调用链缺失"）；`Fallback.md` 第 3 节同步。
- [x] **T6. 测试**：`ToolCallSummarizerTest` 加 getTraceCount 文案用例；`DataAnalyticsServiceTest` 加 getTraceCount 集成用例。
- [x] **T7. 验证与收尾**：`cd backend && mvnw test && mvnw clean install`；按 AGENTS.md 文档自维护约定检查 `backend/README.md`、`docs/DEV_STARTUP.md` 是否需同步；`git status` 核对本计划新增/修改文件并 `git add`；勾选本清单。

### 实现与验证结果（2026-10-07）

- 后端全量 85 个测试通过（新增 6 个：LlmAnalysisExecutorTest 3 个空结果提示用例、ToolCallSummarizerTest 2 个 getTraceCount 文案用例、DataAnalyticsServiceTest 1 个 getTraceCount 集成用例），`mvnw clean install` BUILD SUCCESS。
- 实现中的用户反馈修正：① 提示与 prompt 不一刀切要求"先补查基线"——查空后的后续动作按手册对该类数据的规则执行；② 手册第 1 节"返回非空"笔误未改（缺少上下文无法确认基线补查是否适用）；③ 慢调用查空 ≠ 调用链缺失的语义缺口按用户拍板新增 `getTraceCount` 补齐（双窗口 span 总数均为 0 才是"调用链缺失"）。
- 文档自维护检查结论：`backend/README.md` 工具导览只描述"加新工具三处同步"模式，不枚举工具名，无需更新；前端无代码改动，`frontend/README.md` 不涉及；`DEV_STARTUP.md`、根 `AGENTS.md` 不涉及。

## 验收标准

1. 单测/集成测试：`LlmAnalysisExecutorTest`、`ToolCallSummarizerTest`、`DataAnalyticsServiceTest` 新增用例全通过，存量止损用例不回归；后端全量测试通过。
2. （手动，LLM 模式）对故障窗口慢调用为空的告警触发分析：报告 judgmentLogic 对调用链只出现"未发现慢调用"或"双窗口 span 总数为 0"两类表述，不再出现单窗口查空即"调用链数据缺失"的结论；判定缺失时可见 getTraceCount 调用步骤。
3. 失控扩窗止损行为不变（阈值 8、只注入一次；getTraceCount 返回 0 计入止损计数）。
