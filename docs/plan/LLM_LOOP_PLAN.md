# 薄闭环计划（有 LLM）

## 目标 / 不做

**目标**：在 [THIN_LOOP_PLAN](./THIN_LOOP_PLAN.md) 走通无 LLM 薄闭环之上，走通 LLM 协助分析的闭环：点分析 → 分析页收到 SSE → 后端利用 LLM、工具调用、汇总分析得出结论 → 前端可见，做到过程透明可追溯、崩溃数据可恢复。

**不做**：
- 断点续跑式崩溃恢复（只做"标记 FAILED + 手动重试"，工具全是只读查询，重跑无副作用）
- 跨 incident 的应用层记忆 / memories / checkpoints 表
- 多实例 lease 机制（字段留在 schema 注释）
- text_delta 落库（流式片段只实时推送，不进 incident_events）
- P1 用户提问闭环（见 P1_USER_ISSUE_PLAN.md）
- Runbook 全文注入 prompt（runbook 通过 getRunbook 工具按需获取）

**前置**：THIN_LOOP_PLAN 全部完成（T1~T7 已勾），本机 MySQL、DuckDB 视图、五个 API、前端薄 UI 可用。

## 当前仓库状态

已走通无 LLM 薄闭环：告警进列表 → 点分析 → 分析页收到 SSE → 假报告归档 → 再进只读、不重跑。后端单元测试通过，暂未开始前端测试。

本阶段起点上已确认的存量问题（本计划顺带修复）：
- schema.sql 的 incident 建表语句被多余的 `;` 截断；incident_events 索引引用不存在的 occurred_at；tool_idempotency 用了 MySQL 5.6 不支持的 JSON 类型 → **本地/测试库需要按修好的 schema.sql 重建表**
- FAILED 路径不写 errorMessage、不推 error 事件
- start 接口 `selectByIdForUpdate` 实际无 FOR UPDATE，并发下 409 语义失效
- SSE 无重放/断点续传，emitter 60s 超时对真实 LLM 太短
- AlertController 依赖指向已不存在 JSON 文件的 MockDataLoader（死代码）
- 前端重试按钮把 incidentId 当 alertId 调 start

## 信息索引

