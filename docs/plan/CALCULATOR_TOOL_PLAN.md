# 计算器工具 calculate(expression) 接入计划

## 背景

- LLM 按手册分析时需要计算指标：`HighMemoryUsage.md` 第 1.1 步有公式 `(UserMem - CacheMem) / (UserMem + MEMFreeMem) * 100`，目前只能靠模型心算。
- 本项目已两次验证模型算术不可靠（[ANALYSIS_DISPLAY_OPT2_PLAN.md](./ANALYSIS_DISPLAY_OPT2_PLAN.md) T8：模型算 Unix 时间窗频繁出错，当时用后端预计算窗口解决；指标公式无法预计算，需要给模型一个计算工具）。
- 与 T8 决定"不加计算器"不冲突：那次针对的是查询窗口时间戳（已由预计算覆盖），本轮针对的是数据类指标公式。

## 目标 / 不做

**目标**：LLM 可调用 `calculate(expression)` 做四则运算；手册与 prompt 说明工具存在与使用时机（需要数值计算时禁止心算，必须调 calculate）。

**不做**：不改 mock 执行器；不加证据事件（calculate 是辅助计算不是证据）；不改前端代码（显示文案由 ToolCallSummarizer 驱动）；不做更复杂的数学函数需求（exp4j 自带 sqrt/pow 等，够用即可，手册不引导使用）。

**前置**：[ANALYSIS_DISPLAY_OPT2_PLAN.md](./ANALYSIS_DISPLAY_OPT2_PLAN.md)（T8 预计算窗口）

## 信息索引

| 要查什么 | 看哪里 |
|---|---|
| 工具注册与执行 | `backend/.../tool/AnalysisToolRegistry.java`（specifications + execute） |
| 工具执行链路（幂等/事件/SSE） | `backend/.../service/LlmAnalysisExecutor.java` `executeTool()` |
| 前端工具步骤文案 | `backend/.../service/ToolCallSummarizer.java` |
| prompt 约定（文档先行） | `docs/tech-design-LLM-prompt.md`、`backend/.../service/AnalysisPrompts.java` |
| 手册 | `backend/src/main/resources/mock-data/runbooks/` |
| 上一轮计划 | [ANALYSIS_DISPLAY_OPT2_PLAN.md](./ANALYSIS_DISPLAY_OPT2_PLAN.md) |

## 已拍板的技术选择

| 点 | 落地选择 |
|---|---|
| 求值实现 | exp4j 0.4.8，轻量无传递依赖（Java 21 已移除 Nashorn，pom 原无表达式库） |
| 输入清洗 | 求值前归一化：去空白 + 全角转半角（（）＋－＊／×÷０-９．＾ 等）；非法输入抛描述性异常回填模型（"工具执行失败: …"，模型可自行修正重试） |
| 返回结构 | `{"expression": "清洗后表达式", "value": 数值}` 对象。**不能返回裸数字**：`LlmAnalysisExecutor.isEmptyResult()` 把裸数字 0 判为"全空"，会误计入止损计数；对象无 `total` 字段天然豁免 |
| 精度 | value 统一 4 位小数（BigDecimal HALF_UP）——0-1 区间指标足够精确，同时清洗浮点噪音（`12.500000000000002` → `12.5`）；前端展示最多 4 位去尾 0 |
| 工具声明位置 | `specifications()` 清单末尾（辅助工具，不干扰 getRunbook→数据工具的主流程） |
| prompt 时机约定 | 「手册要求计算指标值/比率/百分比时必须调用 calculate，禁止心算；expression 为纯数学表达式，变量先代入具体数值」 |
| 前端显示 | 前端对工具步骤通用渲染（无按工具名硬编码），文案全部来自 ToolCallSummarizer：折叠头「执行计算」；展开「计算 {expression}，结果 {value}」（>40 字符截断）；不产生证据行 |
| 调用记录 | 与 getLogs 等同一条 `executeTool()` 链路：`tool_call_start`/`tool_call_result` 事件落库 + SSE 推送、`tool_idempotency` 幂等、续跑重建回放；`evidenceSource` 返回 null，不产生证据条目 |

## 不改的文件（调研结论）

