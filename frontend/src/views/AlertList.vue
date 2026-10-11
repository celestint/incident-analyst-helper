<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { alertApi, incidentApi } from '@/api'
import AnalysisPane from '@/components/AnalysisPane.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close, Delete, Filter, Finished } from '@element-plus/icons-vue'
import { useScrollFlash } from '@/composables/useScrollFlash'
import { severityLabel, severityTagType, statusLabel } from '@/utils/labels'
import type { Alert } from '@/types'

/**
 * 告警分析工作台：左栏告警总览（卡片列表），右栏分析面板。
 * 选中态存 route query（?incidentId=），刷新后恢复。
 * 卡片首行：级别 + 服务 + 告警名（单行省略，悬浮显示全名）。
 * 多选模式：列表头图标进入，卡片圆形勾选、顶部已选计数，底部打标/删除（评测打标口径见 docs/plan/EVALUATION_PLAN.md）。
 */
const route = useRoute()
const router = useRouter()

// 仅滚动时显示滚动条
const { scrolling: listScrolling, onScroll: onListScroll } = useScrollFlash()

const alerts = ref<Alert[]>([])
const loading = ref(false)
const analyzing = ref(false)
let pollTimer: number | null = null

// ---- 数据范围过滤（评测打标口径见 docs/plan/EVALUATION_PLAN.md T8）----
/** 按打标三布尔过滤列表：正式 / 评测集 / 测试，默认正式；纯前端过滤，接口不加参数 */
const scopeFilter = ref<'prod' | 'eval' | 'test'>('prod')
const SCOPE_FILTER_LABELS: Record<'prod' | 'eval' | 'test', string> = {
  prod: '正式',
  eval: '评测集',
  test: '测试',
}
const SCOPE_FLAG_KEYS = { prod: 'isProd', eval: 'isEval', test: 'isTest' } as const

const filteredAlerts = computed(() =>
  alerts.value.filter(alert => alert[SCOPE_FLAG_KEYS[scopeFilter.value]] === true)
)

// ---- 多选模式（打标 / 删除）----
const selectMode = ref(false)
const checkedIds = ref<number[]>([])
const marking = ref(false)
const deleting = ref(false)
const markDialogVisible = ref(false)
/** el-checkbox-group 绑定取值数组，提交时映射回三布尔（正式/评测集/测试独立可组合） */
const markSelection = ref<string[]>(['prod'])

const enterSelectMode = () => {
  selectMode.value = true
  checkedIds.value = []
}

const exitSelectMode = () => {
  selectMode.value = false
  checkedIds.value = []
  markDialogVisible.value = false
}

const isChecked = (alert: Alert) => checkedIds.value.includes(alert.id)

const toggleChecked = (alert: Alert) => {
  if (isChecked(alert)) {
    checkedIds.value = checkedIds.value.filter(id => id !== alert.id)
  } else {
    checkedIds.value = [...checkedIds.value, alert.id]
  }
}

const openMarkDialog = () => {
  markSelection.value = ['prod']
  markDialogVisible.value = true
}

const submitMark = async () => {
  if (marking.value) return
  marking.value = true
  try {
    const flags = {
      isProd: markSelection.value.includes('prod'),
      isEval: markSelection.value.includes('eval'),
      isTest: markSelection.value.includes('test'),
    }
    await alertApi.markAlerts(checkedIds.value, flags)
    ElMessage.success(`已为 ${checkedIds.value.length} 个告警更新标记`)
    markDialogVisible.value = false
    exitSelectMode()
    await loadAlerts()
  } catch (err: any) {
    ElMessage.error(err.message || '打标失败')
  } finally {
    marking.value = false
  }
}

