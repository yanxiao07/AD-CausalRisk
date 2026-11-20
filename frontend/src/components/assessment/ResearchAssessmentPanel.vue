<template>
  <section class="research-assessment">
    <div class="ra-head">
      <div>
        <h3 class="ra-title">研究模型评估</h3>
        <p class="ra-subtitle">冻结快照 · 已训练的表格模型 · 24 个月</p>
      </div>
      <el-button text :loading="snapshotLoading" :disabled="submitting" @click="loadSnapshots">
        刷新快照
      </el-button>
    </div>

    <el-alert
      v-if="hostUnavailable"
      class="ra-block"
      type="warning"
      :closable="false"
      show-icon
      title="当前未配置研究宿主，请联系管理员配置研究宿主。"
    >
      <template #default>
        <p class="ra-alert-text">研究表格任务需要 Java 侧配置可用的研究宿主地址；配置前提交会返回 503。</p>
        <el-button size="small" :loading="snapshotLoading" @click="loadSnapshots">重新检查</el-button>
      </template>
    </el-alert>

    <template v-else>
      <el-skeleton v-if="snapshotLoading && !snapshots.length" :rows="2" animated />
      <el-alert
        v-else-if="snapshotError"
        class="ra-block"
        type="error"
        :closable="false"
        show-icon
        :title="snapshotError"
      />

      <template v-else>
        <el-form v-if="snapshots.length" label-width="96px" class="ra-form" @submit.prevent>
          <el-form-item label="冻结快照">
            <div class="ra-picker">
              <el-select
                v-model="selectedSnapshotId"
                class="ra-control"
                placeholder="选择已登记快照"
                :disabled="submitting"
              >
                <el-option
                  v-for="item in snapshots"
                  :key="item.snapshotId"
                  :label="snapshotLabel(item)"
                  :value="item.snapshotId"
                  :disabled="item.compatible === false && !item.taskId"
                />
              </el-select>
            </div>
          </el-form-item>
        </el-form>

        <el-alert
          v-if="!snapshots.length"
          class="ra-block"
          type="info"
          :closable="false"
          show-icon
          title="该受试者还没有当前账号获准评估的冻结快照。"
          :description="emptySnapshotMessage"
        />
        <el-alert
          v-if="selectedIncompatible"
          class="ra-block"
          type="warning"
          :closable="false"
          show-icon
          title="该快照的模型身份或软件锁摘要与新研究宿主不兼容"
          :description="selectedOption?.compatibilityMessage || '请在批量评估或快照列表中重新登记，旧快照会保留用于审计。'"
        />
        <el-alert
          v-if="selectedOption?.taskId"
          class="ra-block"
          type="info"
          :closable="false"
          show-icon
          :title="`该快照已有研究任务 #${selectedOption.taskId}`"
          description="同一份冻结资料对应一条任务，可读取原结果。"
        />
        <el-alert
          v-else-if="waitingForSync"
          class="ra-block"
          type="info"
          :closable="false"
          title="此快照尚未同步"
          description="同步完成后，刷新快照即可提交。"
        />
        <el-alert
          v-else-if="selectedOption?.dataScope === CONTROLLED_RESEARCH"
          class="ra-block"
          type="info"
          :closable="false"
          show-icon
          title="当前受控档案开放资料核验"
          description="模型预测与解释还需完成受控执行接入及授权。"
        />

        <div class="ra-actions">
          <el-button
            type="primary"
            :loading="submitting"
            :disabled="!effectiveSnapshotId"
            @click="run"
          >
            {{ actionLabel }}
          </el-button>
        </div>
      </template>
    </template>

    <el-alert
      v-if="taskError"
      class="ra-block"
      type="error"
      :closable="false"
      show-icon
      :title="taskError"
    />

    <div v-if="task" class="ra-result">
      <div class="ra-result__head">
        <StatusTag :type="task.status" />
        <span class="ra-meta">
          {{ researchScopeLabel(task.dataScope) }} · 任务 #{{ task.taskId }} · 快照 #{{ task.snapshotId }} · 受试者 #{{ task.subjectId }}
        </span>
        <router-link class="el-button is-link el-button--primary" :to="{ name: 'ResearchReportDetail', params: { taskId: task.taskId } }">
          {{ task.status === 'BLOCKED' ? '查看核验报告' : '查看评估报告' }}
        </router-link>
        <router-link v-if="task.status === 'BLOCKED' && task.dataScope === CONTROLLED_RESEARCH"
          class="el-button is-link el-button--primary" :to="completedSampleReports">
          查看已完成测试报告
        </router-link>
      </div>

      <el-alert
        v-if="task.status === 'BLOCKED'"
        class="ra-block"
        type="warning"
        :closable="false"
        show-icon
        title="资料核验未通过，本次未运行模型预测。"
      >
        <ul v-if="admissionIssues.length" class="ra-admission-list">
          <li v-for="(issue, index) in admissionIssues" :key="index">
            {{ issue.severity === 'BLOCK' ? '阻断' : '提示' }} · {{ researchFeatureLabel(issue.feature) }} · {{ researchIssueLabel(issue.code) }}
          </li>
        </ul>
        <p v-else class="ra-alert-text">在核验报告中查看这次核验的资料状态。</p>
      </el-alert>
      <el-alert
        v-else-if="task.status === 'FAILED'"
        class="ra-block"
        type="error"
        :closable="false"
        show-icon
        :title="`评估失败${task.errorCode ? `：${task.errorCode}` : ''}`"
        description="任务已结束但没有可用结果；请检查快照与宿主配置后重试。"
      />
      <el-alert
        v-else-if="task.status === 'OUTCOME_UNKNOWN'"
        class="ra-block"
        type="info"
        :closable="false"
        show-icon
        title="结果未知（OUTCOME_UNKNOWN）"
        description="请求可能已到达研究宿主，但未收到有效响应；用同一幂等键重试只会回读本地记录。"
      />

      <template v-else-if="task.status === 'COMPLETED' && prediction">
        <div class="ra-scores">
          <div class="ra-score">
            <span class="ra-score__label">AD 24 个月 CIF</span>
            <strong class="ra-score__value">{{ formatCif(prediction.adCif24m) }}</strong>
          </div>
          <div class="ra-score">
            <span class="ra-score__label">其他痴呆 24 个月 CIF</span>
            <strong class="ra-score__value">{{ formatCif(prediction.otherDementiaCif24m) }}</strong>
          </div>
        </div>

        <dl class="ra-facts">
          <div>
            <dt>模型</dt>
            <dd>{{ modelText }}</dd>
          </div>
          <div>
            <dt>数据范围</dt>
            <dd>{{ researchScopeLabel(task.dataScope) }}</dd>
          </div>
          <div>
            <dt>预测窗口</dt>
            <dd>{{ result?.predictionWindowMonths ?? 24 }} 个月</dd>
          </div>
          <div>
            <dt>产物 SHA-256</dt>
            <dd class="ra-mono" :title="artifactSha256">{{ shortHash(artifactSha256) }}</dd>
          </div>
        </dl>

        <el-alert
          class="ra-block"
          type="info"
          :closable="false"
          show-icon
          title="解释边界"
          :description="explanationBoundary"
        />

        <el-table
          v-if="explanationRows.length"
          :data="explanationRows"
          size="small"
          class="ra-explain"
        >
          <el-table-column prop="group" label="参考组" min-width="140" />
          <el-table-column label="AD 贡献" width="120" align="right">
            <template #default="{ row }">{{ formatSigned(row.ad) }}</template>
          </el-table-column>
          <el-table-column label="其他痴呆贡献" width="140" align="right">
            <template #default="{ row }">{{ formatSigned(row.other) }}</template>
          </el-table-column>
        </el-table>

        <el-collapse class="ra-raw">
          <el-collapse-item title="完整结果 JSON（审计用）" name="raw">
            <pre>{{ prettyResult }}</pre>
          </el-collapse-item>
        </el-collapse>
      </template>
      <ResearchAdmissionGuidance v-if="task.status === 'BLOCKED' && task.dataScope === CONTROLLED_RESEARCH"
        :issues="admissionIssues" />
    </div>
  </section>
