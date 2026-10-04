# 前后端启动文档

本文档描述本地开发环境的完整启动流程。所有命令均在仓库根目录 `incident-analyst-helper/` 下执行，Windows 环境使用 Git Bash。

## 前置条件

| 依赖 | 版本要求 | 说明 |
| --- | --- | --- |
| JDK | 21 | Spring Boot 4.1.1 要求 Java 21 |
| Node.js | 18+ | 前端 Vite 8 |
| MySQL | 8.x | 本机 3306 端口，需已启动 |
| Maven | 无需安装 | 后端自带 `mvnw` wrapper |

数据库要求（与 `backend/src/main/resources/application-local.yml` 一致）：

- 库名：`incident_analyst`（不存在需先创建：`CREATE DATABASE incident_analyst DEFAULT CHARACTER SET utf8mb4;`）
- 账号：`mysqluser` / `mysqlpass123`
- 表结构：由 `backend/src/main/resources/schema.sql` 初始化（首次启动前手动执行，或确认表已存在）

## 后端启动（端口 8080）

```bash
cd backend
./mvnw spring-boot:run
```

默认激活 `local` profile，`app.analysis.executor=llm`（真实调用智谱 API，密钥在 `application-local.yml`）。

**只想看 UI / 前后端联调、不消耗 API 时，用 mock 执行器启动：**

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--app.analysis.executor=mock"
```

mock 执行器会快速生成一条完整分析链路（思考 / 调用工具 / 报告），用于前端状态走查。

**验证**：

```bash
curl http://localhost:8080/api/alerts
# 返回 {"code":200,"message":"success","data":[...]} 即成功
```

## 前端启动（端口 5173）

```bash
cd frontend
npm install   # 首次或依赖变更后执行
npm run dev
```

Vite 已配置代理（见 `frontend/vite.config.ts`）：`/api` → `http://localhost:8080`，前端无需关心跨域。

**验证**：浏览器打开 <http://localhost:5173>，能看到「告警总览」列表，右栏为分析面板。

## 造测试数据

接口告警（外部系统入口，前端不直接用）：

```bash
curl -X POST http://localhost:8080/api/alerts \
  -H "Content-Type: application/json" \
  -d '{"alertName":"DatabaseConnectionPoolExhausted","severity":"critical","service":"mysql-prod-01","startsAt":"2026-10-03 09:15:00"}'
```

触发分析（`alertId` 替换为上一步返回的 id；FAILED 状态下再次调用即重新分析）：

```bash
curl -X POST http://localhost:8080/api/incidents/{alertId}/start
```

断点续跑（仅 FAILED）：

```bash
curl -X POST http://localhost:8080/api/incidents/{incidentId}/resume
```

## 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| 后端启动报连接失败 | MySQL 未启动，或 `incident_analyst` 库 / 账号密码与 `application-local.yml` 不符 |
| 8080 端口占用 | `netstat -ano | findstr :8080` 找到进程后结束，或改 `server.port` |
| curl 输出中文乱码 | Windows 终端编码问题（GBK vs UTF-8），页面显示正常，可忽略；必要时 `chcp 65001` |
| 前端列表为空 | 正常现象（库中无告警），按上文「造测试数据」POST 几条即可 |
| 分析一直 RUNNING | llm 执行器下检查 `application-local.yml` 的 API key / 网络；mock 执行器下刷新页面 |
| 密钥提交风险 | 密钥只放 `application-local.yml`（已 gitignore），不要写进 `application.yml` |

## 状态速查

告警 / 事件状态：`PENDING`（待分析）→ `RUNNING`（分析中，SSE 实时推送）→ `COMPLETED`（已完成，出报告）/ `FAILED`（失败，可续跑）。
