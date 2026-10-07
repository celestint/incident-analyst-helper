<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { alertApi, incidentApi } from '@/api'
import { ElMessage } from 'element-plus'
import UserChatSide from '@/components/UserChatSide.vue'
import { severityLabel } from '@/utils/labels'
import { formatJudgmentLogic } from '@/utils/format'
import type { Alert, AnalysisReport, SseEvent } from '@/types'

/**
 * 用户视角：仿企业微信群只读视图（群名栏 + 消息流 + 右侧信息栏 + 只读输入条）。
 * 消息流由现有数据驱动，只含机器人三类消息：告警（蓝字「分析」）、失败（蓝字「重试」）、
 * 报告（四段式独立消息）。「分析/重试」复用工作台接口与 SSE，状态变化实时反映在消息流里。
 * 默认只渲染最新 5 条机器人消息，更早的收起可展开；RUNNING 的 incident 各建一条 SSE 跟踪，
 * 30s 轮询 getAlerts 兜底（轮询会整体重建消息流，SSE 只负责及时性）。
 */

// ===== 消息模型 =====

type ChatMsg =
  | { kind: 'alert'; key: string; time: string; alert: Alert }
  | {
      kind: 'failure'
      key: string
      time: string
      alert: Alert
      incidentId: number
      errorMessage: string
    }
  | { kind: 'report'; key: string; time: string; alert: Alert; report: AnalysisReport }

type RenderItem =
  | { type: 'divider'; key: string; label: string }
  | { type: 'msg'; key: string; msg: ChatMsg }

const messages = ref<ChatMsg[]>([])
const loaded = ref(false)
/** 最近一次 getAlerts 的原始列表（SSE 重连时判断告警是否仍 RUNNING） */
const latestAlerts = ref<Alert[]>([])
/** 消息流默认只显示最新 5 条机器人消息 */
const expanded = ref(false)

const visibleMessages = computed(() =>
  expanded.value ? messages.value : messages.value.slice(-5)
)

const hiddenCount = computed(() => messages.value.length - visibleMessages.value.length)

/** 按天插入时间分隔条（同天省略，消息自带时间字段） */
const renderItems = computed<RenderItem[]>(() => {
  const items: RenderItem[] = []
  let lastDate = ''
  for (const msg of visibleMessages.value) {
    const date = msg.time.slice(0, 10)
    if (date && date !== lastDate) {
      items.push({ type: 'divider', key: `divider-${date}`, label: date })
      lastDate = date
    }
    items.push({ type: 'msg', key: msg.key, msg })
  }
  return items
})

// ===== 数据加载：getAlerts + 各 incident 详情整体重建消息流 =====

const loadFlow = async () => {
  try {
    const res = await alertApi.getAlerts()
    // 消息流按时间升序呈现（时间格式固定，字符串比较即时间比较）
    const list = [...res.data].sort((a, b) => a.startsAt.localeCompare(b.startsAt))
    latestAlerts.value = list
    const incidentResList = await Promise.all(
      list.filter(a => a.incidentId != null).map(a => incidentApi.getIncident(a.incidentId!))
    )
    const byIncidentId = new Map(incidentResList.map(r => [r.data.id, r.data]))

    const msgs: ChatMsg[] = []
    for (const alert of list) {
      msgs.push({ kind: 'alert', key: `alert-${alert.id}`, time: alert.startsAt, alert })
      const incident = alert.incidentId != null ? byIncidentId.get(alert.incidentId) : null
      if (!incident) continue
      // 每条告警消息后跟其衍生的失败/报告消息（RUNNING 无衍生消息，告警消息上显示分析中）
      if (incident.status === 'COMPLETED' && incident.report) {
        msgs.push({
          kind: 'report',
          key: `report-${incident.id}`,
          time: incident.completedAt || incident.updatedAt || incident.createdAt,
          alert,
          report: incident.report,
        })
      } else if (incident.status === 'FAILED') {
        msgs.push({
          kind: 'failure',
          key: `failure-${incident.id}`,
          time: incident.updatedAt || incident.createdAt,
          alert,
          incidentId: incident.id,
          errorMessage: incident.errorMessage || '未知错误',
        })
      }
    }
    messages.value = msgs
    reconcileSse(list)
  } catch (err: any) {
    // 首次加载失败才弹提示，30s 兜底轮询失败只记日志，避免刷屏
    if (!loaded.value) ElMessage.error(err.message || '消息流加载失败')
    console.error('加载消息流失败:', err)
  } finally {
    loaded.value = true
  }
}

