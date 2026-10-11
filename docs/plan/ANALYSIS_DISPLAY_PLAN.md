# 分析显示优化计划（工具条目人话化 + 报告格式统一）

## 背景

LLM 循环分析功能上线后，用户验收发现分析展示存在几类问题：

1. **工具调用条目**：非展开栏显示"调用工具 getRunbook · 成功"，展开后"参数/结果"是一段被截断的原始 JSON。期望：非展开栏显示人话（如"查阅 1 文件"），展开显示一句描述（如"读取 HighMemoryUsage.md"、"查找 11:40–12:00 名称包含 memory 的指标，找到 0 条。"）。证据标签（metrics/logs/trace/runbook）保留，但证据内容必须是**从数据得出的结论**（数值、时间点、计数），不能是 JSON 片段。
2. **推荐SOP**：显示"第1步....第2步..."，期望统一为"1.xxx（换行）2.xxx"。
3. **判断逻辑**："第1步..." 与 "1.xxx2.xxx" 混排且挤成一段，期望统一为"1.xxx（换行）2.xxx"。
4. **risk 标签**：每条 SOP 动作后的 LOW/MEDIUM/HIGH 标签对用户没有价值，彻底移除（含后端 prompt）。
5. **置信度**："置信度：N%" 常驻展示没有意义（它只是 LLM 自报的数，prompt 无任何评分锚点，导致明明有数据置信度也偏低）。改为：**任何情况不显示百分比**；仅当 confidence < 0.6 时在"事件摘要"前显示"⚠ 当前置信度较低"，悬浮展示后端返回的置信度理由（如"调了工具但查不到数据"）。

## 目标 / 不做

**目标**：

1. 工具调用条目折叠头、展开描述、证据内容全部人话化、结论化（后端生成语义文案，前端只负责渲染）。
2. 报告区 SOP 与判断逻辑统一为 `1. xxx` 编号 + 换行的格式（prompt 约定 + 前端归一兜底）。
3. 彻底移除 risk；置信度改为低置信度条件提示 + 悬浮理由。
4. 历史落库事件/旧报告向前兼容，不报错。

**不做（留给后续计划）**：

- 不改证据标签体系（`source` 字段）与工具协议本身（工具名、参数、返回结构不变，仅 getRunbook 返回 JSON 多一个 `file` 字段）。
- 不引入 markdown 渲染库，判断逻辑仍为纯文本。
- 不改 `recommended_actions` 的 JSON 字符串存储结构（历史数据中的 risk 由前端忽略）。

**前置**：[LLM_LOOP_PLAN.md](./LLM_LOOP_PLAN.md)

## 当前仓库状态

- `frontend/src/components/ProcessTimeline.vue`：折叠头"调用工具 {{tool}} · 状态"是前端拼的（L239-242）；展开区渲染 `参数：`（`prettyArgs` JSON 格式化）、`结果：{{ step.summary }}`、`证据：` source 标签 + content（L243-257）。
- `backend/.../service/LlmAnalysisExecutor.java`：`tool_call_result.summary` = 工具完整结果文本截断 500 字符（L454-462）；`evidence_collected.content` = 截断 300 字符的原始 JSON（L467-472）。没有任何语义提炼。
- `backend/.../tool/AnalysisToolRegistry.java`：6 个工具（getRunbook/getLogs/countLogs/getMetrics/getKpi/getSlowSpans），`RunbookService.findRunbook` 命中时返回 `{"matched":true,"content":"手册全文"}`，**不含文件名**。
- `backend/.../service/AnalysisPrompts.java`：`recommendedActions` 元素约定 `{priority, action, risk}`；`judgmentLogic` 为单行字符串、无编号/换行约定；`confidence` 仅约定"取 0~1 的小数"，无锚点。
- `frontend/src/components/AnalysisPane.vue`：SOP 行拼接 `第 {{ action.priority }} 步：` + risk 彩色标签（L230-242）；报告头部常驻"置信度：N%"（L227）；`judgmentLogic` 无换行处理直接插值（L243）。
- `frontend/src/types/index.ts`：`recommendedActions: Array<{ priority, action, risk }>`（L41）。
- `backend/.../service/MockAnalysisExecutor.java`：summary 已是人话，但无新字段结构；写死报告含 risk。

## 信息索引