- `LlmAnalysisExecutor.java`：`evidenceSource` default 返回 null ✓；幂等/去重自动复用 ✓；止损由返回结构规避 ✓。
- `MockAnalysisExecutor.java`：mock 路径不走注册表，无需改。
- `frontend/`：无代码改动，`npx vue-tsc -b` 不需跑。

## Todo

- [x] **T1. 文档先行**：`docs/tech-design-LLM-prompt.md` 工具清单加 calculate 一行 + 使用时机约定。
- [x] **T2. 依赖 + 求值器**：pom 加 exp4j；新建 `tool/ExpressionEvaluator`（normalize + evaluate + 4 位小数舍入），单测覆盖：普通四则/嵌套括号/空格/全角符号/除零/非法表达式/浮点噪音清洗/0-1 区间小数精度。
- [x] **T3. 注册表接入**：`AnalysisToolRegistry` specifications + execute case；结果 `{"expression","value"}`。
- [x] **T4. 提示词与手册**：`AnalysisPrompts.SYSTEM_PROMPT` 工具清单；`Fallback.md` 加工具说明段（何时用、变量代入、禁止心算）；`HighMemoryUsage.md` 1.1 公式处注明用 calculate。
- [x] **T5. 前端文案**：`ToolCallSummarizer` 加 calculate case（折叠头「执行计算」/展开「计算 X，结果 Y」/表达式截断；独立小数格式化最多 4 位去尾 0），`ToolCallSummarizerTest` 补用例。
- [x] **T6. 验证与收尾**：`mvn test && mvn clean install`；按 AGENTS.md 文档自维护约定检查根 `AGENTS.md`、`frontend/README.md`、`docs/DEV_STARTUP.md` 是否需同步，确需更新先征得同意再写；`git status` 核对新文件入库。

### 实现与验证结果

- `ExpressionEvaluatorTest` 8 个用例、`ToolCallSummarizerTest` 新增 4 个 calculate 用例全部通过；后端全量 71 个测试通过，`mvnw clean install` BUILD SUCCESS。
- 实现中发现并修复：`execute()` 的 switch 外层已有 `Object result` 变量，calculate 分支改用 `calc` 命名避免编译冲突。
- 文档自维护检查结论：`backend/README.md` 工具导览仅描述"加新工具三处同步"模式（本次正是按此执行），不枚举工具名，无需更新；前端无代码改动，`frontend/README.md` 不涉及；`DEV_STARTUP.md`、根 `AGENTS.md` 不涉及工具清单。

## 追加：getMemoryUsage 内存占比派生指标工具（用户反馈，2026-10-07）

### 背景

一个指标是多个时间点的时序数据，按 HighMemoryUsage.md 1.1 计算占比时模型要逐点调 `calculate`（一次调用只算一个表达式），调用次数多且每次都要模型自行代入数值——这正是要避免的心算环节。改为后端提供专用工具，一次调用返回每个时间点算好的占比。

### 已拍板的技术选择

| 点 | 落地选择 |
|---|---|
| 工具名 | `getMemoryUsage` |
| 参数 | service?/startTime?/endTime?/limit?（与 getMetrics 一致，无 metricName——查询目标固定为内存指标） |
| 数据源 | DuckDB `metrics` 视图，kpi_name 过滤 5 个内存指标：`OSLinux-OSLinux_MEMORY_MEMORY_` 前缀的 UserMem/CacheMem/MEMFreeMem/NoCacheMemPerc/MEMUsedMemPerc |
| 计算规则 | 按时间点分组：NoCacheMemPerc/MEMUsedMemPerc **优先取该点原始指标值**（对齐手册"优先取"），缺失时按手册公式计算——NoCacheMemPerc=(UserMem-CacheMem)/(UserMem+MEMFreeMem)×100，MEMUsedMemPerc=UserMem/(UserMem+MEMFreeMem)×100；该点**拿不到或算不了 → 跳过该点** |
| 输出 | `{total, items: [{time: "2021-03-04 11:50:00", noCacheMemPerc: "80.1%", memUsedMemPerc: "76.2%"}]}`，**按 timestamp 升序**（时序数据按时间正序，便于模型看趋势），limit 默认 100；沿用 {total, items} 约定（total=0 计入止损计数，符合数据工具语义） |
| 数值格式 | 百分比 1 位小数（HALF_UP）+ "%"；time 用项目统一的 `yyyy-MM-dd HH:mm:ss`（系统时区） |
| 证据/记录 | `evidenceSource` → "metrics"，与数据工具同等记录（tool_call 事件、幂等、SSE） |
| 手册 | `HighMemoryUsage.md` 第 1 步改为调用 `getMemoryUsage`（返回已算好，直接判断）；`Fallback.md` 保留 calculate 计算约定（通用兜底手册不绑定内存场景） |
| prompt | `tech-design-LLM-prompt.md` + `AnalysisPrompts` 工具清单加一行：内存类告警直接用它，无需 getMetrics+calculate |

