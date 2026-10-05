# 分析显示优化计划（失败态可见性 + 报错封装 + 列表排序微调）

## 背景

上一轮分析显示优化上线后，用户验收又发现 4 个体验问题：

1. **失败态不可见**：分析失败进入页面时，「分析过程」折叠面板默认收起，用户看不到哪里报错。期望失败时默认展开。
2. **报错文案原始**：失败时用户看到 `LLM 调用失败或超时: 全部模型调用失败: glm-4.6V-flash: ...RateLimitException: {"error":{...}} | ...` 这类长串原始异常。期望统一封装为「全部模型调用失败，请重试」。
3. **重试按钮文案冗余**：按钮显示「重试（从失败点继续）」，期望精简为「重试」。
4. **告警总览排序反了**：当前从旧到新排列，期望从新到旧。
5. **事件摘要文案**：「非噪音报警」/「噪音报警」希望去掉「报警」二字（该文案是前端硬编码，非后端返回）。

## 目标 / 不做

**目标**：

1. FAILED 态进入页面时分析过程默认展开，报错位置直接可见。
2. LLM 全模型失败的对外报错统一为「全部模型调用失败，请重试」，详细原因只进日志。
3. 重试按钮文案精简为「重试」。
4. 告警总览按告警开始时间从新到旧排序。
5. 事件摘要噪音标签改为「噪音」/「非噪音」。

**不做（留给后续计划）**：

- 不改 LLM 故障转移机制本身（轮转顺序、重试次数不变，只改对外文案）。
- 不在前端展示详细错误堆栈（详细原因进后端日志，不在 UI 露出）。
- 不改告警筛选/分页逻辑（只改排序）。
- 不改 `ProcessTimeline.vue` 折叠规则（错误步骤本身是平铺红色文本，不依赖折叠状态）。

**前置**：[ANALYSIS_DISPLAY_PLAN.md](./ANALYSIS_DISPLAY_PLAN.md)

## 当前仓库状态

- `frontend/src/components/AnalysisPane.vue`：`loadIncident()` 只有 `status === 'RUNNING'` 时 `processExpanded = ['process']`（L53-60），FAILED 等结束态走 else 全部折叠；重试按钮文案「重试（从失败点继续）」（L207）；事件摘要标签 `isNoise ? '噪音报警' : '非噪音报警'`（L248）。
- `frontend/src/types/index.ts`：`isNoise` 字段注释「事件摘要：是否噪音报警」（L32）。
- `frontend/src/views/AlertList.vue`：`loadAlerts` 直接 `alerts.value = res.data`，前端无排序逻辑，完全依赖后端顺序。
- `backend/.../service/LlmChatClient.java`：`chat()` 全部模型失败时把各模型错误（`模型名: 异常消息`）用 ` | ` 拼接进异常 message（L94）。
- `backend/.../service/LlmAnalysisExecutor.java`：`streamTurn()` catch 里再包一层 `LLM 调用失败或超时: ` 前缀（L397）；最终经 SSE error 事件和 `incident.errorMessage`（落库、详情接口返回）到达前端。
- `backend/.../mapper/AlertMapper.java`：`selectAll()` 为 `ORDER BY id`（升序，即从旧到新，L27）。
- `backend/src/test/java/com/lyl/backend/LlmLoopIntegrationTest.java`：失败场景只断言 FAILED 状态与 `errorMessage` 非空，不断言文案内容，改文案不影响测试。

## 信息索引

| 要查什么 | 看哪里 |
|---|---|
| 需求出处 | 本轮用户验收反馈（2026-10） |
| LLM 故障转移与报错链路 | `backend/src/main/java/com/lyl/backend/service/LlmChatClient.java`、`LlmAnalysisExecutor.java`、`AnalysisDispatcher.markFailed()` |
| 告警列表查询 | `backend/src/main/java/com/lyl/backend/mapper/AlertMapper.java`、`controller/AlertController.java` |
| 前端分析面板 | `frontend/src/components/AnalysisPane.vue` |
| 上一轮计划 | [ANALYSIS_DISPLAY_PLAN.md](./ANALYSIS_DISPLAY_PLAN.md) |

