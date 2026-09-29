import request from './index'
import type { ApiResponse, Alert, AlertDetail } from '@/types'

export const alertApi = {
  // 获取告警列表
  getAlerts(): Promise<ApiResponse<Alert[]>> {
    return request.get('/alerts')
  },

  // 创建告警（外部系统调用，前端不直接用）
  createAlert(data: Omit<Alert, 'id' | 'incidentId' | 'status'>): Promise<ApiResponse<Alert>> {
    return request.post('/alerts', data)
  },
}

export type { Alert, AlertDetail }
