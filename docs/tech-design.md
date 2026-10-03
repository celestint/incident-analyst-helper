## 技术栈
- 前端：Vue 3 + TypeScript + Vite
- UI：Element Plus + ECharts
- 实时通信：EventSource (SSE)
- 后端：Java 21 + Spring Boot 3.x.x
- Agent 框架：LangChain4j（open-ai 适配器，接任意 OpenAI 兼容接口：智谱 GLM / DeepSeek / 百炼兼容模式等）
- 实时推送：Spring `SseEmitter`
- 业务数据存储：MySQL 5.6（Docker）
- 分析数据查询：DuckDB（嵌入式，JDBC 驱动）
- 数据访问：MyBatis
- 构建工具：Maven（后端）、Vite（前端）

### 选型理由与迁移成本

| 技术              | 为什么选                           | 迁移成本                      |
| --------------- | ------------------------------ | ------------------------- |
| **LangChain4j** | Agent 多步工具调用成熟，手动驱动工具循环便于插入事件落库与幂等控制；open-ai 适配器可接任意 OpenAI 兼容服务 | 若换 Spring AI，需重写工具注册和编排逻辑 |
| **MySQL**       | 熟悉、MVP 够用，JSON 字段整体存取无需额外查询能力  | 若未来需要向量检索，可再引入专用向量库或迁移    |
| **DuckDB**       | 直接读 CSV，省去 Python 转换脚本和中间 JSON；SQL 查询灵活，支持多文件 glob | 若换真实数据源（如 Elasticsearch、Prometheus），替换工具层的查询实现即可    |
| **SSE**         | 服务器单向推送，轻量简单，Spring 原生支持       | 若需双向通信，换 WebSocket        |
| **Vue 3**       | 模板语法接近 HTML，Java 背景上手快         | 换 React 需重写组件             |

## 项目结构

后端：
```
backend/src/main/java/com/lyl/incidentanalysthelper/
  controller/     # REST API 与 SSE 端点
  service/        # 业务逻辑：Runbook、Agent 编排、报告生成
  tool/           # @Tool 注解的模拟数据读取方法
  model/          # 数据模型：Incident、Alert、Report 等
  mapper/         # MyBatis Mapper + MockDataLoader（读 mock 文件）
  config/         # LangChain4j、SSE、MySQL / MyBatis 配置
backend/src/main/resources/mock-data/
  logs/           # log_*.csv（DuckDB使用）
  metrics/        # metric_*.csv、kpi_*.csv（DuckDB使用）
  traces/         # trace_*.csv（DuckDB使用）
  groundtruth/    # aiops21_groundtruth.csv（仅用于生成告警，不入 DuckDB）
  runbooks-keywords.json
  runbooks/
    HighMemoryUsage.md
    HighCPUUsage.md
```

前端：
```
frontend/src/
  views/          # 告警总览、分析页
  components/     # 时间线、报告卡片、进度条
  composables/    # useSse、useIncident
  api/            # Axios 封装
  types/          # TypeScript 类型定义
  router/         # 路由
```

### DuckDB说明
见同层级的tech-design-duckdb.md。

## mock-data数据处理注意
- Groundtruth 的用途：仅用于生成模拟告警。Python 脚本从 groundtruth.csv 读记录，构造 POST /api/alerts 请求体，包含故障服务、开始时间、结束时间和告警名。Groundtruth 不含根因，根因由 Agent 从数据推理得出。P1 可用它做自动评测。
- Groundtruth 的时间说明：关注st_time、ed_time，都是东八区，格式为"xxxx:xx:xx xx:xx:xx"，需要转换为Unix秒级timestamp，再传入。


## 数据模型

> 主键统一用自增 Long。时间统一用 `yyyy-MM-dd HH:mm:ss` 格式，不考虑时区。
> 最新版本见resources/schema.sql

### Alert（告警）
- id: Long
- alertName: string
- severity: string
- service: string
- startsAt: string（如 `2025-01-15 22:33:00`）
- labels: string（json转化的字符串）

