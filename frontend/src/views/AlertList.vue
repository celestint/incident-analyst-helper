<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { alertApi, incidentApi } from '@/api'
import AnalysisPane from '@/components/AnalysisPane.vue'
import { ElMessage } from 'element-plus'
import type { Alert } from '@/types'

/**
 * 告警分析工作台：左栏告警总览（卡片列表），右栏分析面板。
 * 选中态存 route query（?incidentId=），刷新后恢复。
 */
const route = useRoute()
const router = useRouter()

const alerts = ref<Alert[]>([])
const loading = ref(false)
const analyzing = ref(false)
let pollTimer: number | null = null

const selectedIncidentId = computed<number | null>(() => {
  const raw = route.query.incidentId
  const n = Number(Array.isArray(raw) ? raw[0] : raw)
  return Number.isFinite(n) && n > 0 ? n : null
})

onMounted(() => {
  loadAlerts()
  // 兜底轮询：未被选中的 RUNNING 告警状态也能更新
  pollTimer = window.setInterval(loadAlerts, 30_000)
})

onUnmounted(() => {
  if (pollTimer) window.clearInterval(pollTimer)
})

const loadAlerts = async () => {
  try {
    const res = await alertApi.getAlerts()
    alerts.value = res.data
  } catch (err: any) {
    console.error('Failed to load alerts:', err)
  }
}

const select = (alert: Alert) => {
  if (alert.incidentId == null) return
  router.replace({ query: { incidentId: String(alert.incidentId) } })
}

const handleAnalyze = async (alert: Alert) => {
  if (analyzing.value) return
  analyzing.value = true
  try {
    const res = await incidentApi.startAnalysis(alert.id)
    ElMessage.success('分析已启动')
    await loadAlerts()
    router.replace({ query: { incidentId: String(res.data.incidentId) } })
  } catch (err: any) {
    ElMessage.error(err.message || '分析启动失败')
  } finally {
    analyzing.value = false
  }
}

const onFinished = () => {
  loadAlerts()
}

// ===== 展示映射 =====
const severityLabel = (severity: string) => {
  const map: Record<string, string> = { critical: '严重', warning: '警告', info: '信息' }
  return map[severity] ?? severity
}

const severityTagType = (severity: string) => {
  const map: Record<string, string> = { critical: 'danger', warning: 'warning', info: 'info' }
  return map[severity] || 'info'
}

/** 状态圆圈：分析完成视为结束（灰圈），其余视为持续（红圈） */
const isResolved = (alert: Alert) => alert.status === 'COMPLETED'
</script>

<template>
  <div class="workbench">
    <!-- 左栏：告警总览 -->
    <aside class="left-pane">
      <div class="pane-header">
        <h2>告警总览</h2>
      </div>
      <div class="alert-list" v-loading="loading">
        <div
          v-for="alert in alerts"
          :key="alert.id"
          class="alert-card"
          :class="{ active: alert.incidentId != null && alert.incidentId === selectedIncidentId }"
          @click="select(alert)"
        >
          <div class="card-row">
            <span
              class="status-dot"
              :class="isResolved(alert) ? 'resolved' : 'ongoing'"
              :title="isResolved(alert) ? '告警已结束' : '告警持续中'"
            />
            <el-tag type="info" effect="plain" size="small">{{ alert.service }}</el-tag>
            <strong class="alert-name">{{ alert.alertName }}</strong>
            <el-tag :type="severityTagType(alert.severity)" size="small">
              {{ severityLabel(alert.severity) }}
            </el-tag>
          </div>
          <div class="card-row action-row">
            <el-button
              v-if="alert.status === 'PENDING'"
              type="primary"
              size="small"
              :disabled="analyzing"
              @click.stop="handleAnalyze(alert)"
            >
              分析
            </el-button>
            <el-tag
              v-else-if="alert.status === 'RUNNING'"
              type="primary"
              size="small"
              class="clickable-tag"
              @click.stop="select(alert)"
            >
              分析中
            </el-tag>
            <el-button
              v-else-if="alert.status === 'COMPLETED'"
              size="small"
              @click.stop="select(alert)"
            >
              查看报告
            </el-button>
            <el-tag
              v-else-if="alert.status === 'FAILED'"
              type="danger"
              size="small"
              class="clickable-tag"
              @click.stop="select(alert)"
            >
              失败
            </el-tag>
          </div>
        </div>
        <el-empty v-if="alerts.length === 0 && !loading" description="暂无告警" />
      </div>
    </aside>

    <!-- 右栏：分析 -->
    <main class="right-pane">
      <AnalysisPane :incident-id="selectedIncidentId" @finished="onFinished" />
    </main>
  </div>
</template>

<style scoped>
.workbench {
  display: flex;
  height: calc(100vh - 40px);
  gap: 16px;
  padding: 20px;
  box-sizing: border-box;
}

/* 左栏 */
.left-pane {
  width: 380px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 6px;
  border: 1px solid #ebeef5;
  overflow: hidden;
}

.pane-header {
  padding: 14px 16px;
  border-bottom: 1px solid #ebeef5;
}

.pane-header h2 {
  margin: 0;
  font-size: 18px;
}

.alert-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
  box-sizing: border-box;
}

.alert-card {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 12px;
  margin-bottom: 12px;
  cursor: pointer;
  transition: box-shadow 0.2s, border-color 0.2s;
}

.alert-card:hover {
  border-color: #409eff;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.15);
}

.alert-card.active {
  border-color: #409eff;
  background: #ecf5ff;
}

.card-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.card-row + .card-row {
  margin-top: 8px;
}

.alert-name {
  font-size: 14px;
  text-align: left;
}

.card-row .el-tag {
  flex-shrink: 0;
}

/* 状态圆圈：结束=灰，持续=红 */
.status-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

.status-dot.resolved {
  background: #c0c4cc;
}

.status-dot.ongoing {
  background: #f56c6c;
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.4;
  }
}

.action-row {
  justify-content: flex-end;
  min-height: 24px;
}

.clickable-tag {
  cursor: pointer;
}

/* 右栏 */
.right-pane {
  flex: 1;
  min-width: 0;
  background: #fff;
  border-radius: 6px;
  border: 1px solid #ebeef5;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.right-pane > :deep(.analysis-pane) {
  flex: 1;
  padding: 16px;
  box-sizing: border-box;
}
</style>
