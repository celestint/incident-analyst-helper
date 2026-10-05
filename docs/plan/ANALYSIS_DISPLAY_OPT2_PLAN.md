# 分析显示优化计划 2（兜底手册 + 进行中反馈 + 自动滚动）

## 背景

上轮 T7/T8 验收（真实 LLM 跑 `UITestEndsAtAlert` 告警）发现 3 个问题：

1. **执行顺序混乱**：先按手册查了 getRunbook，之后工具调用失去章法——调用链之后又回头查日志，25 次工具调用全部 0 结果、时间窗口越扩越大，直到 12 轮迭代耗尽失败。此前修过两次相关问题（ANALYSIS_DISPLAY_PLAN 的 T9 续跑上下文重建、T10 重复思考折叠），本轮查明根因不同。
2. **无进行中反馈**：有执行步骤后，页面可能很久一动不动（工具执行/等待期间无任何事件），用户不知道系统还在工作。
3. **无自动滚动**：内容超过一屏后，新进展出现在视口外，需要手动下滑。

## 问题 1 根因调查结论

- runbook 未命中后模型进入"自由规划"，prompt 只有一句"未匹配到手册时自主规划排查路径"，无具体路径（查什么、顺序、窗口、何时停止全靠即兴）。incident 10 实际走出：默认窗口查 3 工具 → 全空 → 扩窗轮询全部 5 个工具 → 扩到 ±1 天 → 12 轮耗尽失败。
- **数据缺失是合理场景**（已确认），不做数据时间校验；解法是给未命中场景一个**兜底手册**，让"自由规划"变成"按兜底手册执行"。
- executor 无 bug（去重、max-iterations 均正常工作）。

## 目标 / 不做

**目标**：① 未命中 runbook 时回退到兜底手册（告警名价值判断、固定工具顺序、双窗口策略、数据缺失报告填写约定）；② executor 累计空结果超预算时注入止损提示（手册失效的保险丝）；③ 步骤末尾加载图标；④ 自动滚动。

**不做**：不做告警时间/数据范围校验；不改现有 HighMemoryUsage.md；不改工具协议/事件结构；自动滚动不做跟随/暂停按钮。

**前置**：[ANALYSIS_DISPLAY_OPT_PLAN.md](./ANALYSIS_DISPLAY_OPT_PLAN.md)（T7/T8）

## 当前仓库状态

- `backend/.../mock-data/runbooks/`：仅 `HighMemoryUsage.md`（内存场景专用手册，含窗口定义：告警窗口=告警开始至恢复/结束、基线=告警窗口前紧邻 30 分钟；"数据缺失，不下结论"范式）；`runbook-keywords.json` 列 3 个手册（HighCPUUsage.md / PodCrashLoop.md 实际文件不存在）。
- `backend/.../service/RunbookService.java`：按告警名关键词匹配 `runbooks/` 下手册，未命中返回 null（executor 提示模型"自主排查"）。
- `backend/.../service/AnalysisPrompts.java:21`：规则 4"未匹配到手册时自主规划排查路径"。
- `backend/.../service/LlmAnalysisExecutor.java`：`runLoop`（L145-210）每轮 = 一次 LLM 调用 + 执行其要求的全部工具；跨轮去重按 `toolName|规范化args`；`max-iterations=12` 兜底终止。
- 前端 `ProcessTimeline.vue` / `AnalysisPane.vue`：T7/T8 后的最新结构（流式思考单行框、工具步骤不自动展开）。

## 信息索引

| 要查什么 | 看哪里 |
|---|---|
| 需求出处 | 本轮用户验收反馈（2026-10） |
| 现有手册范式 | `backend/src/main/resources/mock-data/runbooks/HighMemoryUsage.md` |
| 手册匹配 | `backend/.../service/RunbookService.java`、`mock-data/runbook-keywords.json` |
| prompt 约定（文档先行） | `docs/tech-design-LLM-prompt.md`、`backend/.../service/AnalysisPrompts.java` |
| 执行循环与去重 | `backend/.../service/LlmAnalysisExecutor.java` |
| 上一轮计划 | [ANALYSIS_DISPLAY_OPT_PLAN.md](./ANALYSIS_DISPLAY_OPT_PLAN.md) |

## 已拍板的技术选择

| 点 | 落地选择 |
|---|---|
| 兜底手册形态 | 新建 `backend/src/main/resources/mock-data/runbooks/Fallback.md`，结构对齐现有手册（原则/窗口定义/分步判断/分析结果），但内容**通用化**（不绑定内存场景，适配任意告警）；`RunbookService.findRunbook` 关键词未命中时回退返回兜底手册（前端照常显示"读取 Fallback.md"），仅文件读取失败才返回 null |
| 告警名价值判断 | 兜底手册第 0 步（LLM 判断）：告警名含可辨识的服务/资源/症状语义才继续排查；明显测试/无意义名（如 UITestEndsAtAlert、Test、Demo）→ 跳过所有工具调用直接输出报告 |
| 工具顺序与窗口 | 顺序固定：查指标（getMetrics）→ 查日志（getLogs）→ 查调用链（getSlowSpans）；每类先查**告警窗口**（告警开始至恢复/结束，未恢复则至当前），为空补查**基线窗口**（告警窗口前紧邻 30 分钟）；两个窗口都空记该类"数据缺失，不下结论"（沿用现有手册范式）；三类全缺失 → **停止调用工具**，直接进入报告步骤 |
| 数据缺失时报告填写 | prompt + 手册分析结果节约定：`rootCauseHypothesis` 如实写"可观测数据缺失，无法定位产生原因，需人工核查数据采集"（禁止编造）；`judgmentLogic` 编号列出实际执行的查询与结果（如"1. [指标] 告警窗口与基线均无数据"）；`confidence ≤0.3` + confidenceReason 说明数据缺失；`isNoise=false`（数据不足以判噪音）、`needsHandling=true`（保守需处理） |
| prompt 同步 | `AnalysisPrompts.java` 规则 4 改为"getRunbook 返回的手册（含兜底手册）必须严格按步骤顺序执行"，删除"自主规划排查路径"；按文档先行约定先改 `docs/tech-design-LLM-prompt.md` |
| executor 止损 | **累计**口径：全空（total=0）的工具调用累计 ≥8 次时，向消息列表注入一次止损提示（"可观测数据持续为空，禁止再调用工具，直接输出最终 JSON 报告"）。阈值 8 > 兜底手册正常路径的 ~6 次预算（3 类 × 2 窗口），守手册的模型不受干扰，只拦失控循环；1 轮 = 一次 LLM 调用 + 执行其要求的全部工具 |
| 加载图标 | `ProcessTimeline.vue`：`live` 且无流式思考内容时，步骤末尾渲染旋转图标 +「分析执行中…」；与 T8 思考流式框互补、不同时出现（LLM 输出时显示思考框，工具执行/等待时显示加载行） |
| 自动滚动 | `AnalysisPane.vue`：用户在底部附近（距底 <60px）时跟随新内容自动滚到底；用户上滑即暂停跟随（不被拉回），滑回底部自动恢复；切换告警重置为跟随 |

