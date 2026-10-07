// 告警
export interface Alert {
  id: number
  alertName: string
  severity: 'critical' | 'warning' | 'info'
  service: string
  startsAt: string
  /** 告警结束时间，为空（undefined/null/空串）表示未结束，前端显示"至今" */
  endsAt?: string
  incidentId: number | null
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  /** 评测打标三布尔（独立可组合）：正式=isProd；评测集=isEval；测试=isTest */
  isProd?: boolean
  isEval?: boolean
  isTest?: boolean
}

// 分析事件
export interface Incident {
  id: number
  alertId: number
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  /** 细粒度阶段：received/thinking/tool_running/finalizing/done */
  phase?: string
  /** 已落库的最大事件序号，SSE 断点重连游标 */
  lastSeq?: number
  reportId: number | null
  createdAt: string
  updatedAt?: string
  completedAt: string
  /** 仅 FAILED 有值 */
  errorMessage?: string
}

// 分析报告（四段格式：事件摘要 / 根因 / 推荐SOP / 判断逻辑）
export interface AnalysisReport {
  id: number
  /** 事件摘要：是否噪音 */
  isNoise: boolean
  /** 事件摘要：是否需要处理 */
  needsHandling: boolean
  /** 当前报警产生原因 */
  rootCauseHypothesis: string
  /** 结论置信度 0~1；< 0.6 时前端显示"当前置信度较低"提示 */
  confidence: number
  /** 置信度理由：LLM 按评分锚点给出的依据说明（低置信度提示悬浮展示） */
  confidenceReason?: string
  /** 推荐SOP，前端渲染为"1. 动作"编号列表 */
  recommendedActions: Array<{ priority: number; action: string }>
  /** 判断逻辑：证据融入推理链的叙述文本 */
  judgmentLogic: string
  /** 人工采纳标注（评测）：true=赞同 false=不赞同 null/undefined=未标注 */
  adopted?: boolean | null
  /** 不赞同原因（可多选；赞同或未说明原因为空数组） */
  adoptIssues?: AdoptIssue[]
}

// 不赞同原因子维度（评测口径见 docs/plan/EVALUATION_PLAN.md）
export type AdoptIssue = 'noise' | 'evidence' | 'sop' | 'logic' | 'leak' | 'redundant'

// 统一响应
export interface ApiResponse<T = any> {
  code: number
  message: string
  data: T
}

// 落库 SSE 事件类型（带 sequence，可重放）
export type PersistedEventType =
  | 'incident_received'
  | 'agent_thought'
  | 'tool_call_start'
  | 'tool_call_result'
  | 'evidence_collected'
  | 'report_finalized'
  | 'error'

// 全部 SSE 事件类型（text_delta 不落库、无 sequence）
export type SseEventType = PersistedEventType | 'text_delta'

// SSE 事件。text_delta 无 sequence，不推进 last_seq
export interface SseEvent {
  sequence?: number
  type: SseEventType
  data: any
}

// 历史事件（GET /incidents/{id}/events 返回，均已落库、必有 sequence）
export interface HistoryEvent {
  sequence: number
  type: SseEventType
  data: any
}

// 告警详情
export interface AlertDetail extends Alert {
  labels?: Record<string, string>
}

// ---- 评测统计（口径见 docs/plan/EVALUATION_PLAN.md）----

// 汇总指标（GET /api/stats/evaluation）
export interface EvaluationStats {
  scope: { since: string | null; bucket: string; all: boolean; totalIncidents: number }
  completion: { total: number; completed: number; failed: number; running: number; completionRate: number }
  durationSeconds: { count: number; p50: number | null; p95: number | null }
  toolCalls: {
    incidentsWithTools: number
    incidentsWithoutTools: number
    avgCalls: number
    maxCalls: number
    distribution: Array<{ bucket: string; incidents: number }>
  }
  runaway: { iterationExhausted: number; thoughtLoop: number; incidents: number; rate: number }
  failedReasons: { llmCallFailed: number; reportParseFailed: number; iterationExhausted: number; other: number }
  toolSuccess: { total: number; success: number; rate: number }
  adoption: {
    annotated: number
    adopted: number
    dislike: number
    dislikeNoReason: number
    rate: number
    issueCounts: { noise: number; evidence: number; sop: number; logic: number; leak: number; redundant: number }
  }
}

// 下钻明细行（GET /api/stats/evaluation/incidents，按指标附加对应字段）
export interface DrillRow {
  incidentId: number
  alertName: string | null
  service: string | null
  startsAt: string | null
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  durationSeconds: number | null
  adopted?: boolean | null
  adoptIssues?: AdoptIssue[]
  tag?: string | null
  failedReason?: string
  runawaySignal?: string
  toolCallCount?: number
}

// 下钻指标
export type DrillMetric = 'failed' | 'slow' | 'toolCalls' | 'runaway' | 'dislike'
