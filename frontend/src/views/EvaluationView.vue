<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Close, View } from '@element-plus/icons-vue'
import { statsApi, type DrillQuery } from '@/api'
import { ElMessage } from 'element-plus'
import { statusLabel } from '@/utils/labels'
import type { DrillRow, EvaluationStats, AdoptIssue } from '@/types'

/**
 * 评测总览页（口径见 docs/plan/EVALUATION_PLAN.md）：
 * 北极星卡（采纳率）→ 结果卡（完成率/耗时）→ 过程卡（工具调用）→ 护栏卡（失控/FAILED 归因/工具成功率）。
 * 有下钻价值的数字可点，卡片下方内联展开该指标过滤后的分析列表，行尾"查看"跳工作台对应分析。
 * 范围切换三分类（三个独立布尔，可重叠）：正式（is_prod）/ 评测集（is_eval）/ 测试（is_test）；起始日期可选。
 */
const router = useRouter()

const loading = ref(false)
const stats = ref<EvaluationStats | null>(null)
const scopeMode = ref<'prod' | 'eval' | 'test'>('prod')
const sinceDate = ref<string>('')

const SCOPE_LABELS: Record<'prod' | 'eval' | 'test', string> = {
  prod: '正式数据',
  eval: '评测集',
  test: '测试数据',
}

const scopeQuery = computed(() => ({
  tag: scopeMode.value === 'prod' ? undefined : scopeMode.value,
  since: sinceDate.value || undefined,
}))

const scopeNote = computed(() => {
  if (!stats.value) return ''
  const s = stats.value.scope
  const range = SCOPE_LABELS[scopeMode.value]
  return s.since ? `${range} · ${s.since} 起` : range
})

const loadStats = async () => {
  loading.value = true
  closeDrill()
  try {
    const res = await statsApi.evaluation(scopeQuery.value)
    stats.value = res.data
  } catch (err: any) {
    ElMessage.error(err.message || '加载评测数据失败')
  } finally {
    loading.value = false
  }
}

onMounted(loadStats)

// ---- 下钻 ----

const drill = ref<{ title: string; rows: DrillRow[]; loading: boolean } | null>(null)

const openDrill = async (title: string, query: Omit<DrillQuery, 'scope' | 'since'>) => {
  drill.value = { title, rows: [], loading: true }
  try {
    const res = await statsApi.drill({ ...query, ...scopeQuery.value })
    drill.value.rows = res.data
  } catch (err: any) {
    ElMessage.error(err.message || '加载明细失败')
    drill.value = null
  } finally {
    if (drill.value) drill.value.loading = false
  }
}

const closeDrill = () => {
  drill.value = null
}

const viewIncident = (incidentId: number) => {
  router.push({ path: '/', query: { incidentId } })
}

// ---- 文案口径：状态走 labels.ts，信号/归因带浅白解释，禁工程缩写 ----

const RUNAWAY_SIGNAL_TEXT: Record<string, string> = {
  iterationExhausted: '迭代触顶（用满最大分析轮数仍未出报告）',
  thoughtLoop: '思考循环（模型反复输出相同思考）',
}

const FAILED_REASON_TEXT: Record<string, string> = {
  llmCallFailed: 'LLM 调用失败（限流/网络/密钥）',
  reportParseFailed: '报告解析失败',
  iterationExhausted: '迭代触顶',
  other: '其他原因',
}

const ISSUE_TEXT: Record<AdoptIssue, string> = {
  noise: '噪音判断',
  evidence: '证据支撑',
  logic: '判断逻辑',
  leak: '内部逻辑泄露',
  sop: 'SOP 可执行',
  redundant: '信息冗余',
}

/** 北极星卡原因分布展示顺序（与原因弹窗勾选顺序一致）；key 缺失按 0 显示（后端旧版本只返回三个 key） */
const ISSUE_ORDER: AdoptIssue[] = ['noise', 'evidence', 'sop', 'logic', 'leak', 'redundant']

