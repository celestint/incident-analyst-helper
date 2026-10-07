# 用户视角前端实现计划（模拟群消息流）

> 本计划由设计阶段产出。视觉唯一参照物：仓库根目录 `userview-preview.html`（自包含静态预览，已与用户逐轮确认）。执行本计划的 agent **先打开该预览通读样式与结构，再动代码**。

## 背景

产品玩法：告警机器人在用户群（企业微信类）里发告警消息，群成员点击消息最后一行的「分析」即触发自动诊断（与"告警分析"工作台的「分析」是同一条链路）；分析过程群里零推送，**分析完成后**机器人往群里追加一条四段式报告消息，**失败时**也推一条失败消息。

「用户视角」就是网页里呈现这个群消息流的页面：**仿企业微信群的只读视图 + 可触发分析**。设计已经过多轮文字图与 HTML 预览确认，本计划负责落地到真实前端。

## 目标 / 不做

**目标**：

1. 新路由 `/user-view`，`AppNavBar.vue` 的「用户视角」占位启用为真实入口
2. 页面 = 仿企业微信群窗口：群名栏 + 消息流 + 右侧信息栏（群公告在上、群成员在下，等高各半）+ 底部只读输入条
3. 消息流由现有数据驱动，机器人三类消息：告警（末行蓝字「分析」）、失败（蓝字「重试」）、报告（独立消息、四段式、不带引用）
4. 「分析 / 重试」复用现有接口与 SSE，状态变化实时反映在消息流里
5. 消息流**默认只显示最新 5 条**机器人消息，更早的收起、可展开
6. 全部颜色走 token（需新增 4 个 token，见"已拍板的技术选择"）

**不做**（后续单独计划）：

- 真实 IM 机器人对接（告警进群、报告回群的推送是后端需求，与前端页面解耦）
- 群成员 / 群公告的真实数据（纯静态假数据 + 模糊化装饰）
- 深色模式、消息搜索、筛选等扩展功能

**前置**：苔原主题已上线（`style.css` token + `AppNavBar.vue` 占位），工作台可用。启动方式见 [DEV_STARTUP.md](../DEV_STARTUP.md)。

## 当前仓库状态（执行前必读）

- 路由 `frontend/src/router/index.ts`：只有 `/`（工作台）与 `/incidents/:id` 重定向
- 导航 `frontend/src/components/AppNavBar.vue`：`tabs` 数组中「用户视角」是 `planned: true` 占位，无 `to`
- 现有接口（`frontend/src/api/`）全部可复用，无需后端改动：
  - `alertApi.getAlerts()` → Alert 数组
  - `incidentApi.startAnalysis(alertId)` → 触发分析（等同群里的「分析」）
  - `incidentApi.getIncident(id)` → Incident（含 `status`、`errorMessage`（仅 FAILED）、`report`（仅 COMPLETED）、`lastSeq`）
  - `incidentApi.getEvents(id)` / `streamIncident(id, lastSeq)` → 落库事件 / SSE 实时流
  - `incidentApi.resumeAnalysis(id)` → 续跑（等同群里的「重试」）
- 类型 `frontend/src/types/index.ts`：`Alert`（id/alertName/severity/service/startsAt/incidentId/status）、`AnalysisReport`（isNoise/needsHandling/rootCauseHypothesis/confidence/recommendedActions/judgmentLogic）
- 报告四段渲染与文案：`AnalysisPane.vue` 是唯一权威参照（事件摘要 tag 文案、`utils/format.ts` 的 `formatJudgmentLogic` 编号逐行格式）。**注意：报告文案近期有过调整（如"噪音报警"→"噪音"），执行时以 AnalysisPane 当前代码为准，不要凭本计划记忆写死**
- 主题 token：`style.css` `:root` 块（苔原 OKLCH），组件内禁止裸色值
- 视觉参照：`userview-preview.html`（根目录）——布局、间距、模糊化、文本化样式的像素级参照；确认后该文件删除

## 信息索引

