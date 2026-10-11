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
- 既有库升级：schema.sql 的建表语句带 `IF NOT EXISTS`，新增列不会自动加到老表——升级代码后按 schema.sql 末尾注释手动执行对应 `ALTER`（如 P3 新增的 `analysis_report.confidence_reason` 列），否则分析会在报告落库时报错

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

### 可观测数据 CSV（后端启动依赖）

`backend/src/main/resources/mock-data/` 下的 `logs/`、`metrics/`、`traces/` 三个目录里的 CSV（`*_0304.csv`）来自 **AIOps 2021 挑战赛数据集**（网上搜索 "AIOps 2021 挑战赛 数据集" 可找到），文件名中的 `0304` 为数据集日期标记。

- 这批文件体积大（合计约 1.5 GB），**不进 git**（目录靠 `.gitkeep` 保留），需要各自放到对应目录，文件名保持 `*_0304.csv`（代码里 `DataAnalyticsConfig` 用 `log_*_0304.csv` 等通配符加载）
- `groundtruth/aiops21_groundtruth_0304.csv`（根因标注，几 KB）随仓库走，不用另下
- **缺了这批 CSV 后端会启动失败**（DuckDB 建视图时 `read_csv` 报错）；只想看前端 / mock 执行器流程的，也必须先放好数据

### 排查手册（runbooks）

`backend/src/main/resources/mock-data/runbooks/` 下的 Markdown 手册供 LLM 分析执行器使用：分析第一步调用 `getRunbook` 获取手册，之后严格按手册步骤执行。

- **匹配规则**：`mock-data/runbook-keywords.json` 维护「手册文件 → 关键词列表」，告警名（忽略大小写）包含任一关键词即命中对应手册（如告警名含 `memory` 命中 `HighMemoryUsage.md`）
- **未命中走兜底**：关键词未命中（或命中手册文件读取失败）时回退返回通用兜底手册 `Fallback.md`——先做告警名价值判断（明显测试/无意义名不调数据工具直接出报告），再按「指标 → 日志 → 调用链」固定顺序、告警窗口/基线窗口双窗口策略排查；三类数据双窗口均空则停止调工具直接出报告
- **新增专用手册**：在 `runbooks/` 下新建 `.md` 并在 `runbook-keywords.json` 加对应关键词即可，无需改代码；建议结构对齐现有手册（原则 / 窗口定义 / 分步判断 / 分析结果）

### groundtruth 告警导入脚本

`scripts/import_groundtruth_alerts.py` 把 groundtruth CSV（AIOps 2021 故障标注）批量导入为告警，逐行调 `POST /api/alerts`，需后端已启动：

```bash
python scripts/import_groundtruth_alerts.py backend/src/main/resources/mock-data/groundtruth/aiops21_groundtruth_0304.csv
# 可选 --base-url 指定后端地址（默认 http://localhost:8080）；一次只接收一个 CSV 文件
```

- **字段映射**：「故障内容」→ `alertName`；`service` 原样；`st_time`/`ed_time` 截到秒后作为告警开始/结束时间（东八区本地时间，不转时区）；`anomaly_type`、故障类别等原始信息存入 `labels`
- **severity 按 `anomaly_type` 映射**（映射表在脚本头部 `SEVERITY_MAP`）：MEMORY / JVM;MEMORY / NETWORK → critical，CPU / JVM;CPU / DISK → warning；出现新取值会跳过该行并提示补表
- **三布尔打标**：`isProd=1`、`isEval=1`、`isTest=0`（既是评测集也计入正式口径）
- **可重复执行**：唯一键 `(service, alertName, starts_at)` 命中已有告警时自动跳过，不会插入重复数据

### 告警与分析

接口告警（外部系统入口，前端不直接用；`endsAt` 可选，为空表示告警未结束）：
```bash
curl -X POST http://localhost:8080/api/alerts \
  -H "Content-Type: application/json" \
  -d '{"alertName":"DatabaseConnectionPoolExhausted","severity":"critical","service":"mysql-prod-01","startsAt":"2026-10-03 09:15:00","endsAt":"2026-10-03 10:30:00"}'
```

**重复 POST 的行为**：同一告警（服务 + 告警名 + 开始时间相同，唯一键）再次 POST 时——
- 携带 `endsAt` 且有变化：**更新**已有告警的结束时间，返回 `updated: true`（用于"告警结束"通知）
- 未携带 `endsAt` 或值没变：拒绝，报"该告警已存在，不能重复插入"

`endsAt` 传空字符串与不传等价（入库统一为 null，表示未结束）；分析页状态栏第二行显示「开始时间 ~ 结束时间」，未结束显示「开始时间 ~ 至今」。

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