### 改动文件

| 文件 | 改动 |
|---|---|
| `service/DataAnalyticsService.java` | 新增 `getMemoryUsage(service, startTime, endTime, limit)`：查原始行 → 按时间点分组计算；分组计算逻辑抽 package-private static `toMemoryUsageItems` 便于纯单测 |
| `tool/AnalysisToolRegistry.java` | specifications 加 getMemoryUsage（放 getSlowSpans 后、calculate 前）+ execute case |
| `service/LlmAnalysisExecutor.java` | `evidenceSource` 加 `case "getMemoryUsage" -> "metrics"`（唯一需要碰这个文件的一处） |
| `service/ToolCallSummarizer.java` | case getMemoryUsage：折叠头「查阅 N 条指标」，展开「查找 X 的内存占比，得到 N 个时间点」，证据「最新（HH:mm）NoCacheMemPerc 80.1%、MEMUsedMemPerc 76.2%，峰值 NoCacheMemPerc 91.2%；共 N 条」 |
| `docs/tech-design-LLM-prompt.md`、`AnalysisPrompts.java` | 工具清单同步 |
| `runbooks/HighMemoryUsage.md` | 第 1 步改为调用 getMemoryUsage |
| 测试 | 新建纯单测覆盖 toMemoryUsageItems（直接值优先/公式计算/数据不全跳点/1 位小数格式）；`DataAnalyticsServiceTest` 加集成用例；`ToolCallSummarizerTest` 加文案用例 |

### Todo

- [x] **T7. 计划追加**：本文档补充第二轮设计（本节）。
- [x] **T8. DataAnalyticsService.getMemoryUsage** + toMemoryUsageItems 纯单测 + 集成用例。
- [x] **T9. 注册表接入**：AnalysisToolRegistry + evidenceSource("metrics")。
- [x] **T10. 文案与提示词手册**：ToolCallSummarizer 用例；tech-design/AnalysisPrompts 工具清单；HighMemoryUsage.md 第 1 步。
- [x] **T11. 验证与收尾**：`mvnw test && mvnw clean install`；`git status` 核对并 add；计划文档勾选。

### 实现与验证结果（第二轮）

- `DataAnalyticsServiceMemoryUsageTest` 5 个纯单测 + `DataAnalyticsServiceTest.testGetMemoryUsage` 集成用例（Mysql01 真实 mock 数据）+ `ToolCallSummarizerTest` 2 个文案用例全部通过；后端全量 79 个测试通过，`mvnw clean install` BUILD SUCCESS。
- 实现细节与计划差异：limit 限制的是**时间点数**而非原始行数（SQL 层截断会把同一时间点的 5 个指标行截散，导致末点被误判数据不全）；原始行按 timestamp ASC 查询，分组后按 limit 截取前 N 个时间点。
- 用户反馈修正：手册保留 memUsedMemPerc 的参考用途（辅助解释告警触发原因），不能只看 noCacheMemPerc。
- 测试修正：skipsIncompletePoints 用例期望值笔误（(500-100)/(500+400)=44.4% 而非 40.0%）。

## 验收标准

- 单测：ExpressionEvaluator（含全角/空格/除零/非法输入/浮点噪音/0-1 精度）全通过；ToolCallSummarizer calculate 文案正确（0-1 值不丢精度、超长表达式截断）。
- `mvn test && mvn clean install` 通过；前端无代码改动。
- （手动，LLM 模式）对 HighMemoryUsage 类告警触发分析，观察模型在需要计算 NoCacheMemPerc 时调用 calculate，前端时间线出现「执行计算」步骤、展开可见表达式与结果；calculate 返回 0 时不计入止损计数（代码层面已由结构保证）。