const issueSummary = computed(() => {
  const counts = stats.value?.adoption.issueCounts
  if (!counts) return ''
  return ISSUE_ORDER.map(key => `${ISSUE_TEXT[key]} ${counts[key] ?? 0}`).join(' · ')
})

/** 明细行第二行：状态 + 信号/归因解释 + 耗时/调用数 + 标注原因 */
const rowSubText = (row: DrillRow) => {
  const parts: string[] = [statusLabel(row.status)]
  if (row.runawaySignal) parts.push(RUNAWAY_SIGNAL_TEXT[row.runawaySignal] ?? row.runawaySignal)
  if (row.failedReason) parts.push(FAILED_REASON_TEXT[row.failedReason] ?? row.failedReason)
  if (row.durationSeconds != null) parts.push(`耗时 ${humanDuration(row.durationSeconds)}`)
  if (row.toolCallCount != null) parts.push(`工具调用 ${row.toolCallCount} 次`)
  const issues = (row.adoptIssues ?? []).filter(i => i in ISSUE_TEXT).map(i => ISSUE_TEXT[i as AdoptIssue])
  if (issues.length > 0) parts.push(`不赞同原因：${issues.join('、')}`)
  return parts.join(' · ')
}

/** 告警三要素（与左栏告警卡同款）：服务 · 告警名 · 开始时间（月-日 时:分） */
const rowTitle = (row: DrillRow) => {
  const time = row.startsAt ? row.startsAt.slice(5, 16) : ''
  return [row.service, row.alertName, time].filter(Boolean).join(' · ')
}

/** 秒 → 人话时长：<90s 显示秒，<1h 显示分，否则小时分 */
const humanDuration = (seconds: number) => {
  if (seconds < 90) return `${seconds} 秒`
  if (seconds < 3600) return `${Math.round(seconds / 60)} 分钟`
  const h = Math.floor(seconds / 3600)
  const m = Math.round((seconds % 3600) / 60)
  return `${h} 小时 ${m} 分`
}

const humanRate = (rate: number) => `${rate}%`
</script>

