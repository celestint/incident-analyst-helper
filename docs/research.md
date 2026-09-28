
## 目标

做一个个人能跑的 AI 排障助手，基于告警手动触发分析，自动按排障手册查数据，给出原因和建议，并在页面上实时看到分析过程。

## 调研发现

数据来源：
- 现有开源排障 Agent（如 [SRE-Agent-App](https://github.com/qicesun/SRE-Agent-App)、[DeepTicket](https://github.com/shanananana/deepticket)）功能完整，但都面向企业真实环境，需要对接 K8s、GitLab、Prometheus 等系统，有的甚至直接用命令操作，对个人开发者不方便也不安全。
- 真实生产数据拿不到，但公开数据集（如 [AIOps Challenge 2021](https://www.aiops.cn/gitlab/aiops-nankai/data/trace/aiops2021)）可以筛选出单一故障场景，转成模拟数据后足够验证整个闭环。

工作流：
- 但这些项目的核心工作流值得借鉴：按预定义的排障手册顺序取证，而不是让 AI 自由发挥。[SRE-Agent-App](https://github.com/qicesun/SRE-Agent-App) 用 OODA 循环驱动 Agent 逐步观察、定向、决策、行动，这个结构对个人 MVP 有参考价值。
- 有的项目演示视频 用 SSE 把 Agent 的推理步骤实时推到前端，让人能看到每一步在做什么，这种透明感对排障场景很重要。

# 核心需求

1. 展示告警列表，告警处理人员可手动触发分析。
2. 分析时按预定义步骤依次查询模拟数据，并实时展示每一步的结果。
3. 生成包含根因、置信度、建议和证据的分析报告，报告只读可回看。
4. 支持分析失败后重试，并提供历史记录列表。