</template>

<script setup>
import {
  CONTROLLED_RESEARCH, CONSTRUCTED_VALIDATION, completedSampleReports, modelContributions,
  researchFeatureLabel, researchIssueLabel, researchScopeLabel
} from '@/utils/researchResults.js'
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import ResearchAdmissionGuidance from '@/components/assessment/ResearchAdmissionGuidance.vue'
import {
  getResearchTableTask,
  getResearchIntakeState,
  listResearchSnapshots,
  submitResearchTableTask
} from '@/api/research.js'
import { newIdempotencyKey } from '@/api/assessments.js'

const HOST_UNAVAILABLE = '当前未配置研究宿主，请联系管理员配置研究宿主。'

const props = defineProps({
  subjectId: { type: [Number, String], default: null },
  dataScope: { type: String, required: true }
})

const emit = defineEmits(['submitted'])

const snapshots = ref([])
const snapshotLoading = ref(false)
const snapshotError = ref('')
const hostUnavailable = ref(false)
const intakeState = ref('')

const selectedSnapshotId = ref(null)

const submitting = ref(false)
const taskError = ref('')
const task = ref(null)

let intentKey = ''
let intentSnapshotId = null
let snapshotRequestSeq = 0
const loadedSubjectId = ref(null)

const selectedOption = computed(
  () => snapshots.value.find(item => Number(item.snapshotId) === Number(selectedSnapshotId.value)) || null
)