## 已拍板的技术选择

| 点 | 落地选择 |
|---|---|
| 失败展开位置 | 只改外层折叠面板（`AnalysisPane.vue` 的 `processExpanded`）；内层 `ProcessTimeline.vue` 不动，错误步骤平铺即可见 |
| 报错文案生成位置 | **后端**：`LlmChatClient` 抛友好 message，`LlmAnalysisExecutor` 去掉前缀直接透传；详细错误 `log.error` 进日志 |
| 详细错误去向 | 仅后端日志（`LlmChatClient` 汇总各模型错误 + `LlmAnalysisExecutor` 记录最终异常），SSE error 事件与 `errorMessage` 落库均为友好文案 |
| 排序字段 | `ORDER BY starts_at DESC, id DESC`（按告警开始时间倒序，同时刻按 id 倒序兜底；时间格式 `yyyy-MM-dd HH:mm:ss` 字典序即时间序） |
| 噪音文案 | 前端硬编码改「噪音/非噪音」，后端 `isNoise` 布尔字段不变 |

## Todo

- [x] **T1. 前端：FAILED 态默认展开分析过程**
  `AnalysisPane.vue` 的 `loadIncident()`：非 RUNNING 分支中 `processExpanded` 按 `data.status === 'FAILED' ? ['process'] : []` 设置；FAILED 仍不建立 SSE 连接、走 `loadHistoryEvents()`。同步更新模板上方折叠规则注释。

- [x] **T2. 后端：LLM 报错统一封装为「全部模型调用失败，请重试」**
  - `LlmChatClient.chat()`：全部模型失败时改为 `log.error` 记录各模型详细错误（`模型名: 异常`），对外抛出的 message 固定为「全部模型调用失败，请重试」。
  - `LlmAnalysisExecutor.streamTurn()`：catch 里去掉「LLM 调用失败或超时: 」前缀，`log.error` 记录详细原因后透传友好消息（cause 保留供日志排查）。
  - 效果：SSE error 事件与 `incident.errorMessage`（截断 1000 字符落库）均为友好文案。

- [x] **T3. 前端：重试按钮文案精简**
  `AnalysisPane.vue` 按钮文案「重试（从失败点继续）」→「重试」。显示条件（仅 FAILED）与 `handleResume` 逻辑不变。

- [x] **T4. 后端：告警总览从新到旧排序**
  `AlertMapper.selectAll()` 的 `ORDER BY id` → `ORDER BY starts_at DESC, id DESC`。前端 `AlertList.vue` 无需改动。

- [x] **T5. 前端：事件摘要噪音文案**
  `AnalysisPane.vue` 报告区 `isNoise ? '噪音报警' : '非噪音报警'` → `isNoise ? '噪音' : '非噪音'`；`types/index.ts` 字段注释同步为「是否噪音」。

- [x] **T6. 测试与验收**
  `cd backend && mvn test && mvn clean install` 通过（51 个测试，含失败场景集成测试）；`cd frontend && npx vue-tsc -b` 无报错；前端按 README 验证清单手动走查。

## T7. 状态栏第二行显示告警起止时间（用户验收反馈）

**背景**：分析页状态栏没有告警持续时间。系统里没有告警「结束时间」数据（alert 表只有 starts_at，"告警已结束"是借 incident 状态冒充的）。

**已拍板**：alert 表新增 `ends_at` 字段；为空表示告警未结束，状态栏第二行显示「开始 ~ 至今」。

