<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { incidentApi } from '@/api'
import { ElMessage, ElMessageBox } from 'element-plus'

const route = useRoute()
const router = useRouter()
const incidentId = Number(route.params.id)

const alertInfo = ref<any>(null)
const incidentInfo = ref<any>(null)
const reportInfo = ref<any>(null)
const events = ref<any[]>([])
let eventSource: EventSource | null = null
const loading = ref(false)

onMounted(async () => {
  if (!incidentId) return
  await loadIncident()
})

onUnmounted(() => {
  closeSse()
})

const loadIncident = async () => {
  loading.value = true
  try {
    const res = await incidentApi.getIncident(incidentId)
    alertInfo.value = res.data.incident
    reportInfo.value = res.data.report

    // 根据状态决定是否建立 SSE 连接
    if (res.data.incident.status === 'RUNNING') {
      initSse()
    }

  } catch (err: any) {
    ElMessage.error(err.message || '加载失败')
    router.push('/')
  } finally {
    loading.value = false
  }
}

const initSse = () => {
  eventSource = incidentApi.streamIncident(incidentId)

  eventSource.onmessage = (event) => {
    const data = JSON.parse(event.data)
    events.value.push(data)

    // 收到 report 事件后重新加载详情
    if (data.type === 'report' && data.data.reportId) {
      eventSource.close()
      loadIncident()
    }

    // 收到 done 事件后停止
    if (data.type === 'done') {
      eventSource.close()
      loadIncident()
    }
  }

  eventSource.onerror = (error) => {
    console.error('SSE error:', error)
    if (alertInfo.value?.status === 'RUNNING') {
      // 重连
      setTimeout(initSse, 3000)
    } else {
      eventSource?.close()
    }
  }
}

const closeSse = () => {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
}

const handleRetry = async () => {
  if (!incidentId) return

  try {
    await ElMessageBox.confirm('确定要重新分析吗？', '确认重试', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })

    loading.value = true
    await incidentApi.startAnalysis(incidentId)
    await loadIncident()
  } catch (err) {
    if (err !== 'cancel') {
      ElMessage.error('重试失败')
    }
  } finally {
    loading.value = false
  }
}

const formatTime = (timestamp: any) => {
  if (!timestamp) return ''
  if (typeof timestamp === 'string') return timestamp
  if (typeof timestamp === 'number') {
    const date = new Date(timestamp * 1000)
    return date.toLocaleString('zh-CN')
  }
  return String(timestamp)
}

const getEventTypeColor = (type: string) => {
  const colorMap: Record<string, string> = {
    step: 'primary',
    tool_call: 'warning',
    tool_result: 'info',
    evidence: 'success',
    report: 'success',
    done: 'success',
  }
  return colorMap[type] || 'info'
}

const getRiskLevelColor = (level: string) => {
  const colorMap: Record<string, string> = {
    LOW: '#67C23A',
    MEDIUM: '#E6A23C',
    HIGH: '#F56C6C',
  }
  return colorMap[level] || '#909399'
}

const getStatusColor = (status: string) => {
  const colorMap: Record<string, string> = {
    PENDING: '#E6A23C',
    RUNNING: '#409EFF',
    COMPLETED: '#67C23A',
    FAILED: '#F56C6C',
  }
  return colorMap[status] || '#909399'
}

const getStatusText = (status: string) => {
  const textMap: Record<string, string> = {
    PENDING: '待分析',
    RUNNING: '分析中',
    COMPLETED: '已完成',
    FAILED: '失败',
  }
  return textMap[status] || status
}
</script>

