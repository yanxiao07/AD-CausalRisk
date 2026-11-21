<template>
  <div class="research-home">
    <PageHeader
      title="研究员工作台"
      subtitle="查看获授权病例、研究资料质控和评估任务。"
      :terms="['quality', 'controlled', 'frozen']"
    >
      <template #extra>
        <el-button type="primary" @click="router.push({ name: 'ResearchEvidence' })">模型验证与更新</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <el-alert
      v-if="loadError"
      :title="loadError"
      type="warning"
      show-icon
      :closable="false"
      class="load-alert"
    />

    <div class="stats">
      <DataCard v-for="stat in stats" :key="stat.key" hover>
        <div class="stat">
          <span class="stat__label">{{ stat.label }}</span>
          <strong class="stat__value">{{ loading ? '…' : stat.value }}</strong>
          <span class="stat__hint">{{ stat.hint }}</span>
        </div>
      </DataCard>
    </div>

    <div class="grid">
      <DataCard title="质控异常待办" subtitle="核对提醒。被阻断的资料修正后，请重新质控。" padding="none">
        <template #header-extra>
          <el-button text type="primary" @click="router.push('/quality')">全部质控</el-button>
        </template>

        <el-skeleton v-if="loading" :rows="4" animated />
        <EmptyState
          v-else-if="qcItems.length === 0"
          title="没有待处理质控"
          description="当前没有需关注或被阻断的质控记录。"
          :icon="Checked"
        />
        <div v-else class="qc-list">
          <div v-for="item in qcItems" :key="item.id" class="qc-row">
            <div class="qc-row__main">
              <strong>{{ item.subjectAlias || item.subjectCode || `受试者 ${item.subjectId}` }}</strong>
              <span>{{ item.subjectCode || '—' }} · {{ formatTime(item.checkedAt) }}</span>
            </div>
            <StatusTag :type="item.qualityStatus" />
            <span class="qc-row__issue">{{ firstIssue(item) }}</span>
          </div>
        </div>
      </DataCard>

      <DataCard title="核验受控研究资料" subtitle="核对另行登记的研究受试者及资料快照。">
        <div class="batch-card">
          <div class="batch-card__point">
            <el-icon><Files /></el-icon>
            <span>从档案选择已登记的资料快照，提交核验。</span>
          </div>
          <div class="batch-card__point">
            <el-icon><Connection /></el-icon>
            <span>打开核验报告，查看仍需补证或复核的项目。</span>
          </div>
          <div class="batch-actions">
            <el-button @click="router.push({ name: 'PatientList' })">查看研究档案</el-button>
            <el-button @click="router.push({ name: 'TaskList' })">打开评估任务</el-button>
          </div>
        </div>
      </DataCard>
    </div>

    <DataCard title="今日评估任务" subtitle="受控研究" padding="none">
      <template #header-extra>
        <el-button text type="primary" @click="router.push('/tasks')">查看全部</el-button>
      </template>

      <el-skeleton v-if="loading" :rows="4" animated />
      <EmptyState
        v-else-if="recentTasks.length === 0"
        title="还没有研究任务"
        description="提交受控研究评估后，可在这里查看当日进度。"
        :icon="Cpu"
      />
      <el-table v-else :data="recentTasks" class="task-table" row-key="taskId">
        <el-table-column label="任务" width="90">
          <template #default="{ row }">#{{ row.taskId }}</template>
        </el-table-column>
        <el-table-column label="快照" width="90">
          <template #default="{ row }">#{{ row.snapshotId }}</template>
        </el-table-column>
        <el-table-column label="受试者" min-width="160">
          <template #default="{ row }">{{ row.subjectCode || `#${row.subjectId}` }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }"><StatusTag :type="row.status" /></template>
        </el-table-column>
        <el-table-column label="AD 24 个月" width="120">
          <template #default="{ row }">{{ formatCif(row.result?.prediction?.adCif24m) }}</template>
        </el-table-column>
        <el-table-column label="其他痴呆 24 个月" width="140">
          <template #default="{ row }">{{ formatCif(row.result?.prediction?.otherDementiaCif24m) }}</template>
        </el-table-column>
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openTask(row)">查看报告</el-button>
          </template>
        </el-table-column>
      </el-table>
    </DataCard>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Checked, Connection, Cpu, Files, Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import { listImportJobs } from '@/api/imports.js'
import { listQualityChecks } from '@/api/qc.js'
import { getResearchTaskSummary } from '@/api/research.js'
import { listValidationCases } from '@/api/validationCohort.js'
import { CONTROLLED_RESEARCH } from '@/utils/researchResults.js'
import { formatDateTime } from '@/utils/datetime.js'

const router = useRouter()
const loading = ref(true)
const loadError = ref('')
const importToday = ref(null)
const summary = ref(null)
const qcItems = ref([])
const qcPending = ref(null)
const validationTotal = ref(null)

