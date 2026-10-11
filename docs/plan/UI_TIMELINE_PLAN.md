# 前端 UI 改进计划（分析过程时间线 + 审计问题）

## 背景

对首页（`AlertList.vue` 工作台）做了一次 hallmark 审计，截图与标注见 `docs/plan/pic/audit-1-overview.png`、`audit-2-annotated.png`、`audit-3-mobile-375.png`。主要发现：全页使用 Element Plus 出厂默认配色（`#409eff` 等裸 hex 散落三个文件）、窄屏（375px）横向溢出（`scrollWidth 417 > 375`）、分析过程时间线存在空行与低对比度灰字。同时用户希望时间线步骤从纯文字 tag 换成「图标 + 名称 · 状态」的样式（参考 Claude Code 式的时间线渲染）。

## 目标 / 不做

**目标**：

1. 分析过程时间线图标化：每个步骤用彩色图标 + 名称 + 状态后缀（如 `思考 · 已完成`、`调用工具 getMetrics · 成功`），替代现有纯文字 el-tag
2. 修复时间线空行、低对比度灰字
3. 修复审计主要问题：主题 token 化（替换框架默认蓝）、窄屏溢出、常驻动画的 reduced-motion 降级

**不做**（留给后续计划）：

- 后端任何改动（SSE 事件协议、事件字段均不动）
- 深色模式
- 更换组件库或大规模视觉重设计
- 审计中的次要项（脚手架残留清理、死代码删除只顺带做，不展开）

**前置**：薄闭环完成（THIN_LOOP_PLAN），前端工作台可用。启动方式见 [DEV_STARTUP.md](../DEV_STARTUP.md)。

## 当前仓库状态

- 时间线渲染在 `frontend/src/components/ProcessTimeline.vue`：事件流聚合为五类步骤（info / thought / tool / error / final），每步用 `el-timeline` + 文字 `el-tag` + 可折叠 `el-collapse` 渲染
- 分析面板在 `frontend/src/components/AnalysisPane.vue`：状态色硬编码在 JS（`statusColor` map）、模板内联 style 多处
- 全局样式 `frontend/src/style.css`：无 token，仅基础 reset
- 图标：未安装 `@element-plus/icons-vue`（Element Plus 本体不含图标，需单独引入）
- 已知 bug 顺带修：`AlertList.vue` 的 `loading` ref 永远为 `false`（`v-loading` 失效）、`ProcessTimeline.vue` switch 中重复的 `case 'error'`（后者不可达）

## 信息索引

| 要查什么 | 看哪里 |
| --- | --- |
| 时间线聚合规则 | [ProcessTimeline.vue](../../frontend/src/components/ProcessTimeline.vue) 顶部注释 |
| SSE 事件类型 | `frontend/src/types/index.ts`（`PersistedEventType`） |
| 状态色 / 内联 style 位置 | [AnalysisPane.vue](../../frontend/src/components/AnalysisPane.vue) `statusColor`、模板 `style="..."` |
| 审计问题明细与截图 | `docs/plan/pic/audit-2-annotated.png`（图上编号 1–7） |
| 空时间线行现象 | `docs/plan/pic/audit-1-overview.png`（两条空白节点行） |
| 测试要求 | [docs/AGENTS.md](../AGENTS.md) §测试要求 |

## 已拍板的技术选择

| 点 | 落地选择 |
| --- | --- |
| 图标来源 | **`@element-plus/icons-vue`**（与组件库同源，线性单色，可按步骤类型着色），需新增依赖 |
| 步骤图标映射 | 开始分析→`Promotion`（主题蓝）· 思考→`ChatDotRound`（橙）· 调用工具→`Tools`（橙）· 错误→`CircleCloseFilled`（红）· 报告完成→`DocumentChecked`（绿） |
| 步骤行结构 | 「彩色图标 + 名称 · 状态后缀」：如 `思考 · 已完成`、`调用工具 getMetrics · 成功 / 失败 / 执行中`；折叠交互保留 el-collapse，仅改头部呈现 |
| 主题 token | `style.css` 新增 `:root` token 块（语义色 / 灰阶 / 边框），通过 Element Plus `--el-color-primary` 系列变量接管主题色；组件内裸 hex 全部替换为 token 引用 |
| 窄屏策略 | `@media (max-width: 768px)` 单列堆叠；`html, body { overflow-x: clip }`；告警名 `overflow-wrap: anywhere` |
| 动画降级 | `pulse`（状态红点）与 `blink`（流式光标）包 `@media (prefers-reduced-motion: reduce)` 收敛为静态 |

