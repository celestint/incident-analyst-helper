<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Component } from 'vue'
import {
  CircleCloseFilled,
  DocumentChecked,
  Loading,
  Promotion,
} from '@element-plus/icons-vue'
import type { SseEvent } from '@/types'

/**
 * 分析过程时间线：把扁平事件流聚合为结构化步骤后渲染（RUNNING/FAILED/COMPLETED 共用）。
 * 聚合规则：
 * - 相邻的 tool_call_start + tool_call_result + 紧随的 evidence_collected 合并为一个可展开的"调用工具"步骤
 * - 历史失败尝试（非末次尝试）整轮不展示，只渲染末次尝试：开始分析 / 思考（可折叠）/ 工具步骤 / 错误（红色）/ 报告完成
 * 折叠规则：步骤默认折叠且不自动展开（执行中的工具只显示"· 执行中"），非 live 一律收起；
 * 呈现规则：每步「彩色图标 + 名称 · 状态」；无时间线圆点/竖线，图标与"分析过程"标题左对齐；
 *          折叠头单行省略，避免长摘要换行撑爆固定行高（字重叠）
 * 流式规则：live 且 streamingThought 非空时，步骤列表末尾渲染流式中的「思考」——
 *          标题右侧固定单行框只显示最新一行（替换式），agent_thought 落库后由正常思考步骤接替；
 *          live 且无流式思考内容时，步骤末尾渲染「分析执行中…」加载行（工具执行/等待间隙的进行中反馈），两者不同时出现
 */
const props = defineProps<{ events: SseEvent[]; live?: boolean; streamingThought?: string }>()

interface ToolStep {
  kind: 'tool'
  key: number
  tool: string
  args: string
  success: boolean | null
  /** 折叠头人话（如"查阅 1 文件"），后端语义化生成；仅当 detail 存在时使用 */
  summary: string
  /** 展开描述（如"读取 HighMemoryUsage.md"）；历史事件无此字段，走旧渲染兜底 */
  detail: string
  error: string
  evidence: { source: string; content: string; timestamp: string } | null
}
interface ThoughtStep {
  kind: 'thought'
  key: number
  thought: string
}
interface InfoStep {
  kind: 'info'
  key: number
  /** 开始分析 / 续跑分析 */
  label: string
}
interface ErrorStep {
  kind: 'error'
  key: number
  message: string
}
interface FinalStep {
  kind: 'final'
  key: number
}
type Step = ToolStep | ThoughtStep | InfoStep | ErrorStep | FinalStep