<template>
  <div class="incident-detail">
    <el-card v-loading="loading">
      <template #header>
        <div class="card-header">
          <h2>{{ alertInfo?.alertName || '分析详情' }}</h2>
          <el-tag :type="alertInfo?.severity === 'critical' ? 'danger' : 'warning'" size="large">
            {{ alertInfo?.severity }}
          </el-tag>
        </div>
      </template>
      <div class="alert-info">
        <p><strong>服务：</strong>{{ alertInfo?.service }}</p>
        <p><strong>开始时间：</strong>{{ alertInfo?.startsAt }}</p>
      </div>
    </el-card>

    <el-card v-if="incidentInfo" style="margin-top: 20px">
      <template #header>
        <div class="card-header">
          <h3>分析状态</h3>
          <el-tag :color="getStatusColor(incidentInfo.status)" style="color: white">
            {{ getStatusText(incidentInfo.status) }}
          </el-tag>
        </div>
      </template>
      <p><strong>创建时间：</strong>{{ incidentInfo.createdAt }}</p>
      <p v-if="incidentInfo.completedAt">
        <strong>完成时间：</strong>{{ incidentInfo.completedAt }}
      </p>
      <p v-if="incidentInfo.errorMessage">
        <strong>错误信息：</strong>
        <el-alert type="error" :closable="false">
          {{ incidentInfo.errorMessage }}
        </el-alert>
      </p>
    </el-card>

    <!-- RUNNING 状态：显示 SSE 时间线 -->
    <el-card v-if="incidentInfo?.status === 'RUNNING'" style="margin-top: 20px">
      <template #header>
        <h3>分析进度</h3>
      </template>
      <el-timeline>
        <el-timeline-item
          v-for="event in events"
          :key="event.sequence"
          :timestamp="formatTime(event.data.timestamp || event.sequence)"
          :type="getEventTypeColor(event.type)"
        >
          <div>
            <el-tag :type="getEventTypeColor(event.type)" size="small">{{ event.type }}</el-tag>
            <p v-if="event.type === 'step'" style="margin: 5px 0 0 0">
              {{ event.data.message }}
            </p>
            <p v-else-if="event.type === 'tool_call'">
              调用工具：<strong>{{ event.data.tool }}</strong>
              <br>
              <small>参数：{{ JSON.stringify(event.data.args) }}</small>
            </p>
            <p v-else-if="event.type === 'tool_result'">
              {{ event.data.summary }}
            </p>
            <p v-else-if="event.type === 'evidence'">
              <strong>{{ event.data.content }}</strong>
              <br>
              <small>时间：{{ event.data.timestamp }}</small>
            </p>
          </div>
        </el-timeline-item>
      </el-timeline>
      <el-alert
        v-if="events.length === 0"
        type="info"
        :closable="false"
        style="margin-top: 10px"
      >
        等待分析结果...
      </el-alert>
    </el-card>

    <!-- FAILED 状态：显示错误信息和重试按钮 -->
    <el-card v-if="incidentInfo?.status === 'FAILED'" style="margin-top: 20px">
      <template #header>
        <h3>分析失败</h3>
      </template>
      <el-alert type="error" :closable="false">
        {{ incidentInfo.errorMessage }}
      </el-alert>
      <div style="margin-top: 20px">
        <el-button type="primary" @click="handleRetry" :loading="loading">
          重新分析
        </el-button>
      </div>
    </el-card>

    <!-- COMPLETED 状态：显示报告 -->
    <el-card v-if="reportInfo" style="margin-top: 20px">
      <template #header>
        <h3>分析报告</h3>
      </template>

      <el-divider />

      <h4>根因假设</h4>
      <p style="color: #666">{{ reportInfo.rootCauseHypothesis }}</p>

      <el-divider />

      <h4>风险等级</h4>
      <el-tag :color="getRiskLevelColor(reportInfo.riskLevel)" style="color: white; font-size: 16px">
        {{ reportInfo.riskLevel }}
      </el-tag>
      <p>置信度：{{ (reportInfo.confidence * 100).toFixed(0) }}%</p>

      <el-divider />

      <h4>推荐操作</h4>
      <ul>
        <li v-for="action in reportInfo.recommendedActions" :key="action.priority">
          <strong>第 {{ action.priority }} 步：</strong>{{ action.action }}
          <el-tag :type="action.risk === 'LOW' ? 'success' : 'warning'" size="small">
            {{ action.risk }}
          </el-tag>
        </li>
      </ul>

      <el-divider />

      <h4>证据链</h4>
      <el-timeline>
        <el-timeline-item
          v-for="(evidence, index) in reportInfo.evidenceChain"
          :key="index"
          :timestamp="evidence.timestamp"
          :type="evidence.source === 'metrics' ? 'success' : evidence.source === 'logs' ? 'warning' : 'primary'"
        >
          <div>
            <el-tag :type="evidence.source === 'metrics' ? 'success' : evidence.source === 'logs' ? 'warning' : 'primary'" size="small">
              {{ evidence.source }}
            </el-tag>
            {{ evidence.content }}
          </div>
        </el-timeline-item>
      </el-timeline>
    </el-card>
  </div>
</template>

<style scoped>
.incident-detail {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-header h2,
.card-header h3 {
  margin: 0;
}

.alert-info p {
  margin: 5px 0;
}

ul {
  margin: 10px 0;
  padding-left: 20px;
}

li {
  margin: 5px 0;
}

.time {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 0.9em;
}
</style>