| 要查什么 | 看哪里 |
| --- | --- |
| 事件命名、text_delta 不落库、重连契约 | [tech-design-LLM.md](../tech-design-LLM.md) |
| System prompt、工具清单、报告 JSON 结构 | [tech-design-LLM-prompt.md](../tech-design-LLM-prompt.md) |
| API 契约、报告结构（四段格式扩展）、状态码 | [tech-design.md](../tech-design.md) |
| 表结构 | backend/src/main/resources/schema.sql |
| 现有可包装的工具方法 | service/DataAnalyticsService.java（getLogs/countLogs/getMetrics/getKpi/getSlowSpans） |
| Runbook 关键词匹配 | mock-data/runbook-keywords.json + mock-data/runbooks/*.md |

## 已拍板的技术选择

| 点 | 落地选择 |
| --- | --- |
| LLM 接入 | **OpenAI 兼容接口**：langchain4j-open-ai + `ai.models` 多模型配置（base-url/api-key/model 可按条目覆盖），按顺序故障转移（限流/异常自动切下一个，优先复用最近成功的），可接智谱 GLM / DeepSeek / 百炼兼容模式 |
| 工具循环驱动 | **手动驱动**（ChatModel.generate(messages, toolSpecs) + 自行处理 toolExecutionRequest），不用 AiService 自动执行，便于插手事件落库与幂等 |
| Runbook | 做成 `getRunbook(alertName)` 工具：匹配到手册按步骤顺序执行（手册模式），无匹配自主探索（探索模式）；不注入 system prompt |
| 流式输出 | **做**。text_delta 不落库不带 seq 只实时推；report_finalized 单事件落库；重连时前端用完整报告替换半截流式文本 |
| 崩溃恢复 | 启动扫描超时 RUNNING → FAILED（errorMessage="分析中断，请重试"）+ error 事件，手动重试，seq 继续累加 |
| 事件命名 | 落库：incident_received / agent_thought / tool_call_start / tool_call_result / evidence_collected / report_finalized / error |
| 事件一致性 | 写 incident_events 与更新 incident 状态在同一事务 |
| 降级开关 | `app.analysis.executor: mock\|llm`，test profile 用 mock（不需要 API key） |
| 报告结构 | 四段格式扩展：eventSummary(isNoise/needsHandling) + rootCauseHypothesis + recommendedActions + judgmentLogic（证据融入，无独立 evidenceChain、无 riskLevel），保留 confidence |

## Todo

- [x] **T1. 文档修订**
  修 schema.sql（截断 `;`、occurred_at→created_at、JSON→MEDIUMTEXT、event_typ→event_type、analysis_report 改四段格式结构）；
  tech-design.md 统一事件命名 + last_seq 重连契约 + Incident 补 phase/lastSeq/updatedAt + 技术栈改 OpenAI 兼容 + 报告契约更新；
  tech-design-LLM.md 解决两处"（？）"（不加排队状态、evidence 不建表）、明确记忆方案与崩溃恢复策略；
  tech-design-LLM-prompt.md 落地为真实 system prompt 草案；
  填充本计划全部空节。

- [x] **T2. 依赖与配置**
  pom 加 langchain4j-open-ai；ai.base-url/api-key/model、analysis.max-iterations、app.analysis.executor: mock|llm 开关（test profile 用 mock）；分析专用有界线程池。

- [x] **T3. 持久层**
  IncidentEvent / ToolIdempotency model+mapper；Incident 模型补 phase/lastSeq/updatedAt；AnalysisReport 模型按新报告结构调整；写事件与更新 incident 状态同一事务。

- [x] **T4. 工具层**
  getLogs/countLogs/getMetrics/getKpi/getSlowSpans 包装为 LLM 工具注册表（名称/描述/JSON Schema/执行器），另有 getRunbook(alertName)；执行前查 tool_idempotency，命中复用，未命中执行后写入。

- [x] **T5. LLM 分析执行器**
  mock/llm 分流：incident_received → system prompt（告警信息+工具清单）→ 循环（流式调模型 → agent_thought → tool_call_start → 执行 → tool_call_result/evidence_collected）→ text_delta 实时推送（不落库）→ 解析结尾 JSON 块 → AnalysisReport 落库 → report_finalized → COMPLETED；迭代上限/超时；异常路径 error 事件 + FAILED + errorMessage。

- [x] **T6. SSE 重放与重连**
  stream 端点支持 ?since=（及 Last-Event-ID）：先重放 events 表 seq>since 再注册 live emitter；emitter 超时调大 + 心跳。

- [x] **T7. 崩溃恢复 + 并发修复**
  ApplicationRunner 启动扫描超时 RUNNING → FAILED；start 接口事务 + 真 FOR UPDATE；清理 MockDataLoader 残留。

- [x] **T8. 前端适配**
  types 新事件集；分析页渲染 thought 折叠行/工具步骤/流式缓冲/report_finalized 替换规则/error 节点+重试；EventSource 带 last_seq 重连；修重试按钮 id bug；报告页按四段格式渲染。

- [x] **T9. 后端测试**
  stub ChatModel 单测（模拟工具调用序列→报告）、事件重放/幂等测试、状态机回归；mvn test 通过。

- [x] **T10. 真实验证**
  配真实 key 走全流程：真实告警 → 分析 → 前端看到思考/工具/流式回答/报告；断线重连与刷新恢复；运行中 kill 后端重启 → FAILED 可重试。

  已验证（真实环境，2026-10-03）：
  - ✓ 真实模型调用打通：getRunbook/getMetrics/getLogs 真实执行、agent_thought/text_delta 流式推送、多模型故障转移（1302/1305 自动切换并聚合报错）、FAILED + errorMessage 错误路径
  - ✓ 失败页显示完整已执行链路（GET /events 历史事件端点 + ProcessTimeline 组件），每轮重试与失败原因可追溯
  - ✓ 断线重连/刷新重建：分析中刷新页面，SSE 带 since 重放接续（生产直连场景）；重放契约另有集成测试覆盖
  - ✓ 崩溃恢复真机验证：分析中 kill 后端 → 重启后恢复扫描标记 FAILED（errorMessage="分析中断，请重试（服务重启导致）"）+ 补写 error 事件 → 页面展示失败链路与重试按钮
  - ✓ **完整出报告**：限流窗口恢复后真实跑通全流程，四段格式报告落库（事件摘要/根因/推荐SOP/判断逻辑），前端正确渲染
  - ✓ mvn test 39/39（含 stub LLM 闭环、断点续跑、崩溃恢复、SSE 重放集成测试）

- [ ] **T11. 重试续跑与过程展示优化**
  - [x] 后端：POST /api/incidents/{id}/resume 断点续跑（仅 FAILED；LLM 上下文从事件重建，工具完整结果从幂等表取回，中断工具先补执行；run/resume 共用循环体）；mvn test 39/39
  - [x] 前端：FAILED 卡片双按钮——【重试（从失败点继续）】primary 主按钮（resume），【重新分析】次按钮（start，带确认）
  - [x] 前端：ProcessTimeline 聚合渲染——工具 start/result/evidence 合并为可展开步骤；历史失败尝试整轮不展示（仅末次尝试详细渲染，最终失败时保留红色错误节点）；开始分析只显示一次
  - [x] 浏览器真机验证（COMPLETED 聚合过程 + FAILED 双按钮；后调整为历史尝试不展示）

- [x] **T12. 前端单页双栏改版**
  - [x] 布局：左栏告警总览（卡片：名称前状态圆圈——结束灰圈/持续红圈；级别中文映射 critical→严重/warning→警告/info→信息；服务 tag（不带"服务:"前缀）；时间小字；操作按钮按状态切换：分析/分析中/查看报告/失败），右栏分析（未选中空态提示）
  - [x] 右栏：吸顶状态行（状态 tag + FAILED 时【重试（从失败点继续）】；重新分析按钮前端隐藏、后端保留）；下方仅"分析过程"+"分析报告"
  - [x] 折叠规则：分析过程默认折叠（RUNNING 自动展开）；每个步骤默认折叠，RUNNING 时执行中的工具步骤自动展开
  - [x] 路由：合并为 /?incidentId=，/incidents/:id 重定向兼容；删除 IncidentDetail.vue
  - [x] 验证：npm build + 浏览器（空态/双栏卡片/COMPLETED 折叠/FAILED 吸顶重试/重试真实续跑出报告 Redis02/RUNNING 实时事件）

## 完成标准

1. 真实告警 → 点分析 → 前端实时可见 LLM 思考、工具调用与结果、流式最终回答、结构化报告落库（mock-data 中可查到对应日志/指标/trace 证据）。
2. 断线重连/刷新页面：按 last_seq 重放不丢事件。
3. 分析中 kill 服务 → 重启后该 incident 为 FAILED（有 errorMessage），可重试；`tool_idempotency` 生效。
4. COMPLETED 再 start → 409；并发 start 只有一个 200。
5. `mvn test` 通过（mock 执行器路径，不依赖 API key）。
6. 文档与实现一致（事件名、schema、配置项、报告结构）。

## 后续

- text_delta 落库与断点续跑式恢复（若真有副作用工具时再评估）
- 多实例部署：启用 lease_owner/lease_until
- 跨 incident 记忆（同类告警历史结论参考）
- 指标曲线 ECharts 展示（P1）
- 用户提问闭环（P1_USER_ISSUE_PLAN.md）
- 用 groundtruth 做自动评测
