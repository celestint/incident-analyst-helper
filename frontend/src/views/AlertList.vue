<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useIncident } from '@/composables/useIncident'
import { ElMessage } from 'element-plus'

const router = useRouter()
const { alerts, loadAlerts, isAnalyzing, startAnalysis, analyzeError } = useIncident()

onMounted(() => {
  loadAlerts()
})

const handleAnalyze = (alertId: number) => {
  if (isAnalyzing.value) return
  startAnalysis(alertId).then(result => {
    if (result) {
      ElMessage.success('分析已启动')
      router.push(`/incidents/${result.incidentId}`)
    } else if (analyzeError.value) {
      ElMessage.error(analyzeError.value)
    }
  })
}

const handleViewDetail = (incidentId: number) => {
  router.push(`/incidents/${incidentId}`)
}

const getSeverityColor = (severity: string) => {
  const colorMap: Record<string, string> = {
    critical: '#F56C6C',
    warning: '#E6A23C',
    info: '#909399',
  }
  return colorMap[severity] || '#909399'
}

const getStatusText = (incidentId: number | null, status: string) => {
  if (incidentId === null) return '待分析'
  if (status === 'FAILED') return '失败'
  if (status === 'COMPLETED') return '已完成'
  return status
}

const getStatusTagType = (incidentId: number | null, status: string) => {
  if (incidentId === null) return 'warning'
  if (status === 'FAILED') return 'danger'
  if (status === 'COMPLETED') return 'success'
  if (status === 'RUNNING') return 'primary'
  return 'info'
}
</script>

<template>
  <div class="alert-list">
    <el-card class="header-card">
      <h1>告警总览</h1>
      <p>点击 [分析] 按钮开始排障流程</p>
    </el-card>

    <el-table :data="alerts" style="width: 100%" v-loading="isAnalyzing">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="alertName" label="告警名称" width="200" />
      <el-table-column prop="severity" label="级别" width="120">
        <template #default="{ row }">
          <el-tag :color="getSeverityColor(row.severity)" type="info" style="color: white">
            {{ row.severity }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="service" label="服务" width="150" />
      <el-table-column prop="startsAt" label="开始时间" width="180" />
      <el-table-column prop="status" label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="getStatusTagType(row.incidentId, row.status)">
            {{ getStatusText(row.incidentId, row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="280">
        <template #default="{ row }">
          <el-button
            type="primary"
            size="small"
            :disabled="row.incidentId !== null || isAnalyzing"
            @click="handleAnalyze(row.id)"
          >
            分析
          </el-button>
          <el-button
            type="info"
            size="small"
            :disabled="row.incidentId === null || isAnalyzing"
            @click="handleViewDetail(row.incidentId!)"
          >
            {{ row.status === 'FAILED' ? '查看失败原因' : '查看报告' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.alert-list {
  padding: 20px;
}

.header-card {
  margin-bottom: 20px;
}

.header-card h1 {
  margin: 0 0 10px 0;
  font-size: 24px;
}

.header-card p {
  margin: 0;
  color: #666;
}
</style>
