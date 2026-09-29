import { ref } from 'vue'
import { alertApi, incidentApi } from '@/api'
import type { Alert, Incident, AnalysisReport, SseEvent } from '@/types'

export function useIncident() {
  const alerts = ref<Alert[]>([])
  const currentAlert = ref<Alert | null>(null)
  const currentIncident = ref<Incident | null>(null)
  const currentReport = ref<AnalysisReport | null>(null)
  const isAnalyzing = ref(false)
  const analyzeError = ref<string | null>(null)
  const events: SseEvent[] = []

  // 加载告警列表
  const loadAlerts = async () => {
    try {
      const res = await alertApi.getAlerts()
      alerts.value = res.data
    } catch (err: any) {
      console.error('Failed to load alerts:', err)
    }
  }

  // 触发分析
  const startAnalysis = async (alertId: number) => {
    analyzeError.value = null
    isAnalyzing.value = true

    try {
      const res = await incidentApi.startAnalysis(alertId)
      const incidentId = res.data.incidentId
      currentAlert.value = alerts.value.find(a => a.id === alertId) || null

      // 获取分析详情
      const detailRes = await incidentApi.getIncident(incidentId)
      currentIncident.value = detailRes.data.incident
      currentReport.value = detailRes.data.report

      return { incidentId, status: res.data.status }
    } catch (err: any) {
      analyzeError.value = err.message || '分析失败'
      return null
    } finally {
      isAnalyzing.value = false
    }
  }

  return {
    alerts,
    currentAlert,
    currentIncident,
    currentReport,
    isAnalyzing,
    analyzeError,
    events,
    loadAlerts,
    startAnalysis,
  }
}