const stats = computed(() => {
  const data = summary.value || {}
  return [
    {
      key: 'validation',
      label: '获授权病例',
      value: validationTotal.value ?? '—',
      hint: '医生档案的预测与随访结局'
    },
    {
      key: 'imports',
      label: '今日数据导入',
      value: importToday.value ?? '—',
      hint: '今日创建的导入任务'
    },
    {
      key: 'tasks',
      label: '今日受控评估',
      value: data.tasksToday ?? '—',
      hint: summary.value ? `完成 ${data.completedToday ?? 0} · 失败 ${data.failedToday ?? 0}` : '受控研究任务'
    },
    {
      key: 'avg',
      label: '平均耗时',
      value: formatSeconds(data.averageSeconds),
      hint: '今日已完成研究任务'
    },
    {
      key: 'qc',
      label: '质控异常待办',
      value: qcPending.value ?? '—',
      hint: 'WARN + BLOCKED'
    }
  ]
})

function todayKey() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
}

function formatSeconds(value) {
  const seconds = Number(value)
  if (!Number.isFinite(seconds) || seconds <= 0) return '—'
  if (seconds < 60) return `${seconds.toFixed(1)}s`
  return `${(seconds / 60).toFixed(1)}min`
}

function formatTime(value) {
  if (!value) return '—'
  return formatDateTime(value)
}

function formatCif(value) {
  return Number.isFinite(value) ? `${(value * 100).toFixed(2)}%` : '—'
}

function issuesOf(row) {
  const raw = row?.issues
  if (Array.isArray(raw)) return raw.filter(Boolean)
  if (typeof raw !== 'string' || !raw.trim()) return []
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter(Boolean) : [raw]
  } catch {
    return [raw]
  }
}

function firstIssue(row) {
  const issues = issuesOf(row)
  return issues[0] || '无异常明细'
}

function openTask(row) {
  if (row?.taskId) router.push(`/reports/research/${row.taskId}`)
}

async function load() {
  loading.value = true
  loadError.value = ''
  const today = todayKey()
  const results = await Promise.allSettled([
    listValidationCases(1),
    listImportJobs({ page: 1, pageSize: 1, from: today, to: today }),
    getResearchTaskSummary(CONTROLLED_RESEARCH),
    listQualityChecks({ status: 'WARN', page: 1, pageSize: 5 }),
    listQualityChecks({ status: 'BLOCKED', page: 1, pageSize: 5 })
  ])
  const [validation, imports, taskSummary, warn, blocked] = results

  const failed = results.filter(item => item.status === 'rejected').length
  if (failed === results.length) {
    loadError.value = '工作台数据读取失败，请稍后刷新重试。'
  } else if (failed > 0) {
    loadError.value = '部分数据读取失败，已展示可用的内容。'
  }

  validationTotal.value = validation.status === 'fulfilled' ? validation.value.total : null
  importToday.value = imports.status === 'fulfilled' ? imports.value.total : null
  summary.value = taskSummary.status === 'fulfilled' ? taskSummary.value : null
  const qcPages = [warn, blocked].filter(result => result.status === 'fulfilled')
  qcItems.value = qcPages.flatMap(result => result.value.items)
    .sort((left, right) => Date.parse(right.checkedAt) - Date.parse(left.checkedAt)).slice(0, 5)
  qcPending.value = qcPages.length === 2 ? qcPages.reduce((total, result) => total + result.value.total, 0) : null
  loading.value = false
}

const recentTasks = computed(() => (Array.isArray(summary.value?.recent) ? summary.value.recent : []))

onMounted(load)
</script>

<style scoped>
.research-home {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.load-alert {
  border-radius: var(--r-lg);
}

.stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 14px;
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.stat__label {
  font-size: 12px;
  font-weight: 600;
  color: var(--muted);
}

.stat__value {
  font-family: var(--font-display);
  font-size: 28px;
  line-height: 1.1;
  color: var(--ink);
}

.stat__hint {
  font-size: 11px;
  color: var(--soft);
}

.grid {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(280px, 1fr);
  gap: 16px;
  align-items: start;
}

.qc-list {
  display: flex;
  flex-direction: column;
}

.qc-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  grid-template-rows: auto auto;
  gap: 4px 10px;
  padding: 10px 16px;
  border-top: 1px solid var(--line);
}

.qc-row:first-child {
  border-top: 0;
}

.qc-row__main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.qc-row__main strong {
  font-size: 13px;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.qc-row__main span {
  font-size: 11px;
  color: var(--soft);
}

.qc-row__issue {
  grid-column: 1 / -1;
  font-size: 12px;
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.batch-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.batch-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.batch-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.batch-card__point {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
}

.batch-card__point .el-icon {
  margin-top: 3px;
  color: var(--brand-600);
}

.batch-card__point code {
  font-family: var(--font-display);
  font-size: 11px;
  padding: 1px 4px;
  border-radius: 4px;
  background: var(--brand-50);
  color: var(--brand-700);
}

.batch-card__hint {
  margin: 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--soft);
}

.task-table {
  width: 100%;
}

@media (max-width: 960px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
</style>
