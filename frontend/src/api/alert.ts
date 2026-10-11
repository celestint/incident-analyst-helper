import request from './index'
import type { ApiResponse, Alert, AlertDetail } from '@/types'

export interface AlertFlags {
  isProd: boolean
  isEval: boolean
  isTest: boolean
}

export const alertApi = {
  // 获取告警列表
  getAlerts(): Promise<ApiResponse<Alert[]>> {
    return request.get('/alerts')
  },

  // 获取告警详情
  getAlert(id: number): Promise<ApiResponse<AlertDetail>> {
    return request.get(`/alerts/${id}`)
  },

  // 创建告警（外部系统调用，前端不直接用；agent/ai 测试告警带 isTest: true）
  createAlert(data: Omit<Alert, 'id' | 'incidentId' | 'status'>): Promise<ApiResponse<Alert>> {
    return request.post('/alerts', data)
  },

  // 批量打标（评测）：提交三布尔完整状态，独立可组合
  markAlerts(ids: number[], flags: AlertFlags): Promise<ApiResponse<{ updated: number } & AlertFlags>> {
    return request.post('/alerts/mark', { ids, ...flags })
  },

  // 批量删除告警（级联删除关联的分析数据，前端须二次确认）
  deleteAlerts(ids: number[]): Promise<ApiResponse<{ deleted: number }>> {
    return request.post('/alerts/delete', { ids })
  },
}

export type { Alert, AlertDetail }
