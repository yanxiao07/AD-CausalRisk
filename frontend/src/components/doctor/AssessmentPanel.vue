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
        {{ restoredIntent ? '恢复上次提交' : '发起风险预测' }}
      </el-button>
      <p v-if="restoredIntent" class="start__notice">
        检测到该档案有一次提交未确认结果：本次会复用原请求，后端不会重复建任务。
        <el-button link type="primary" @click="discardIntentAndReset">改为新评估</el-button>
      </p>
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

    <!-- 3. 失败：区分「恢复原任务」与「新建评估」 -->
    <div v-else-if="phase === 'failed'" class="failed">
      <el-alert
        type="error"
        :closable="false"
        show-icon
        :title="errorTitle"
        :description="errorText"
      />
      <div class="failed__actions">
        <el-button v-if="canReplayIntent" class="failed__retry" @click="retrySameIntent">
          重试原请求
        </el-button>
        <el-button type="primary" class="failed__new" @click="startNewIntent">
          新建评估
        </el-button>
      </div>
      <p class="failed__hint">{{ retryHint }}</p>
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
import { computed, onBeforeUnmount, ref, watch } from 'vue'
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
  newIdempotencyKey,
  reviewAssessment
} from '@/api/assessments.js'
import {
  clearAssessmentIntent,
  readAssessmentIntent,
  saveAssessmentIntent
} from '@/utils/assessmentIntent.js'

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
/** 本次打开抽屉时，是否从 sessionStorage 恢复了一次未闭环的提交。 */
const restoredIntent = ref(false)

let pollTimer = null
let tickTimer = null
let startedAt = 0

/**
 * 幂等键：一次"业务意图"对应一个键，不是一次 HTTP 请求。
 *
 *  - 打开抽屉时优先恢复该档案上次未闭环的键（关抽屉/刷新都不会丢）；
 *  - 发请求**之前**就落盘：这样即使响应丢失，再提交也是同一个键 → 后端重放而不是新建；
 *  - 参数（预测窗口）变了 = 换意图 → 换新键，避免"同键不同请求"被后端拒绝；
 *  - 出结果后清空，下一次才是全新评估。
 */
let idempotencyKey = newIdempotencyKey()
/** 当前键绑定的预测窗口；null 表示这个键还没提交过。 */
let keyWindow = null

function adoptIntent(subjectId) {
  const stored = readAssessmentIntent(subjectId)
  if (stored?.key) {
    idempotencyKey = stored.key
    keyWindow = stored.windowMonths
    restoredIntent.value = true
    if (Number.isFinite(stored.windowMonths)) windowMonths.value = stored.windowMonths
    return
  }
  idempotencyKey = newIdempotencyKey()
  keyWindow = null
  restoredIntent.value = false
}

adoptIntent(props.subjectId)

watch(
  () => props.subjectId,
  (next) => {
    // 换患者 = 换意图：各自恢复各自的键，互不串
    adoptIntent(next)
  }
)

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

/** 把"这一次意图"落盘；必须在发请求之前调用。 */
function persistIntent() {
  saveAssessmentIntent(props.subjectId, {
    key: idempotencyKey,
    windowMonths: keyWindow
  })
  restoredIntent.value = true
}

/** 关闭当前意图：清掉持久化的键，下一次点击就是全新评估。 */
function closeIntent() {
  clearAssessmentIntent(props.subjectId)
  idempotencyKey = ''
  keyWindow = null
  restoredIntent.value = false
}

