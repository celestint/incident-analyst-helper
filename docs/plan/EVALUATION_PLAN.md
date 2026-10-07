# 效果评测指标体系计划

## 目标 / 不做

**目标**：为当前个人 MVP（无真实用户）建立一套可落地的效果评测指标体系，回答两个问题：**输出敢不敢信（质量）**、**为输出付出多少（效率与成本）**。本计划先定指标与采集方案，实施见 Todo。

**不做**（后续视需要启用）：

- 增长类指标（DAU/留存/触发频次）——个人 MVP 无意义
- **groundtruth 相关指标（本计划暂不执行）**：根因溯源准确率 / 方向一致率需先按 groundtruth 故障窗口造对齐的评估告警集（含噪音用例）并建 `groundtruth_fault` 表，启用方案存档见附录
- 证据引用可验证率脚本——需解析报告文本并与工具结果语义比对，MVP 样本量（20~30）下人工顺手看更划算，脚本化后置
- 失控率的"止损触发 / 连续空调用"信号——结果为空可能是数据缺失下的合法行为（按手册多查几轮空结果属预期），单看空调用会误伤；迭代触顶、思考循环两个硬信号已覆盖主要失控场景
- 置信度校准、费用 token 落库——等采纳率样本攒够 / 真要优化成本时再做

## 已拍板的设计原则

讨论中收敛出的三条原则，后续增删指标时遵循：

1. **指标必须挂在能拿到可靠判定依据的层面**——判定依据拿不到（如 groundtruth 无根因字段、报告不复述故障服务/时间窗）的指标不做，硬凑会把大量样本判成"无法评估"
2. **自动化 vs 人工的分配看判定方式**——纯结构化字段能算的（事件流、表字段）→ SQL 自动化；需要"读懂文本"做语义判断的（结论合理性、证据支撑、SOP 质量）→ 人工标注
3. **告警已携带故障类型+服务名，任何"报告是否识别了故障类型/服务"的指标都是送分题**——指标只度量分析超出告警信息本身的增量（真伪判别、证据支撑、可执行 SOP）

## 指标体系（定稿）

| 层 | 指标 | 定义 | 采集方式 |
| --- | --- | --- | --- |
| **北极星** | 分析采纳率 | 被人工判定"赞同"的完成分析 ÷ 已标注的完成分析 | 人工标注 |
| 结果 | 分析完成率 | COMPLETED ÷ 触发总数 | SQL |
| 结果 | 端到端耗时 P50/P95 | `created_at → completed_at`（P99 在 MVP 样本量下无意义，不取） | SQL |
| 过程 | 工具调用次数分布 | 每次 incident 的工具调用数（是否在预算内收敛；量模型效率，不量管道） | SQL |
| 质量 | （并入采纳率，无独立自动化指标） | 见下方"采纳率的标注口径" | 人工标注 |
| 护栏 | 失控率 | 迭代触顶、思考循环两个硬信号任一命中即算失控 | SQL |
| 护栏 | FAILED 归因分布 | 按 `error_message` 细分失败原因 | SQL |
| 护栏 | 工具成功率 | `tool_call_result` success 占比（量管道，稳态应≈100%；跌破时拆环境错误 vs 模型传参错误） | SQL |

### 北极星：采纳率的标注口径

报告页展示后人工点**赞同 / 不赞同**：

- 点赞同 → 记录一条采纳
- 点不赞同 → **可选**勾选不满足的子维度（多选，可以全不选直接跳过）

| 子维度 | 判定内容 |
| --- | --- |
| 噪音判断 | 告警背后有没有真故障的判断不合理（isNoise / needsHandling 判错） |
| 证据支撑 | 推理链不自洽、与告警已知信息矛盾、引用的数值/时间点不真实 |
| SOP 可执行 | recommendedActions 不具体、不可操作、排序混乱 |
| 判断逻辑 | 从证据到结论的推断不合理（结论与证据脱节、归因牵强、前后矛盾），与"证据支撑"的区别：后者是证据本身有问题，前者是证据对但推不出结论 |
| 内部逻辑泄露 | 报告文本暴露内部执行细节（工具名 / prompt / 手册路径 / 执行器机制等不该让使用者看到的内容） |
| 信息冗余 | 报告内容重复啰嗦、信息组织或展示方式不合理，影响阅读与使用 |

