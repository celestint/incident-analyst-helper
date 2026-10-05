<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { Warning } from '@element-plus/icons-vue'
import { incidentApi } from '@/api'
import { ElMessage } from 'element-plus'
import ProcessTimeline from '@/components/ProcessTimeline.vue'
import { useScrollFlash } from '@/composables/useScrollFlash'
import { severityLabel, severityTagType, statusLabel, statusTagType } from '@/utils/labels'
import { formatJudgmentLogic } from '@/utils/format'
import type { Alert, Incident, SseEvent, AnalysisReport } from '@/types'

/**
 * 右栏分析面板：吸顶状态行（级别+服务+告警名+状态）+ 分析过程 + 分析报告。
 * 未选中时显示空态提示；RUNNING 走 SSE（重放+实时），结束态拉历史事件。
 * 状态行满宽平铺（无边距无阴影），滚动时内容从其下方穿过。
 * 自动滚动：内容超屏且用户在底部附近时跟随新内容滚到底；上滑暂停跟随，滑回底部恢复，切换告警重置为跟随。
 */
const props = defineProps<{ incidentId: number | null; alert?: Alert | null }>()
const emit = defineEmits<{ (e: 'finished'): void }>()

// 仅滚动时显示滚动条
const { scrolling: paneScrolling, onScroll: onPaneScroll } = useScrollFlash()

// 自动滚动跟随：距底 < 60px 视为"在底部"，内容增长时自动滚到底；
// 用户上滑（离开底部）暂停跟随不被拉回，滑回底部恢复；切换告警重置为跟随
const FOLLOW_BOTTOM_THRESHOLD = 60
const paneRef = ref<HTMLElement | null>(null)
const followBottom = ref(true)

const handlePaneScroll = () => {
  onPaneScroll()
  if (!paneRef.value) return
  followBottom.value =
    paneRef.value.scrollHeight - paneRef.value.scrollTop - paneRef.value.clientHeight < FOLLOW_BOTTOM_THRESHOLD
}

/** 跟随中且内容增长后滚到底（nextTick 等 DOM 渲染完成再取 scrollHeight） */
const scrollToBottomIfFollowing = async () => {
  if (!followBottom.value) return
  await nextTick()
  const el = paneRef.value
  if (el && followBottom.value) {
    el.scrollTop = el.scrollHeight
  }
}

const incidentInfo = ref<Incident | null>(null)
const reportInfo = ref<AnalysisReport | null>(null)
/** 已落库事件（RUNNING 来自 SSE，结束态来自历史接口） */
const events = ref<SseEvent[]>([])
/** text_delta 流式缓冲区（不落库；每收到落库事件即清空，只保留当前流式轮次，```json 界前为思考、界后为报告块） */
const streamingText = ref('')
const reconnecting = ref(false)
const loading = ref(false)
/** 分析过程折叠面板：RUNNING 默认展开，结束态默认收起 */
const processExpanded = ref<string[]>([])
let eventSource: EventSource | null = null
/** 已收到的最大事件序号，重连带回后端做增量重放 */
let lastSeq = 0
let reconnectTimer: number | null = null

onUnmounted(() => {
  closeSse()
  if (reconnectTimer) window.clearTimeout(reconnectTimer)
})

const loadIncident = async () => {
  if (!props.incidentId) return
  loading.value = true
  try {
    const res = await incidentApi.getIncident(props.incidentId)
    const data = res.data
    incidentInfo.value = data
    reportInfo.value = data.report ?? null
    lastSeq = data.lastSeq ?? 0

    if (data.status === 'RUNNING') {
      processExpanded.value = ['process']
      initSse()
    } else {
      closeSse()
      // FAILED 也默认展开，让用户直接看到报错位置
      processExpanded.value = data.status === 'FAILED' ? ['process'] : []
      await loadHistoryEvents()
    }
  } catch (err: any) {
    ElMessage.error(err.message || '加载失败')
  } finally {
    loading.value = false
  }
}

