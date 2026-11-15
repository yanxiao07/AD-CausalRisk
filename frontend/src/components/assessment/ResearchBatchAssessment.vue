<template>
  <section class="batch-assessment">
    <div class="ba-head">
      <div>
        <h3 class="ba-title">批量研究评估</h3>
        <p class="ba-subtitle">冻结快照 · 已训练的七变量逻辑回归 · 24 个月 · 研究验证数据</p>
      </div>
    </div>

    <el-alert
      v-if="hostUnavailable"
      class="ba-block"
      type="warning"
      :closable="false"
      show-icon
      title="当前未配置研究宿主，请联系管理员配置研究宿主。"
    >
      <template #default>
        <p class="ba-alert-text">研究表格任务需要 Java 侧配置可用的研究宿主地址；配置前提交会返回 503。</p>
        <el-button size="small" :loading="loading" @click="loadSnapshots()">重新检查</el-button>
      </template>
    </el-alert>

    <template v-else>
      <el-skeleton v-if="loading && !snapshots.length" :rows="2" animated />
      <el-alert
        v-else-if="error"
        class="ba-block"
        type="error"
        :closable="false"
        show-icon
        :title="error"
      />
      <el-empty
        v-else-if="!snapshots.length"
        description="该受试者还没有当前账号获准评估的冻结快照。请先由受控登记流程生成快照，或换一个有快照的受试者。"
      />

      <template v-else>
        <div class="ba-filter">
          <span class="ba-muted">同步状态</span>
          <el-radio-group v-model="syncFilter" size="small">
            <el-radio-button value="ALL">全部</el-radio-button>
            <el-radio-button value="SYNCED">已同步</el-radio-button>
            <el-radio-button value="PENDING">待同步</el-radio-button>
            <el-radio-button value="FAILED">同步失败</el-radio-button>
          </el-radio-group>
        </div>

        <el-table
          v-if="filteredSnapshots.length"
          ref="tableRef"
          :data="filteredSnapshots"
          row-key="snapshotId"
          class="ba-table"
          @selection-change="onSelectionChange"
        >
          <el-table-column type="selection" width="48" />
          <el-table-column label="快照 ID" width="130">
            <template #default="{ row }">#{{ row.snapshotId }}</template>
          </el-table-column>
          <el-table-column label="已有任务" width="140">
            <template #default="{ row }">
              <span v-if="row.taskId" class="ba-link">#{{ row.taskId }}</span>
              <span v-else class="ba-muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="宿主同步" width="150">
            <template #default="{ row }">
              <el-tag :type="syncTag(row.hostSyncStatus)" size="small" effect="light">
                {{ syncLabel(row.hostSyncStatus) }}
              </el-tag>
              <el-button
                v-if="row.hostSyncStatus !== 'SYNCED'"
                link
                type="primary"
                size="small"
                class="ba-retry"
                :loading="retryingId === row.snapshotId"
                @click.stop="retrySync(row)"
              >
                重试
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="280">
            <template #default="{ row }">
              {{ row.taskId ? '已有研究任务，批量评估时直接回读结果。' : '尚未提交，批量评估时创建研究任务。' }}
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="当前同步状态下没有快照。" :image-size="72" />

        <div class="ba-actions">
          <span class="ba-count">
            已选 <strong>{{ selected.length }}</strong> / {{ filteredSnapshots.length }} 个快照
          </span>
          <el-button
            type="primary"
            :disabled="!selected.length || batchRunning"
            @click="openBatch"
          >
            批量评估
          </el-button>
        </div>
      </template>
    </template>

    <div v-if="validSubject" class="ba-history">
      <div class="ba-history__head">
        <div>
          <h4 class="ba-history__title">研究任务历史</h4>
          <p class="ba-history__hint">最近 50 条，来自后端持久化记录；刷新页面不会丢失。</p>
        </div>
      </div>
      <el-alert
        v-if="historyError"
        type="error"
        :closable="false"
        show-icon
        :title="historyError"
      />
      <el-table
        v-else
        :data="historyRows"
        row-key="taskId"
        class="ba-table"
        empty-text="该受试者还没有研究任务。"
      >
        <el-table-column label="任务" width="100">
          <template #default="{ row }">#{{ row.taskId }}</template>
        </el-table-column>
        <el-table-column label="快照" width="100">
          <template #default="{ row }">#{{ row.snapshotId }}</template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <StatusTag v-if="isKnownStatus(row.status)" :type="row.status" />
            <el-tag v-else size="small" type="info">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="AD 24 个月" width="120" align="right">
          <template #default="{ row }">{{ formatCif(row.result?.prediction?.adCif24m) }}</template>
        </el-table-column>
        <el-table-column label="其他痴呆 24 个月" width="150" align="right">
          <template #default="{ row }">
            {{ formatCif(row.result?.prediction?.otherDementiaCif24m) }}
          </template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </div>

    <el-drawer
      v-model="drawerOpen"
      title="批量评估进度"
      direction="rtl"
      size="920px"
      :close-on-press-escape="true"
    >
      <div class="ba-progress">
        <div class="ba-progress__head">
          <div>
            <strong>{{ doneCount }}</strong>
            <span class="ba-muted"> / {{ batchRows.length }} 个任务已结束</span>
          </div>
          <div class="ba-progress__tags">
            <el-tag type="success" size="small">成功 {{ successCount }}</el-tag>
            <el-tag type="warning" size="small">阻断 {{ blockedCount }}</el-tag>
            <el-tag type="danger" size="small">失败 {{ failedCount }}</el-tag>
          </div>
        </div>
        <el-progress :percentage="progressPercent" :status="progressStatus" :stroke-width="10" />
      </div>

      <el-table :data="batchRows" row-key="snapshotId" class="ba-table">
        <el-table-column label="快照" width="100">
          <template #default="{ row }">#{{ row.snapshotId }}</template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <StatusTag v-if="isKnownStatus(row.status)" :type="row.status" />
            <el-tag v-else size="small" :type="row.status === 'ERROR' ? 'danger' : 'info'">
              {{ row.status === 'READY' ? '待提交' : row.status === 'SUBMITTING' ? '提交中' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="任务" width="110">
          <template #default="{ row }">
            <span v-if="row.taskId">#{{ row.taskId }}</span>
            <span v-else class="ba-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="AD 24 个月" width="120" align="right">
          <template #default="{ row }">{{ formatCif(row.result?.prediction?.adCif24m) }}</template>
        </el-table-column>
        <el-table-column label="其他痴呆 24 个月" width="150" align="right">
          <template #default="{ row }">
            {{ formatCif(row.result?.prediction?.otherDementiaCif24m) }}
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="260">
          <template #default="{ row }">
            <span :class="{ 'ba-danger': row.status === 'ERROR' }">{{ row.message || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button
              v-if="canRetryTask(row)"
              link
              type="primary"
              size="small"
              :loading="retryingTaskId === row.taskId"
              @click.stop="retryTask(row)"
            >
              重试
            </el-button>
            <span v-else class="ba-muted">—</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="ba-drawer-foot">
        <span class="ba-muted">同一快照复用幂等键；已有任务直接回读，不会重复提交。</span>
        <el-button @click="drawerOpen = false">关闭</el-button>
      </div>
    </el-drawer>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import { newIdempotencyKey } from '@/api/assessments.js'
import {
  getResearchTableTask,
  listResearchSnapshots,
  listResearchTaskHistory,
  retryResearchSnapshotSync,
  retryResearchTableTask,
  submitResearchTableTask
} from '@/api/research.js'

const HOST_UNAVAILABLE = '当前未配置研究宿主，请联系管理员配置研究宿主。'
const TERMINAL = new Set(['COMPLETED', 'BLOCKED', 'FAILED', 'OUTCOME_UNKNOWN', 'ERROR'])
const KNOWN = new Set(['COMPLETED', 'BLOCKED', 'FAILED', 'OUTCOME_UNKNOWN'])

const props = defineProps({
  subjectId: { type: [Number, String], default: null }
})

const snapshots = ref([])
const loading = ref(false)
const error = ref('')
const hostUnavailable = ref(false)

const tableRef = ref(null)
const selected = ref([])

const drawerOpen = ref(false)
const batchRows = ref([])
const batchRunning = ref(false)
const retryingId = ref(null)

const syncFilter = ref('ALL')
const historyRows = ref([])
const historyLoading = ref(false)
const historyError = ref('')
const retryingTaskId = ref(null)

/** snapshotId -> idempotency key，重试同一快照时复用 */
const intentKeys = new Map()

const validSubject = computed(() => {
  const subjectId = Number(props.subjectId)
  return Number.isInteger(subjectId) && subjectId > 0
})

const filteredSnapshots = computed(() => {
  if (syncFilter.value === 'ALL') return snapshots.value
  return snapshots.value.filter(row => (row.hostSyncStatus || 'PENDING') === syncFilter.value)
})

const doneCount = computed(
  () => batchRows.value.filter(row => TERMINAL.has(row.status)).length
)
const successCount = computed(
  () => batchRows.value.filter(row => row.status === 'COMPLETED').length
)
const blockedCount = computed(
  () => batchRows.value.filter(row => row.status === 'BLOCKED').length
)
const failedCount = computed(
  () =>
    batchRows.value.filter(
      row => row.status === 'FAILED' || row.status === 'OUTCOME_UNKNOWN' || row.status === 'ERROR'
    ).length
)
const progressPercent = computed(() =>
  batchRows.value.length
    ? Math.round((doneCount.value / batchRows.value.length) * 100)
    : 0
)
const progressStatus = computed(() => {
  if (batchRunning.value) return ''
  if (failedCount.value > 0) return 'exception'
  return 'success'
})

function isKnownStatus(value) {
  return KNOWN.has(value)
}

function formatCif(value) {
  const number = Number(value)
  return Number.isFinite(number) ? `${(number * 100).toFixed(2)}%` : '—'
}

function syncLabel(status) {
  if (status === 'SYNCED') return '已同步'
  if (status === 'FAILED') return '同步失败'
  return '待同步'
}

function syncTag(status) {
  if (status === 'SYNCED') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'warning'
}

function onSelectionChange(rows) {
  selected.value = Array.isArray(rows) ? rows : []
}

async function retrySync(row) {
  if (retryingId.value) return
  retryingId.value = row.snapshotId
  try {
    const result = await retryResearchSnapshotSync(row.snapshotId)
    row.hostSyncStatus = result?.hostSyncStatus || 'PENDING'
    if (row.hostSyncStatus === 'SYNCED') {
      ElMessage.success('快照已同步到研究宿主')
    } else {
      ElMessage.warning('同步尚未完成，请稍后刷新快照后重试')
    }
    await loadSnapshots()
  } catch (problem) {
    ElMessage.error(describeProblem(problem))
  } finally {
    retryingId.value = null
  }
}

function formatTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? String(value)
    : date.toLocaleString('zh-CN', { hour12: false })
}

function canRetryTask(row) {
  return Boolean(
    row.taskId &&
    (row.status === 'FAILED' || row.status === 'OUTCOME_UNKNOWN')
  )
}

async function retryTask(row) {
  if (!canRetryTask(row) || retryingTaskId.value) return
  retryingTaskId.value = row.taskId
  try {
    const task = await retryResearchTableTask(row.taskId)
    applyTask(row, task)
    ElMessage.success(`研究任务 #${row.taskId} 已重新执行`)
    await loadHistory()
    await loadSnapshots()
  } catch (problem) {
    ElMessage.error(describeProblem(problem))
  } finally {
    retryingTaskId.value = null
  }
}

function historyProblem(problem) {
  if (problem?.errorCode === 'NETWORK_ERROR') return '无法连接服务器，请确认后端已启动。'
  if (problem?.httpStatus === 403) return '当前账号无权查看该受试者的研究任务。'
  return problem?.message || '研究任务历史加载失败，请稍后重试。'
}

async function loadHistory() {
  historyError.value = ''
  if (!validSubject.value) {
    historyRows.value = []
    return
  }
  historyLoading.value = true
  try {
    const data = await listResearchTaskHistory(Number(props.subjectId))
    historyRows.value = Array.isArray(data) ? data : []
  } catch (problem) {
    historyRows.value = []
    historyError.value = historyProblem(problem)
  } finally {
    historyLoading.value = false
  }
}

function describeProblem(problem) {
  const status = problem?.httpStatus
  if (status === 503 || problem?.errorCode === 'RESEARCH_UNAVAILABLE') return HOST_UNAVAILABLE
  if (status === 403) return '当前账号无权评估该快照（需要研究员/管理员，且拥有该档案的原始数据权限）。'
  if (status === 409) return '该快照已有提交意图正在处理中（409），请刷新快照后读取已有任务。'
  if (status === 404) return problem?.message || '研究任务不存在。'
  if (problem?.errorCode === 'NETWORK_ERROR') return '无法连接服务器，请确认后端已启动。'
  return problem?.message || '提交失败，请稍后重试。'
}

function handleSnapshotProblem(problem) {
  const status = problem?.httpStatus
  if (status === 503 || problem?.errorCode === 'RESEARCH_UNAVAILABLE') {
    hostUnavailable.value = true
    return
  }
  if (status === 403) {
    error.value = '当前账号没有研究评估权限（需要研究员或管理员角色）。'
    return
  }
  if (status === 404 || status === 405 || status === 501) {
    error.value = '当前后端未提供快照列表接口，请确认 Java 服务版本。'
    return
  }
  if (problem?.errorCode === 'NETWORK_ERROR') {
    error.value = '无法连接服务器，请确认后端已启动。'
    return
  }
  error.value = problem?.message || '快照列表加载失败，请稍后重试。'
}

async function loadSnapshots() {
  const subjectId = Number(props.subjectId)
  error.value = ''
  hostUnavailable.value = false
  selected.value = []
  if (tableRef.value) tableRef.value.clearSelection()
  if (!Number.isInteger(subjectId) || subjectId <= 0) {
    snapshots.value = []
    return
  }
  loading.value = true
  try {
    const data = await listResearchSnapshots(subjectId)
    snapshots.value = Array.isArray(data) ? data : []
  } catch (problem) {
    snapshots.value = []
    handleSnapshotProblem(problem)
  } finally {
    loading.value = false
  }
}

function messageForTask(task) {
  if (task.status === 'BLOCKED') return task.errorCode || '本次评估被阻断，未给出风险数值。'
  if (task.status === 'FAILED') return task.errorCode || '任务已结束但没有可用结果。'
  if (task.status === 'OUTCOME_UNKNOWN') return '请求可能已到达研究宿主，但未收到有效响应。'
  return ''
}

function applyTask(row, task) {
  row.taskId = task?.taskId ?? row.taskId
  row.status = task?.status || 'ERROR'
  row.result = task?.result || null
  row.message = messageForTask(task || {})
}

async function processRow(row) {
  row.status = 'SUBMITTING'
  row.message = ''
  const snapshotId = Number(row.snapshotId)
  try {
    if (row.existingTaskId) {
      const task = await getResearchTableTask(row.existingTaskId)
      applyTask(row, task)
      return
    }
    if (!intentKeys.has(snapshotId)) intentKeys.set(snapshotId, newIdempotencyKey())
    const task = await submitResearchTableTask({
      snapshotId,
      idempotencyKey: intentKeys.get(snapshotId)
    })
    applyTask(row, task)
  } catch (problem) {
    row.status = 'ERROR'
    row.message = describeProblem(problem)
    if (problem?.httpStatus === 503 || problem?.errorCode === 'RESEARCH_UNAVAILABLE') {
      hostUnavailable.value = true
    }
  }
}

async function executeRows(rows) {
  batchRunning.value = true
  const queue = rows.filter(row => !TERMINAL.has(row.status))
  let cursor = 0
  async function worker() {
    while (cursor < queue.length) {
      const row = queue[cursor]
      cursor += 1
      await processRow(row)
    }
  }
  const workers = Math.min(3, queue.length)
  await Promise.all(Array.from({ length: workers }, () => worker()))
  batchRunning.value = false
}

async function openBatch() {
  if (!selected.value.length) return
  const pending = selected.value.filter(item => item.hostSyncStatus !== 'SYNCED')
  if (pending.length) {
    ElMessage.warning('所选快照尚未同步到研究宿主，请稍后刷新或重新登记。')
    return
  }
  batchRows.value = selected.value.map(item => ({
    snapshotId: item.snapshotId,
    existingTaskId: item.taskId || null,
    taskId: item.taskId || null,
    status: 'READY',
    result: null,
    message: ''
  }))
  drawerOpen.value = true
  await executeRows(batchRows.value)
  await loadHistory()
  await loadSnapshots()
}

watch(
  () => props.subjectId,
  () => {
    loadSnapshots()
    loadHistory()
  },
  { immediate: true }
)

watch(syncFilter, () => {
  selected.value = []
  if (tableRef.value) tableRef.value.clearSelection()
})

defineExpose({
  reload: () => {
    loadSnapshots()
    loadHistory()
  }
})
</script>

<style scoped>
.batch-assessment {
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: var(--surface);
  padding: 18px 20px 20px;
}

.ba-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.ba-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--text);
}

.ba-subtitle {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--muted);
}

.ba-block {
  margin-top: 12px;
}

.ba-alert-text {
  margin: 4px 0 10px;
  font-size: 12px;
  color: var(--muted);
}

.ba-filter {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  font-size: 12px;
}

.ba-table {
  width: 100%;
}

.ba-history {
  margin-top: 22px;
  padding-top: 18px;
  border-top: 1px dashed var(--line);
}

.ba-history__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.ba-history__title {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
  color: var(--text);
}

.ba-history__hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--muted);
}

.ba-link {
  color: var(--brand-600);
  font-weight: 600;
}

.ba-muted {
  color: var(--muted);
}

.ba-retry {
  margin-left: 4px;
}

.ba-danger {
  color: var(--danger);
}

.ba-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 14px;
}

.ba-count {
  font-size: 13px;
  color: var(--muted);
}

.ba-count strong {
  color: var(--brand-600);
}

.ba-progress {
  margin-bottom: 16px;
}

.ba-progress__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  font-size: 14px;
  color: var(--text);
}

.ba-progress__tags {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.ba-drawer-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--line);
}

.ba-drawer-foot .ba-muted {
  font-size: 12px;
}
</style>