// ===== SSE 跟踪：每个 RUNNING 的 incident 一条连接，终端事件触发整体重建 =====

interface SseTrack {
  es: EventSource
  timer: number | null
}

const sseMap = new Map<number, SseTrack>()

const closeSse = (incidentId: number) => {
  const track = sseMap.get(incidentId)
  if (!track) return
  if (track.timer) window.clearTimeout(track.timer)
  track.es.close()
  sseMap.delete(incidentId)
}

const openSse = (incidentId: number) => {
  // since=0 全量重放：本页面只关心终端事件（report_finalized/error），重放无副作用
  const es = incidentApi.streamIncident(incidentId, 0)
  const track: SseTrack = { es, timer: null }
  sseMap.set(incidentId, track)

  es.onmessage = event => {
    const data: SseEvent = JSON.parse(event.data)
    if (data.type === 'report_finalized' || data.type === 'error') {
      closeSse(incidentId)
      loadFlow()
    }
  }

  es.onerror = () => {
    // 断线 2 秒后重开；告警已不在 RUNNING（被轮询先更新）则不再重连
    const t = sseMap.get(incidentId)
    if (!t) return
    t.es.close()
    t.timer = window.setTimeout(() => {
      sseMap.delete(incidentId)
      const alert = latestAlerts.value.find(a => a.incidentId === incidentId)
      if (alert?.status === 'RUNNING') openSse(incidentId)
    }, 2000)
  }
}

const reconcileSse = (alerts: Alert[]) => {
  const runningIds = new Set(
    alerts.filter(a => a.status === 'RUNNING' && a.incidentId != null).map(a => a.incidentId!)
  )
  for (const id of [...sseMap.keys()]) {
    if (!runningIds.has(id)) closeSse(id)
  }
  for (const id of runningIds) {
    if (!sseMap.has(id)) openSse(id)
  }
}

// ===== 分析 / 重试 =====

/** 已点击「分析」但状态尚未刷新为 RUNNING 的告警（链接即时置灰） */
const startingIds = ref(new Set<number>())
/** 已点击「重试」但状态尚未刷新的 incident */
const retryingIds = ref(new Set<number>())

const handleAnalyze = async (alert: Alert) => {
  if (startingIds.value.has(alert.id)) return
  startingIds.value.add(alert.id)
  try {
    await incidentApi.startAnalysis(alert.id)
    await loadFlow()
  } catch (err: any) {
    ElMessage.error(err.message || '分析启动失败')
  } finally {
    startingIds.value.delete(alert.id)
  }
}

const handleRetry = async (incidentId: number) => {
  if (retryingIds.value.has(incidentId)) return
  retryingIds.value.add(incidentId)
  try {
    await incidentApi.resumeAnalysis(incidentId)
    await loadFlow()
  } catch (err: any) {
    ElMessage.error(err.message || '重试失败')
  } finally {
    retryingIds.value.delete(incidentId)
  }
}

// ===== 轮询兜底与生命周期清理 =====

let pollTimer: number | null = null

onMounted(() => {
  loadFlow()
  pollTimer = window.setInterval(loadFlow, 30_000)
})

onUnmounted(() => {
  if (pollTimer) window.clearInterval(pollTimer)
  for (const id of [...sseMap.keys()]) closeSse(id)
})

// ===== 滚动：新消息追加后保持在可视区内 =====

const bodyRef = ref<HTMLElement | null>(null)

const scrollToBottom = async () => {
  await nextTick()
  const el = bodyRef.value
  if (el) el.scrollTop = el.scrollHeight
}

watch(
  () => [messages.value.length, visibleMessages.value.length],
  () => {
    if (loaded.value) scrollToBottom()
  }
)

// ===== 展示辅助 =====

const severityClass = (severity: string) =>
  severity === 'critical' ? 'txt-danger' : severity === 'warning' ? 'txt-warning' : ''

/** 过滤 LLM 输出中 action 为空的脏数据条目（与 AnalysisPane 一致） */
const sopActions = (report: AnalysisReport) =>
  (report.recommendedActions ?? []).filter(a => (a?.action ?? '').trim() !== '')
</script>