const loadHistoryEvents = async () => {
  if (!props.incidentId) return
  try {
    const res = await incidentApi.getEvents(props.incidentId)
    events.value = res.data
  } catch (err: any) {
    console.error('加载历史事件失败:', err)
  }
}

const initSse = () => {
  closeSse()
  if (!props.incidentId) return
  const es = incidentApi.streamIncident(props.incidentId, lastSeq)
  eventSource = es

  es.onmessage = (event) => {
    reconnecting.value = false
    const data: SseEvent = JSON.parse(event.data)

    // text_delta 不带 sequence：只追加流式缓冲区，不推进 lastSeq
    if (data.type === 'text_delta') {
      streamingText.value += data.data?.delta ?? ''
      return
    }

    // 落库事件：按 sequence 去重（重连重放可能与实时推送重叠）
    const seq = data.sequence
    if (seq == null || seq <= lastSeq) return
    lastSeq = seq
    events.value = [...events.value, data]
    // 落库即一轮流式结束：清空缓冲，只保留当前正在流式的轮次，避免与已显示步骤重复
    streamingText.value = ''

    // 报告定稿：用完整报告替换流式半截文本，重载详情
    if (data.type === 'report_finalized') {
      streamingText.value = ''
      closeSse()
      loadIncident()
      emit('finished')
    }
    // 错误：重载详情进入 FAILED 展示
    if (data.type === 'error') {
      closeSse()
      loadIncident()
      emit('finished')
    }
  }

  es.onerror = () => {
    // RUNNING 中断线：2 秒后带 lastSeq 重开，后端增量重放，不丢事件
    if (incidentInfo.value?.status === 'RUNNING') {
      reconnecting.value = true
      closeSse()
      reconnectTimer = window.setTimeout(initSse, 2000)
    } else {
      closeSse()
    }
  }
}

const closeSse = () => {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
}

// 选中告警切换：重置状态并加载（须在 closeSse/loadIncident 定义之后）
watch(
  () => props.incidentId,
  () => {
    closeSse()
    if (reconnectTimer) window.clearTimeout(reconnectTimer)
    incidentInfo.value = null
    reportInfo.value = null
    events.value = []
    streamingText.value = ''
    lastSeq = 0
    followBottom.value = true
    if (props.incidentId) {
      loadIncident()
    }
  },
  { immediate: true }
)

// 内容增长（步骤事件/流式文本/报告/过程折叠展开）时按跟随状态滚到底
watch(
  () => [events.value.length, streamingText.value, reportInfo.value, processExpanded.value],
  scrollToBottomIfFollowing
)

/** 过滤 LLM 输出中 action 为空的脏数据条目，避免渲染出空行 */
const sopActions = computed(() =>
  (reportInfo.value?.recommendedActions ?? []).filter(a => (a?.action ?? '').trim() !== '')
)

/** 报告块起始标记：prompt 约定最终报告以 ```json 代码块结尾 */
const JSON_FENCE = '```json'

/** 流式缓冲拆分：```json 界前为当前轮思考叙述（在「思考」步骤局部显示），界后为报告块（底部框显示） */
const streamingThought = computed(() => {
  const idx = streamingText.value.indexOf(JSON_FENCE)
  return (idx >= 0 ? streamingText.value.slice(0, idx) : streamingText.value).trim()
})

const streamingReport = computed(() => {
  const idx = streamingText.value.indexOf(JSON_FENCE)
  return idx >= 0 ? streamingText.value.slice(idx) : ''
})

