<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useIncident } from '@/composables/useIncident'
import { ElMessage } from 'element-plus'

const route = useRoute()
const incidentId = Number(route.params.id)

const { currentAlert, currentIncident, currentReport, events, close } = useIncident()
let eventSource: EventSource | null = null

onMounted(async () => {
  if (!incidentId) return

  // 模拟加载（等待后端连接）
  try {
    // 这里应该调用 API 加载详情
    // const res = await incidentApi.getIncident(incidentId)
    // currentAlert.value = res.data.alert
    // currentIncident.value = res.data.incident
    // currentReport.value = res.data.report

    // 临时使用 mock 数据
    currentAlert.value = {
      id: incidentId,
      alertName: 'HighCPUUsage',
      severity: 'critical',
      service: 'dbservice1',
      startsAt: '2025-01-15 22:33:00',
      incidentId: incidentId,
      status: 'COMPLETED',
    }

    currentIncident.value = {
      id: incidentId,
      alertId: incidentId,
      status: 'COMPLETED',
      reportId: incidentId,
      createdAt: '2025-01-15 22:33:05',
      completedAt: '2025-01-15 22:33:15',
    }

    currentReport.value = {
      id: incidentId,
      rootCauseHypothesis: 'dbservice1 存在异常进程持续占用 CPU，导致服务响应变慢，进而影响支付服务',
      confidence: 0.85,
      riskLevel: 'MEDIUM',
      recommendedActions: [
        { priority: 1, action: '检查 dbservice1 上的异常进程', risk: 'LOW' },
        { priority: 2, action: '如确认异常进程，考虑重启该服务', risk: 'MEDIUM' }
      ],
      evidenceChain: [
        { source: 'metrics', content: 'CPU 使用率 95%（22:23-22:28）', timestamp: '2025-01-15 22:23:00' },
        { source: 'logs', content: '警告：检测到异常高 CPU 占用程序', timestamp: '2025-01-15 22:33:05' },
        { source: 'trace', content: 'order-service 调用 dbservice1 平均耗时从 50ms 升至 800ms', timestamp: '2025-01-15 22:25:00' }
      ]
    }
  } catch (err: any) {
    ElMessage.error(err.message || '加载失败')
  }
})

onUnmounted(() => {
  if (eventSource) {
    eventSource.close()
  }
})

const getRiskLevelColor = (level: string) => {
  const colorMap: Record<string, string> = {
    LOW: '#67C23A',
    MEDIUM: '#E6A23C',
    HIGH: '#F56C6C',
  }
  return colorMap[level] || '#909399'
}
</script>

<template>
  <div class="incident-detail">
    <el-card v-if="currentAlert">
      <template #header>
        <div class="card-header">
          <h2>{{ currentAlert.alertName }}</h2>
          <el-tag :type="currentAlert.severity === 'critical' ? 'danger' : 'warning'">
            {{ currentAlert.severity }}
          </el-tag>
        </div>
      </template>
      <div class="alert-info">
        <p><strong>服务：</strong>{{ currentAlert.service }}</p>
        <p><strong>开始时间：</strong>{{ currentAlert.startsAt }}</p>
      </div>
    </el-card>

    <el-card v-if="currentIncident" style="margin-top: 20px">
      <template #header>
        <h3>分析状态</h3>
      </template>
      <p><strong>状态：</strong>{{ currentIncident.status }}</p>
      <p><strong>创建时间：</strong>{{ currentIncident.createdAt }}</p>
      <p v-if="currentIncident.completedAt">
        <strong>完成时间：</strong>{{ currentIncident.completedAt }}
      </p>
    </el-card>

    <el-card v-if="currentReport" style="margin-top: 20px">
      <template #header>
        <h3>分析报告</h3>
      </template>

      <el-divider />

      <h4>根因假设</h4>
      <p style="color: #666">{{ currentReport.rootCauseHypothesis }}</p>

      <el-divider />

      <h4>风险等级</h4>
      <el-tag :color="getRiskLevelColor(currentReport.riskLevel)" style="color: white; font-size: 16px">
        {{ currentReport.riskLevel }}
      </el-tag>
      <p>置信度：{{ (currentReport.confidence * 100).toFixed(0) }}%</p>

      <el-divider />

      <h4>推荐操作</h4>
      <ul>
        <li v-for="action in currentReport.recommendedActions" :key="action.priority">
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
          v-for="(evidence, index) in currentReport.evidenceChain"
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

.card-header h2 {
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
</style>
