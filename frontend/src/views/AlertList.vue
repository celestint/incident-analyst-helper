<script setup lang="ts">
import { onMounted } from 'vue'
import { useIncident } from '@/composables/useIncident'
import { ElMessage } from 'element-plus'

const { alerts, loadAlerts, isAnalyzing, startAnalysis, analyzeError } = useIncident()

onMounted(() => {
  loadAlerts()
})

const handleAnalyze = (alertId: number) => {
  if (isAnalyzing.value) return
  startAnalysis(alertId).then(result => {
    if (result) {
      ElMessage.success('分析已启动')
    } else if (analyzeError.value) {
      ElMessage.error(analyzeError.value)
    }
  })
}

const getSeverityColor = (severity: string) => {
  const colorMap: Record<string, string> = {
    critical: '#F56C6C',
    warning: '#E6A23C',
    info: '#909399',
  }
  return colorMap[severity] || '#909399'
}

const getStatusText = (incidentId: number | null) => {
  if (incidentId === null) return '待分析'
  return '已完成'
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
          <el-tag :type="row.incidentId ? 'success' : 'warning'">
            {{ getStatusText(row.incidentId) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button
            type="primary"
            size="small"
            :disabled="row.incidentId !== null || isAnalyzing"
            @click="handleAnalyze(row.id)"
          >
            分析
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