| 要查什么 | 看哪里 |
|---|---|
| 需求出处 | 本轮用户验收反馈（2026-10） |
| LLM prompt 现状 | [../tech-design-LLM-prompt.md](../tech-design-LLM-prompt.md)、`backend/src/main/java/com/lyl/backend/service/AnalysisPrompts.java` |
| 工具定义与执行 | `backend/src/main/java/com/lyl/backend/tool/AnalysisToolRegistry.java`、`RunbookService.java`、`DataAnalyticsService.java` |
| 事件流与报告生成 | `backend/src/main/java/com/lyl/backend/service/LlmAnalysisExecutor.java` |
| 前端时间线/分析面板 | `frontend/src/components/ProcessTimeline.vue`、`frontend/src/components/AnalysisPane.vue` |
| 上一轮计划 | [LLM_LOOP_PLAN.md](./LLM_LOOP_PLAN.md)、[P2_UI_TIMELINE_PLAN.md](./P2_UI_TIMELINE_PLAN.md) |

## 已拍板的技术选择

| 点 | 落地选择 |
|---|---|
| 语义文案生成位置 | **后端**（新增 `ToolCallSummarizer`）：前端拿不到完整结果（summary/content 均被截断），只有后端能解析结果 JSON 提炼结论 |
| 折叠头文案映射 | getRunbook→"查阅 N 文件"；getMetrics/getKpi→"查阅 N 条指标"；getLogs/countLogs→"查阅 N 条日志"；getSlowSpans→"查阅 调用链" |
| 展开内容 | 只显示一句描述 + 证据（描述/证据由后端 `detail`/结论文案提供）；**不保留原始参数/结果 JSON** |
| 证据内容 | 从结果数据提炼的结论（峰值/末值+时间点、关键字计数、最慢 span 等），标签机制不变 |
| 事件协议 | `tool_call_result` 的 `summary` 改为人话折叠头、新增 `detail` 字段；`evidence_collected.content` 改为结论文案。旧事件无 `detail` 时前端走旧渲染兜底 |
| SOP/判断逻辑格式 | prompt 约定 `1. xxx\n2. xxx`（禁"第X步"）+ 前端 `formatJudgmentLogic()` 归一兜底（历史数据"第X步"也转"X."） |
| risk | 彻底移除：prompt 不再要求、mock 报告删除、前端删标签、类型删字段 |
| 置信度 | 前端任何情况不显示"N%"；`confidence < 0.6` 时事件摘要前显示"⚠ 当前置信度较低"（`el-tooltip` 悬浮展示后端 `confidenceReason`）；prompt 加评分锚点并要求输出 `confidenceReason` |
| 置信度锚点 | ≥2 类证据相互印证且时间吻合→≥0.8；单一来源→0.6~0.7；调了工具但查不到数据/仅间接推测→0.3~0.5；无法定位→≤0.3 |

## Todo

- [x] **T1. 后端：RunbookService 返回命中文件名**
  `findRunbook` 命中时返回 JSON 增加 `"file":"HighMemoryUsage.md"` 字段（未命中不加），供展开描述"读取 HighMemoryUsage.md"使用；对 LLM 无副作用（仅多一个字段）。

- [x] **T2. 后端：新增 ToolCallSummarizer，工具结果语义化（核心）**
  新建 `backend/src/main/java/com/lyl/backend/service/ToolCallSummarizer.java`，输入工具名 + args JSON + 结果 JSON + `ToolContext`（args 缺省时间时用默认窗口 `start-600 ~ start+300`，时间格式化为 `HH:mm`），输出 `{collapsed, detail, evidence}` 三段文案。`LlmAnalysisExecutor` 的 `tool_call_result`（L454-462）改用：`summary` = collapsed，新增 `detail` 字段 = detail；`evidence_collected.content`（L467-470）= evidence。各工具规则：
  - getRunbook：命中→折叠"查阅 1 文件"、展开"读取 {file}"、证据"命中《{file}》，含 X 个排查步骤"（按 Markdown 标题计数）；未命中→折叠"查阅 0 文件"、展开"未找到匹配的运维手册，转入自主排查"、证据"无匹配手册"。
  - getMetrics：折叠"查阅 N 条指标"，展开"查找 {起}–{止} 服务 {svc} 名称含 {metricName} 的指标，找到 N 条。"，证据从 items 提炼（如"apache01 该时段 memory 指标峰值 95.2%（11:52），末值 …；共 N 条"）；N=0 时"未查询到数据"。
  - getLogs：折叠"查阅 N 条日志"，展开"查找 {起}–{止} 服务 {svc} 包含 {keyword} 的日志，找到 N 条。"，证据提炼关键字计数与典型条目（时间点 + 内容摘要）。
  - countLogs：折叠"查阅 N 条日志"，展开"统计 {起}–{止} 服务 {svc} 包含 {keyword} 的日志数量：N 条"。
  - getKpi：折叠"查阅 N 条指标"，展开"查找 {起}–{止} 服务 {svc} 的 KPI 数据，找到 N 条。"，证据提炼成功率/平均响应时间关键值。
  - getSlowSpans：折叠"查阅 调用链"，展开"查找 {起}–{止} 服务 {svc} 最慢的 N 个调用。"，证据"最慢调用 {duration}ms（trace_id=…，{时间}）"。
  - 失败时：detail/evidence 置空，错误文案走现有 `error` 字段；重复调用去重逻辑不变。