### Incident（分析事件）（有追加）
- id: Long
- alertId: Long
- status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
- phase: string（细粒度阶段：received/thinking/tool_running/finalizing/done，仅 RUNNING 有意义）
- lastSeq: int（已写入的最大事件序号，前端增量拉取游标）
- reportId: Long（分析完成后才有值）
- errorMessage: string（仅 FAILED 有值）
- createdAt: string
- updatedAt: string（崩溃恢复据此判断 RUNNING 是否超时）
- completedAt: string

### AnalysisReport（分析报告）
- id: Long
- isNoise: boolean（事件摘要：是否噪音报警）
- needsHandling: boolean（事件摘要：是否需要处理）
- rootCauseHypothesis: string（当前报警产生原因）
- confidence: number（0~1）
- recommendedActions: string（json转化的字符串：`[{"priority":1,"action":"...","risk":"LOW|MEDIUM|HIGH"}]`，risk 标注该动作自身的执行风险）
- judgmentLogic: string（判断逻辑：证据融入推理链的叙述文本，证据注明来源/数值/时间点，不单列证据表）

### Evidence（证据，不是独立报告字段）
证据通过两条途径体现：
1. 过程事件：`evidence_collected` 事件 payload（source/content/timestamp），前端在时间线中弱化展示；
2. 报告内容：融入 `judgmentLogic` 推理链中（注明来源/数值/时间点）。
- source: 'metrics' | 'logs' | 'trace' | 'runbook'
- content: string
- timestamp: string

### incident_events（过程事件表）
- id: Long
- incidentId: Long
- seq: int（Incident 内从 1 递增，UNIQUE(incidentId, seq)）
- eventType: 'incident_received' | 'agent_thought' | 'tool_call_start' | 'tool_call_result' | 'evidence_collected' | 'report_finalized' | 'error'
- payload: string（json 字符串，结构随 eventType 变化）
- createdAt: string

### tool_idempotency（工具幂等表）
- idempotencyKey: string（SHA256(incidentId + stepSeq + toolName + args)）
- incidentId: Long
- stepSeq: int
- toolName: string
- result: string（工具执行结果 JSON 字符串，MEDIUMTEXT）
- createdAt: string

## 通信协议（SSE）

SseEvent 是传输格式；落库事件同时写入 incident_events（带 seq，可重放）。

- sequence: number（递增序号，用于排序和断线恢复；只在落库事件上存在）
- type: 'incident_received' | 'agent_thought' | 'tool_call_start' | 'tool_call_result' | 'evidence_collected' | 'report_finalized' | 'error' | 'text_delta'
- data: object

**断线重连契约**：
- 前端记录收到的最大 seq（last_seq）；`text_delta` 不带 seq，不推进 last_seq。
- 重连：`GET /api/incidents/{id}/stream?since=last_seq`，后端先重放 seq > since 的落库事件，再转实时推送。
- 重放流里没有 text_delta：前端收到 report_finalized 时，用完整报告替换任何正在流式显示的半截文本。
- EventSource 原生重连不带自定义 query，前端需主动关闭后带 since 重开。


## 关键技术点

