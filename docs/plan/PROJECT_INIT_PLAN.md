# 项目初始化计划（脚手架）

## 目标 / 不做

**目标**：后端能连上 MySQL、表结构可初始化、五个 API 有空壳；前端工程能 `npm run dev` 并打开两个空路由页。

**不做**（留给 [THIN_LOOP_PLAN.md](./THIN_LOOP_PLAN.md) 及后续 Agent 计划）：

- Incident 状态机业务逻辑
- SSE 真实推送 / 时间线 UI
- LLM、Runbook 匹配、探索模式
- 告警列表完整交互

下一阶段：[THIN_LOOP_PLAN.md](./THIN_LOOP_PLAN.md)（无 LLM 的可跑通薄闭环）。

## 当前仓库状态

- 后端：Spring Boot 空项目（`spring-boot-starter-webmvc` + lombok + test），parent **4.1.1**，Java **21**
- 包名：`com.lyl.backend`（`[BackendApplication.java](../../backend/src/main/java/com/lyl/backend/BackendApplication.java)`）
- `[application.yml](../../backend/src/main/resources/application.yml)`：仅 `spring.profiles.active: local`
- 无 `frontend/`、无 MyBatis / 实体 / Controller
- 本机 MySQL（Docker）已默认可用，宿主机端口 **3306**；**不需要**在仓库根目录再写 `docker-compose.yml`



## 信息索引


| 要查什么                                    | 看哪里                                                |
| --------------------------------------- | -------------------------------------------------- |
| MVP 做什么、页面、验收一句话                        | [docs/PRD.md](../PRD.md) §3、§5、§7                  |
| 技术栈、包结构、实体字段、MyBatis/MySQL              | [docs/tech-design.md](../tech-design.md) 开篇 + 数据模型 |
| API 路径、响应 `{code,message,data}`、404/409 | [docs/tech-design.md](../tech-design.md)「API 契约」   |
| SSE 事件 type / sequence / 推送顺序           | 同文件「通信协议」+「SSE 事件样例」                               |
| mock 文件格式、Runbook 写法                    | 同文件「模拟数据样例」                                        |
| 错误时前后端表现                                | 同文件「错误处理约定」                                        |
| 仓库级开发约定                                 | [CLAUDE.md](../../CLAUDE.md)                       |




## 已拍板的技术选择

以 **tech-design 为准**；仓库现状优先于设计里过时的包名 / Boot 版本表述。数据访问以本计划拍板为准（MyBatis）。


| 点           | 落地选择                                                                                                |
| ----------- | --------------------------------------------------------------------------------------------------- |
| 数据访问        | **MyBatis**（不是 Spring Data JPA）                                                                     |
| 数据库         | 复用本机已有 MySQL（Docker 默认开启，端口 **3306**）；库名建议 `incident_analyst`；JSON 以 String 落库。**不**在仓库内维护 `docker-compose.yml` |
| 建表          | MyBatis **不**自动建表；用 `schema.sql`（或等价脚本）初始化                                                          |
| Java 包名     | 保持 `com.lyl.backend`，不改为设计中的 `com.lyl.incidentanalysthelper`                                        |
| Spring Boot | 保持 parent **4.1.1** + Java 21；Web 用现有 `spring-boot-starter-webmvc`；加 MyBatis starter + MySQL driver |
| LangChain4j | **本阶段不加**（依赖与编排放后续 Agent 计划）                                                                        |
| 前端图表        | P0 初始化 **不装 ECharts**（指标曲线是 P1）                                                                     |


目标目录（后端挂在现有包下）：

```
backend/src/main/java/com/lyl/backend/
  controller/ service/ tool/ model/ mapper/ config/ exception/
backend/src/main/resources/
  mock-data/          # 可建空目录；json/md 由你提供
  schema.sql          # 表结构初始化
  mapper/             # MyBatis XML（若用注解则可省略）
```

前端：`frontend/src/{views,components,composables,api,types,router}`。

## Todo

- [x] **T1. 后端依赖与本地数据源**  
  ✅ **已完成**：`pom.xml` 已增加 MyBatis Spring Boot Starter + MySQL driver；`application.yml` / `application-local.yml` 已配置；`schema.sql` 已创建。

- [x] **T2. 包目录 + 模型 / Mapper**  
  ✅ **已完成**：`Alert` / `Incident` / `AnalysisReport` / `Evidence` / `ApiResponse` POJO 已创建；`schema.sql` 已建三张表。

- [x] **T3. 统一响应 + 空 Controller**  
  ✅ **已完成**：`ApiResponse` 已创建；`IncidentController` 已实现三个端点（`POST /api/incidents/{alertId}/start`、`GET /api/incidents/{id}`、`GET /api/incidents/{id}/stream`）；`AlertController` 需补充 `GET /api/alerts` 端点。

- [x] **T4. 全局异常骨架**  
  ✅ **已完成**：`GlobalExceptionHandler` 已实现，统一包装为 `{code,message,data}`；业务码留给薄闭环。

- [x] **T5. Vite 前端脚手架**  
  ✅ **已完成**：`frontend/` 已创建（Vue 3 + TS + Vite）；依赖：Element Plus、Vue Router、Axios、Pinia（**不装 ECharts**）。`vite.config.ts` 代理 `/api` → `8080`；路由：`/` 告警总览、`/incidents/:id` 分析页（可几乎空白）。



## 完成标准

1. 本机 **3306** MySQL 可连，`application-local.yml` 配置正确
2. `schema.sql` 已执行，三张表存在
3. `cd backend && mvn test && mvn clean install` 通过
4. `mvn spring-boot:run` 启动无报错（能连库）
5. `cd frontend && npm run dev`，5173 能打开 `/` 与 `/incidents/1`
6. 五个 API 路径已注册（可用 curl 打到 Controller，不要求业务正确）

不要求 Postman 走通「告警 → 分析 → 报告」全流程（那是薄闭环）。