## Todo

- [x] **T1. 引入图标包并图标化时间线步骤**
  `npm install @element-plus/icons-vue`；`ProcessTimeline.vue` 中把每类步骤的文字 tag 替换为「图标 + 名称 · 状态」行：图标按上表映射着色，状态后缀按 `success / success==null→执行中 / 失败` 输出；`getStepLabel` / `getStepColor` 改造为返回图标与颜色的统一映射。

- [x] **T2. 时间线视觉修整**
  修复空行：聚合时无内容（无 args、无 summary、无 evidence 且非执行中）的 tool 步骤与空 thought 不渲染 `el-timeline-item`，或保证 collapse 头部始终有标题；"思考"折叠头与正文颜色 `#909399` → `#606266`（AA 达标）；时间线节点圆点颜色按步骤类型语义化（与图标色一致）。

- [x] **T3. 主题 token 化**
  `style.css` 新增 `:root` 块：定义 `--color-primary` 等语义 token 并映射到 `--el-color-primary` 及其 `light-* / dark-*` 梯度，替换 Element Plus 默认蓝；`AlertList.vue`（hover/active 边框、选中底色）、`AnalysisPane.vue`（`statusColor` map、`.cursor`、边框灰阶）全部改引 token；清理模板内联 `style="..."`（改为 scoped class）；顺带删除死 CSS（`.pane-card`、`.streaming-box h4`）与重复的 `case 'error'`。

- [x] **T4. 布局与动画降级**
  告警名加 `overflow-wrap: anywhere; min-width: 0`（修复长名截断与溢出）；`.workbench` 加 `@media (max-width: 768px)` 单列堆叠（左栏不再定宽 380px）；`html, body { overflow-x: clip }`；`pulse` / `blink` 包 `prefers-reduced-motion` 降级；`AlertList.vue` 的 `loading` 在请求前置 true / finally 置 false，失败补 `ElMessage.error`；删除 `views/HelloWorld.vue`。

- [x] **T5. 手动测试**
  按 [AGENTS.md](../AGENTS.md)：后端 `cd backend && mvn test`；mock 执行器启动（见 [DEV_STARTUP.md](../DEV_STARTUP.md)），走查 PENDING / RUNNING / COMPLETED / FAILED 全状态（RUNNING 看流式光标、FAILED 看续跑按钮）；浏览器 375 / 768 / 1440 三档宽度检查无横向滚动、无截断；键盘 Tab 可选中告警卡片。

## T6. 时间线呈现微调（用户验收反馈，已完成）

T1–T5 落地后走查发现的呈现问题与修复：

| 问题 | 根因 | 修复 |
| --- | --- | --- |
| 步骤图标左侧的时间线圆点和竖线多余 | `el-timeline` 自带节点 | `ProcessTimeline.vue` 弃用 `el-timeline`，改普通纵向堆叠行；图标与"分析过程"标题文字左对齐 |
| "分析过程"的展开箭头在面板最右，不好找 | Element Plus 折叠箭头默认 `margin-left: auto` | `AnalysisPane.vue` 覆盖 `.el-collapse-item__arrow { margin: 0 0 0 6px }`，箭头紧跟标题 |
| "分析过程"字号看起来比"事件摘要"小 | "事件摘要"是 14px 加粗，折叠头默认无字重 | 折叠头显式 `font-size: 14px; font-weight: 600` |
| 步骤文字有的重叠、有的太宽 | 折叠头固定 24px 高，长思考摘要换行溢出 | 全部步骤行统一单行省略：`思考 · [摘要…]` 超宽截断，工具行单行，等宽无重叠 |

## T7. 告警总览与分析状态栏改版（用户验收反馈，已完成）

新增公共文案工具 `frontend/src/utils/labels.ts`（级别/状态 → 文案与 tag type 的统一映射，两个组件消重）。状态文案拍板：**COMPLETED 已完成→已分析、FAILED 失败→分析失败**，PENDING=待分析、RUNNING=分析中不变。