1. **SSE 实时推送**：后端用 `SseEmitter` 逐条推送事件。SseEmitter 非线程安全，用 `ConcurrentHashMap` 管理活跃连接。分析任务在独立线程池执行，不依赖 emitter 生命周期；emitter 超时/断线仅移除推送通道，分析继续，报告照常落库。
2. **LangChain4j 工具调用**：用 `@Tool` 注解将模拟数据读取方法暴露给 LLM，Agent 按 Runbook 顺序调用。Runbook **不直接注入 system prompt**（内容可能变大），而是做成 `getRunbook(alertName)` 工具：LLM 第一步调用获取匹配手册，按手册步骤顺序调用后续工具；无匹配手册则进入探索模式自主分析。循环由后端**手动驱动**（`ChatModel.generate(messages, toolSpecs)` + 自行处理 toolExecutionRequest），不用 AiService 自动执行——事件落库和幂等控制需要插手每次工具调用。避免每个请求都创建新的模型实例。
3. **Runbook 驱动执行**：根据 alertName 匹配预定义步骤，每步调用工具并归一化为证据。
4. **状态机与幂等**：`PENDING → RUNNING → COMPLETED/FAILED`，`POST /start` 仅允许 PENDING/FAILED。COMPLETED 直接返回已关联的报告，不重新执行 Agent。
5. **MySQL 持久化**：Alert、Incident、Report 分表存储，通过外键关联。证据链和推荐操作使用 String 存储。本地开发复用本机已有 MySQL（Docker 默认开启，端口 3306），应用连 `localhost:3306`；不在仓库内维护 `docker-compose.yml`。表结构用 `schema.sql` 初始化（MyBatis 不自动建表）。
6. DuckDB 查询分析数据：原始 CSV 直接放在 mock-data/ 下，工具通过 DuckDB JDBC 执行 SQL。启动时创建视图，避免每次查询扫描文件。时间戳存原始 Unix 秒。多文件用 glob 一次读取。
6. **模拟数据加载**：启动时从 `mock-data/*.json` 读取，通过 `MockDataLoader` 统一访问，便于后续替换真实数据源。对接真实 Prometheus 时，在转换层将 `alertname` 映射为 `alertName`，时间戳转为 `yyyy-MM-dd HH:mm:ss` 格式。


##  API 契约

### 通用响应格式

**成功**：
```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

**错误**：
```json
{
  "code": 400,
  "message": "错误描述",
  "data": null
}
```

> HTTP 状态码与 `code` 保持一致。成功统一 200。

### POST /api/alerts

**用途**：接收告警，存入列表，状态 `PENDING`。不触发分析。  
**调用方**：监控系统 / 模拟数据生成器。 **【外部系统调用，前端不关注】**
**注意**：
1. 相同service、alertName、startsAt，只能由一条数据，不能重复插入
2. alertName、severity、service、startsAt要求非空，否则响应400，message也相应提示"alertName 不能为空"、"severity 不能为空"、"service 不能为空"、"startsAt 不能为空"

**请求**：
```json
{
  "alertName": "HighCPUUsage",
  "severity": "critical",
  "service": "dbservice1",
  "startsAt": 1736951580,
  "labels": {
    "pod": "dbservice1-7d9f8b6c4-x2k9m",
    "namespace": "production"
  }
}
```

**响应 200**：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "alertName": "HighCPUUsage",
    "severity": "critical",
    "service": "dbservice1",
    "startsAt": "2025-01-15 22:33:00",
    "labels": {
      "pod": "dbservice1-7d9f8b6c4-x2k9m",
      "namespace": "production"
    },
    "incidentId": null,
    "status": "PENDING"
  }
}
```

**响应 400**：
```json
{
  "code": 400,
  "message": "alertName 不能为空",
  "data": null
}
```

### GET /api/alerts

**用途**：告警列表，供前端总览页展示。根据 `status` 决定按钮。  
**调用方**：前端。 **【前端关注】**

**响应 200**：
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 1,
      "alertName": "HighCPUUsage",
      "severity": "critical",
      "service": "dbservice1",
      "startsAt": "2025-01-15 22:33:00",
      "incidentId": 1,
      "status": "COMPLETED"
    },
    {
      "id": 2,
      "alertName": "HighMemoryUsage",
      "severity": "warning",
      "service": "cacheservice",
      "startsAt": "2025-01-15 23:10:00",
      "incidentId": null,
      "status": "PENDING"
    }
  ]
}
```

> `status`：`incidentId` 为空时为 `PENDING`，否则取 Incident 状态。

### POST /api/incidents/{alertId}/start

**用途**：点击 [分析] 后调用，创建 Incident 并创建一个新对话。  
**调用方**：前端。 **【前端关注】**
**注意**：
1. 要考虑如果有两个同时触发一个PENDING的要求分析的情况，应该只能有一个200。

**响应 200**：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "incidentId": 1,
    "status": "RUNNING"
  }
}
```

**响应 404**：
```json
{
  "code": 404,
  "message": "告警不存在",
  "data": null
}
```

**响应 409**：
```json
{
  "code": 409,
  "message": "该告警已完成分析，不可重复触发",
  "data": null
}
```