| 要查什么 | 看哪里 |
| --- | --- |
| 页面视觉（布局/颜色/模糊化/文本化链接） | `userview-preview.html`（仓库根目录，浏览器打开） |
| 「分析」触发行为与轮询兜底 | [AlertList.vue](../../frontend/src/views/AlertList.vue) `handleAnalyze` |
| 报告四段渲染格式 | [AnalysisPane.vue](../../frontend/src/components/AnalysisPane.vue) `report-section` 模板 |
| 判断逻辑编号归一 | [format.ts](../../frontend/src/utils/format.ts) `formatJudgmentLogic` |
| 状态/级别文案 | `frontend/src/utils/labels.ts` |
| SSE 断线重连（lastSeq 增量重放） | [AnalysisPane.vue](../../frontend/src/components/AnalysisPane.vue) `initSse` |
| 导航占位启用方式 | [AppNavBar.vue](../../frontend/src/components/AppNavBar.vue) `tabs` 数组 |
| 主题 token 与 EP 接管 | `frontend/src/style.css` `:root` 块 |

## 已拍板的技术选择

| 点 | 落地选择 |
| --- | --- |
| 页面组件 | 新增 `frontend/src/views/UserView.vue`（页面骨架）+ 建议拆 `UserChatSide.vue`（右侧信息栏，纯静态）等子组件，粒度执行 agent 自定，保持简洁 |
| 路由 | `/user-view`，name `UserView`；AppNavBar `tabs` 中「用户视角」补 `to: '/user-view'` 并去掉 `planned` |
| 布局 | 顶部导航（52px，已有）→ 群聊窗口占满剩余视口：群名栏 48px + 消息流（`flex:1` 内部滚动）+ 右侧信息栏 240px + 底部只读输入条；≤768px 隐藏右侧信息栏 |
| 消息样式 | **全部 IM 文本风格，禁止 tag 圆角 / 按钮**：严重=红字加粗、服务=`[service]`、事件摘要=变色文字（噪音=橙字、需要处理=橙字、不需要处理=绿字，具体文案以 AnalysisPane 当前实现为准）、「分析/重试/分析中…」=蓝字链接 |
| 蓝字链接色 | 新增 token `--color-link: #576b95`（微信系聊天链接蓝）；点击后置灰用 `--color-text-placeholder` |
| 鲜艳语义文字色 | 预览页三个值需提升饱和度，新增 token：`--color-danger-strong: oklch(52% 0.23 27)`、`--color-warning-strong: oklch(55% 0.19 55)`、`--color-success-strong: oklch(40% 0.13 150)`（供"严重/事件摘要"变色文字使用；普通语义色不动） |
| 默认条数 | 消息流**默认只渲染最新 5 条**机器人消息；更早的收起，消息流顶部提供「查看更早的消息」入口（点击展开全部或按批向前加载，执行 agent 按简洁原则选一种）；新产生的消息（分析完成/失败）实时追加并保持在可视区内 |
| 模糊化 | 群公告内容 + 群成员（机器人除外）的头像与名字：`filter: blur(4px)` + `user-select: none` + `pointer-events: none`，纯静态假数据 |
| 右侧信息栏 | 群公告在上、群成员在下，**上下各占一半**（`flex: 1 1 0` + 内部滚动），中间 `border-top` 分隔；群规模 12 人假数据，机器人名字可见 |
| 消息流构成 | 只含机器人消息，按时间升序；每条告警消息后跟其衍生的失败/报告消息。时间分隔条按天显示（同天省略，消息自带时间字段） |
| 分析交互 | 蓝字「分析」点击 → `startAnalysis(alert.id)` → 该条链接置灰显示「分析中…」→ SSE `report_finalized` 后在其后追加报告消息、`error` 后追加失败消息；RUNNING 中断按 AnalysisPane 的 lastSeq 重连模式 |
| 重试交互 | 失败消息的蓝字「重试」→ `resumeAnalysis(incident.id)` → 同上进入分析中流程 |
| 多告警并发 | 用户视角可能有多条告警各自 RUNNING：每条独立跟踪状态（轮询 `getAlerts` 30s 兜底 + 对 RUNNING 的 incident 各建 SSE 或降级轮询，执行 agent 按简洁原则取舍，注意组件卸载时清理连接） |
| 报告渲染 | 复用 AnalysisPane 的四段结构：事件摘要（变色文字）/ 产生原因 / 推荐SOP（"N. 内容"列表）/ 判断逻辑（`formatJudgmentLogic` 编号逐行）；低置信度（confidence < 0.6）在报告消息里也显示"当前置信度较低"提示 |
| 空态 | 无告警时消息流显示空态提示（如"暂无告警消息"） |
| 动画降级 | 无常驻动画可不处理；若加输入条闪烁等必须包 `prefers-reduced-motion` |