const steps = computed<Step[]>(() => {
  const events = props.events
  if (events.length === 0) return []

  // 切分尝试段：incident_received 是边界；带 resumed 标记的是续跑边界，
  // 但续跑与被续跑的段属于同一条成功链路，这里不按它拆分（错误节点单独隐藏，见下），
  // 这样"续跑前成功的前缀 + 续跑后的后续步骤"能拼成一条完整链路
  const runs: SseEvent[][] = []
  let current: SseEvent[] = []
  for (const e of events) {
    if (e.type === 'incident_received' && !e.data?.resumed && current.length > 0) {
      runs.push(current)
      current = []
    }
    current.push(e)
  }
  if (current.length > 0) runs.push(current)

  const out: Step[] = []
  runs.forEach((run, index) => {
    const isLast = index === runs.length - 1
    if (!isLast) {
      // 历史失败尝试：不展示
      return
    }
    // 末次尝试：详细渲染。最终成功（以 report_finalized 结尾）时，
    // 中途已通过续跑恢复的 error 节点不展示；最终失败时保留红色错误节点
    const lastEvent = run[run.length - 1]
    const runSucceeded = lastEvent?.type === 'report_finalized'
    let openTool: ToolStep | null = null
    for (const e of run) {
      switch (e.type) {
        case 'incident_received':
          if (!e.data?.resumed) {
            out.push({ kind: 'info', key: e.sequence ?? 0, label: '开始分析' })
          }
          break
        case 'agent_thought':
          openTool = null
          // 空思考不渲染；trim 去掉首尾换行，避免 pre-wrap 渲染出多余空行；
          // 相邻重复思考（去重命中时模型反复输出同一意图且不落工具事件）只渲染一条
          if ((e.data?.thought ?? '').trim() !== '') {
            const text = (e.data?.thought ?? '').trim()
            const last = out[out.length - 1]
            if (!(last?.kind === 'thought' && last.thought === text)) {
              out.push({ kind: 'thought', key: e.sequence ?? 0, thought: text })
            }
          }
          break
        case 'tool_call_start':
          openTool = {
            kind: 'tool',
            key: e.sequence ?? 0,
            tool: e.data?.tool ?? '',
            args: e.data?.args ?? '{}',
            success: null,
            summary: '',
            detail: '',
            error: '',
            evidence: null,
          }
          out.push(openTool)
          break
        case 'tool_call_result':
          if (openTool && openTool.tool === e.data?.tool) {
            openTool.success = !!e.data?.success
            openTool.summary = e.data?.summary ?? ''
            openTool.detail = e.data?.detail ?? ''
            openTool.error = e.data?.error ?? ''
          } else {
            // 没匹配到打开的步骤（跨重连丢 start 的兜底），独立成步
            out.push({
              kind: 'tool',
              key: e.sequence ?? 0,
              tool: e.data?.tool ?? '',
              args: '',
              success: !!e.data?.success,
              summary: e.data?.summary ?? '',
              detail: e.data?.detail ?? '',
              error: e.data?.error ?? '',
              evidence: null,
            })
          }
          break
        case 'evidence_collected':
          if (openTool && openTool.evidence === null) {
            openTool.evidence = {
              source: e.data?.source ?? '',
              content: e.data?.content ?? '',
              timestamp: e.data?.timestamp ?? '',
            }
          } else if ((e.data?.content ?? '').trim() !== '') {
            out.push({ kind: 'thought', key: e.sequence ?? 0, thought: `[${e.data?.source}] ${(e.data?.content ?? '').trim()}` })
          }
          break
        case 'error':
          openTool = null
          // 最终成功的链路里，中途已通过续跑恢复的 error 节点不展示
          if (!runSucceeded) {
            out.push({ kind: 'error', key: e.sequence ?? 0, message: e.data?.message ?? '未知错误' })
          }
          break
        case 'report_finalized':
          out.push({ kind: 'final', key: e.sequence ?? 0 })
          break
        default:
          break
      }
    }
  })
  // 兜底：聚合后仍无文本内容的思考步骤不渲染
  const rendered = out.filter(s => !(s.kind === 'thought' && s.thought.trim() === ''))
  // "报告已生成"前一步的思考实为报告正文（report_finalized 前的输出），不二次展示
  const lastIdx = rendered.length - 1
  if (rendered[lastIdx]?.kind === 'final' && rendered[lastIdx - 1]?.kind === 'thought') {
    rendered.splice(lastIdx - 1, 1)
  }
  return rendered
})

const truncate = (text: string, max = 60) => {
  if (!text) return ''
  return text.length > max ? text.slice(0, max) + '…' : text
}

/** 流式思考框内容：只显示最新一个非空行，随流式替换 */
const latestStreamingLine = computed(() => {
  const lines = (props.streamingThought ?? '').split('\n').map(l => l.trim()).filter(Boolean)
  return lines[lines.length - 1] ?? ''
})

const prettyArgs = (args: string) => {
  try {
    return JSON.stringify(JSON.parse(args), null, 2)
  } catch {
    return args
  }
}

/**
 * 折叠头标题：有语义化 detail 的新事件用后端人话 summary（如"查阅 1 文件"）；
 * 执行中/失败/历史事件回退为"调用工具 {tool}"，保证失败时工具名可见
 */
const toolHeader = (step: ToolStep) => {
  if (step.detail && step.summary) return step.summary
  return `调用工具 ${step.tool}`
}

/**
 * 步骤图标（颜色走 token）。
 * 思考/工具两个图标不用 EP 现成图标（Opportunity 形似热气球、Search 镜面过大），
 * 改为模板内联 SVG 直绘（灯泡 / 小镜面放大镜），见模板中 step-icon 对应分支。
 */
const stepIcon = (step: Step) => {
  if (step.kind === 'tool' && step.success === null) return Loading
  // 思考/已完成工具的图标由模板内联 SVG 直绘，不会走到这里
  const map: Partial<Record<Step['kind'], Component>> = {
    info: Promotion,
    error: CircleCloseFilled,
    final: DocumentChecked,
  }
  return map[step.kind]
}

/** 工具步骤状态：执行中 / 成功 / 失败 */
const toolStatus = (step: ToolStep) => {
  if (step.success === null) return { text: '执行中', cls: 'is-running' }
  return step.success ? { text: '成功', cls: 'is-success' } : { text: '失败', cls: 'is-failed' }
}

/** 展开的折叠项 key（默认全折叠，不自动展开；仅非 live 时整体收起，live 时保留用户手动展开） */
const expanded = ref<number[]>([])

watch(
  () => props.live,
  () => {
    if (!props.live) {
      expanded.value = []
    }
  },
  { immediate: true }
)

/** 单个折叠项的受控展开状态与手动切换 */
const isExpanded = (key: number) => expanded.value.includes(key)
const toggleExpanded = (key: number, open: boolean) => {
  expanded.value = open
    ? [...expanded.value, key]
    : expanded.value.filter(k => k !== key)
}
</script>