### GET /api/incidents/{id}

**用途**：获取分析详情和报告。用于查看报告、进入分析页、收到 `report` 事件后拉取完整内容。  
**调用方**：前端。 **【前端关注】**
**注意**：
1. 考虑到失败需要了解失败原因，在 Incident 里增加：errorMessage: string（仅 FAILED 状态有值）

**前端怎么用**：
前端进入分析页时，先调这个接口：
如果 status = COMPLETED：直接渲染 report，不建立 SSE。
如果 status = RUNNING：建立 SSE 连接，接收实时事件；这个接口只用来拿基础状态，不提供过程事件。
如果 status = FAILED：显示失败提示和 [重试] 按钮。

**响应 200**：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "alertId": 1,
    "status": "COMPLETED",
    "reportId": 1,
    "createdAt": "2025-01-15 22:33:05",
    "completedAt": "2025-01-15 22:33:15",
    "report": {
      "id": 1,
      "isNoise": false,
      "needsHandling": true,
      "rootCauseHypothesis": "dbservice1 存在异常进程持续占用 CPU，导致服务响应变慢，进而影响支付服务",
      "confidence": 0.85,
      "recommendedActions": [
        { "priority": 1, "action": "检查 dbservice1 上的异常进程", "risk": "LOW" },
        { "priority": 2, "action": "如确认异常进程，考虑重启该服务", "risk": "MEDIUM" }
      ],
      "judgmentLogic": "getMetrics 显示 dbservice1 CPU 使用率 22:23 起升至 95%（来源 metrics）；getLogs 在同时段发现异常高 CPU 占用程序日志 3 条（来源 logs）；getSlowSpans 显示 order-service 调用 dbservice1 平均耗时从 50ms 升至 800ms（来源 trace）。三者时间吻合，得出上述根因假设。"
    }
  }
}
```

**RUNNING 状态的响应示例**：
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "alertId": 1,
    "status": "RUNNING",
    "reportId": null,
    "createdAt": "2025-01-15 22:33:05",
    "completedAt": null,
    "report": null
  }
}

**FAILED 响应示例**：
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "alertId": 1,
    "status": "FAILED",
    "reportId": null,
    "createdAt": "2025-01-15 22:33:05",
    "completedAt": null,
    "errorMessage": "LLM 调用超时，未能在 30 秒内生成结论",
    "report": null
  }
}

**响应 404**：
```json
{
  "code": 404,
  "message": "分析事件不存在",
  "data": null
}
```

### POST /api/incidents/{id}/resume

**用途**：断点续跑。仅 FAILED 状态可触发（其他状态 409、不存在 404）：状态置回 RUNNING，LLM 上下文从 incident_events 最近一次尝试段重建（工具完整结果从 tool_idempotency 取回，中断工具先补执行），从失败点继续分析。
**调用方**：前端。【前端关注】
**与 start（FAILED 时）的区别**：resume 续跑保留上下文从失败点继续；start 是从头再来（全新 LLM 上下文，工具幂等缓存仍复用）。

**响应 200**：
```json
{
  "code": 200,
  "message": "success",
  "data": { "incidentId": 1, "status": "RUNNING" }
}
```

**响应 409**：非 FAILED 状态；**响应 404**：incident 不存在。

### GET /api/incidents/{id}/events

**用途**：历史事件查询（只读）。返回该 incident 全部落库事件 `data: [{sequence, type, data}]`，供 FAILED/COMPLETED 状态下前端重建已执行链路。RUNNING 状态走 SSE 重放，不依赖本端点。
**调用方**：前端。【前端关注】
**错误**：404 incident 不存在。

### GET /api/incidents/{id}/stream

**用途**：SSE 实时推送分析过程。前端进入分析页后建立连接，逐条渲染事件。  
**调用方**：前端。 **【前端关注】**

**响应 200**，`Content-Type: text/event-stream`。事件格式见第 2 节。

**响应 404**：
```json
{
  "code": 404,
  "message": "分析事件不存在",
  "data": null
}
```

---

## 状态码汇总

| 端点 | 用途 | 调用方 | 前端关注 | 成功 | 错误 |
|---|---|---|---|---|---|
| `POST /api/alerts` | 接收告警 | 监控系统 / 模拟生成器 | ❌ | 200 | 400 |
| `GET /api/alerts` | 告警列表 | 前端 | ✅ | 200 | — |
| `POST /api/incidents/{alertId}/start` | 触发分析 | 前端 | ✅ | 200 | 404、409 |
| `GET /api/incidents/{id}` | 分析详情与报告 | 前端 | ✅ | 200 | 404 |
| `GET /api/incidents/{id}/stream` | SSE 实时推送 | 前端 | ✅ | 200 | 404 |

**统一约定**：
- 非 SSE 响应体格式为 `{ "code": ..., "message": ..., "data": ... }`。
- 成功统一 `code: 200`。
- `4xx` 客户端问题，`5xx` 服务端问题（后端记录日志并将 Incident 改为 `FAILED`）。

其余章节（SSE 事件样例、模拟数据、Runbook 结构、配置模板、系统提示词、开发顺序、错误处理）保持不变。

## SSE 事件样例

按实际推送顺序（LLM 分析一个 HighMemoryUsage 告警的示意）：

```
event: message
data: {"sequence":1,"type":"incident_received","data":{"alertName":"HighMemoryUsage","service":"cacheservice","severity":"critical","startsAt":"2025-01-15 23:10:00"}}

