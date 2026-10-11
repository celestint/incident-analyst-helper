文档用途：LLM协助分析闭环，确保过程透明可追溯，考虑到工具校验、记忆、服务崩溃引起的数据恢复

# 整体联动架构

```text
前端
  │ 1. 发起分析
  │ 2. SSE 订阅（带 last_seq 断点）
  ▼
后端
  │ 创建 Incident、写 incident_received 事件、异步执行 LLM 分析
  ▼
DB
  incident / incident_events / tool_idempotency / analysis_report
  ▲
  │ LLM 执行器：调模型、校验工具、执行、写事件（事件写入与状态更新同一事务）
  ▼
事件推送 SSE（落库事件带 seq；text_delta 只实时推送不落库）
```

原则：
1. 写事件和更新 incident 状态必须在同一个事务里。
2. 工具调用必须幂等，否则崩溃重试会重复副作用（本阶段工具全是只读查询，幂等表是防御性设计 + 流程验证）。
3. 前端按 seq 增量拉取，断线重连不丢状态。（落库事件每条带 seq，前端记录 last_seq，重连时带 `?since=last_seq`）
4. `text_delta`（最终回答流式片段）不落库、不带 seq，只是实时推送；最终内容以落库的 `report_finalized` 事件为准。

# 事件命名（统一拍板）

落库事件（写入 incident_events，带 seq，可重放）：

| event_type | payload 要点 | 前端表现 |
| --- | --- | --- |
| `incident_received` | alertName/service/severity/startsAt | "开始分析" 行 |
| `agent_thought` | thought（模型本轮思考/观察结论摘要） | 可折叠思考行 |
| `tool_call_start` | tool / args / stepSeq | 工具步骤"执行中" |
| `tool_call_result` | tool / success / summary（+错误信息） | 工具步骤完成/失败，可展开 |
| `evidence_collected` | source / content / timestamp | 证据节点（弱化展示） |
| `report_finalized` | reportId | 完成：拉取完整报告，折叠中间过程 |
| `error` | message | 红色错误节点 + 重试 |

不落库事件（仅 SSE 实时推送）：

| event_type | payload | 说明 |
| --- | --- | --- |
| `text_delta` | delta（文本片段） | 最终回答逐字推送，无 seq；重连重放流里没有它 |

# 从发起到拿到结果的过程说明

以未分析的告警为例
1. 点击分析，前端发起 start（创建 Incident），根据返回的 incidentId 订阅事件流
2. 后端 LLM 执行器在独立线程池中驱动分析（**不单独加排队状态**：单实例下 PENDING→RUNNING 已覆盖语义，DB 轮询只用于崩溃扫描，不再引入 QUEUED）
   每一步都：
     1. 写入 incident_events（seq = last_seq + 1）
     2. 更新 incident.phase / last_seq / updated_at（同一事务）
     3. 工具调用读写 tool_idempotency
   提交事务后推送给 SSE 订阅者
3. 前端消费事件：按 seq 排序、去重，按类型渲染。思考显示摘要可折叠；工具执行时显示"执行中"，结果可展开；最终回答按 text_delta 流式拼接。
4. 完成：后端写 report_finalized 事件（含 reportId），incident.status=COMPLETED。前端收到后：用完整报告替换正在流式的半截文本，折叠中间步骤，只保留最终报告和一条弱化的"过程"头部行，点击可重新展开。
5. 断线重连：前端重开 SSE 带 `?since=last_seq`。后端先从 incident_events 重放 seq > last_seq 的事件，再注册实时推送。重放流里没有 text_delta——前端规则：**收到 report_finalized 就用其完整报告替换任何正在流式显示的半截文本**。
6. 崩溃恢复与断点续跑
   - **崩溃恢复**：服务启动时扫描超时的 RUNNING Incident（updated_at 距今超过阈值）→ 标记 FAILED + errorMessage="分析中断，请重试"，写 error 事件。事件与已生成报告都在库里不丢；lease 字段（lease_owner/lease_until）已留在 schema 注释里，多实例时再启用。
   - **断点续跑（POST /api/incidents/{id}/resume，仅 FAILED）**：LLM 上下文从 incident_events 最近一次尝试段重建——agent_thought → AiMessage(含该轮 toolRequests)，工具完整结果从 tool_idempotency 按 key 取回（事件 payload 只有截断摘要），有 start 无 result 的工具先补执行（幂等命中复用）；然后进入既有循环继续。与 start（从头再来）的区别：resume 保留上下文，模型不用重复推理已完成的步骤。

