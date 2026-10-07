# 前端说明（告警分析工作台）

面向人与 AI 的前端项目导览：快速了解结构、找到"要改 X 该动哪个文件"、遵守项目约定。全项目开发约定见根 [AGENTS.md](../AGENTS.md)，启动方式见 [docs/DEV_STARTUP.md](../docs/DEV_STARTUP.md)。

## 技术栈

Vue 3（组合式 API + `<script setup>`）· TypeScript · Vite 8 · Element Plus 2.9（+ `@element-plus/icons-vue` 图标）· vue-router · axios

## 页面 → 文件对照

| 页面 | 路由 | 实现文件 |
| --- | --- | --- |
| 首页 · 告警分析工作台 | `/`（`?incidentId=` 保存选中态） | 左栏告警总览：`src/views/AlertList.vue`；右栏分析面板：`src/components/AnalysisPane.vue`（内嵌时间线 `src/components/ProcessTimeline.vue`） |
| 评测总览（指标卡 + 下钻明细） | `/evaluation` | `src/views/EvaluationView.vue`（数据来自 `src/api/stats.ts`，口径见 `docs/plan/EVALUATION_PLAN.md`） |
| 用户视角 · 模拟群消息流 | `/user-view` | `src/views/UserView.vue`（右侧信息栏：`src/components/UserChatSide.vue`，纯静态假数据 + 模糊化） |
| 旧详情链接兼容 | `/incidents/:id` | 重定向到 `/?incidentId=:id`，无独立页面 |

路由注册在 `src/router/index.ts`；新增页面需在此加路由并同步更新本表。

## 目录结构

```
frontend/
├── index.html / vite.config.ts     入口与构建配置（vite 已配 /api 代理到 8080）
└── src/
    ├── main.ts                     应用入口（Element Plus 全局注册 + 样式引入顺序：EP 样式在前，style.css 在后覆盖主题）
    ├── App.vue / router/           根组件与路由
    ├── style.css                   全局样式 + 设计 token（:root 块，全站颜色唯一定义处）
    ├── api/                        axios 封装与后端接口（统一解 { code, message, data } 包装；HTTP 非 2xx 时透传响应体中的业务 message）
    ├── types/index.ts              与后端契约的类型定义（Alert/Incident/SseEvent 等，改接口先看这里）
    ├── utils/labels.ts             级别/状态 → 文案与 tag 类型的统一映射（AlertList 与 AnalysisPane 共用）
    ├── utils/format.ts             报告文本格式化（判断逻辑编号归一/全角转半角，AnalysisPane 共用）
    ├── composables/                组合式函数（useSse/useIncident/useScrollFlash 等）
    ├── views/AlertList.vue         首页左栏：告警总览（卡片列表）
    ├── views/EvaluationView.vue   评测总览：指标卡（采纳率/完成率/失控率等）+ 按指标下钻明细
    ├── views/UserView.vue         用户视角：仿企业微信群只读视图（机器人消息流 + 分析/重试触发）
    └── components/
        ├── AnalysisPane.vue        右栏分析面板：吸顶状态栏 + 分析过程 + 分析报告
        ├── AppNavBar.vue           顶部导航：品牌名 + 视角切换器（用户视角/告警分析/评测）
        ├── UserChatSide.vue        用户视角右侧信息栏：群公告/群成员（纯静态假数据 + 模糊化）
        └── ProcessTimeline.vue     分析过程时间线（SSE 事件流聚合为步骤，图标化渲染）
```

## 「要改 X → 去哪个文件」对照表

