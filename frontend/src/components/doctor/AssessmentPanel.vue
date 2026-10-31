<template>
  <section class="assessment">
    <header class="assessment__head">
      <h3 class="assessment__title">风险评估</h3>
      <StatusTag v-if="statusTag" :type="statusTag" />
    </header>

    <!-- 1. 未发起 -->
    <div v-if="phase === 'idle'" class="start">
      <div class="start__field">
        <span class="start__label">预测窗口</span>
        <el-input-number
          v-model="windowMonths"
          :min="WINDOW_MIN"
          :max="WINDOW_MAX"
          :step="6"
          controls-position="right"
        />
        <span class="start__unit">个月（6–60）</span>
      </div>
      <el-button type="primary" size="large" :loading="starting" @click="start">
        发起风险预测
      </el-button>
      <p class="start__hint">
        调用 Python 因果模型，通常需要数秒到数十秒；提交后可关闭抽屉，任务仍在后端运行。
      </p>
    </div>

    <!-- 2. 进行中 -->
    <div v-else-if="phase === 'running'" class="running" aria-live="polite">
      <div class="running__head">
        <el-icon class="is-loading running__spin"><Loading /></el-icon>
        <span>正在计算（{{ elapsedText }}）</span>
        <StatusTag v-if="task" :type="task.status" />
      </div>
      <ol class="stages">
        <li v-for="stage in STAGES" :key="stage.key" class="stage">
          <span class="stage__dot" />
          <span class="stage__name">{{ stage.label }}</span>
          <span class="stage__status">
            {{ stageStatus(stage.key) }}
          </span>
        </li>
      </ol>
      <p class="running__hint">每 2 秒查询一次任务状态，直到完成或失败。</p>
    </div>

    <!-- 3. 失败 -->
    <div v-else-if="phase === 'failed'" class="failed">
      <el-alert
        type="error"
        :closable="false"
        show-icon
        :title="errorTitle"
        :description="errorText"
      />
      <el-button class="failed__retry" @click="reset">重新发起</el-button>
    </div>

    <!-- 4. 结果 -->
    <div v-else-if="phase === 'done' && assessment" class="result">
      <div class="score">
        <div class="score__main">
          <span class="score__label">风险概率</span>
          <div class="score__value" :class="{ 'is-empty': riskScore === null }">
            {{ riskScore === null ? '不可用' : (riskScore * 100).toFixed(1) + '%' }}
          </div>
          <span class="score__meta">
            预测窗口 {{ assessment.agentResult?.predictionWindowMonths ?? windowMonths }} 个月 ·
            {{ assessment.agentResult?.dataVersion || '—' }}
          </span>
        </div>
        <div class="score__side">
          <StatusTag :type="riskLevel || 'UNAVAILABLE'" />
          <span class="score__review">
            <span class="score__review-label">复核</span>
            <StatusTag :type="assessment.reviewStatus" :label="reviewLabel" />
          </span>
          <span class="score__quality">质控：{{ assessment.agentResult?.qualityStatus || '—' }}</span>
        </div>
      </div>

      <el-alert
        v-if="riskLevel === 'UNAVAILABLE'"
        class="blocked"
        type="warning"
        :closable="false"
        show-icon
        title="本次评估被阻断，未给出数值"
        description="窗格内数据不足或质控未通过时，系统返回 UNAVAILABLE 而不是 0；不要用插值或 0 代替。"
      />

      <template v-if="graph">
        <h4 class="assessment__subtitle">个体因果网络</h4>
        <CausalGraph :graph="graph" :height="380" />
      </template>

      <template v-if="explanations.length">
        <h4 class="assessment__subtitle">关键解释</h4>
        <ul class="explanations">
          <li v-for="(item, index) in explanations" :key="index" class="explanation">
            <span class="explanation__label">{{ item.label || item.feature }}</span>
            <span class="explanation__text">{{ item.interpretation || item.message }}</span>
          </li>
        </ul>
      </template>

      <el-alert
        v-if="warnings.length"
        class="warnings"
        type="info"
        :closable="false"
        show-icon
        title="模型提示"
        :description="warnings.join('；')"
      />

      <div class="review">
        <template v-if="assessment.reviewStatus === 'PENDING'">
          <el-button type="primary" :loading="reviewing === 'ACKNOWLEDGED'" @click="review('ACKNOWLEDGED')">
            确认复核
          </el-button>
          <el-button :loading="reviewing === 'NEEDS_DATA'" @click="review('NEEDS_DATA')">
            要求补充数据
          </el-button>
        </template>
        <span v-else class="review__done">
          该评估已复核（{{ reviewLabel }}），如需改判需在后台另行处理。
        </span>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import StatusTag from '@/components/common/StatusTag.vue'
