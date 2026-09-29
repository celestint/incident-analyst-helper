## 项目概述
告警驱动的排障分析助手 MVP。用户手动触发分析，系统实时展示分析过程，生成只读报告。

## 开发规范
- 后端：Java 21、Spring Boot 4.1.1、LangChain4j（Agent 阶段再接入）、MyBatis
- 前端：Vue 3、TypeScript、Vite、Element Plus、ECharts（指标曲线为 P1，脚手架可不装）
- 数据库：MySQL（本机 Docker 默认开启，端口 3306，应用直连；仓库不维护 docker-compose），JSON 以 String 存证据链等半结构化数据；表结构用 `schema.sql` 初始化
- 实时推送：SSE（SseEmitter + EventSource）
- 模拟数据放 `resources/mock-data`，不接真实系统
- 时间格式统一 `yyyy-MM-dd HH:mm:ss`，不考虑时区
- Java 包名：`com.lyl.backend`（与现有工程一致）

## 计划分期
- 脚手架：[docs/plan/PROJECT_INIT_PLAN.md](plan/PROJECT_INIT_PLAN.md)
- 无 LLM 薄闭环：[docs/plan/THIN_LOOP_PLAN.md](plan/THIN_LOOP_PLAN.md)
- Agent / Runbook：后续另开计划

## 代码风格
- Java：类 PascalCase，方法/变量 camelCase，常量 UPPER_SNAKE_CASE
- 前端：组件 PascalCase，组合式函数 use 前缀
- 统一驼峰命名，如 `alertName`，避免 `alertname`
- 常量使用 UPPER_SNAKE_CASE

## 测试要求
- 每个功能完成后手动测试
- 确保数据正确存储和读取
- 测试各种边界情况
- 后端改动后：`cd backend && mvn test && mvn clean install`

## 注意事项
- Agent 只诊断不修复，不执行写操作
- 保持代码简洁，避免过度设计
- 优先实现核心功能
- 密钥、token 不进代码；本地密码放 `application-local.yml`（gitignore）