const activeTaskId = computed(() => selectedOption.value?.taskId ?? null)
const actionLabel = computed(() => {
  if (activeTaskId.value) return '读取已有任务'
  if (selectedOption.value?.dataScope === CONSTRUCTED_VALIDATION) return '运行模型预测'
  return '提交资料核验'
})

const selectedIncompatible = computed(
  () => !activeTaskId.value && selectedOption.value?.compatible === false
)
const waitingForSync = computed(() => !activeTaskId.value && selectedOption.value
  && selectedOption.value.hostSyncStatus !== 'SYNCED')

const effectiveSnapshotId = computed(() => {
  if (selectedIncompatible.value || waitingForSync.value) return 0
  return selectedOption.value?.snapshotId ?? 0
})
const emptySnapshotMessage = computed(() => {
  if (props.dataScope === CONTROLLED_RESEARCH) return '请核对该档案的来源资料登记和研究授权。'
  return ({
    WAITING_RESEARCH_GRANT: '医生已保存测试资料。请管理员为当前研究账号授予这份档案的研究访问权限。',
    READY: '资料已交接。请刷新快照或从评估任务中查阅已有结果。'
  })[intakeState.value] || '请让医生先录入并保存测试资料，或选择已有快照的受试者。'
})

const result = computed(() => task.value?.result || null)
const admissionIssues = computed(() => result.value?.admission?.issues || [])
const prediction = computed(() => result.value?.prediction || null)
const model = computed(() => result.value?.model || null)
const artifactSha256 = computed(() => model.value?.artifactSha256 || '')
const prettyResult = computed(() => JSON.stringify(task.value, null, 2))

const modelText = computed(() => {
  const value = model.value
  if (!value) return '已训练的七变量逻辑回归（24 个月）'
  const family = value.family === 'logistic' ? '逻辑回归' : value.family
  const specification = value.specification === 'seven' ? '七变量' : value.specification
  return `${specification} ${family}（24 个月）`
})

const explanationBoundary = computed(() => {
  const explanation = result.value?.explanation
  if (!explanation) return '本次结果没有附带解释数据。'
  const parts = ['同次参考组 SHAP 归因，用于解释模型输出，不是个体因果效应。']
  if (explanation.isCausalEffect === false) parts.push('isCausalEffect=false。')
  if (explanation.missingnessEffectsExplained === false) parts.push('缺失值效应未解释。')
  if (explanation.limeStatus) parts.push(`LIME：${explanation.limeStatus}。`)
  return parts.join(' ')
})