## Todo

- [x] **T1. 路由与导航启用**
  `router/index.ts` 加 `/user-view`；`AppNavBar.vue` 的「用户视角」补 `to: '/user-view'`、去掉 `planned`（「监控管理」保持占位）。

- [x] **T2. token 新增**
  `style.css` `:root` 增加 `--color-link`、`--color-danger-strong`、`--color-warning-strong`、`--color-success-strong`（数值见上表），不加 EP 接管（这几个不喂给 Element Plus）。

- [x] **T3. 页面骨架（先静态）**
  新增 `UserView.vue`：群聊窗口布局（群名栏「告警响应群（12）」、消息流、右侧信息栏、只读输入条），右侧信息栏用假数据 + 模糊化（群公告内容、非机器人成员的头像与名字）；对照 `userview-preview.html` 核对样式细节（卡片圆角 `4px 10px 10px 10px`、消息间距 18px、头像 34px 等）。

- [x] **T4. 消息流数据接入**
  加载 `getAlerts()` 渲染告警消息（蓝字「分析」或状态链接）；对 COMPLETED 的告警 `getIncident` 渲染其后的报告消息；对 FAILED 渲染失败消息（`errorMessage` 作失败原因）；时间分隔条按天分组；**默认只渲染最新 5 条 + 「查看更早的消息」展开入口**。

- [x] **T5. 分析 / 重试交互**
  「分析」→ `startAnalysis`、「重试」→ `resumeAnalysis`；点击后该链接置灰「分析中…」；SSE（参考 AnalysisPane 的 `initSse`，含 lastSeq 重连）驱动：`report_finalized` → 追加报告消息并刷新告警状态，`error` → 追加失败消息；`getAlerts` 30s 轮询兜底（可复用 AlertList 模式）；组件卸载清理全部连接与定时器。

- [x] **T6. 验证与文档**
  `npx vue-tsc -b` 通过；浏览器走查：PENDING→分析→RUNNING（链接置灰）→报告追加 / 失败追加→重试，多告警并发，"最新 5 条 + 展开"行为，375/768/1440 三档无横向溢出（768 以下隐藏右侧信息栏）；`frontend/README.md` 的页面对照表、目录结构、对照表补「用户视角」条目；完成后删除 `userview-preview.html`。

- [x] **T7. 失败/报告消息不显示具体时间**
  分析触发当前是手动、未来可能是用户配置的自动触发，失败/报告属于"事后自动回推"消息，显示具体时间无意义（且模拟数据中 completedAt 可能早于告警 startsAt，视觉矛盾）。修改点：`UserView.vue` 消息名行的时间戳只对告警消息显示，失败/报告消息不显示；时间分隔条按天分组仍用消息内部时间字段，不受影响。

## 风险 / 备注

- 群名栏成员数、群公告文案、群成员名单均为**纯装饰假数据**，用户已确认模糊化处理，不要接真数据
- 报告文案（噪音/非噪音、需要处理等）在近期有过调整，**以 AnalysisPane.vue 当前模板与 labels.ts 为准**，本计划中的示例文案仅示意
- 工作台（AlertList）与用户视角共用同一套 Alert/Incident 数据：一边触发分析，另一边通过轮询/SSE 也会看到状态变化，这是预期行为，无需做跨页同步的特殊处理
- 本页面与真实 IM 机器人完全解耦：页面上的"消息流"是按现有数据模拟呈现，后续接入真实机器人时（后端需求）只需保证消息文案模板一致