import CausalGraph from '@/components/charts/CausalGraph.vue'
import {
  WINDOW_DEFAULT,
  WINDOW_MAX,
  WINDOW_MIN,
  createAssessmentTask,
  getAssessment,
  getAssessmentTask,
  reviewAssessment
} from '@/api/assessments.js'

const props = defineProps({
  subjectId: { type: [Number, String], required: true }
})
const emit = defineEmits(['reviewed'])

const POLL_MS = 2000
const MAX_WAIT_MS = 3 * 60 * 1000

const STAGES = [
  { key: 'DATA_FUSION', label: '数据融合' },
  { key: 'CAUSAL_GRAPH', label: '因果图谱' },
  { key: 'WARNING_MODEL', label: '预警模型' },
  { key: 'EXPLANATION', label: '个体解释' }
]

const windowMonths = ref(WINDOW_DEFAULT)
const phase = ref('idle') // idle | running | done | failed
const starting = ref(false)
const task = ref(null)
const assessment = ref(null)
const errorTitle = ref('评估失败')
const errorText = ref('')
const elapsed = ref(0)
const reviewing = ref('')

let pollTimer = null
let tickTimer = null
let startedAt = 0

const terminal = (status) => ['COMPLETED', 'BLOCKED', 'FAILED', 'CANCELLED'].includes(status)

const riskScore = computed(() => {
  const value = assessment.value?.agentResult?.riskScore
  return typeof value === 'number' ? value : null
})
const riskLevel = computed(() => assessment.value?.agentResult?.riskLevel || '')
const graph = computed(() => assessment.value?.agentResult?.graph || null)
const warnings = computed(() => assessment.value?.agentResult?.warnings || [])
const explanations = computed(() =>
  (assessment.value?.agentResult?.explanations || []).filter((item) => item.interpretation || item.message)
)

const statusTag = computed(() => {
  if (phase.value === 'done') return assessment.value?.agentResult?.status || 'COMPLETED'
  if (phase.value === 'failed') return 'FAILED'
  return task.value?.status || ''
})

const reviewLabel = computed(
  () =>
    ({
      // 复核语义：PENDING 是"待复核"，不能复用任务队列的"排队中"
      PENDING: '待复核',
      ACKNOWLEDGED: '已确认',
      NEEDS_DATA: '要求补充数据',
      REJECTED: '已驳回'
    })[assessment.value?.reviewStatus] || '—'
)

