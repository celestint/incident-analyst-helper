import request from './index'
import type { ApiResponse, Incident, AnalysisReport } from '@/types'

export const incidentApi = {
  // 触发分析
  startAnalysis(alertId: number): Promise<ApiResponse<{ incidentId: number; status: string }>> {
    return request.post(`/incidents/${alertId}/start`)
  },

  // 获取分析详情
  getIncident(id: number): Promise<ApiResponse<{ incident: Incident; report: AnalysisReport }>> {
    return request.get(`/incidents/${id}`)
  },

  // SSE 实时推送
  streamIncident(id: number): EventSource {
    return new EventSource(`/api/incidents/${id}/stream`)
  },
}

export type { Incident, AnalysisReport }