<template>
  <div class="evaluation-page" v-loading="loading">
    <!-- 范围栏：三分类（三个独立布尔，可重叠），历史污染数据打 test 标后自动移出正式 -->
    <div class="scope-bar">
      <el-radio-group v-model="scopeMode" @change="loadStats">
        <el-radio-button value="prod">正式数据</el-radio-button>
        <el-radio-button value="eval">评测集</el-radio-button>
        <el-radio-button value="test">测试数据</el-radio-button>
      </el-radio-group>
      <el-date-picker
        v-model="sinceDate"
        type="date"
        placeholder="起始日期（可选）"
        value-format="YYYY-MM-DD"
        :clearable="true"
        class="since-picker"
        @change="loadStats"
      />
      <span v-if="scopeNote" class="scope-note">当前范围：{{ scopeNote }} · 共 {{ stats?.scope.totalIncidents ?? 0 }} 次分析</span>
    </div>

    <el-empty v-if="stats && stats.scope.totalIncidents === 0" description="当前范围内暂无分析数据" />

    <template v-if="stats && stats.scope.totalIncidents > 0">
      <!-- 北极星：分析采纳率 -->
      <section class="card north-star">
        <div class="card-title">分析采纳率（北极星）</div>
        <div class="north-main">
          <span class="big-number">{{ humanRate(stats.adoption.rate) }}</span>
          <span class="north-meta">
            已标注 {{ stats.adoption.annotated }} · 赞同 {{ stats.adoption.adopted }} ·
            <span class="clickable" @click="openDrill('不赞同的分析', { metric: 'dislike' })">
              不赞同 {{ stats.adoption.dislike }}
            </span>
            <template v-if="stats.adoption.dislike > 0">
              （原因：{{ issueSummary }}
              <template v-if="stats.adoption.dislikeNoReason > 0">· 未说明 {{ stats.adoption.dislikeNoReason }}</template>）
            </template>
          </span>
        </div>
        <p class="card-note">已标注 {{ stats.adoption.annotated }} 次，样本 ≥20 后采纳率才有参考价值</p>
      </section>

      <!-- 结果 + 过程 -->
      <section class="cards-grid">
        <div class="card">
          <div class="card-title">完成率</div>
          <div class="metric-line">
            <span class="number">{{ humanRate(stats.completion.completionRate) }}</span>
            <span class="unit">{{ stats.completion.completed }}/{{ stats.completion.total }} 次完成</span>
          </div>
          <div class="sub-line">
            <span class="clickable" @click="openDrill('失败的分析', { metric: 'failed' })">
              失败 {{ stats.completion.failed }} 次
            </span>
            <template v-if="stats.completion.running > 0"> · 进行中 {{ stats.completion.running }}</template>
          </div>
        </div>

        <div class="card">
          <div class="card-title">端到端耗时</div>
          <div class="metric-line">
            <span class="number">{{ stats.durationSeconds.p50 != null ? humanDuration(stats.durationSeconds.p50) : '—' }}</span>
            <span class="unit">P50</span>
          </div>
          <div class="sub-line">
            <span
              class="clickable"
              @click="openDrill('耗时最长的分析', { metric: 'slow' })"
            >
              P95 {{ stats.durationSeconds.p95 != null ? humanDuration(stats.durationSeconds.p95) : '—' }}
            </span>
            · 样本 {{ stats.durationSeconds.count }}
          </div>
        </div>

        <div class="card">
          <div class="card-title">工具调用次数</div>
          <div class="metric-line">
            <span class="number">{{ stats.toolCalls.avgCalls }}</span>
            <span class="unit">次/分析 · 最多 {{ stats.toolCalls.maxCalls }}</span>
          </div>
          <div class="sub-line distribution">
            <span
              v-for="item in stats.toolCalls.distribution"
              :key="item.bucket"
              class="clickable"
              :class="{ 'zero': item.incidents === 0 }"
              @click="item.incidents > 0 && openDrill(`调用 ${item.bucket} 次的分析`, { metric: 'toolCalls', bucket: item.bucket })"
            >
              {{ item.bucket }}: {{ item.incidents }}
            </span>
          </div>
        </div>
      </section>

      <!-- 护栏 -->
      <section class="cards-grid">
        <div class="card">
          <div class="card-title">失控率</div>
          <div class="metric-line">
            <span
              class="number"
              :class="{ 'clickable': stats.runaway.incidents > 0 }"
              @click="stats.runaway.incidents > 0 && openDrill('失控的分析', { metric: 'runaway' })"
            >
              {{ humanRate(stats.runaway.rate) }}
            </span>
            <span class="unit">{{ stats.runaway.incidents }}/{{ stats.completion.total }}</span>
          </div>
          <div class="sub-line">
            迭代触顶 {{ stats.runaway.iterationExhausted }} · 思考循环 {{ stats.runaway.thoughtLoop }}
          </div>
        </div>

        <div class="card">
          <div class="card-title">FAILED 归因</div>
          <div class="reason-lines">
            <span class="clickable" @click="openDrill('失败的分析', { metric: 'failed' })">
              LLM 调用失败 {{ stats.failedReasons.llmCallFailed }}
            </span>
            <span class="clickable" @click="openDrill('失败的分析', { metric: 'failed' })">
              迭代触顶 {{ stats.failedReasons.iterationExhausted }}
            </span>
            <span class="clickable" @click="openDrill('失败的分析', { metric: 'failed' })">
              解析失败 {{ stats.failedReasons.reportParseFailed }}
            </span>
            <span class="clickable" @click="openDrill('失败的分析', { metric: 'failed' })">
              其他 {{ stats.failedReasons.other }}
            </span>
          </div>
        </div>

        <div class="card">
          <div class="card-title">工具成功率</div>
          <div class="metric-line">
            <span class="number">{{ humanRate(stats.toolSuccess.rate) }}</span>
            <span class="unit">{{ stats.toolSuccess.success }}/{{ stats.toolSuccess.total }}</span>
          </div>
          <div class="sub-line">稳态应接近 100%，跌破需拆环境错误 vs 传参错误</div>
        </div>
      </section>

      <!-- 下钻明细：卡片下方内联展开 -->
      <section v-if="drill" class="card drill-panel">
        <div class="drill-header">
          <strong>{{ drill.title }}</strong>
          <span class="drill-count">{{ drill.rows.length }} 条</span>
          <el-button :icon="Close" text size="small" class="drill-close" @click="closeDrill" />
        </div>
        <div v-if="drill.loading" class="drill-loading" v-loading="true" />
        <el-empty v-else-if="drill.rows.length === 0" description="没有符合条件的数据" :image-size="60" />
        <div v-else class="drill-list">
          <div v-for="row in drill.rows" :key="row.incidentId" class="drill-row">
            <div class="drill-text">
              <el-tooltip :content="rowTitle(row)" placement="top" :show-after="300">
                <span class="row-title">{{ rowTitle(row) }}</span>
              </el-tooltip>
              <span class="row-sub">{{ rowSubText(row) }}</span>
            </div>
            <el-button :icon="View" text type="primary" size="small" @click="viewIncident(row.incidentId)">
              查看
            </el-button>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.evaluation-page {
  max-width: 960px;
  margin: 0 auto;
  padding: 16px 20px 32px;
  min-height: calc(100% - 52px);
  box-sizing: border-box;
}