- [x] **T3. 后端：置信度理由（confidenceReason）落地**
  - `AnalysisPrompts.java` 与 `docs/tech-design-LLM-prompt.md` 同步修改：报告 JSON 新增 `"confidenceReason": "一句话说明置信度依据"`，必须与锚点档位对应（如"调用了工具但未查询到相关指标数据"）；confidence 加锚点（见上表）；`judgmentLogic` 要求 `1. xxx\n2. xxx` 编号 + `\n` 分行、禁止"第X步"写法；`recommendedActions` 删除 `risk` 字段。
  - 存储：`analysis_report` 表加 `confidence_reason` 列（建表/初始化 SQL 同步），`AnalysisReport` 模型加字段，`IncidentController.convertReportToResponse` 透传；历史数据该列为 null，前端兜底。

- [x] **T4. 后端：MockAnalysisExecutor 同步**
  两处 `tool_call_result` 补 `summary`/`detail`，`evidence_collected.content` 改结论式；写死报告删除 risk、增加 `confidenceReason`（confidence 0.85 对应"指标与日志两类证据相互印证"）。

- [x] **T5. 前端：ProcessTimeline 渲染改造**
  `ToolStep` 接口增加 `detail`；折叠头改为 `step.summary || '调用工具 ' + step.tool`（状态后缀"· 成功/失败/执行中"保留）；展开区：有 `detail` 时显示描述句 + 证据（source 标签 + content），无 `detail`（历史落库事件）走现有"参数/结果/证据"旧渲染兜底。

- [x] **T6. 前端：报告区改造（AnalysisPane.vue）**
  - **置信度**：删除"置信度：N%"展示（L227 及 `.confidence` 样式）——任何情况都不显示百分比；当 `confidence != null && confidence < 0.6` 时，在"事件摘要"前显示一行"（el-icon Warning 图标）当前置信度较低"，该行包 `el-tooltip`，悬浮内容为后端返回的 `confidenceReason`（为空时兜底"分析证据不足，建议人工核实"）。
  - **SOP**：`第 {{ priority }} 步：` 改为 `{{ priority }}.`；删除 risk 标签；`types/index.ts` 删 `risk`、加 `confidenceReason`。
  - **判断逻辑**：新建 `frontend/src/utils/format.ts`，`formatJudgmentLogic()`：把"第X步/第X步："归一为"X."；对未换行的 `数字.` 列表标记智能插入换行（仅当前置字符为句号/分号/空白或文首、且后跟中文/字母时断行，避免误伤小数与版本号），段落加 `white-space: pre-wrap` 渲染。

- [ ] **T7. 测试与验收**
  新增 `backend/src/test/java/com/lyl/backend/service/ToolCallSummarizerTest.java`（六工具 × 命中/空结果/缺参用例）；`cd backend && mvn test` 通过并修正受影响断言（如 `LlmLoopIntegrationTest` 对 summary 的断言）；前端手动验收：mock 执行器 + 真实 LLM 各跑一次 apache01 HighMemoryUsage 场景（含查不到数据场景：验证低置信度提示与悬浮理由）。

## 验收标准

- getRunbook 命中：折叠头"查阅 1 文件 · 成功"，展开"读取 HighMemoryUsage.md"，证据为结论句而非 JSON。
- getMetrics（apache01、metricName=memory、0 命中）：折叠头"查阅 0 条指标 · 成功"，展开"查找 11:40–12:00 服务 apache01 名称含 memory 的指标，找到 0 条。"，证据"未查询到数据"。
- 推荐SOP 统一渲染为"1. xxx / 2. xxx"各占一行，无"第1步"字样、无 risk 标签。
- 判断逻辑统一为"1. xxx（换行）2. xxx"；历史数据中的"第X步"也被归一为"X."并正确分行。
- 置信度任何情况都不显示"置信度：N%"；confidence < 0.6 时事件摘要前出现"⚠ 当前置信度较低"，悬浮显示后端理由（如"调了工具但未查询到相关指标数据"）；≥ 0.6 时无任何置信度元素。
- 历史落库事件与旧报告（无新字段）正常渲染不报错；`cd backend && mvn test` 通过。