<template>
  <div class="user-view">
    <div class="chat-main">
      <!-- 群名栏 -->
      <div class="chat-head">
        <span>告警响应群</span>
        <span class="count">（12）</span>
        <span class="tools" aria-hidden="true">🔍 ⋯</span>
      </div>

      <!-- 消息流 -->
      <div ref="bodyRef" class="chat-body">
        <div v-if="renderItems.length === 0" class="empty-state">暂无告警消息</div>
        <template v-else>
          <a v-if="hiddenCount > 0" class="load-earlier" @click="expanded = true">
            查看更早的消息
          </a>
          <template v-for="item in renderItems" :key="item.key">
            <div v-if="item.type === 'divider'" class="time-divider">{{ item.label }}</div>
            <div v-else class="msg">
              <div class="avatar">🤖</div>
              <div class="msg-body">
                <div class="msg-name">
                  告警机器人
                  <!-- 失败/报告是"事后自动回推"消息（触发方式可能是手动或自动配置），不显示具体时间 -->
                  <span v-if="item.msg.kind === 'alert'" class="msg-time">{{ item.msg.time }}</span>
                </div>
                <div class="bot-card">
                  <!-- ① 告警消息 -->
                  <template v-if="item.msg.kind === 'alert'">
                    <div class="card-title">
                      <span class="txt" :class="severityClass(item.msg.alert.severity)">
                        {{ severityLabel(item.msg.alert.severity) }}
                      </span>
                      <span>[{{ item.msg.alert.service }}]</span>
                      <strong class="card-alert-name">{{ item.msg.alert.alertName }}</strong>
                    </div>
                    <div class="line">开始时间：{{ item.msg.alert.startsAt }}</div>
                    <div
                      v-if="item.msg.alert.status === 'PENDING' && !startingIds.has(item.msg.alert.id)"
                      class="action-row"
                    >
                      <a class="chat-link" @click="handleAnalyze(item.msg.alert)">分析</a>
                    </div>
                    <div
                      v-else-if="item.msg.alert.status === 'PENDING' || item.msg.alert.status === 'RUNNING'"
                      class="action-row"
                    >
                      <span class="chat-link is-disabled">分析中…</span>
                    </div>
                  </template>

                  <!-- ② 失败消息 -->
                  <template v-else-if="item.msg.kind === 'failure'">
                    <div class="card-title">
                      ⛔ 分析失败 · {{ item.msg.alert.alertName }} @ {{ item.msg.alert.service }}
                    </div>
                    <div class="line">失败原因：{{ item.msg.errorMessage }}</div>
                    <div class="action-row">
                      <a
                        v-if="!retryingIds.has(item.msg.incidentId)"
                        class="chat-link"
                        @click="handleRetry(item.msg.incidentId)"
                      >
                        重试
                      </a>
                      <span v-else class="chat-link is-disabled">分析中…</span>
                    </div>
                  </template>

                  <!-- ③ 报告消息：独立消息、四段式 -->
                  <template v-else>
                    <div class="card-title">
                      ✅ 分析报告 · {{ item.msg.alert.alertName }} @ {{ item.msg.alert.service }}
                    </div>
                    <hr />
                    <div class="line">
                      <strong>事件摘要：</strong>
                      <span class="txt" :class="item.msg.report.isNoise ? 'txt-warning' : 'txt-danger'">
                        {{ item.msg.report.isNoise ? '噪音' : '非噪音' }}
                      </span>
                      <span class="dot">·</span>
                      <span class="txt" :class="item.msg.report.needsHandling ? 'txt-warning' : 'txt-success'">
                        {{ item.msg.report.needsHandling ? '需要处理' : '不需要处理' }}
                      </span>
                    </div>
                    <div class="line">
                      <strong>当前报警产生原因：</strong>{{ item.msg.report.rootCauseHypothesis }}
                    </div>
                    <div class="line"><strong>推荐SOP：</strong></div>
                    <div
                      v-for="action in sopActions(item.msg.report)"
                      :key="action.priority"
                      class="line"
                    >
                      {{ action.priority }}. {{ action.action }}
                    </div>
                    <div class="line"><strong>判断逻辑：</strong></div>
                    <div class="line judgment">{{ formatJudgmentLogic(item.msg.report.judgmentLogic) }}</div>
                    <div
                      v-if="item.msg.report.confidence != null && item.msg.report.confidence < 0.6"
                      class="line low-confidence"
                      :title="item.msg.report.confidenceReason || '分析证据不足，建议人工核实'"
                    >
                      ⚠ 当前置信度较低
                    </div>
                  </template>
                </div>
              </div>
            </div>
          </template>
        </template>
      </div>

      <!-- 底部只读输入条 -->
      <div class="chat-input">
        <input placeholder="仅展示群消息，无法发言" disabled />
        <button class="send" disabled>发送</button>
      </div>
    </div>

    <!-- 右侧信息栏：群公告 / 群成员（纯静态假数据 + 模糊化） -->
    <UserChatSide />
  </div>