const explanationRows = computed(() => modelContributions(task.value))

function snapshotLabel(item) {
  const base = item.taskId
    ? `快照 #${item.snapshotId} · 已有任务 #${item.taskId}`
    : `快照 #${item.snapshotId}`
  const withModel = [researchScopeLabel(item.dataScope), base, item.modelKey].filter(Boolean).join(' · ')
  return item.compatible === false ? `${withModel} · 需重新登记` : withModel
}

function formatCif(value) {
  return Number.isFinite(value) ? `${(value * 100).toFixed(2)}%` : '—'
}

function formatSigned(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) return '—'
  const text = number.toFixed(4)
  return number > 0 ? `+${text}` : text
}

function shortHash(value) {
  if (!value) return '—'
  return value.length > 22 ? `${value.slice(0, 12)}…${value.slice(-8)}` : value
}

function handleSnapshotProblem(problem) {
  const status = problem?.httpStatus
  if (status === 503 || problem?.errorCode === 'RESEARCH_UNAVAILABLE') {
    hostUnavailable.value = true
    return
  }
  if (status === 403) {
    snapshotError.value = '当前账号没有研究评估权限（需要研究员或管理员角色）。'
    return
  }
  if (problem?.errorCode === 'NETWORK_ERROR') {
    snapshotError.value = '无法连接服务器，请确认后端已启动。'
    return
  }
  snapshotError.value = problem?.message || '快照列表加载失败，请稍后重试。'
}

async function loadSnapshots() {
  const subjectId = Number(props.subjectId)
  const requestId = ++snapshotRequestSeq
  snapshotError.value = ''
  intakeState.value = ''
  hostUnavailable.value = false
  if (!Number.isInteger(subjectId) || subjectId <= 0) {
    snapshots.value = []
    selectedSnapshotId.value = null
    loadedSubjectId.value = null
    return
  }
  snapshotLoading.value = true
  try {
    const data = await listResearchSnapshots(subjectId)
    if (requestId !== snapshotRequestSeq || Number(props.subjectId) !== subjectId) return
    snapshots.value = Array.isArray(data) ? data : []
    loadedSubjectId.value = subjectId
    const current = snapshots.value.find(item => Number(item.snapshotId) === Number(selectedSnapshotId.value))
    if (!current || (current.compatible === false && !current.taskId)) {
      const firstCompatible = snapshots.value.find(item => item.compatible !== false || item.taskId)
      selectedSnapshotId.value = firstCompatible ? Number(firstCompatible.snapshotId) : null
    }
    if (!snapshots.value.length && props.dataScope === CONSTRUCTED_VALIDATION) {
      const state = await getResearchIntakeState(subjectId).catch(() => null)
      if (requestId === snapshotRequestSeq && Number(props.subjectId) === subjectId) {
        intakeState.value = state?.status || ''
      }
    }
  } catch (problem) {
    if (requestId !== snapshotRequestSeq || Number(props.subjectId) !== subjectId) return
    snapshots.value = []
    selectedSnapshotId.value = null
    loadedSubjectId.value = null
    handleSnapshotProblem(problem)
  } finally {
    if (requestId === snapshotRequestSeq) snapshotLoading.value = false
  }
}

function describeTaskProblem(problem) {
  const status = problem?.httpStatus
  if (status === 503 || problem?.errorCode === 'RESEARCH_UNAVAILABLE') return problem?.message || HOST_UNAVAILABLE
  if (status === 409) return problem?.message || '快照状态已变化，请刷新后重试。'
  if (status === 403) return '当前账号无法使用此档案的快照，请检查研究账号的档案授权。'
  if (status === 404) return problem?.message || '研究任务不存在或后端尚未提供该接口。'
  if (problem?.errorCode === 'NETWORK_ERROR') return '无法连接服务器，请确认后端已启动。'
  return problem?.message || '提交失败，请稍后重试。'
}