## Todo

- [x] **T1. 兜底手册 + RunbookService 回退**
  `docs/tech-design-LLM-prompt.md` 先行更新（自由规划删除，改为兜底手册约定）→ 新建通用化 `Fallback.md` → `RunbookService.findRunbook` 未命中回退 → `AnalysisPrompts.java` 规则 4 同步。

- [x] **T2. executor 累计空结果止损**
  `runLoop` 统计全空工具调用累计次数，≥8 注入一次止损提示；单测覆盖（含"手册路径 6 次空调用不触发"用例）。

- [x] **T3. 前端：进行中加载图标**
  `ProcessTimeline.vue`：`live && !latestStreamingLine` 时步骤末尾渲染旋转图标 +「分析执行中…」行（复用 `is-spinning`）。

- [x] **T4. 前端：自动滚动**
  `AnalysisPane.vue`：底部跟随（距底 <60px）、上滑暂停、回底部恢复、切换告警重置。

- [x] **T5. 文档同步与测试**
  - `docs/DEV_STARTUP.md`：补手册匹配规则（runbook-keywords.json 关键词命中 / 未命中走 Fallback.md 兜底）。
  - **按 AGENTS.md 文档自维护约定收尾自检**：逐一检查根 `AGENTS.md`、`frontend/README.md`、`docs/` 下其他相关文档，确需更新先征得用户同意再写。
  - `mvn test && mvn clean install`；`npx vue-tsc -b`；浏览器走查（见验收标准）。

- [x] **T6. 修正：移除冗余的「等待分析结果」提示**
  T7/T8 轮在 `AnalysisPane.vue` 加的 el-alert（条件 `RUNNING && events.length === 0`）与 T3 的「分析执行中…」加载行必然同屏重复（用户验收截图发现）。删除该 el-alert 及其专属 CSS（`.section :deep(.el-alert--info.is-light)` 两段规则）；"RUNNING 且无事件"场景由加载行作为唯一反馈来源。

- [ ] **T7. 修正：判断逻辑的窗口表述与数据缺失合并（用户验收反馈）**
  验收发现判断逻辑输出两个问题：① 多类数据缺失时逐类罗列查询过程过于啰嗦；② 输出含 Unix 时间戳与"告警窗口/基线窗口"术语，用户看不懂。改法（文档先行，`tech-design-LLM-prompt.md` → `AnalysisPrompts.java` → `Fallback.md` 三处同步）：
  - judgmentLogic 约定改为"编号列出实际执行的查询与结果。遇到多条数据缺失（如指标/日志/调用链）合并为一点，如'指标、日志、调用链三类数据均缺失，无法判定'"
  - 新增窗口表述规则：面向用户用"故障发生时间窗口"（告警窗口）、"故障发生前 30 分钟"（基线窗口），禁止输出 Unix 时间戳、窗口术语与起止范围
  - `Fallback.md` 窗口定义注明"仅供内部推理"、输出判断示例同步改为用户友好表述

## 验收标准

- 用 `UITestEndsAtAlert` 重新分析：getRunbook 返回兜底手册，模型判断告警名无价值 → 不调用数据工具，直接产出报告（rootCauseHypothesis/judgmentLogic 按"数据缺失"约定填写，confidence ≤0.3）。
- 用有语义但无手册命中的告警名（如 `DiskIOLatencyHigh`）：按兜底手册顺序查指标→日志→调用链，告警窗口空自动补查基线，三类全空后停止调用工具并出报告，累计空调用 ≤8 次不触发止损提示，不再出现"达到最大迭代次数"失败。
- runbook 命中的告警（HighMemoryUsage 等）仍严格按原手册执行，行为不变。
- RUNNING 中：LLM 输出时思考框单行滚动；无事件时步骤末尾出现加载行；两者不同时出现。
- RUNNING 初期（尚无落库事件）：步骤末尾仅显示「分析执行中…」加载行，不再出现「等待分析结果...」提示。
- 数据缺失报告的判断逻辑：多类数据缺失合并为一点（如"指标、日志、调用链三类数据均缺失，无法判定"）；输出不含 Unix 时间戳与"告警窗口/基线窗口"术语，时段用"故障发生时间窗口 / 故障发生前 30 分钟"表述。
- 内容超屏自动跟随滚动；上滑不被拉回，回到底部恢复跟随。
- `mvn test && mvn clean install`、`npx vue-tsc -b` 通过。