</template>

<style scoped>
/* 群聊窗口占满导航以下视口 */
.user-view {
  display: flex;
  height: calc(100vh - 52px);
  min-width: 0;
}

.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

/* 群名栏 */
.chat-head {
  height: 48px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 16px;
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--color-border-light);
  font-weight: 600;
  color: var(--color-text-primary);
}

.chat-head .count {
  color: var(--color-text-secondary);
  font-weight: 400;
  font-size: 13px;
}

/* 右侧装饰图标（同真实 IM 群名栏），无功能 */
.chat-head .tools {
  margin-left: auto;
  color: var(--color-text-secondary);
  letter-spacing: 8px;
  font-weight: 400;
}

/* 消息流 */
.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
  /* 滚动条默认隐藏，仅滚动时显示 */
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}

.chat-body:hover {
  scrollbar-color: var(--color-border) transparent;
}

.chat-body::-webkit-scrollbar {
  width: 8px;
}

.chat-body::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: 4px;
}

.chat-body:hover::-webkit-scrollbar-thumb {
  background: var(--color-border);
}

/* 展开更早消息入口 */
.load-earlier {
  display: block;
  text-align: center;
  font-size: 12px;
  color: var(--color-link);
  cursor: pointer;
  margin: 6px 0 18px;
}

.load-earlier:hover {
  text-decoration: underline;
}

.empty-state {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-secondary);
  font-size: 13px;
}

.time-divider {
  text-align: center;
  font-size: 12px;
  color: var(--color-text-secondary);
  margin: 6px 0 18px;
}

/* 机器人消息 */
.msg {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
}

.avatar {
  flex-shrink: 0;
  width: 34px;
  height: 34px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  background: var(--color-primary-light-9);
  border: 1px solid var(--color-border-light);
}

.msg-body {
  min-width: 0;
  max-width: 620px;
}

.msg-name {
  font-size: 12px;
  color: var(--color-text-secondary);
  margin-bottom: 4px;
}

.msg-time {
  margin-left: 4px;
}

.bot-card {
  background: var(--el-bg-color);
  border: 1px solid var(--color-border-light);
  border-radius: 4px 10px 10px 10px;
  padding: 12px 14px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.bot-card .card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  flex-wrap: wrap;
  color: var(--color-text-primary);
}

/* 告警名单行省略，超长不撑爆卡片 */
.card-alert-name {
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.bot-card .line {
  margin-top: 6px;
  font-size: 13px;
  color: var(--color-text-regular);
  line-height: 1.7;
}

.bot-card .line strong {
  color: var(--color-text-primary);
}

.bot-card hr {
  border: none;
  border-top: 1px solid var(--color-border-light);
  margin: 8px 0;
}

.action-row {
  margin-top: 10px;
}

/* IM 文本风格链接：蓝字，点击后置灰 */
.chat-link {
  color: var(--color-link);
  cursor: pointer;
  text-decoration: none;
}

.chat-link:hover {
  text-decoration: underline;
}

.chat-link.is-disabled {
  color: var(--el-text-color-placeholder);
  cursor: not-allowed;
}

/* 强语义文字：只变色不做底色（鲜艳醒目） */
.txt {
  font-weight: 600;
}

.txt-danger {
  color: var(--color-danger-strong);
}

.txt-warning {
  color: var(--color-warning-strong);
}

.txt-success {
  color: var(--color-success-strong);
}

.dot {
  margin: 0 2px;
}

/* 判断逻辑：保留编号分条换行 */
.judgment {
  white-space: pre-wrap;
}

.low-confidence {
  color: var(--color-warning);
  cursor: help;
}

/* 底部只读输入条 */
.chat-input {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  background: var(--el-bg-color);
  border-top: 1px solid var(--color-border-light);
}

.chat-input input {
  flex: 1;
  height: 32px;
  border: 1px solid var(--color-border-light);
  border-radius: 6px;
  padding: 0 12px;
  font-size: 13px;
  background: var(--color-bg-page);
  color: var(--el-text-color-placeholder);
}

.chat-input input::placeholder {
  color: var(--el-text-color-placeholder);
}

.chat-input .send {
  height: 32px;
  padding: 0 16px;
  border-radius: 6px;
  border: none;
  background: var(--color-border-light);
  color: var(--el-text-color-placeholder);
  cursor: not-allowed;
}

/* 窄屏隐藏右侧信息栏 */
@media (max-width: 768px) {
  .user-view :deep(.chat-side) {
    display: none;
  }

  .chat-body {
    padding: 16px 12px;
  }
}
</style>
