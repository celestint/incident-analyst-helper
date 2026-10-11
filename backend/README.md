# 后端说明（告警分析服务）

面向人与 AI 的后端项目导览：快速了解架构与一次分析的完整链路、找到"要改 X 该动哪个文件"、遵守项目约定。全项目开发约定见根 [AGENTS.md](../AGENTS.md)，启动步骤 / 测试数据造法 / 常见问题见 [docs/DEV_STARTUP.md](../docs/DEV_STARTUP.md)。

## 技术栈

Java 21 · Spring Boot 4.1.1 · MyBatis 4.1（注解 SQL，不用 XML）· LangChain4j（OpenAI 兼容流式客户端）· MySQL 8（业务库）· DuckDB JDBC（可观测数据，只读查询）· SSE（SseEmitter 实时推送）

## 一次分析的完整链路

告警入库 → 手动触发分析 → 异步执行 LLM 工具循环 → 过程事件实时推送 → 报告落库：

1. **告警入库**：`POST /api/alerts`（AlertController）。唯一键 `service+alertName+startsAt`；重复 POST 带 `endsAt` 时回填结束时间，否则拒绝。
2. **触发分析**：`POST /api/incidents/{alertId}/start` → `IncidentService.startAnalysis` 状态机（事务 + `SELECT FOR UPDATE` 锁告警行；RUNNING 幂等返回、FAILED 重试置回 RUNNING、COMPLETED 409）→ `AnalysisDispatcher.dispatch` 提交到 `analysisExecutor` 线程池异步执行。
3. **执行器分流**：`AnalysisDispatcher` 按 `app.analysis.executor` 分流 `MockAnalysisExecutor`（固定事件序列 + 写死报告，无 LLM）/ `LlmAnalysisExecutor`；执行异常统一 `markFailed`（error 事件 + 状态 FAILED + errorMessage）。
4. **LLM 工具循环**（LlmAnalysisExecutor）：
   - 构建 `ToolContext`（默认查询窗口：告警开始前 10 分钟 ~ 后 5 分钟，Unix 秒）
   - 每轮 `streamTurn` 流式调模型：文本增量实时推 `text_delta`（**不落库**）；整段叙述落库 `agent_thought`
   - 工具调用：`tool_call_start` 落库 → 幂等表查询/执行 → `tool_call_result` 落库 → 数据类工具附 `evidence_collected` → 结果回填模型消息
   - 模型不再调用工具时，从结尾 ```json 块解析报告（失败注入 `JSON_RETRY_PROMPT` 重试一次）→ 报告落库 + `report_finalized` + 状态 COMPLETED
   - 保护机制：重复调用去重（同工具+同参数直接复用结果）、空结果纠偏（首次全空结果在工具结果消息内注入一次提示：total=0≠数据缺失，判定与补查按手册执行）、全空结果止损（连续 8 次空结果后禁止再调工具）、最大迭代 `max-iterations`
5. **事件写入与推送**：`IncidentEventService.appendAndPush` 在**同一事务**写 `incident_events` + 推进 `incident.last_seq/phase`，事务提交后才推 SSE（提交失败不推送）。事件只追加不修改（事件溯源）。
6. **SSE 推送**：`SsePushService` 管理连接（一个 incident 允许多订阅者）、15 秒心跳保活。断线重连走 `GET /api/incidents/{id}/stream?since=`，先重放 seq > since 的落库事件再转实时（前端按 sequence 去重）。
7. **断点续跑**：`POST /api/incidents/{id}/resume`（仅 FAILED 可触发）。`rebuildContext` 从全部落库事件重建 LLM 消息上下文，未拿到结果的工具补执行；工具结果从 `tool_idempotency` 幂等复用。
8. **崩溃恢复**：`CrashRecoveryRunner` 启动时把 `updated_at` 超过 `stale-minutes` 的 RUNNING 标记 FAILED，不做自动续跑（工具全只读，用户手动重试无副作用）。

## 目录结构

```
backend/
├── pom.xml / mvnw                  Maven 配置与 wrapper（Spring Boot 4.1.1，Java 21）
├── schema.sql → resources/schema.sql  业务库建表（带 IF NOT EXISTS，老库加列需手动 ALTER，见文件末尾注释）
├── data/                           DuckDB 本地文件（analytics.duckdb，gitignore）
└── src/main/
    ├── resources/
    │   ├── application.yml             默认配置（app.analysis.* 开关在这）
    │   ├── application-local.yml       本地密钥配置（gitignore，照 example 复制）
    │   ├── application-local.yml.example  本地配置模板（改配置结构必须同步此文件）
    │   ├── schema.sql                  MySQL 表结构：alert / incident / incident_events / analysis_report / tool_idempotency
    │   └── mock-data/                  模拟数据（AIOps 竞赛数据 CSV + 排查手册）
    │       ├── logs/ metrics/ traces/  DuckDB 视图的数据源 CSV
    │       ├── runbook-keywords.json   告警名关键词 → 手册文件映射
    │       └── runbooks/*.md           排查手册全文（Fallback.md 为未命中兜底）
    └── java/com/lyl/backend/
        ├── controller/             HTTP 接口（AlertController / IncidentController）
        ├── service/                业务逻辑（分析调度、LLM 循环、事件、SSE、数据查询等）
        ├── tool/                   LLM 工具注册表与上下文
        ├── mapper/                 MyBatis 注解 SQL 接口（无 XML）
        ├── model/                  实体 + ApiResponse 包装
        ├── config/                 配置类（线程池、LLM 客户端装配、DuckDB 连接等）
        └── exception/              业务异常 + 全局异常处理器（统一 ApiResponse 错误格式）
```

## API 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/alerts` | 告警入库；唯一键冲突拒绝；带 `endsAt` 回填结束时间；可选 `isTest/isEval/isProd` 打标（agent 测试告警创建时带 `isTest:true`） |
| POST | `/api/alerts/mark` | 批量打标：`{ids, isProd, isEval, isTest}` 三独立布尔完整状态（无互斥） |
| POST | `/api/alerts/delete` | 批量删除告警：级联删除关联 incident/事件流/幂等记录/报告 |
| GET | `/api/alerts` · `/api/alerts/{id}` | 告警列表/详情（附关联 incident 状态） |
| POST | `/api/incidents/{alertId}/start` | 触发分析（COMPLETED 返回 409） |
| POST | `/api/incidents/{id}/resume` | 断点续跑（仅 FAILED，其余 409） |
| GET | `/api/incidents/{id}` | 分析详情：RUNNING 返回游标；COMPLETED 附报告；FAILED 附 errorMessage |
| GET | `/api/incidents/{id}/events` | 全部落库事件（历史重建用，RUNNING 走 SSE 重放不依赖此端点） |
| GET | `/api/incidents/{id}/stream` | SSE 事件流（`?since=` 或 `Last-Event-ID` 断点重放） |
| POST | `/api/incidents/{id}/report/adoption` | 报告采纳标注（评测）：`{adopted: bool, issues?: ["noise","evidence","sop","logic","leak","redundant"]}`，issues 可空=未说明原因 |
| GET | `/api/stats/evaluation` | 评测指标统计（只读）：采纳率/完成率/耗时 P50·P95/工具调用分布/失控率/FAILED 归因/工具成功率；范围过滤默认正式数据（tag 为空），`?tag=` 单看标签、`?scope=all` 全量、`?since=` 收窄 |
| GET | `/api/stats/evaluation/incidents` | 评测下钻明细：`?metric=failed/slow/toolCalls/runaway/dislike`（toolCalls 需 `&bucket=`，slow 可 `&limit=`），返回告警三要素 + 状态 + 信号/归因 + 标注 |

所有响应统一 `{code, message, data}` 包装（`ApiResponse`）；错误格式见 `exception/GlobalExceptionHandler`。

## SSE 事件契约

- 落库事件格式 `{"sequence": n, "type": t, "data": {...}}`，类型：`incident_received` / `agent_thought` / `tool_call_start` / `tool_call_result` / `evidence_collected` / `report_finalized` / `error`
- `text_delta`（最终回答逐字流）不落库、无 sequence，前端只做展示不参与去重
- `tool_call_result.data` 中 `summary`/`detail`/`error` 为前端时间线文案（ToolCallSummarizer 生成）；`evidence_collected.data.content` 为证据结论文本（非原始 JSON）
- 事件类型增改需同步：落库写入点（LlmAnalysisExecutor / MockAnalysisExecutor）→ 前端 `src/types/index.ts` 与 `ProcessTimeline.vue`

## 「要改 X → 去哪个文件」对照表

| 要改什么 | 文件 |
| --- | --- |
| 告警接收/去重/结束回填/创建时打标规则 | `controller/AlertController.java` |
| 批量打标与级联删除（评测数据治理） | `service/AlertService.java` |
| 评测范围过滤（is_prod/is_eval/is_test 关联 alert） | `service/EvaluationStatsService` 的 ScopeFilter |
| 分析触发的并发与状态机规则（幂等/重试/409） | `service/IncidentService.java` |
| 报告采纳标注（评测）的取值/存储规则 | `controller/IncidentController.submitAdoption`（口径见 `docs/plan/EVALUATION_PLAN.md`） |
| 评测指标统计口径（失控信号/FAILED 归因/分桶/采纳率聚合/scope 过滤） | `service/EvaluationStatsService.java`（接口入口 `controller/StatsController.java`） |
| mock 执行器的事件序列与写死报告 | `service/MockAnalysisExecutor.java` |
| LLM 循环主体（迭代/工具执行/去重/止损/续跑/报告解析） | `service/LlmAnalysisExecutor.java` |
| System Prompt、输出 JSON 格式、置信度锚点 | `service/AnalysisPrompts.java`（**先改** `docs/tech-design-LLM-prompt.md` 再同步代码） |
| 工具清单/参数 Schema/工具执行分发 | `tool/AnalysisToolRegistry.java`（加新工具三处同步：注册表 + `ToolCallSummarizer` 文案 + `LlmAnalysisExecutor.evidenceSource` 证据来源映射） |
| 可观测数据查询 SQL | `service/DataAnalyticsService.java`；CSV → 视图映射在 `config/DataAnalyticsConfig.java` |
| 排查手册关键词/内容、兜底手册 | `resources/mock-data/runbook-keywords.json` + `runbooks/*.md`（匹配逻辑 `service/RunbookService.java`） |
| 工具调用时间线文案（折叠头/展开详情/证据结论） | `service/ToolCallSummarizer.java` |
| 推荐 SOP 归一规则（字符串→{priority,action}） | `service/RecommendedActionsNormalizer.java` |
| SSE 连接管理/心跳/推送失败策略 | `service/SsePushService.java`；事件落库+推送的事务时序在 `service/IncidentEventService.java` |
| 断点续跑上下文重建 | `service/LlmAnalysisExecutor.rebuildContext` |
| 崩溃恢复策略（超时判定/是否自动续跑） | `service/CrashRecoveryRunner.java` |
| 分析线程池大小 | `config/AsyncConfig.java` |
| 模型列表/超时/故障转移 | `config/AiProperties.java`（配置结构）+ `service/LlmChatClient.java`（轮转逻辑） |
| executor/max-iterations/stale-minutes 开关 | `resources/application.yml` 的 `app.analysis.*`（`config/AnalysisProperties.java`） |
| MySQL 连接、LLM 密钥 | `resources/application-local.yml`（不入库；改结构须同步 example） |
| 表结构/加字段 | `resources/schema.sql`（老库需按末尾注释手动 ALTER） |
| 错误响应格式/新增业务异常 | `exception/` |

## 关键约定

- **Agent 只诊断不修复**：所有 LLM 工具都是只读查询；给模型加新工具时禁止引入写操作
- **双库分工**：MySQL 存业务状态（告警/分析/事件/报告，JSON 一律 String 存，兼容 MySQL 5.6）；DuckDB 只读查可观测数据（视图 `logs/metrics/kpi/traces`，时间戳为 Unix 秒，traces 原始毫秒已在视图除以 1e6）
- **DuckDB 连接不共享**：`DataAnalyticsConfig.getConnection()` 每次返回新连接，调用方 try-with-resources 关闭
- **时间格式** `yyyy-MM-dd HH:mm:ss`，不考虑时区；给模型的时间参数用 Unix 秒（首条用户消息已附换算值，防模型算错）
- **工具幂等**：幂等键 = SHA256(incidentId|stepSeq|toolName|args)，崩溃重试直接复用结果，不重跑工具
- **失败兜底统一走 `AnalysisDispatcher.markFailed`**：error 事件 + FAILED + errorMessage 同事务，不要在执行器里各自造失败路径
- **Mapper 用注解 SQL**（`resources/mapper/` 为空占位）；驼峰映射已全局开启
- **测试**：`cd backend && mvn test` 需本机 MySQL（`incident_analyst_test` 库，`mysqluser/mysqlpass123`，见 `src/test/resources/application-test-mysql.yml`）；LLM 集成测试（`LlmLoopIntegrationTest`）用桩模型不真调 API，配置见 `application-test-llm.yml`

## 相关文档

- `docs/tech-design-LLM.md` / `docs/tech-design-LLM-prompt.md`：LLM 循环与提示词设计（改 prompt 先改文档）
- `docs/tech-design-duckdb.md`：可观测数据与 DuckDB 设计
- `docs/DEV_STARTUP.md`：启动步骤、mock 数据造法、常见问题
- `docs/plan/`：各期开发计划（按文件名与内容查找）