const elapsedText = computed(() => {
  const seconds = elapsed.value
  return seconds < 60 ? `${seconds} 秒` : `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
})

function stageStatus(key) {
  const stage = (task.value?.stages || []).find((item) => item.stage === key)
  if (stage) return `${stage.status === 'BLOCKED' ? '阻断' : '完成'} · ${Math.round(stage.durationMs)} ms`
  return task.value?.status === 'RUNNING' || task.value?.status === 'PENDING' ? '等待中' : '—'
}

function stopTimers() {
  if (pollTimer) window.clearTimeout(pollTimer)
  if (tickTimer) window.clearInterval(tickTimer)
  pollTimer = null
  tickTimer = null
}

function reset() {
  stopTimers()
  phase.value = 'idle'
  task.value = null
  assessment.value = null
  errorText.value = ''
  elapsed.value = 0
}

async function start() {
  if (phase.value === 'running') return
  reset()
  starting.value = true
  phase.value = 'running'
  startedAt = Date.now()
  tickTimer = window.setInterval(() => {
    elapsed.value = Math.round((Date.now() - startedAt) / 1000)
  }, 1000)

  try {
    const created = await createAssessmentTask({
      subjectId: Number(props.subjectId),
      predictionWindowMonths: windowMonths.value
    })
    task.value = created
    if (terminal(created.status)) {
      await finish(created)
      return
    }
    schedulePoll(created.taskId)
  } catch (problem) {
    fail('无法发起评估', problem?.message || '请确认后端与 Python 子服务已启动。')
  } finally {
    starting.value = false
  }
}

function schedulePoll(taskId) {
  pollTimer = window.setTimeout(async () => {
    try {
      const latest = await getAssessmentTask(taskId)
      task.value = latest
      if (terminal(latest.status)) {
        await finish(latest)
        return
      }
      if (Date.now() - startedAt > MAX_WAIT_MS) {
        fail('评估超时', '任务仍在运行，可稍后回到档案查看；本次等待已超过 3 分钟。')
        return
      }
      schedulePoll(taskId)
    } catch (problem) {
      fail('无法获取任务状态', problem?.message || '请稍后重试。')
    }
  }, POLL_MS)
}

async function finish(latest) {
  stopTimers()
  if (latest.assessmentId) {
    try {
      assessment.value = await getAssessment(latest.assessmentId)
      phase.value = 'done'
      return
    } catch (problem) {
      fail('任务完成但结果读取失败', problem?.message || '请稍后重试。')
      return
    }
  }
  if (latest.status === 'BLOCKED') {
    fail('评估被阻断', latest.message || '数据不足以给出风险结果。')
    return
  }
  fail('评估未产生结果', latest.message || latest.errorCode || '请查看后端日志。')
}

function fail(title, text) {
  stopTimers()
  errorTitle.value = title
  errorText.value = text
  phase.value = 'failed'
}

async function review(decision) {
  if (!assessment.value) return
  const label = decision === 'ACKNOWLEDGED' ? '确认复核' : '要求补充数据'
  let comment = ''
  try {
    const result = await ElMessageBox.prompt(`请填写复核意见（${label}）`, '人工复核', {
      confirmButtonText: '提交',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '例如：已核对量表与影像，同意该风险分层。',
      inputValidator: (value) => {
        const text = (value || '').trim()
        if (!text) return '复核意见不能为空'
        if (text.length > 2000) return '不能超过 2000 字'
        return true
      }
    })
    comment = result.value.trim()
  } catch {
    return
  }

  reviewing.value = decision
  try {
    await reviewAssessment(assessment.value.assessmentId, decision, comment)
    assessment.value = { ...assessment.value, reviewStatus: decision }
    ElMessage.success('复核已记录')
    emit('reviewed')
  } catch {
    // request.js 已提示
  } finally {
    reviewing.value = ''
  }
}

onBeforeUnmount(stopTimers)
</script>

<style scoped>
.assessment {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: #fff;
}

.assessment__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.assessment__title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
}

.assessment__subtitle {
  margin: 6px 0 0;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-700);
}

/* 发起 */
.start {
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: flex-start;
}

.start__field {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: var(--ink-700);
}

.start__label {
  font-weight: 600;
}

.start__unit {
  color: var(--muted);
  font-size: 13px;
}

.start__hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--soft);
}

/* 进行中 */
.running__head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}

.running__spin {
  color: var(--brand-600);
  font-size: 16px;
}

.stages {
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.stage {
  display: grid;
  grid-template-columns: 14px 1fr auto;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--ink-700);
}

.stage__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--brand-600);
  box-shadow: 0 0 0 4px var(--brand-50);
}

.stage__status {
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

.running__hint {
  margin: 0;
  font-size: 12px;
  color: var(--soft);
}

/* 失败 */
.failed__retry {
  align-self: flex-start;
}

/* 结果 */
.score {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 18px;
  border-radius: var(--r-md);
  background: linear-gradient(120deg, #f2fbff 0%, #ffffff 100%);
  border: 1px solid var(--line);
}

.score__label {
  display: block;
  font-size: 12px;
  color: var(--muted);
  letter-spacing: 0.4px;
}

.score__value {
  font-family: var(--font-display);
  font-size: 44px;
  line-height: 1.1;
  font-weight: 700;
  color: var(--brand-700);
  font-variant-numeric: tabular-nums;
}

.score__value.is-empty {
  font-size: 26px;
  color: var(--muted);
}

.score__meta {
  font-size: 12px;
  color: var(--soft);
}

.score__side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
}

.score__quality {
  font-size: 12px;
  color: var(--muted);
}

.score__review {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.score__review-label {
  font-size: 12px;
  color: var(--muted);
}

.blocked,
.warnings {
  border-radius: var(--r-md);
}

.explanations {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.explanation {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border-radius: var(--r-sm);
  background: var(--bg-soft);
  border: 1px solid var(--line);
}

.explanation__label {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink);
}

.explanation__text {
  font-size: 13px;
  line-height: 1.6;
  color: var(--ink-700);
}

.review {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-top: 4px;
}

.review__done {
  font-size: 13px;
  color: var(--muted);
}
</style>