<template>
  <div class="steps">
    <div v-for="step in steps" :key="step.key" class="step">
      <el-icon class="step-icon" :class="`step-icon--${step.kind}`" :size="16">
        <!-- 思考：灯泡（EP 无贴合图标，模板内联 SVG 直绘，尺寸由 el-icon 统一到 1em） -->
        <svg
          v-if="step.kind === 'thought'"
          viewBox="0 0 24 24" fill="none" stroke="currentColor"
          stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"
        >
          <path d="M12 3a6 6 0 0 0-3.6 10.8c.7.5 1.1 1.3 1.3 2.2h4.6c.2-.9.6-1.7 1.3-2.2A6 6 0 0 0 12 3z" />
          <path d="M9 18h6" />
          <path d="M10 21h4" />
        </svg>
        <!-- 调用工具：放大镜（小镜面 + 长柄，强调"查询取证"） -->
        <svg
          v-else-if="step.kind === 'tool' && step.success !== null"
          viewBox="0 0 24 24" fill="none" stroke="currentColor"
          stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"
        >
          <circle cx="10.5" cy="10.5" r="5.5" />
          <path d="M20.5 20.5l-5.6-5.6" />
        </svg>
        <component
          v-else
          :is="stepIcon(step)"
          :class="{ 'is-spinning': step.kind === 'tool' && step.success === null }"
        />
      </el-icon>

      <!-- 开始分析 / 续跑分析：仅图标 + 名称 -->
      <span v-if="step.kind === 'info'" class="step-title">{{ step.label }}</span>

      <div v-else class="step-content">
        <!-- 工具步骤：合并 start/result/evidence，默认收起不自动展开，可手动展开 -->
        <template v-if="step.kind === 'tool'">
          <el-collapse
            class="tool-collapse"
            :model-value="isExpanded(step.key) ? [step.key] : []"
            @update:model-value="(names: any) => toggleExpanded(step.key, Array.isArray(names) && names.length > 0)"
          >
            <el-collapse-item :name="step.key">
              <template #title>
                <span class="step-title">{{ toolHeader(step) }}</span>
                <!-- 成功状态不言自明（折叠头已是人话），仅执行中/失败需要提示 -->
                <span
                  v-if="step.success === null || step.success === false"
                  class="step-status"
                  :class="toolStatus(step).cls"
                >&nbsp;· {{ toolStatus(step).text }}</span>
              </template>
              <!-- 新格式：一句人话描述；历史事件无 detail 时走"参数/结果"旧渲染兜底 -->
              <p v-if="step.detail" class="detail-text">
                {{ step.detail }}
              </p>
              <template v-else>
                <p class="detail-text">
                  <strong>参数：</strong>
                  <pre class="args-pre">{{ prettyArgs(step.args) }}</pre>
                </p>
                <p v-if="step.summary" class="detail-text">
                  <strong>结果：</strong>{{ step.summary }}
                </p>
              </template>
              <p v-if="step.error" class="detail-text error-text">
                <strong>错误：</strong>{{ step.error }}
              </p>
              <p v-if="step.evidence" class="detail-text">
                <el-tag size="small" type="success">{{ step.evidence.source }}</el-tag>
                {{ step.evidence.content }}
              </p>
            </el-collapse-item>
          </el-collapse>
        </template>

        <!-- 思考：摘要 + 可折叠全文 -->
        <el-collapse
          v-else-if="step.kind === 'thought'"
          class="thought-collapse"
          :model-value="isExpanded(step.key) ? [step.key] : []"
          @update:model-value="(names: any) => toggleExpanded(step.key, Array.isArray(names) && names.length > 0)"
        >
          <el-collapse-item :name="step.key">
            <template #title>
              <span class="step-title">思考</span>
              <!-- 已完成态不显示摘要字，仅悬浮/展开看全文；RUNNING 实时显示滚动摘要 -->
              <span v-if="live" class="step-summary">&nbsp;· {{ truncate(step.thought) }}</span>
            </template>
            <p class="thought-text">{{ step.thought }}</p>
          </el-collapse-item>
        </el-collapse>

        <!-- 错误：红色标注失败原因 -->
        <p v-else-if="step.kind === 'error'" class="detail-text error-text">
          {{ step.message }}
        </p>

        <!-- 报告完成 -->
        <p v-else-if="step.kind === 'final'" class="detail-text">
          报告已生成，见下方报告内容
        </p>
      </div>
    </div>

    <!-- 流式中的思考：标题右侧固定单行框，只显示最新一行，落库后由上方正常思考步骤接替 -->
    <div v-if="live && latestStreamingLine" class="step">
      <el-icon class="step-icon step-icon--thought" :size="16">
        <svg
          viewBox="0 0 24 24" fill="none" stroke="currentColor"
          stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"
        >
          <path d="M12 3a6 6 0 0 0-3.6 10.8c.7.5 1.1 1.3 1.3 2.2h4.6c.2-.9.6-1.7 1.3-2.2A6 6 0 0 0 12 3z" />
          <path d="M9 18h6" />
          <path d="M10 21h4" />
        </svg>
      </el-icon>
      <div class="step-content streaming-thought">
        <span class="step-title">思考</span>
        <span class="streaming-frame">{{ latestStreamingLine }}<span class="streaming-cursor">▍</span></span>
      </div>
    </div>

    <!-- 工具执行/等待期间的加载行：live 且无流式思考时显示（与思考流式框互补、不同时出现），
         让"长时间无事件"的间隙也有进行中反馈 -->
    <div v-else-if="live" class="step">
      <el-icon class="step-icon step-icon--info" :size="16">
        <Loading class="is-spinning" />
      </el-icon>
      <span class="step-title loading-text">分析执行中…</span>
    </div>
  </div>
