# Incident Analyst Helper

告警驱动的排障分析助手：运维人员对告警**手动触发**分析，系统（后端 LLM Agent 或 Mock 执行器）按排查手册逐步调用工具收集证据（日志/指标/调用链），前端实时展示分析过程（思考、工具调用、证据），最终生成**只读**分析报告（根因假设 + 判断逻辑 + 推荐 SOP + 置信度）。Agent 只诊断不修复。

## 核心能力

- **告警驱动的排障闭环**：告警入库 → 手动触发 → 实时分析 → 报告归档（根因假设 + 判断逻辑 + 推荐 SOP + 置信度）；状态机 + 行锁防重复触发，回看不重跑，失败可重试、可断点续跑
- **按手册逐步排查**：按告警名关键词匹配专用排查手册（Runbook）严格执行；未命中时回退到通用兜底手册（Fallback.md，固定步骤：指标 → 日志 → 调用链），前端展示手册进度
- **工具调用收集证据**：Agent 通过 getLogs / getMetrics / getSlowSpans 等全只读工具查询日志、指标、KPI、调用链，另有内存占比派生指标工具 getMemoryUsage 与四则运算计算器 calculate，只诊断不修复
- **分析过程实时可视化**：思考、工具调用、证据、报告逐事件经 SSE 推送，前端单条时间线还原 Agent 完整推理路径；断线按 sequence 重放
- **用户视角（仿群消息流）**：仿用户群消息页面，群成员点消息里的「分析」触发同一条分析链路，完成后群内收到四段式报告消息
- **效果评测**：告警打标（测试 / 评测 / 生产）隔离样本；报告页人工「赞同 / 不赞同」标注采集北极星采纳率；SQL 自动统计完成率、端到端耗时、失控率等护栏指标

## 整体架构

```
┌─────────────────────┐   REST /api/*    ┌──────────────────────────────────┐
│  前端 (Vue 3 + TS)   │ ◄───SSE 实时推送──► │  后端 (Spring Boot, :8080)         │
│  告警列表 + 分析面板   │                  │                                  │
└─────────────────────┘                  │  AlertController / IncidentController
                                         │        │ 触发分析（状态机 + 行锁）
                                         │        ▼
                                         │  AnalysisDispatcher ──┬─► MockAnalysisExecutor（固定链路）
                                         │                       └─► LlmAnalysisExecutor
                                         │                             │  LLM 工具循环（LangChain4j 流式）
                                         │                             ▼
                                         │  AnalysisToolRegistry（getRunbook/getLogs/
                                         │   getMetrics/getKpi/countLogs/getSlowSpans/
                                         │   getTraceCount/getMemoryUsage/calculate，全只读）
                                         │        │                    │
                                         │        ▼                    ▼
                                         │  MySQL :3306           DuckDB（本地文件）
                                         │  告警/分析/事件/报告      只读视图 ← mock-data CSV
                                         │                        (AIOps 2021 数据集)
                                         └──────────────────────────────────┘
```

- **双库分工**：MySQL 存业务状态（告警、分析事件、报告、工具幂等记录）；DuckDB 只读查可观测数据（视图 `logs/metrics/kpi/traces`，数据源为 `backend/src/main/resources/mock-data/` 下的 CSV）
- **事件溯源**：分析过程逐事件落库（`incident_events` 只追加），SSE 实时推送 + 断线按 sequence 重放，失败可断点续跑（工具幂等复用）
- **模拟数据**：不接真实系统；LLM 走 OpenAI 兼容接口（智谱 GLM / DeepSeek 等，支持多模型故障转移）

## 快速开始

前置条件：JDK 21、Node.js 18+、MySQL 8（本机 3306），Maven 用自带 wrapper 无需安装。

1. **准备数据**：创建 `incident_analyst` 库并执行 `backend/src/main/resources/schema.sql`；复制 `backend/src/main/resources/application-local.yml.example` 为 `application-local.yml` 填入数据库账号与 LLM API key；下载 AIOps 2021 CSV 放入 `backend/src/main/resources/mock-data/` 对应目录（**缺失会导致后端启动失败**）
2. **启动后端**：`cd backend && ./mvnw spring-boot:run`（:8080）。只想起前端 / 不消耗 API 时用 mock 执行器：`./mvnw spring-boot:run -Dspring-boot.run.arguments="--app.analysis.executor=mock"`
3. **启动前端**：`cd frontend && npm install && npm run dev`（:5173，已配 `/api` 代理）
4. **造告警并触发分析**：`POST /api/alerts` 建告警 → 前端点击或 `POST /api/incidents/{alertId}/start` 触发分析

每步的完整命令、CSV 下载方式、告警造数示例、常见问题见 **[docs/DEV_STARTUP.md](docs/DEV_STARTUP.md)**。

验证：`curl http://localhost:8080/api/alerts` 返回 `{"code":200,...}` 即后端就绪；浏览器打开 http://localhost:5173 能看到告警总览。

## 文档地图

| 文档 | 内容 |
| --- | --- |
| [AGENTS.md](AGENTS.md) | **AI 与协作者必读**：开发约定（分支/git 规范、代码规范、测试要求、文档自维护） |
| [docs/DEV_STARTUP.md](docs/DEV_STARTUP.md) | 前后端完整启动步骤、mock 数据造法、常见问题 |
| [backend/README.md](backend/README.md) | 后端架构、一次分析的完整链路、「要改 X → 去哪个文件」对照表 |
| [frontend/README.md](frontend/README.md) | 前端结构、页面→文件对照、颜色 token / 文案映射等约定 |
| [docs/tech-design.md](docs/tech-design.md) 及同目录其他 tech-design | 各模块技术设计（主设计、LLM 循环、LLM prompt、DuckDB） |
| [docs/plan/](docs/plan/) | 各期开发计划（按文件名与内容查找） |
| [docs/PRD.md](docs/PRD.md) | 产品需求 |

## 给 AI / Agent 协作者

1. 动手改代码前，先读 **[AGENTS.md](AGENTS.md)** 的开发约定
2. 改前端先读 [frontend/README.md](frontend/README.md)，改后端先读 [backend/README.md](backend/README.md)——两份 README 都有「要改 X → 去哪个文件」对照表
3. 需求与设计背景按主题查 `docs/`（技术设计）与 `docs/plan/`（分期计划），不依赖特定文件名