子维度分布只基于勾选样本统计，**接受跳过导致的缺失**（缺失本身就是信息：不赞同但说不清原因的占比）。采纳率分母为已标注样本，未标注不计入。

### 失控率硬信号定义

| 信号 | 识别方式 | 说明 |
| --- | --- | --- |
| 迭代触顶 | `incident.error_message` 含"达到最大迭代次数" | 用尽全部预算仍未出报告 |
| 思考循环 | 同一 incident 内 `agent_thought` payload 内容完全相同出现 ≥2 次 | 去重机制针对的行为模式即"连续输出相同思考+相同工具调用"；重复的工具调用被内存去重拦下不落库，但重复思考会落库，是可观测的循环痕迹 |

### FAILED 归因分类

按 `error_message` 归类：LLM 调用失败（网络/限流/全部模型失败）、报告 JSON 解析失败、迭代触顶、其他。

## 采集方案

### SQL 层（零维护，直接查库）

数据都在 MySQL：`alert` / `incident` / `incident_events` / `analysis_report` / `tool_idempotency`。完成率、耗时、失控硬信号、FAILED 归因、工具成功率、调用次数分布均可从这些表算出，实施为一条 SQL 或一个轻量统计接口（见 Todo T1）。

关键口径：

- 耗时用 `created_at`/`completed_at`（VARCHAR 存 `yyyy-MM-dd HH:mm:ss`，可直接比较）
- 失控信号需解析 `incident_events.payload`（TEXT 存 JSON），在 SQL 里用字符串匹配或取出到应用层算
- 工具调用次数从 `tool_idempotency` 按 `incident_id` 计数（幂等表即执行记录，且天然去重）

### 人工层（唯一需要新增的记录）

- **采集交互**：报告出来后，报告页新增"赞同 / 不赞同"操作；不赞同时可勾选原因子维度，可跳过
- **记录载体**：`analysis_report` 加 `adopted`（TINYINT，NULL=未标注）与 `adopt_issues`（VARCHAR 存 JSON 数组字符串，如 `["evidence","sop"]`，空=未说明原因），实施细节见 Todo T2
- **样本量**：先攒 20~30 条已标注分析再算采纳率，绝对值无意义，看比率与不赞同原因分布

### 基线口径与数据污染打标（已拍板）

测试期曾专门使用限流模型制造失败场景，历史数据中的 FAILED 归因、耗时等指标被污染，**不作为基线**。

**评测页范围切换（已按三分类调整）**：正式数据（is_prod=1）/ 评测集（is_eval=1）/ 测试数据（is_test=1）三项；`?scope=all` 保留在 API 层但不上 UI。三个布尔**相互独立、无互斥**：一条数据可同时属于多个桶（如既是正式又进了评测集）。

**数据打标方案 v2（页面打标，布尔列只挂 alert，支持多标签）**：

- 只有 `alert` 加 `is_prod`（DEFAULT 1）/ `is_eval` / `is_test`（DEFAULT 0）三个布尔列：新告警默认正式；统计与明细按 `incident.alert_id` 关联 alert 取布尔过滤（单一事实来源，incident 不冗余；已加的 incident.tag 列迁移后删除）
- `POST /api/alerts` 支持携带标记：**agent/ai 造的测试告警创建时就标 `isTest`**，生来不进正式口径
- 告警总览页新增多选模式：列表头多选图标进入 → 卡片出现圆形勾选框、顶部显示"已选 N 个告警"、底部操作栏**打标 / 删除**
  - 打标：弹窗**三个独立勾选** 正式 / 评测集 / 测试，提交完整三布尔状态，批量更新 alert
  - 删除：确认弹窗后级联删除 alert + 其 incident / incident_events / tool_idempotency / analysis_report（避免孤儿数据）
- **约定：agent/ai 测试要么创建告警时带 isTest，要么事后用打标接口/页面补标**，否则留在正式口径（无法自动识别，靠约定兜底）
- 存量污染数据一次性 UPDATE 打标（如标记为测试并移出正式）：
  `UPDATE alert SET is_test=1, is_prod=0 WHERE <按时间或 id 圈定>;`

## 评测页（前端展示，已拍板）