function rotateIntent() {
  idempotencyKey = newIdempotencyKey()
  keyWindow = null
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
    if (!idempotencyKey) idempotencyKey = newIdempotencyKey()
    // 参数变了就是新意图：同一个键配不同 body 会被后端判成"同键不同请求"。
    if (keyWindow !== null && keyWindow !== windowMonths.value) {
      rotateIntent()
      ElMessage.info('预测窗口已修改，本次按新参数创建评估')
    }
    keyWindow = windowMonths.value
    // 先落盘再请求：万一响应丢了，重开抽屉还能拿回同一个键
    persistIntent()

    const created = await createAssessmentTask({
      subjectId: Number(props.subjectId),
      predictionWindowMonths: windowMonths.value,
      idempotencyKey
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
      // 已出结果：这次意图闭环，清掉持久化键，下一次点击是全新评估
      closeIntent()
      return
    } catch (problem) {
      fail('任务完成但结果读取失败', problem?.message || '请稍后重试。')
      return
    }
  }
  if (latest.status === 'FAILED' || latest.status === 'CANCELLED') {
    // 明确失败：重放只会拿回同一个失败任务，直接关掉这次意图，重试按"新建评估"走
    closeIntent()
    fail('评估未产生结果', latest.message || latest.errorCode || '请查看后端日志。')
    return
  }
  if (latest.status === 'BLOCKED') {
    fail('评估被阻断', latest.message || '数据不足以给出风险结果。')
    return
  }
  fail('评估未产生结果', latest.message || latest.errorCode || '请查看后端日志。')
}

/** 上次提交的响应是否丢失（没有拿到任务对象）——这种情况重放才有价值。 */
const canReplayIntent = computed(() => {
  const status = task.value?.status
  return !status || !['FAILED', 'CANCELLED'].includes(status)
})

const retryHint = computed(() =>
  canReplayIntent.value
    ? '「重试原请求」复用同一个幂等键：若上次其实已在后端建好任务，会直接拿回那个任务，不会重复创建。'
    : '该任务已明确失败，重放只会得到同样的失败结果。「新建评估」会换用新键，并按当前预测窗口重新提交。'
)

/** 恢复原任务：同一个键、同一组参数。 */
function retrySameIntent() {
  reset()
  start()
}

/** 新建评估：换键 + 保留当前窗口，属于一次全新意图。 */
function startNewIntent() {
  closeIntent()
  reset()
  start()
}

/** 抽屉里主动放弃上次恢复的意图，改为全新评估。 */
function discardIntentAndReset() {
  closeIntent()
  reset()
  ElMessage.success('已切换为新建评估')
}

function fail(title, text) {
  stopTimers()
  errorTitle.value = title
  errorText.value = text
  phase.value = 'failed'
}

async function review(decision) {
  if (!assessment.value) return
  // 记录这次提交究竟属于哪条评估：等待期间用户可能换了患者/换了评估，
  // 响应回来时必须核对，否则会把 A 的"已确认"写到 B 的界面上。
  const targetAssessmentId = assessment.value.assessmentId
  const targetSubjectId = Number(props.subjectId)

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

  // 弹窗期间也可能已经切换了患者，提交前再核对一次
  if (assessment.value?.assessmentId !== targetAssessmentId
    || Number(props.subjectId) !== targetSubjectId) {
    return
  }

  reviewing.value = decision
  try {
    await reviewAssessment(targetAssessmentId, decision, comment)

    // 响应回来时再核对：不是同一条评估就整段丢弃，绝不跨评估写状态
    if (Number(props.subjectId) !== targetSubjectId
      || assessment.value?.assessmentId !== targetAssessmentId) {
      ElMessage.info('已记录复核；当前显示的是另一条评估，未刷新其状态。')
      return
    }

    assessment.value = { ...assessment.value, reviewStatus: decision }
    ElMessage.success('复核已记录')
    emit('reviewed')
  } catch {
    // request.js 已提示
  } finally {
    if (Number(props.subjectId) === targetSubjectId) reviewing.value = ''
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

/* 恢复上次未闭环提交时的提示条 */
.start__notice {
  margin: 0;
  padding: 8px 12px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--brand-700);
  background: var(--brand-50);
  border: 1px solid rgba(10, 115, 173, 0.18);
  border-radius: var(--r-sm);
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
.failed__actions {
  display: flex;
  gap: 10px;
}

.failed__retry {
  align-self: flex-start;
}

.failed__hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--soft);
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
