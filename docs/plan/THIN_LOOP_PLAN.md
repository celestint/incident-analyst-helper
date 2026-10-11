# 薄闭环计划（无 LLM）

## 目标 / 不做

**目标**：在 [PROJECT_INIT_PLAN.md](./PROJECT_INIT_PLAN.md) 脚手架之上，不接 LLM，走通 PRD 运维闭环的「形状」：告警进列表 → 点分析 → 分析页收到 SSE → 假报告归档 → 再进只读、不重跑。

**不做**（留给后续 Agent 计划，建议路径 `docs/plan/`）：

- LangChain4j / 阿里云百炼
- Runbook 关键词匹配与两种执行模式（手册 / 探索）
- `@Tool` 真实读 mock 指标/日志/trace 并推理
- 指标曲线（ECharts，P1）

**前置**：脚手架完成（本机 MySQL 3306、MyBatis 模型/Mapper、`schema.sql`、五个 API 路径、前端空页）。见 [PROJECT_INIT_PLAN.md](./PROJECT_INIT_PLAN.md)。

## 当前仓库状态

以脚手架完成后的状态为起点：目录与空 Controller 已在，薄闭环填业务。

## 信息索引

| 要查什么 | 看哪里 |
| --- | --- |
| 运维闭环与验收句 | [docs/PRD.md](../PRD.md) §3 |
| 告警总览 / 分析页交互 | [docs/PRD.md](../PRD.md) §5 |
| 实体字段、状态机、关键技术点 | [docs/tech-design.md](../tech-design.md) 数据模型 + 关键技术点 |
| API 契约、404/409 | 同文件「API 契约」+「状态码汇总」 |
| SSE 事件 type / sequence / 推送顺序 | 同文件「通信协议」+「SSE 事件样例」 |
| 错误时前后端表现 | 同文件「错误处理约定」 |
| 脚手架边界与目录约定 | [PROJECT_INIT_PLAN.md](./PROJECT_INIT_PLAN.md) |

## 已拍板的技术选择

与初始化计划一致，本阶段额外约定：

| 点 | 落地选择 |
| --- | --- |
| 分析执行 | **Mock 执行器**：异步推固定/缩短版 SSE 序列，写死一份 `AnalysisReport`，无 LLM |
| SSE 连接管理 | `ConcurrentHashMap` + `SseEmitter`（见 tech-design 关键技术点 §1） |
| 告警造数 | `POST /api/alerts` 或启动种子即可；从 `mock-data/alerts.json` 加载为加分项，不阻塞 |
| 筛选栏 | PRD 说可选；本阶段可不做或极简 |

## Todo

- [x] **T1. Alert 入库与列表**
  实现 `POST /api/alerts`、`GET /api/alerts`（字段与契约一致：`labels` 入参对象、库内 String）。`incidentId` 为空时列表 `status` 为 `PENDING`，否则取 Incident 状态。

- [x] **T2. Incident 状态机**
  `POST /api/incidents/{alertId}/start`：仅 `PENDING` / `FAILED` 可触发；`RUNNING` / `COMPLETED` → **409**；告警不存在 → **404**。创建并落库 `Incident`，状态 `RUNNING`。

- [x] **T3. Mock 分析执行器 + SSE**
  start 后异步：按 tech-design「SSE 事件样例」推送（可缩短，但 type 齐全：`step` / `tool_call` / `tool_result` / `evidence` / `report` / `done`）；落库固定 `AnalysisReport`；`done` 后 Incident → `COMPLETED` 并关联 `reportId`。`GET .../stream` 用 Map 管理 emitter，超时/断线移除。分析任务在独立线程中执行，不依赖 emitter 生命周期。emitter 超时/断线仅移除推送通道，分析继续，报告照常落库。

- [x] **T4. GET incident 详情**
  `GET /api/incidents/{id}` 返回 status + 嵌套 `report`（COMPLETED 时有完整内容）。已完成再 start 仍 409。

- [x] **T5. DuckDB 集成与视图**
  配置 DuckDB 持久化连接（./data/analytics.duckdb），启动时创建视图 logs / metrics / kpi / traces（统一 service 列和秒级 timestamp）。实现 MockDataTools 中的 getLogs（返回 {total, items}）、countLogs（只返回数字）、getMetrics、getKpi、getSlowSpans。

- [x] **T6. 前端薄 UI**
  - 总览：列表 + 按 status 显示按钮（分析 / 查看进度 / 查看报告 / 重试）；FAILED 时可进入分析页查看失败原因
  - 分析页：进入时调 GET /api/incidents/{id}，按 status 分流：
    - RUNNING → EventSource 接 SSE，按 type 渲染时间线文本节点
    - COMPLETED → 直接渲染报告
    - FAILED → 展示 errorMessage 和 [重试]
  - 收到 report 事件后调 GET /api/incidents/{id} 展示只读报告

- [x] **T7. 后端测试**
  至少覆盖：状态机（可触发 / 409 / 404）、各个接口的注意点通过、统一响应包装；`mvn test` 通过。

## 完成标准

对应 PRD 验收句，再加异常码：

1. 发一条告警（POST）→ 总览可见
2. 点分析 → 状态变分析中 → 分析页实时看到 SSE 节点
3. 得到报告 → 状态已完成；「查看报告」直接加载，不重跑
4. 对 COMPLETED 再 start → 409；不存在的 alert/incident → 404
5. 启动后能通过工具查询到指定服务在时间窗口内的日志、指标、trace。查询结果字段与预期一致，时间窗口无数据时返回空列表，不报错。
6. `cd backend && mvn test && mvn clean install` 通过

## 后续

本闭环之后，在 `docs/plan/` 另开 **Agent 计划**，覆盖：

- LangChain4j + DashScope
- Runbook / 探索两种模式
- `@Tool` 读 `mock-data`（metrics / logs / traces）