</template>

<style scoped>
.steps {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-top: 2px;
}

.step {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  min-width: 0;
}

.step-icon {
  flex-shrink: 0;
  /* 与 14px 文字行（行高约 24px）垂直居中：16px 图标 + 4px ≈ 视觉对齐 */
  margin-top: 4px;
}

.step-icon--info {
  color: var(--color-primary);
}

/* 思考/工具步骤用中性灰绿：语义色只留给 开始/执行中(苔绿)、错误(红)、完成(绿) */
.step-icon--thought {
  color: var(--color-text-secondary);
}

.step-icon--tool {
  color: var(--color-text-secondary);
}

.step-icon--error {
  color: var(--color-danger);
}

.step-icon--final {
  color: var(--color-success);
}

.is-spinning {
  animation: spin 1.2s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .is-spinning {
    animation: none;
  }
}

.step-title {
  color: var(--color-text-primary);
  font-size: 14px;
  flex-shrink: 0;
  white-space: nowrap;
}

.step-summary {
  color: var(--color-text-regular);
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}

.step-status {
  font-size: 14px;
  flex-shrink: 0;
  white-space: nowrap;
}

.step-status.is-running {
  color: var(--color-primary);
}

.step-status.is-success {
  color: var(--color-success);
}

.step-status.is-failed {
  color: var(--color-danger);
}

.step-content {
  flex: 1;
  min-width: 0;
}

/* 折叠头压成单行高度；标题单行省略，防止长摘要换行撑爆行高导致文字重叠。
   .steps 前缀抬高特异性 + min-height 抵消 EP 的 min-height:48px 与 AnalysisPane 的 36px 覆盖 */
.steps .tool-collapse :deep(.el-collapse-item__header),
.steps .thought-collapse :deep(.el-collapse-item__header) {
  --el-collapse-header-height: 24px;
  height: 24px;
  min-height: 24px;
  padding: 0;
  font-size: 14px;
  color: var(--color-text-regular);
  border-bottom: none;
  white-space: nowrap;
  overflow: hidden;
}

/* EP 折叠内容自带 25px 底内边距，去掉后步骤间距统一由 .steps 的 gap 控制 */
.tool-collapse :deep(.el-collapse-item__content),
.thought-collapse :deep(.el-collapse-item__content) {
  padding-bottom: 0;
}

.tool-collapse,
.thought-collapse {
  border: none;
}

.detail-text {
  margin: 2px 0 0 0;
}

.error-text {
  color: var(--color-danger);
}

.thought-text {
  white-space: pre-wrap;
  color: var(--color-text-regular);
  margin: 0;
}

.args-pre {
  margin: 4px 0 0 0;
  padding: 8px;
  background: var(--color-bg-page);
  border-radius: 4px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}

/* 流式中的思考：标题 + 右侧固定单行框（高度固定，内容替换式更新） */
.streaming-thought {
  display: flex;
  align-items: center;
  gap: 8px;
}

.streaming-frame {
  flex: 1;
  min-width: 0;
  height: 24px;
  line-height: 24px;
  padding: 0 8px;
  background: var(--el-bg-color);
  border-radius: 4px;
  font-size: 12px;
  color: var(--color-text-regular);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.streaming-cursor {
  color: var(--color-primary);
  animation: cursor-blink 1s infinite;
}

.loading-text {
  color: var(--color-text-regular);
}

@keyframes cursor-blink {
  50% {
    opacity: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .streaming-cursor {
    animation: none;
  }
}
</style>