| 问题 | 根因 | 修复 |
| --- | --- | --- |
| 告警卡的"严重/警告"级别标签不齐 | 告警名宽度不定，tag 跟着名字走 | 卡片首行改为「级别 tag + 服务 tag + 告警名」：名走 `flex:1` 吃剩余宽度，tag 位置固定 |
| 告警名多行显示 | `overflow-wrap: anywhere` 允许换行 | 改单行省略（`nowrap + ellipsis`），外包 `el-tooltip` 悬浮 300ms 显示全名 |
| 滚动时分析过程/报告文字压在吸顶状态栏上 | 状态栏吸顶在面板 16px 内边距内，顶部与两侧留缝，内容从缝里露出 | 16px 边距从 `.analysis-pane` 移到内容区（`.section`/`.report-section` 各自 margin），状态栏 `top:0` 满宽吸顶 |
| 状态栏有明显阴影、未占满上块 | `box-shadow` + `border-radius` + 边距内缩 | 去阴影去圆角，满宽平铺，仅留 1px 底边线 |
| 状态栏信息量不足（只有"分析状态：xx"） | — | 状态栏改为「级别 tag + 服务 tag + 告警名（超长省略+tooltip）+ 状态 tag + FAILED 重试按钮」；告警信息由左栏 `selectedAlert` 传入 |

## T8. 二次验收反馈（已完成）

| 问题 | 根因 | 修复 |
| --- | --- | --- |
| 已完成的分析里"思考"行右侧仍显示摘要字 | T6 单行省略时保留了摘要 | 思考折叠头摘要加 `v-if="live"`：RUNNING 实时显示滚动摘要，已完成/失败态仅显示「思考」，展开仍看全文 |
| T7 改版误删了告警卡最左侧的状态圆点 | 重排卡片首行时未保留 | 恢复「状态圆点 + 级别 tag + 服务 tag + 告警名」：COMPLETED 灰点、其余红点脉冲（含 reduced-motion 降级） |

## T9. 三次验收反馈（已完成）

| 问题 | 根因 | 修复 |
| --- | --- | --- |
| 告警页看不到时间 | 卡片第二行只有操作按钮 | 第二行左侧追加 `startsAt` 灰色小字（12px），`space-between` 布局；第二行 `padding-left: 18px`（圆点 10px + 间距 8px）使时间与首行级别 tag 左缘对齐 |
| 分析状态栏下方有灰色线 | `.status-bar` 的 `border-bottom` | 去掉底边线，状态栏完全无边线 |
| 思考展开有大小不一的空行 | 两个来源：① EP 折叠内容自带 25px 底内边距（正文下方空行）；② 思考文本自带首尾换行，`pre-wrap` 渲染带出（正文上方空行） | ① `:deep(.el-collapse-item__content) { padding-bottom: 0 }`，步骤间距统一由 `.steps` gap 控制；② 聚合时对思考文本 `trim()` |
| "报告已生成"前一步的思考是报告正文，二次展示冗余 | report_finalized 前的最后一个 agent_thought 即报告内容 | 聚合后处理：末步为 `final` 时移除紧邻其前的 thought（FAILED 无 final 不受影响） |

## T10. 滚动条优化 + 前端说明文档与指令入口重组（已完成）

| 项 | 内容 |
| --- | --- |
| 滚动条常驻 | 新增 `composables/useScrollFlash.ts`（滚动置 true、停止 800ms 复位）；`.alert-list` 与 `.analysis-pane` 滚动条默认全透明，仅 `.is-scrolling` 时显示灰色细条（`scrollbar-color` + `::-webkit-scrollbar-*` 双方案） |
| 前端说明文档 | 重写 `frontend/README.md`：页面→文件对照、目录结构、「要改 X → 去哪个文件」对照表、关键约定（token/labels/SSE/省略/reduced-motion/a11y）、验证清单；**含文档自维护约定：每次前端改动收尾前检查本 README 是否需同步更新，更新前征得用户同意** |
| 指令入口重组 | `docs/AGENTS.md` 迁移为根 `AGENTS.md` 并融合原根 CLAUDE.md 内容（分支/Git 规范、application-local.yml 与 .example 同步约定、文档自维护约定需征得同意）；根 `CLAUDE.md` 与 `frontend/CLAUDE.md` 指针化；`.zcodeignore` 清理已迁移的 `docs/AGENTS.md` 死条目 |

## 验收标准

- 时间线每个步骤显示「图标 + 名称 · 状态」，无空节点行，思考文字对比度 ≥ 4.5:1
- 页面中不再出现 `#409eff` / `#ebeef5` 等裸 hex（全部走 token）
- 375px 宽度下无横向滚动条，左右栏堆叠展示
- `prefers-reduced-motion: reduce` 下无持续动画
- 后端零改动，`mvn test` 通过