- [x] **T7.1 后端：ends_at 落地**
  `schema.sql` alert 表加 `ends_at VARCHAR(19) NULL`（末尾按约定补 ALTER 注释，本机 `incident_analyst` / `incident_analyst_test` 两库手动 ALTER）；`Alert.java` 加 `endsAt`；`AlertMapper` 4 个 SELECT 与 INSERT 补列，新增 `selectByUniqueKey` / `updateEndsAt`；`AlertController` POST 接收可选 `endsAt`（null/空串归一为 null，表示未结束），createAlert / listAlerts / getAlert 返回体补 `endsAt`。
  **重复 POST 约定**：同一告警（服务+告警名+开始时间相同，唯一键）再次 POST 时，携带 `endsAt` 且有变化 → 更新已有记录的结束时间并返回 `updated: true`（供后续"告警结束"通知使用）；未携带或值没变 → 维持"该告警已存在"拒绝。逻辑已同步到 `docs/DEV_STARTUP.md`。

- [x] **T7.2 前端：状态栏两行**
  `types/index.ts` Alert 加 `endsAt?: string`；`AnalysisPane.vue` 状态栏改两行：第一行现状不变，第二行灰色小字 `{{ alert.startsAt }} ~ {{ alert.endsAt || '至今' }}`；两行总高与左侧告警卡片一致。

- [x] **T7.3 文档**：`docs/DEV_STARTUP.md` 造数据 curl 示例补可选 `endsAt`。

## T8. 流式输出不重复已显示步骤、局部显示 + 工具步骤不再自动展开（用户验收反馈）

**根因**：前端把所有轮次的 `text_delta` 一直累计在底部流式框（仅 report_finalized 清空），而已完成轮次又落库成步骤 → 底部框全是重复。现状只有思考叙述和报告块会边跑边出文字（工具同步执行只推 start/result，无流式文字）。

**已拍板**：思考流式在「思考」步骤右侧固定单行框内只显示最新一行（替换式）；报告块（```json 起）流式仍留在底部框；工具执行中不再自动展开显示参数。

- [x] **T8.1 流式缓冲按轮重置 + 拆分**
  `AnalysisPane.vue`：收到任何带 sequence 的落库事件时清空 `streamingText`（缓冲只保留当前流式轮次），`error` 事件同样清空；以 ` ```json ` 为界拆成思考叙述（界前）/ 报告块（界后），底部流式框只显示报告块。

- [x] **T8.2 思考流式局部显示**
  `ProcessTimeline.vue` 新增 `streamingThought` prop：live 且有内容时在步骤列表末尾渲染流式中的「思考」步骤——图标 + 「思考」标题 + 右侧固定单行框（固定高度、超长省略），框内只显示最新一个非空行随流式替换；`agent_thought` 落库后缓冲清空、转为正常思考步骤，不重复。

- [x] **T8.3 工具步骤去掉执行中自动展开**
  `ProcessTimeline.vue` 的 watch 不再把执行中（`success === null`）的工具步骤加入 `expanded`；执行中仅「调用工具 X · 执行中」+ 旋转图标，结果到达后折叠头变人话、可手动展开（现状不变）；live 时不再自动改动展开状态（手动展开不被后续事件冲掉），非 live 仍全部收起。

## 验收标准（T7/T8 追加）

- 状态栏第二行显示告警起止时间：有 `endsAt` 的告警显示「开始 ~ 结束」，没有的显示「开始 ~ 至今」；状态栏两行总高与左侧告警卡片一致。
- RUNNING 期间：思考流式只在「思考」步骤右侧单行框滚动，底部不再出现重复的已显示步骤文本；底部流式框仅在报告块（```json）流式时出现，完成后被完整报告替换。
- 工具执行中只有折叠头 + 旋转图标，不自动展开；结果后可手动展开查看详情。

## 验收标准

- 分析失败进入页面：「分析过程」面板默认展开，报错位置直接可见。
- LLM 全模型失败时：页面/落库的报错文案为「全部模型调用失败，请重试」，无模型名与原始异常堆栈；各模型详细错误可在后端日志查到。
- FAILED 态按钮只显示「重试」两个字。
- 告警总览最新告警在最上（按 `starts_at` 倒序）。
- 报告事件摘要显示「噪音」/「非噪音」。
- `cd backend && mvn test && mvn clean install`、`cd frontend && npx vue-tsc -b` 通过。
