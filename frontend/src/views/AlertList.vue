<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { alertApi, incidentApi } from '@/api'
import AnalysisPane from '@/components/AnalysisPane.vue'
import { ElMessage } from 'element-plus'
import { useScrollFlash } from '@/composables/useScrollFlash'
import { severityLabel, severityTagType, statusLabel } from '@/utils/labels'
import type { Alert } from '@/types'

/**
 * 告警分析工作台：左栏告警总览（卡片列表），右栏分析面板。
 * 选中态存 route query（?incidentId=），刷新后恢复。
 * 卡片首行：级别 + 服务 + 告警名（单行省略，悬浮显示全名）。
 */
const route = useRoute()
const router = useRouter()

// 仅滚动时显示滚动条
const { scrolling: listScrolling, onScroll: onListScroll } = useScrollFlash()

const alerts = ref<Alert[]>([])
const loading = ref(false)
const analyzing = ref(false)
let pollTimer: number | null = null

const selectedIncidentId = computed<number | null>(() => {
  const raw = route.query.incidentId
  const n = Number(Array.isArray(raw) ? raw[0] : raw)
  return Number.isFinite(n) && n > 0 ? n : null
})

/** 选中的告警（含服务/级别/告警名，供右栏状态栏展示） */
const selectedAlert = computed<Alert | null>(() => {
  if (selectedIncidentId.value == null) return null
  return alerts.value.find(a => a.incidentId === selectedIncidentId.value) ?? null
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
  loading.value = true
  try {
    const res = await alertApi.getAlerts()
    alerts.value = res.data
  } catch (err: any) {
    console.error('Failed to load alerts:', err)
    ElMessage.error(err.message || '告警列表加载失败')
  } finally {
    loading.value = false
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
</script>

<template>
  <div class="workbench">
    <!-- 左栏：告警总览 -->
    <aside class="left-pane">
      <div class="pane-header">
        <h2>告警总览</h2>
      </div>
      <div
        class="alert-list"
        :class="{ 'is-scrolling': listScrolling }"
        @scroll="onListScroll"
        v-loading="loading"
      >
        <div
          v-for="alert in alerts"
          :key="alert.id"
          class="alert-card"
          :class="{ active: alert.incidentId != null && alert.incidentId === selectedIncidentId }"
          role="button"
          tabindex="0"
          :aria-label="`选择告警 ${alert.alertName}`"
          @click="select(alert)"
          @keydown.enter.prevent="select(alert)"
          @keydown.space.prevent="select(alert)"
        >
          <div class="card-row">
            <span
              class="status-dot"
              :class="alert.status === 'COMPLETED' ? 'resolved' : 'ongoing'"
              :title="alert.status === 'COMPLETED' ? '告警已结束' : '告警持续中'"
            />
            <el-tag :type="severityTagType(alert.severity)" size="small" class="severity-tag">
              {{ severityLabel(alert.severity) }}
            </el-tag>
            <el-tag type="info" effect="plain" size="small" class="service-tag">{{ alert.service }}</el-tag>
            <el-tooltip :content="alert.alertName" placement="top" :show-after="300">
              <strong class="alert-name">{{ alert.alertName }}</strong>
            </el-tooltip>
          </div>
          <!-- 第二行：左时间（与首行级别 tag 左对齐）+ 右操作 -->
          <div class="card-row action-row">
            <span class="alert-time">{{ alert.startsAt }}</span>
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
              {{ statusLabel(alert.status) }}
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
              {{ statusLabel(alert.status) }}
            </el-tag>
          </div>
        </div>
        <el-empty v-if="alerts.length === 0 && !loading" description="暂无告警" />
      </div>
    </aside>

    <!-- 右栏：分析 -->
    <main class="right-pane">
      <AnalysisPane
        :incident-id="selectedIncidentId"
        :alert="selectedAlert"
        @finished="onFinished"
      />
    </main>
  </div>
</template>

<style scoped>
/* 高度只减 52px 顶部导航：border-box 已含上下 20px 页边距（此前多减 40px 导致底部空白） */
.workbench {
  display: flex;
  height: calc(100vh - 52px);
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
  background: var(--el-bg-color);
  border-radius: 12px;
  border: 1px solid var(--color-border-light);
  overflow: hidden;
}

.pane-header {
  padding: 14px 16px;
  border-bottom: 1px solid var(--color-border-light);
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
  /* 滚动条默认隐藏，仅滚动时显示 */
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}

.alert-list.is-scrolling {
  scrollbar-color: var(--color-border) transparent;
}

.alert-list::-webkit-scrollbar {
  width: 8px;
}

.alert-list::-webkit-scrollbar-track {
  background: transparent;
}

.alert-list::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: 4px;
}

.alert-list.is-scrolling::-webkit-scrollbar-thumb {
  background: var(--color-border);
}

.alert-card {
  border: 1px solid var(--color-border-light);
  border-radius: 10px;
  padding: 12px;
  margin-bottom: 12px;
  cursor: pointer;
  transition: box-shadow 0.2s, border-color 0.2s;
}

.alert-card:hover {
  border-color: var(--color-primary-light-3);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.alert-card.active {
  border-color: var(--color-primary);
  background: var(--color-primary-light-9);
}

.alert-card:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

.card-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

/* 状态圆点：结束=灰，持续=红（脉冲） */
.status-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

.status-dot.resolved {
  background: var(--el-text-color-placeholder);
}

.status-dot.ongoing {
  background: var(--color-danger);
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

.card-row + .card-row {
  margin-top: 8px;
}

.severity-tag,
.service-tag {
  flex-shrink: 0;
}

/* 告警名单行省略，悬浮经 tooltip 显示全名 */
.alert-name {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.action-row {
  /* 18px = 状态圆点 10px + 间距 8px，使时间左缘与首行级别 tag 左缘对齐 */
  padding-left: 18px;
  justify-content: space-between;
  min-height: 24px;
}

.alert-time {
  color: var(--color-text-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.clickable-tag {
  cursor: pointer;
}

/* 右栏 */
.right-pane {
  flex: 1;
  min-width: 0;
  background: var(--el-bg-color);
  border-radius: 12px;
  border: 1px solid var(--color-border-light);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.right-pane > :deep(.analysis-pane) {
  flex: 1;
}

/* 窄屏单列：左栏不再定宽，上下堆叠 */
@media (max-width: 768px) {
  .workbench {
    flex-direction: column;
    height: auto;
    padding: 12px;
    gap: 12px;
  }

  .left-pane {
    width: 100%;
  }

  .alert-list {
    max-height: 40vh;
  }

  .right-pane {
    min-height: 50vh;
  }
}
</style>