const handleBatchDelete = async () => {
  const n = checkedIds.value.length
  try {
    await ElMessageBox.confirm(
      `将删除所选 ${n} 个告警及其全部分析数据（过程记录与报告），不可恢复。确认删除？`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  if (deleting.value) return
  deleting.value = true
  try {
    await alertApi.deleteAlerts(checkedIds.value)
    ElMessage.success(`已删除 ${n} 个告警`)
    exitSelectMode()
    await loadAlerts()
  } catch (err: any) {
    ElMessage.error(err.message || '删除失败')
  } finally {
    deleting.value = false
  }
}

/** 告警是否已结束：分析完成，或结束时间已过（endsAt 存在且不晚于当前时间）。
 * endsAt 是 yyyy-MM-dd HH:mm:ss 本地时间字符串，new Date 按本地时区解析，直接与 Date.now() 比较 */
function isAlertEnded(alert: Alert): boolean {
  if (alert.status === 'COMPLETED') return true
  if (!alert.endsAt) return false
  return new Date(alert.endsAt).getTime() <= Date.now()
}

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
        <!-- 普通模式：标题 + 范围过滤 + 批量选择入口；多选模式：已选计数 + 退出 -->
        <template v-if="!selectMode">
          <h2>告警总览</h2>
          <div class="header-actions">
            <!-- 不与 el-tooltip 嵌套（tooltip+popover 同触发元素在触摸端会吞点击），用原生 title 提示 -->
            <el-popover placement="bottom-end" :width="150" trigger="click">
              <template #reference>
                <el-button
                  :icon="Filter"
                  text
                  class="select-entry"
                  title="按数据范围过滤"
                  aria-label="按数据范围过滤"
                />
              </template>
              <el-radio-group v-model="scopeFilter" class="scope-radio-group">
                <el-radio v-for="(label, key) in SCOPE_FILTER_LABELS" :key="key" :value="key">
                  {{ label }}
                </el-radio>
              </el-radio-group>
            </el-popover>
            <el-tooltip content="批量选择告警（打标 / 删除）" placement="top" :show-after="300">
              <el-button :icon="Finished" text class="select-entry" aria-label="批量选择告警" @click="enterSelectMode" />
            </el-tooltip>
          </div>
        </template>
        <template v-else>
          <h2 class="select-title">已选 {{ checkedIds.length }} 个告警</h2>
          <el-button :icon="Close" text class="select-entry" aria-label="退出多选" @click="exitSelectMode" />
        </template>
      </div>
      <div
        class="alert-list"
        :class="{ 'is-scrolling': listScrolling }"
        @scroll="onListScroll"
        v-loading="loading"
      >
        <div
          v-for="alert in filteredAlerts"
          :key="alert.id"
          class="alert-card"
          :class="{ active: !selectMode && alert.incidentId != null && alert.incidentId === selectedIncidentId, checking: selectMode && isChecked(alert) }"
          role="button"
          tabindex="0"
          :aria-label="selectMode ? `${isChecked(alert) ? '取消选择' : '选择'}告警 ${alert.alertName}` : `选择告警 ${alert.alertName}`"
          @click="selectMode ? toggleChecked(alert) : select(alert)"
          @keydown.enter.prevent="selectMode ? toggleChecked(alert) : select(alert)"
          @keydown.space.prevent="selectMode ? toggleChecked(alert) : select(alert)"
        >
          <div class="card-row">
            <!-- 多选模式：状态点换成圆形勾选框（与聊天多选交互一致） -->
            <span
              v-if="selectMode"
              class="check-circle"
              :class="{ checked: isChecked(alert) }"
              aria-hidden="true"
            />
            <span
              v-else
              class="status-dot"
              :class="isAlertEnded(alert) ? 'resolved' : 'ongoing'"
              :title="isAlertEnded(alert) ? '告警已结束' : '告警持续中'"
            />
            <el-tag :type="severityTagType(alert.severity)" size="small" class="severity-tag">
              {{ severityLabel(alert.severity) }}
            </el-tag>
            <el-tag type="info" effect="plain" size="small" class="service-tag">{{ alert.service }}</el-tag>
            <el-tooltip :content="alert.alertName" placement="top" :show-after="300">
              <strong class="alert-name">{{ alert.alertName }}</strong>
            </el-tooltip>
          </div>
          <!-- 第二行：左时间（与首行级别 tag 左对齐）+ 右操作；多选模式下隐藏操作按钮 -->
          <div class="card-row action-row">
            <span class="alert-time">{{ alert.startsAt }}</span>
            <template v-if="!selectMode">
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
            </template>
          </div>
        </div>
        <el-empty
          v-if="filteredAlerts.length === 0 && !loading"
          :description="`当前范围（${SCOPE_FILTER_LABELS[scopeFilter]}）暂无告警`"
        />
      </div>
      <!-- 多选模式底部操作栏：打标 / 删除 -->
      <div v-if="selectMode" class="select-actions">
        <el-button :icon="Finished" type="primary" plain :disabled="checkedIds.length === 0" @click="openMarkDialog">
          打标
        </el-button>
        <el-button :icon="Delete" type="danger" plain :disabled="checkedIds.length === 0" :loading="deleting" @click="handleBatchDelete">
          删除
        </el-button>
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

    <!-- 批量打标弹窗：三个独立布尔（正式/评测集/测试），提交完整状态，无互斥 -->
    <el-dialog v-model="markDialogVisible" title="设置评测标记" width="360px">
      <p class="mark-hint">将为所选 {{ checkedIds.length }} 个告警统一设置以下标记（三档独立可组合）：</p>
      <el-checkbox-group v-model="markSelection">
        <el-checkbox value="prod">正式数据</el-checkbox>
        <el-checkbox value="eval">评测集</el-checkbox>
        <el-checkbox value="test">测试数据</el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="markDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="marking" @click="submitMark">提交</el-button>
      </template>
    </el-dialog>
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
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px 10px 16px;
  border-bottom: 1px solid var(--color-border-light);
}

.pane-header h2 {
  margin: 0;
  font-size: 18px;
}

.select-entry {
  font-size: 16px;
}

/* 标题右侧操作区：范围过滤 + 批量选择。
   间距看的是"图标到图标"的视觉距离 = gap + 两侧按钮内边距，
   EP 图标按钮默认左右内边距 15px，须一并收紧，保证 ≤ 图标本身大小（16px） */
.header-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.header-actions .el-button + .el-button {
  margin-left: 0;
}

.header-actions .select-entry {
  padding: 6px;
}

/* 过滤弹层内的单选纵向排列 */
.scope-radio-group {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}

.select-title {
  font-size: 15px;
}

/* 多选模式卡片：勾选中高亮 */
.alert-card.checking {
  border-color: var(--color-primary);
  background: var(--color-primary-light-9);
}

/* 圆形勾选框：多选模式下替代状态点，选中填充主题色 + 白色对勾 */
.check-circle {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  flex-shrink: 0;
  border: 1.5px solid var(--color-border);
  background: var(--el-bg-color);
  box-sizing: border-box;
  position: relative;
}

.check-circle.checked {
  border-color: var(--color-primary);
  background: var(--color-primary);
}

.check-circle.checked::after {
  content: '';
  position: absolute;
  left: 5px;
  top: 2px;
  width: 5px;
  height: 9px;
  border: solid #fff;
  border-width: 0 1.5px 1.5px 0;
  transform: rotate(45deg);
}

/* 多选模式底部操作栏 */
.select-actions {
  display: flex;
  gap: 12px;
  padding: 12px 16px;
  border-top: 1px solid var(--color-border-light);
}

.select-actions .el-button {
  flex: 1;
  margin: 0;
}

.mark-hint {
  margin: 0 0 12px 0;
  font-size: 13px;
  color: var(--color-text-secondary);
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