event: message
data: {"sequence":2,"type":"agent_thought","data":{"thought":"先获取内存排查手册，再按步骤查指标和日志"}}

event: message
data: {"sequence":3,"type":"tool_call_start","data":{"tool":"getRunbook","args":{"alertName":"HighMemoryUsage"},"stepSeq":1}}

event: message
data: {"sequence":4,"type":"tool_call_result","data":{"tool":"getRunbook","success":true,"summary":"匹配到手册：HighMemoryUsage"}}

event: message
data: {"sequence":5,"type":"tool_call_start","data":{"tool":"getMetrics","args":{"service":"cacheservice","metricName":"memory"},"stepSeq":2}}

event: message
data: {"sequence":6,"type":"tool_call_result","data":{"tool":"getMetrics","success":true,"summary":"内存使用率 95%（23:00-23:10）"}}

event: message
data: {"sequence":7,"type":"evidence_collected","data":{"source":"metrics","content":"内存使用率 95%（23:00-23:10）","timestamp":"2025-01-15 23:00:00"}}

event: message
data: {"sequence":8,"type":"tool_call_start","data":{"tool":"getLogs","args":{"service":"cacheservice","keyword":"error"},"stepSeq":3}}

event: message
data: {"sequence":9,"type":"tool_call_result","data":{"tool":"getLogs","success":true,"summary":"OOM 日志 3 条"}}

event: message
data: {"sequence":10,"type":"evidence_collected","data":{"source":"logs","content":"OOM: java.lang.OutOfMemoryError","timestamp":"2025-01-15 23:08:00"}}

event: message
data: {"type":"text_delta","data":{"delta":"根据指标…"}}
data: {"type":"text_delta","data":{"delta":"，内存使用率…"}}   ← 不带 sequence，不落库

event: message
data: {"sequence":11,"type":"report_finalized","data":{"reportId":1}}
```

> `report_finalized` 的 `data` 只包含 `reportId`，前端收到后调用 `GET /api/incidents/{id}` 获取完整报告，并用它替换任何正在流式显示的半截文本。


## LLM 注意
看同层级的文件tech-design-LLM.md

## 错误处理约定

| 场景       | 后端行为             | 前端表现           |
| -------- | ---------------- | -------------- |
| LLM 超时   | 状态改为 FAILED      | 显示“重试”按钮       |
| 工具执行异常   | 记录错误事件，状态 FAILED | 时间线显示红色错误节点    |
| SSE 连接断开 | 保留已缓存事件          | 重新进入页面时加载已缓存内容 |
| 重复触发分析   | 返回 409           | 提示“该告警已完成分析”   |
| 告警不存在    | 返回 404           | 提示“告警不存在”      |