# 前端视角的流程

```text
用户点击分析
  → POST /api/incidents/{alertId}/start，拿到 incidentId
  → 打开 SSE（记录 last_seq），显示"开始分析"
  → 收到事件：
       incident_received → 开始分析行
       agent_thought     → 可折叠思考行
       tool_call_start   → 新增工具步骤，状态"运行中"
       tool_call_result  → 更新工具步骤，结果可展开
       evidence_collected→ 证据节点
       text_delta        → 流式拼接最终回答（不推进 last_seq）
       report_finalized  → 用完整报告替换流式文本，折叠过程
       error             → 显示错误，提供"重试"
  → 断线：重开 EventSource，带 ?since=last_seq
  → 刷新页面：GET /api/incidents/{id} + GET stream?since=0 全量重放，重建 UI
```

# 后端视角的流程

1. API 层
   - 鉴权、校验输入
   - start：事务 + `SELECT ... FOR UPDATE` 锁 alert 行，创建 Incident，入队（个人项目直接异步提交到分析线程池，DB 表只做状态与事件持久化）
   - 返回 incidentId
2. 工具校验与执行
   - 工具注册表：每个工具声明 名称/描述/JSON Schema 参数/执行器。数据读取工具包装 DataAnalyticsService（getLogs/countLogs/getMetrics/getKpi/getSlowSpans），另有 `getRunbook(alertName)`：按关键词匹配（runbook-keywords.json）返回手册全文或"无匹配"
   - Runbook 两种执行模式：匹配到手册 → LLM 按手册步骤顺序调用工具（手册模式）；无匹配 → LLM 自主规划查询（探索模式）
   - 执行前：JSON Schema 校验参数；只允许注册表内的工具
   - 执行时：
     - 写 tool_call_start 事件，提交事务
     - idempotency_key = SHA256(incident_id, step_seq, tool_name, args)，查 tool_idempotency，命中直接复用结果
     - 未命中则执行，结果写入 tool_idempotency
   - 执行后：写 tool_call_result（含成功/失败、结构化摘要），回填给模型继续决策
3. LLM 记忆
   - MVP 只做单 incident 内的消息列表：system prompt（告警信息 + 工具清单 + 输出格式要求）+ 每轮工具结果（含 getRunbook 返回的手册），随循环累积
   - 不建 memories / checkpoints 表；跨 incident 记忆留给后续
4. LLM 输出与报告
   - 模型先流式输出分析叙述（text_delta 实时推送），结尾输出 ```json 代码块（结构见 tech-design-LLM-prompt.md）
   - 结束后从全文解析 JSON 块 → AnalysisReport 落库 → report_finalized 事件 → COMPLETED
   - 解析失败重试一次；仍失败 → error 事件 + FAILED
5. 崩溃恢复
   - 启动扫描：
     ```sql
     SELECT id FROM incident
     WHERE status = 'RUNNING'
       AND updated_at < :threshold;
     ```
   - 命中 → FAILED + error 事件（见上"崩溃恢复"）
6. 降级开关与多模型故障转移
   - `app.analysis.executor: mock|llm`；test profile 用 mock（不需要 API key），local 默认 llm
   - `ai.models` 支持配置多个 OpenAI 兼容模型，按顺序故障转移：某模型限流/异常自动切下一个，优先复用最近成功的；全部失败才 error + FAILED
   - 注意：故障转移发生在"轮"的边界，切换后重新生成当前轮，已流出的叙述片段可能重复（结构化报告不受影响）

# LLM prompt
见同层级文件 tech-design-LLM-prompt.md