async function run() {
  const subjectId = Number(props.subjectId)
  if (!Number.isInteger(subjectId) || subjectId <= 0) {
    ElMessage.warning('请先选择受试者')
    return
  }
  if (loadedSubjectId.value !== subjectId) {
    ElMessage.warning('该受试者的快照仍在加载，请稍后再提交')
    return
  }
  if (selectedIncompatible.value) {
    ElMessage.warning('该快照与新研究宿主不兼容，请重新登记后再提交')
    return
  }
  const snapshotId = effectiveSnapshotId.value
  if (!snapshotId) {
    ElMessage.warning('请选择此受试者的快照')
    return
  }
  submitting.value = true
  taskError.value = ''
  try {
    if (activeTaskId.value) {
      const loaded = await getResearchTableTask(activeTaskId.value)
      if (Number(props.subjectId) !== subjectId) return
      task.value = loaded
    } else {
      if (intentSnapshotId !== snapshotId || !intentKey) {
        intentKey = newIdempotencyKey()
        intentSnapshotId = snapshotId
      }
      const created = await submitResearchTableTask({ subjectId, snapshotId, idempotencyKey: intentKey })
      if (Number(props.subjectId) !== subjectId) return
      task.value = created
      intentKey = ''
      intentSnapshotId = null
      if (created?.taskId) emit('submitted', created)
      await loadSnapshots()
    }
  } catch (problem) {
    if (Number(props.subjectId) !== subjectId) return
    taskError.value = describeTaskProblem(problem)
    if (problem?.httpStatus === 409) {
      await loadSnapshots()
      if (Number(props.subjectId) === subjectId && selectedOption.value?.snapshotId === snapshotId
          && activeTaskId.value) {
        const existing = await getResearchTableTask(activeTaskId.value).catch(() => null)
        if (existing && Number(props.subjectId) === subjectId) {
          task.value = existing
          taskError.value = ''
        }
      }
    }
  } finally {
    submitting.value = false
  }
}

watch(
  () => props.subjectId,
  () => {
    task.value = null
    taskError.value = ''
    selectedSnapshotId.value = null
    loadedSubjectId.value = null
    loadSnapshots()
  },
  { immediate: true }
)

watch(selectedSnapshotId, () => {
  task.value = null
  taskError.value = ''
})
</script>

<style scoped>
.ra-admission-list { margin: 8px 0 4px; padding-left: 1.25rem; line-height: 1.6; }

.research-assessment {
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: var(--surface);
  padding: 18px 20px 20px;
}

.ra-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.ra-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--text);
}

.ra-subtitle {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--muted);
}

.ra-form {
  margin-bottom: 4px;
}

.ra-picker {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
}

.ra-control {
  width: 320px;
  max-width: 100%;
}

.ra-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}


.ra-block {
  margin-top: 12px;
}

.ra-alert-text {
  margin: 4px 0 10px;
  font-size: 12px;
  color: var(--muted);
}

.ra-result {
  margin-top: 16px;
  border-top: 1px dashed var(--line);
  padding-top: 14px;
}

.ra-result__head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.ra-meta {
  font-size: 12px;
  color: var(--muted);
}

.ra-scores {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
  margin: 10px 0 14px;
}

.ra-score {
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--surface-subtle, #f7fafc);
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ra-score__label {
  font-size: 12px;
  color: var(--muted);
}

.ra-score__value {
  font-size: 26px;
  line-height: 1.1;
  color: var(--text);
}

.ra-facts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 10px 18px;
  margin: 0 0 12px;
}

.ra-facts div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.ra-facts dt {
  font-size: 12px;
  color: var(--muted);
}

.ra-facts dd {
  margin: 0;
  font-size: 13px;
  color: var(--text);
  word-break: break-all;
}

.ra-mono {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
}

.ra-explain {
  margin-top: 10px;
}

.ra-raw {
  margin-top: 12px;
}

.ra-raw pre {
  margin: 0;
  max-height: 320px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.55;
  color: var(--text-secondary, #334);
}
</style>