入口：AppNavBar 现有"规划中"占位里启用**评测** tab，新页面 `EvaluationView.vue`。

**卡片布局**（置顶到护底）：北极星卡（采纳率，大字）→ 结果卡（完成率、耗时 P50/P95）→ 过程卡（工具调用分布）→ 护栏卡（失控率、FAILED 归因、工具成功率）。

**下钻交互**（不做平铺明细表——用户关心的是"这个数字是谁"）：

- 每张卡有下钻价值的数字可点 → 卡片下方内联展开该指标过滤后的 incident 列表 → 行尾"查看"跳 `?incidentId=` 分析页
- 下钻映射：失败数→FAILED 列表（附归因）；P95→耗时 Top N；调用分布桶→该桶 incident；失控率→命中信号的 incident（标注信号名）；FAILED 归因每项→该归因列表；不赞同数→不赞同报告（附原因）
- 后端明细接口按指标参数化：`GET /api/stats/evaluation/incidents?metric=...`（`failed` / `slow` / `toolCalls&bucket=` / `runaway` / `dislike`，同样吃 scope 过滤，join alert 带出告警三要素）

**行文案口径**（禁止工程缩写）：

- 每行两行式：第一行 = 告警三要素 `服务 · 告警名 · 告警开始时间`（与左栏告警卡同款，长文本省略 + tooltip）；第二行 = 状态文案（走 `labels.ts`）+ 信号解释 + 耗时
- incident ID 不进界面（只在跳转链接里）
- 失控信号解释：**迭代触顶** = 模型用满最大分析轮数仍未产出报告；**思考循环** = 模型连续输出一模一样的思考内容（卡住绕圈的痕迹）。FAILED 归因同理带浅白解释（如"LLM 调用失败（限流/网络/密钥）"）

## 首轮评测的执行顺序

1. T1 统计 SQL → 用历史数据验证口径算得对（不产出基线数字）
2. T2 标注功能上线 → 开始采集干净的基线数据
3. 新数据样本 ≥20 后出首轮评测报告 → 决定优化方向（改 prompt / 换模型 / 调工具）

## Todo

- [x] **T1. 指标统计 SQL / 轻量接口**（已完成）
  拍板：做成**只读接口**（验证口径时页面直接看，后续天然是观测入口），不走 SQL 手册。
  落地：`GET /api/stats/evaluation`（`controller/StatsController.java` + `service/EvaluationStatsService.java`），可选 `since` 参数（yyyy-MM-dd）圈定范围以排除被污染的历史数据；耗时取 P50/P95；失控硬信号含"思考循环"（同一 incident 内相同 `agent_thought` payload ≥2 次；连续空调用为合法行为，不做信号）；工具调用分布只统计有调用的 incident（mock 执行器无工具调用）。

- [x] **T2. 赞同/不赞同标注落地**（已完成）
  落地：`analysis_report` 加 `adopted`（TINYINT(1)，Boolean 三态映射）/ `adopt_issues` 列（schema.sql + 两个老库已 ALTER）；标注接口 `POST /api/incidents/{id}/report/adoption`（赞同直接提交，不赞同原因可选、可跳过=空数组）；详情接口回传 `adopted`/`adoptIssues`；前端报告页在判断逻辑之后加灰色"AI生成，仅供参考"+ 赞同/不赞同按钮，不赞同弹原因勾选弹窗。

- [ ] **T3. 首轮评测报告**
  标注功能上线后攒新数据，样本 ≥20 产出首轮评测结论（文档追加在本 plan 末尾或独立报告文档）。

- [x] **T4. 数据打标与统计 scope**（已完成）
  `incident` 加 `tag` 列（schema.sql + 两个老库已 ALTER）；start 接口加可选 `?tag=` 参数（FAILED 重试时携带则同步更新标签）；统计与明细接口按 scope 过滤（默认正式 / `?tag=` / `?scope=all`，tag 与 scope=all 互斥）。验证数据 incident 17/22 已打 `test` 标作为存量打标示范；**其余历史污染数据的打标由使用者自行执行**：`UPDATE incident SET tag='test' WHERE <按时间或 id 圈定>;`