const handleResume = async () => {  if (!incidentInfo.value) return
  loading.value = true
  try {
    await incidentApi.resumeAnalysis(incidentInfo.value.id)
    events.value = []
    streamingText.value = ''
    await loadIncident()
  } catch (err: any) {
    ElMessage.error(err.message || '续跑失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div
    ref="paneRef"
    class="analysis-pane"
    :class="{ 'is-scrolling': paneScrolling }"
    @scroll="handlePaneScroll"
  >
    <!-- 未选中：空态提示 -->
    <div v-if="!incidentId" class="empty-state">
      <el-empty description="← 请选择左侧告警查看分析" />
    </div>

    <template v-else>
      <!-- 吸顶状态栏：第一行 级别+服务+告警名+状态，第二行告警起止时间；两行总高与左侧告警卡片一致 -->
      <div class="status-bar" v-loading="loading">
        <div class="status-main">
          <template v-if="alert">
            <el-tag :type="severityTagType(alert.severity)" size="small" class="no-shrink">
              {{ severityLabel(alert.severity) }}
            </el-tag>
            <el-tag type="info" effect="plain" size="small" class="no-shrink">{{ alert.service }}</el-tag>
            <el-tooltip :content="alert.alertName" placement="top" :show-after="300">
              <strong class="status-name">{{ alert.alertName }}</strong>
            </el-tooltip>
          </template>
          <el-tag v-if="reconnecting" type="warning" size="small" class="no-shrink">连接中断，重连中…</el-tag>
          <el-tag :type="statusTagType(incidentInfo?.status ?? '')" size="small" class="no-shrink">
            {{ statusLabel(incidentInfo?.status ?? '') }}
          </el-tag>
          <el-button
            v-if="incidentInfo?.status === 'FAILED'"
            type="primary"
            size="small"
            class="retry-button"
            @click="handleResume"
            :loading="loading"
          >
            重试
          </el-button>
        </div>
        <!-- 第二行：告警起止时间；endsAt 为空表示未结束，显示"至今" -->
        <p v-if="alert" class="status-time">{{ alert.startsAt }} ~ {{ alert.endsAt || '至今' }}</p>
      </div>

      <!-- 分析过程：RUNNING/FAILED 默认展开（失败需看到报错），其余结束态默认折叠 -->
      <div class="section">
        <el-collapse v-model="processExpanded">
          <el-collapse-item title="分析过程" name="process">
            <ProcessTimeline
              :events="events"
              :live="incidentInfo?.status === 'RUNNING'"
              :streaming-thought="streamingThought"
            />
            <!-- 流式输出区：仅报告块（```json 起）在此流式，report_finalized 到达后被完整报告替换 -->
            <div v-if="incidentInfo?.status === 'RUNNING' && streamingReport" class="streaming-box">
              <p class="streaming-text">{{ streamingReport }}<span class="cursor">▍</span></p>
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>

      <!-- 分析报告（仅 COMPLETED）：平铺在过程下方，与过程同一内容区，无标题无分隔线 -->
      <div v-if="reportInfo" class="report-section">
        <!-- 低置信度提示：仅 confidence < 0.6 时展示，悬浮显示后端给出的理由 -->
        <el-tooltip
          v-if="reportInfo.confidence != null && reportInfo.confidence < 0.6"
          :content="reportInfo.confidenceReason || '分析证据不足，建议人工核实'"
          placement="top"
          :show-after="200"
        >
          <p class="low-confidence">
            <el-icon :size="14"><Warning /></el-icon>
            当前置信度较低
          </p>
        </el-tooltip>
        <p>
          <strong>事件摘要：</strong>
          <el-tag :type="reportInfo.isNoise ? 'info' : 'danger'" size="small">
            {{ reportInfo.isNoise ? '噪音' : '非噪音' }}
          </el-tag>
          <el-tag :type="reportInfo.needsHandling ? 'warning' : 'success'" size="small" class="report-tag">
            {{ reportInfo.needsHandling ? '需要处理' : '不需要处理' }}
          </el-tag>
        </p>
        <p><strong>当前报警产生原因：</strong>{{ reportInfo.rootCauseHypothesis }}</p>
        <p><strong>推荐SOP：</strong></p>
        <ul class="sop-list">
          <li v-for="action in sopActions" :key="action.priority">
            {{ action.priority }}. {{ action.action }}
          </li>
        </ul>
        <p><strong>判断逻辑：</strong></p>
        <p class="judgment-text">{{ formatJudgmentLogic(reportInfo.judgmentLogic) }}</p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.analysis-pane {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  /* 滚动条默认隐藏，仅滚动时显示 */
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}

.analysis-pane.is-scrolling {
  scrollbar-color: var(--color-border) transparent;
}

.analysis-pane::-webkit-scrollbar {
  width: 8px;
}

.analysis-pane::-webkit-scrollbar-track {
  background: transparent;
}

.analysis-pane::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: 4px;
}

.analysis-pane.is-scrolling::-webkit-scrollbar-thumb {
  background: var(--color-border);
}

.empty-state {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 吸顶状态栏：满宽平铺，无边距/圆角/阴影/分隔线；两行（主行 + 起止时间行）总高对齐左侧告警卡片 */
.status-bar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 16px;
  background: var(--el-bg-color);
  flex-shrink: 0;
}

.status-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

/* 第二行：告警起止时间，灰色小字；行高使两行总高与左侧告警卡片（约 77px）一致 */
.status-time {
  margin: 0;
  font-size: 12px;
  line-height: 24px;
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.no-shrink {
  flex-shrink: 0;
}

/* 告警名超长省略，悬浮经 tooltip 显示全名 */
.status-name {
  min-width: 0;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.retry-button {
  margin-left: auto;
  flex-shrink: 0;
}

.section {
  margin: 12px 16px 0;
}

/* 去掉折叠组件自带的分隔线 */
.section :deep(.el-collapse) {
  border: none;
}

/* "分析过程"标题与"事件摘要"同字号（14px 加粗） */
.section :deep(.el-collapse-item__header) {
  border-bottom: none;
  /* EP 默认 48px（且带 min-height:48px），文字居中上下各空一半，视觉空行过大，压到 36px */
  height: 36px;
  min-height: 36px;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-primary);
}

/* 展开箭头默认被 flex:1 的标题 span 推到面板最右，收紧标题宽度让箭头紧跟文字 */
.section :deep(.el-collapse-item__title) {
  flex: 0 0 auto;
}

.section :deep(.el-collapse-item__arrow) {
  margin: 0 0 0 6px;
}

.section :deep(.el-collapse-item__wrap) {
  border-bottom: none;
}

/* EP 内容区自带 25px 底部内边距，与报告区外边距叠加导致空行过大，收掉 */
.section :deep(.el-collapse-item__content) {
  padding-bottom: 0;
}

.report-section {
  margin: 12px 16px 16px;
}

.report-section p {
  margin: 10px 0 0 0;
  line-height: 1.7;
}

.report-section ul {
  margin: 6px 0 0 0;
  padding-left: 20px;
}

.report-section li {
  margin: 4px 0;
}

.report-tag {
  margin-left: 6px;
}

/* 低置信度提示行：警告色，图标与文字同行，悬浮（tooltip）显示置信度理由 */
.low-confidence {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 0 0 4px 0;
  color: var(--el-color-warning);
  font-size: 13px;
  width: fit-content;
  cursor: help;
}

/* SOP 列表：编号由数据提供，去掉默认项目符号与缩进（提高优先级，压过 .report-section ul 的缩进规则） */
.report-section ul.sop-list {
  list-style: none;
  padding-left: 0;
}

/* 判断逻辑：保留 \n 换行（编号分条格式） */
.judgment-text {
  white-space: pre-wrap;
}

.streaming-box {
  margin-top: 16px;
  padding: 12px;
  background: var(--el-bg-color);
  border-radius: 6px;
}

.streaming-text {
  white-space: pre-wrap;
  margin: 0;
  line-height: 1.6;
}

.cursor {
  animation: blink 1s infinite;
  color: var(--color-primary);
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

ul {
  margin: 10px 0;
  padding-left: 20px;
}

li {
  margin: 5px 0;
}
</style>
