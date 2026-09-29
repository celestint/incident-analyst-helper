// 告警
export interface Alert {
  id: number
  alertName: string
  severity: 'critical' | 'warning' | 'info'
  service: string
  startsAt: string
  incidentId: number | null
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
}

// 分析事件
export interface Incident {
  id: number
  alertId: number
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  reportId: number | null
  createdAt: string
  completedAt: string
}

// 分析报告
export interface AnalysisReport {
  id: number
  rootCauseHypothesis: string
  confidence: number
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH'
  recommendedActions: Array<{ priority: number; action: string; risk: string }>
  evidenceChain: Array<{ source: string; content: string; timestamp: string }>
}

// 统一响应
export interface ApiResponse<T = any> {
  code: number
  message: string
  data: T
}

// SSE 事件
export interface SseEvent {
  sequence: number
  type: 'step' | 'tool_call' | 'tool_result' | 'evidence' | 'report' | 'done'
  data: any
}

// 告警详情
export interface AlertDetail extends Alert {
  labels?: Record<string, string>
}