- [x] **T5. 评测接口增量**（已完成）
  统计接口补采纳率聚合（annotated/adopted/dislike/dislikeNoReason/rate/issueCounts）；新增 `GET /api/stats/evaluation/incidents?metric=failed|slow|toolCalls&bucket=|runaway|dislike`（join alert 带告警三要素，标注状态一次加载避免 N+1）。

- [x] **T6. 评测页前端**（已完成）
  `views/EvaluationView.vue` + 路由 `/evaluation` + AppNavBar 启用"评测"tab；北极星卡 + 结果/过程/护栏卡；可点数字内联展开下钻面板（`api/stats.ts` 的 drill），行文案按"评测页"章节口径；范围切换（正式/全部）+ 起始日期。浏览器走查通过（含 tag 排除、下钻、查看跳转、范围切换）。

- [x] **T7. 页面打标与删除（告警多选）+ 评测页三分类**（已完成）
  - 后端：`alert` 加 `is_prod`（默认 1）/ `is_eval` / `is_test` 三独立布尔列（两库已 ALTER，incident.tag 已迁移删除）；`POST /api/alerts` 支持创建时携带 `isTest/isEval/isProd`（agent 测试告警生来是 test 且默认移出正式）；`POST /api/alerts/mark` 批量打标（三布尔完整状态）；`POST /api/alerts/delete` 批量删除（级联删 incident/事件/幂等/报告，**删除顺序：子表 → incident → report**，因 incident.report_id 外键指向 report）；统计与明细改为关联 alert 过滤（AlertService 承载批量逻辑）
  - 前端：告警总览多选模式（列表头 Finished 图标进入 → 圆形勾选框 + "已选 N 个告警" + 底部打标/删除，打标弹窗三个独立勾选）；评测页范围切换三分类（正式/评测集/测试，可重叠桶）
  - 浏览器走查通过：多选勾选、打标提交（真实点击路径）、三分类视图计数正确、级联删除清理干净（alert/incident/events/report 全 0）

- [x] **T8. 告警分析页数据范围过滤**（已完成，2026-10 整理的现有问题 1）
  **问题**：告警总览列表把正式/评测集/测试告警混在一起显示，日常使用只想看正式告警，目前只能靠肉眼分辨。
  **拍板**：列表头"批量选择"图标（Finished）**左侧**新增一个过滤图标，点开按 正式 / 评测集 / 测试 单选过滤，**默认正式**。
  **落地**：`AlertList.vue` 纯前端过滤——列表接口本就返回三布尔，不加后端参数（与"scope=all 保留在 API 层不上 UI"同一取舍：只暴露三个桶，不做"全部"选项）；过滤图标带 tooltip 显示当前范围，空列表提示"当前范围（X）暂无告警"；被过滤掉的告警不影响右栏已打开的分析（选中告警从全量列表查找）。浏览器走查通过：默认正式 11 条 / 评测集 3 条 / 测试 11 条，与接口数据一致。

- [x] **T9. 不赞同原因子维度调整**（已完成，2026-10 整理的现有问题 2）
  **问题**：三个子维度不够用，且部分命名不准："真伪判别不合理"实际说的是噪音判断；"推荐SOP 不可执行"漏掉"SOP 本身不合理"的情况；缺少"判断逻辑不合理"和"泄露内部执行逻辑"两类常见不赞同原因。
  **拍板**：
  - 新增两个子维度：**判断逻辑不合理**（logic）、**泄露内部执行逻辑**（leak），判定内容见上方"采纳率的标注口径"表
  - 改名：**真伪判别不合理 → 噪音判断不合理**；**推荐SOP 不可执行 → 推荐 SOP 不合理或不可执行**
  - **存量兼容**：落库的是英文 key（`noise`/`evidence`/`sop`/`logic`/`leak`），noise 与 sop 的 key 不变只改展示文案，历史标注数据无需迁移
  **落地**：`types/index.ts` 的 `AdoptIssue` 类型与 issueCounts 类型、`AnalysisPane.vue`（原因弹窗五个勾选项 + 原因文案映射）、`EvaluationView.vue`（北极星卡原因分布改为按 ISSUE_ORDER 动态拼装）、后端 `IncidentController.ALLOWED_ADOPT_ISSUES` 白名单与 `EvaluationStatsService` 的 issueCounts 固定 key，均已同步。vue-tsc 与后端 mvn test（59 个用例）通过；弹窗五选项浏览器走查通过。**注意：需重启后端进程后新 key 才会被接受（旧进程白名单还是三个 key）。**