| 要改什么 | 文件 |
| --- | --- |
| 顶部导航、品牌名、视角切换器 | `src/components/AppNavBar.vue`（新页面就绪后在 tabs 数组补 `to` 路由） |
| 告警卡片/列表布局、筛选、轮询 | `src/views/AlertList.vue` |
| 告警多选模式（批量打标/删除，评测数据治理） | `src/views/AlertList.vue`（打标弹窗三布尔独立勾选） |
| 用户视角群消息流（消息卡片/分析触发/SSE/默认 5 条展开） | `src/views/UserView.vue` |
| 用户视角右侧信息栏（群公告/群成员假数据） | `src/components/UserChatSide.vue` |
| 右栏顶部状态栏、分析报告排版 | `src/components/AnalysisPane.vue` |
| 分析过程时间线（步骤图标/折叠/聚合规则） | `src/components/ProcessTimeline.vue` |
| 主题色/灰阶/全局 token | `src/style.css`（`:root` 块 + `--el-color-*` 接管） |
| 级别/状态文案（如"已分析""严重"） | `src/utils/labels.ts` |
| 报告区 SOP/判断逻辑的编号格式与文本归一 | `src/utils/format.ts`（模板在 `AnalysisPane.vue`） |
| 报告采纳标注（AI生成提示 + 重新分析 + 赞同/不赞同 + 原因弹窗） | `src/components/AnalysisPane.vue`（原因取值定义在 `src/types/index.ts` 的 `AdoptIssue`；重新分析调 start 接口，当前后端对 COMPLETED 仍返回 409） |
| 评测页指标卡/下钻面板/范围切换/信号与归因文案 | `src/views/EvaluationView.vue`（RUNAWAY_SIGNAL_TEXT / FAILED_REASON_TEXT / ISSUE_TEXT 集中在此页） |
| 评测统计与下钻接口调用 | `src/api/stats.ts` |
| 后端接口调用、响应包装 | `src/api/` |
| 类型契约（新字段/新事件类型） | `src/types/index.ts` |
| 滚动条"仅滚动时显示"等通用行为 | `src/composables/` |

## 关键约定

- **颜色必须走 token**：`style.css` 的 `:root` 块是全站颜色唯一定义处（`--color-*` 语义 token + `--el-color-primary` 系列接管 Element Plus 主题）。组件内禁止裸 hex/rgb，需要新颜色先加 token 再引用
- **状态/级别文案统一走 `utils/labels.ts`**：不要在组件里再写文案 map（当前映射：PENDING=待分析、RUNNING=分析中、COMPLETED=已分析、FAILED=分析失败）
- **报告区文案格式统一"N. 内容"编号**（半角点 + 单个空格）：推荐SOP 前缀由模板拼、判断逻辑经 `utils/format.ts` 归一（含全角"１．"转半角、"第X步"转"N."、缺失换行智能补齐）；无 risk 标签；置信度不显示百分比，仅 `confidence < 0.6` 时显示"当前置信度较低"提示（悬浮展示后端 `confidenceReason`）
- **SSE 事件契约**：`text_delta` 不落库无 sequence；落库事件按 sequence 去重；事件类型见 `types/index.ts`
- **时间格式**：`yyyy-MM-dd HH:mm:ss`，不考虑时区
- **长文本单行省略**：列表/折叠头一律 `nowrap + ellipsis`，全名用 `el-tooltip` 悬浮展示，禁止多行换行撑爆固定行高
- **动画降级**：常驻动画（pulse/blink/spin）必须包 `@media (prefers-reduced-motion: reduce)` 降级
- **可访问性**：可点击卡片用 `role="button" + tabindex` + Enter/Space 键盘支持 + `:focus-visible` 焦点环

## 改动后验证

1. `cd frontend && npx vue-tsc -b`（类型检查必须通过）
2. 浏览器走查（前后端启动见 docs/DEV_STARTUP.md）：告警列表各状态卡片、分析过程时间线、报告区
3. 宽度 375 / 768 / 1440 三档检查：无横向滚动、无截断
4. 迭代历史与验收记录见 `docs/plan/` 下的前端改进计划（如 P2_UI_TIMELINE_PLAN.md）

## 文档自维护约定

**每次前端改动收尾前，检查本 README 是否需要同步更新**（页面对照、目录结构、对照表、关键约定如有变化就先改文档）；根 `AGENTS.md` 与 `docs/` 下相关文档同理。更新文档属于对仓库的修改，需先征得用户同意再写。
