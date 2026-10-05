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
}

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
