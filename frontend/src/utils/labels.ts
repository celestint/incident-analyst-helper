/**
 * 告警/事件展示文案与标签类型的统一映射（AlertList 与 AnalysisPane 共用）。
 */

// 告警级别：critical=严重 / warning=警告 / info=信息
export const severityLabel = (severity: string): string => {
  const map: Record<string, string> = { critical: '严重', warning: '警告', info: '信息' }
  return map[severity] ?? severity
}

export const severityTagType = (severity: string): 'danger' | 'warning' | 'info' => {
  const map: Record<string, 'danger' | 'warning' | 'info'> = {
    critical: 'danger',
    warning: 'warning',
    info: 'info',
  }
  return map[severity] ?? 'info'
}

// 分析状态文案：已完成→已分析，失败→分析失败
export const statusLabel = (status: string): string => {
  const map: Record<string, string> = {
    PENDING: '待分析',
    RUNNING: '分析中',
    COMPLETED: '已分析',
    FAILED: '分析失败',
  }
  return map[status] ?? status
}

export const statusTagType = (status: string): 'warning' | 'primary' | 'success' | 'danger' | 'info' => {
  const map: Record<string, 'warning' | 'primary' | 'success' | 'danger'> = {
    PENDING: 'warning',
    RUNNING: 'primary',
    COMPLETED: 'success',
    FAILED: 'danger',
  }
  return map[status] ?? 'info'
}