- [x] **T10. 标注与过滤 UI 四项修正**（已完成，2026-10 走查发现的现有问题）
  1. **评测页原因分布出现 `undefined`**（"判断逻辑 undefined · 内部逻辑泄露 undefined"）：前端按五个 key 取值但未兜底，后端旧进程只返回三个 key 时直接显示 undefined。**落地**：`EvaluationView.issueSummary` 取值加 `?? 0`；后端重启后根因消除。走查确认旧后端下显示"判断逻辑 0 · 内部逻辑泄露 0"
  2. **告警总览标题右侧两个图标间隔过宽**：Element Plus 相邻按钮默认 `margin-left: 12px` 与 flex gap 叠加，且图标按钮左右各有 15px 内边距，"图标到图标"的视觉空隙是 gap + 两侧内边距。**落地**：`AlertList.vue` 清零相邻按钮 margin、gap 2px、按钮内边距收紧到 6px，实测图标间距 14px（< 图标本身 16px），点击不受影响
  3. **触摸屏点击过滤图标无反应**：el-tooltip 与 el-popover 套在同一个触发元素上是 Element Plus 不推荐的用法，触摸端点击被吞。**落地**：去掉嵌套 tooltip，改用原生 title 提示；修复后鼠标标准点击恢复（此前 Playwright 标准点击也超时，印证冲突），**触摸屏请实际再点一次确认**
  4. **不赞同原因顺序与排版**：顺序调整为 噪音判断 → 证据支撑 → 推荐 SOP → 判断逻辑 → 内部执行逻辑（页面勾选文字不变），标注口径表与后端 issueCounts 顺序同步；弹窗每项单独一行。走查确认五项五行、顺序正确

- [x] **T11. 不赞同原因追加"信息冗余或展示不合理" + 弹窗行间距加大**（已完成，2026-10）
  **问题**：六个原因仍缺"报告内容重复啰嗦 / 展示方式不合理"这一类；原因弹窗改成每项一行后行间距太小（gap 10px）。
  **拍板**：新增子维度 **信息冗余或展示不合理**（key: `redundant`，短名"信息冗余"），排在**最后**（噪音判断 → 证据支撑 → 推荐 SOP → 判断逻辑 → 内部逻辑泄露 → 信息冗余）；弹窗行间距加大到 16px。
  **落地**：`AdoptIssue` 类型与 issueCounts、`AnalysisPane.vue`（勾选项 + 文案映射 + 间距）、`EvaluationView.vue`（ISSUE_TEXT / ISSUE_ORDER）、后端 `ALLOWED_ADOPT_ISSUES` 与 issueCounts、backend/README.md 接口示例均已同步；存量数据无需迁移（纯新增 key）。vue-tsc 与后端 mvn clean install 通过；浏览器走查确认六项、顺序正确、行间距 16px。**注意：需重启后端进程后 `redundant` key 才会被接受。**

## 附录：方向一致率（暂不执行，仅存档启用方案）

启用条件：按 groundtruth 故障窗口 + 服务**故意造一批评估告警**（含窗口外的噪音用例）。届时：

1. 导入 `groundtruth_fault` 表（service、fault_type、start_time、end_time，时间统一 `yyyy-MM-dd HH:mm:ss`，cmdb_id 与告警 service 命名对齐）
2. 核对口径：告警窗口内 → 报告应 `needs_handling=1`；窗口外 → 应 `is_noise=1`；LEFT JOIN 命不上的告警天然归为噪音桶
3. 该指标定位是回归底线（改 prompt/换模型后先跑它），与采纳率不交叉：采纳率 ≤ 方向一致率恒成立

groundtruth 现状：`backend/src/main/resources/mock-data/groundtruth/aiops21_groundtruth_0304.csv`（仅故障内容与时间，无根因字段），未建表、DuckDB 无视图。2026-10 已通过 `scripts/import_groundtruth_alerts.py` 把该 CSV 的 28 条记录导入为告警（isProd=1、isEval=1，见 docs/DEV_STARTUP.md「groundtruth 告警导入脚本」）；`groundtruth_fault` 表仍未建，启用方向一致率时按上文方案单独导入。