## T8. 报告与工具条目微调（用户验收反馈）

| 问题 | 根因 | 修复 |
|---|---|---|
| 折叠头"查阅 1 文件 · 成功"后缀冗余 | 前端无条件渲染状态后缀 | 成功时不渲染"· 成功"，保留"· 执行中/· 失败"（`ProcessTimeline.vue`） |
| 展开区"证据："标签字样冗余 | 前端硬编码前缀 | 删除前缀，直接显示 source 标签 + 结论内容 |
| SOP 显示为"- 1.xxx" | `ul/li` 默认项目符号 | `sop-list` 类去除符号与缩进，渲染为"1. xxx"分行；选择器用 `.report-section ul.sop-list` 压过 `.report-section ul` 旧规则的优先级 |
| SOP 与判断逻辑编号格式不一致（"1.xxx" vs "1. xxx"） | SOP 模板无空格，判断逻辑保留 LLM 输出的空格 | 统一为带空格的"N. 内容"：SOP 模板补空格，判断逻辑保持原样 |
| 判断逻辑编号显示为全角"１．"（点更宽、间距更大） | LLM 偶发输出全角数字/句点/空格 | `formatJudgmentLogic` 先做全角→半角归一（０-９/．/全角空格），行首编号统一为"N. "（单个半角空格），与 SOP 完全一致 |
| 判断逻辑内容紧跟标签 | 标签与内容同段插值 | "判断逻辑："独占一行，编号内容从下一行开始 |

## T9. 续跑上下文完整重建（用户验收反馈）

| 现象 | 根因 | 修复 |
|---|---|---|
| 调用链之后又重新调用 getRunbook；前后两次查询结果不一致；续跑后重复已执行的步骤 | `LlmAnalysisExecutor.rebuildContext` 只回放"最近一次尝试段"（最后一条 incident_received 之后），此前段落的工具结果全部不在模型上下文 → 模型重新调用已执行过的工具（排查证据：incident 8 四次续跑日志均打印 `Resuming … from step N, 0 pending`，N 随段落变化） | 改为从头重放全部事件：跳过 incident_received/evidence_collected/report_finalized/error，收集所有段落的 tool_call_start/result（成功结果优先取幂等表完整结果），agent_thought 全部纳入；stepSeq 全局续接。result 与 start 的匹配取"最近一条未回填的同 stepSeq + 同工具名"，兼容多次续跑导致的 stepSeq 跨段重复 |

## T10. 连续重复思考折叠（用户验收反馈）

| 现象 | 根因 | 修复 |
|---|---|---|
| 时间线出现连续多条一模一样的"我将调用 getLogs…"思考，中间无任何工具步骤 | 模型重复调用被去重拦截时不落任何事件，但每轮思考照常落库 | 前端聚合时相邻 `agent_thought` 文本相同只渲染一条（`ProcessTimeline.vue`） |

## T11. 推荐SOP 空条目修复（用户验收反馈）

| 现象 | 根因 | 修复 |
|---|---|---|
| 低置信度场景（incident 6/7）的推荐SOP 部分条目显示为空 | 取证确认：LLM 输出的 `recommendedActions` 是纯字符串数组（如 `["检查监控数据采集配置，…", …]`），不是 `[{priority, action}]` 对象数组，前端插值全为 undefined | 三道保障：① prompt 明确"元素必须是 {priority, action} 对象、action 非空字符串"；② 写入侧 `parseReport` 经 `RecommendedActionsNormalizer` 归一，**归一后为空视为报告无效，走 LLM 重试**（`JSON_RETRY_PROMPT` 点名 recommendedActions 问题）；③ 读取侧 Controller 返回前同一规则归一，兼容修复历史报告（incident 6/7 无需重新分析即恢复显示） |

### 追加验收标准（T8–T11）

- 折叠头成功态无"· 成功"后缀；展开区直接是 source 标签 + 结论内容，无"证据："字样。
- SOP 无项目符号，渲染为"1. xxx"分行；无空条目（纯字符串数组/缺 action 的历史脏数据也不再出现空行）。
- "判断逻辑："独占一行，编号内容从下一行开始。
- 续跑后模型不再重复调用已执行的工具（getRunbook 全程只出现一次），时间线无重复步骤与连续重复思考。
