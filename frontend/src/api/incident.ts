import request from './index'
import type { ApiResponse, Incident, AnalysisReport, HistoryEvent, AdoptIssue } from '@/types'

export const incidentApi = {
  // 触发分析（传告警 ID；FAILED 状态下再次调用即"重新分析"，从头再来）
  startAnalysis(alertId: number): Promise<ApiResponse<{ incidentId: number; status: string }>> {
    return request.post(`/incidents/${alertId}/start`)
  },

  // 断点续跑（仅 FAILED）：从最近一次失败的点继续，LLM 上下文从事件重建
  resumeAnalysis(incidentId: number): Promise<ApiResponse<{ incidentId: number; status: string }>> {
    return request.post(`/incidents/${incidentId}/resume`)
  },

  // 获取分析详情（后端返回扁平结构，report 仅 COMPLETED 有值）
  getIncident(id: number): Promise<ApiResponse<Incident & { report?: AnalysisReport }>> {
    return request.get(`/incidents/${id}`)
  },

  // 历史事件：FAILED/COMPLETED 状态下重建已执行链路用
  getEvents(id: number): Promise<ApiResponse<HistoryEvent[]>> {
    return request.get(`/incidents/${id}/events`)
  },

  // 报告采纳标注（评测）：赞同传 issues=[]；不赞同可附原因，可跳过（空数组=未说明原因）
  submitAdoption(
    id: number,
    adopted: boolean,
    issues: AdoptIssue[]
  ): Promise<ApiResponse<{ incidentId: number; reportId: number; adopted: boolean; adoptIssues: AdoptIssue[] }>> {
    return request.post(`/incidents/${id}/report/adoption`, { adopted, issues })
  },

  // SSE 实时推送。since 为已收到的最大事件序号，后端先重放之后的事件再转实时
  streamIncident(id: number, since = 0): EventSource {
    return new EventSource(`/api/incidents/${id}/stream?since=${since}`)
  },
}

export type { Incident, AnalysisReport, AdoptIssue }