/* 范围栏 */
.scope-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}

.since-picker {
  width: 180px;
}

.scope-note {
  font-size: 12px;
  color: var(--color-text-secondary);
}

/* 卡片通用：与告警卡同风格（边框圆角，无重阴影） */
.card {
  background: var(--el-bg-color);
  border: 1px solid var(--color-border-light);
  border-radius: 8px;
  padding: 14px 16px;
}

.card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-secondary);
}

.card-note {
  margin: 8px 0 0 0;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

/* 北极星卡 */
.north-star {
  margin-bottom: 12px;
}

.north-main {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-top: 6px;
  flex-wrap: wrap;
}

.big-number {
  font-size: 40px;
  font-weight: 700;
  color: var(--color-primary-dark-2);
  line-height: 1.1;
}

.north-meta {
  font-size: 13px;
  color: var(--color-text-regular);
}

/* 指标卡网格 */
.cards-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.metric-line {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-top: 6px;
}

.number {
  font-size: 28px;
  font-weight: 700;
  color: var(--color-text-primary);
}

.unit {
  font-size: 12px;
  color: var(--color-text-secondary);
}

.sub-line {
  margin-top: 6px;
  font-size: 12px;
  color: var(--color-text-secondary);
}

/* 分布桶一排多个可点项，留出间距避免挤在一起 */
.sub-line.distribution {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
}

.reason-lines {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 8px;
  font-size: 13px;
  color: var(--color-text-regular);
}

/* 可点数字：弱链接样式，不可点时还原 */
.clickable {
  color: var(--color-link);
  cursor: pointer;
}

.clickable:hover {
  text-decoration: underline;
}

.clickable.zero {
  color: var(--el-text-color-placeholder);
  cursor: default;
}

.clickable.zero:hover {
  text-decoration: none;
}

/* 下钻面板 */
.drill-panel {
  margin-bottom: 12px;
}

.drill-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.drill-count {
  font-size: 12px;
  color: var(--color-text-secondary);
}

.drill-close {
  margin-left: auto;
}

.drill-loading {
  height: 60px;
}

.drill-list {
  margin-top: 4px;
}

.drill-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
  border-top: 1px solid var(--color-border-light);
}

.drill-row:first-child {
  border-top: none;
}

.drill-text {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.row-title {
  font-size: 13px;
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.row-sub {
  font-size: 12px;
  color: var(--color-text-secondary);
}

.drill-row .el-button {
  flex-shrink: 0;
  margin-left: auto;
}

@media (max-width: 768px) {
  .evaluation-page {
    padding: 12px;
  }
}
</style>
