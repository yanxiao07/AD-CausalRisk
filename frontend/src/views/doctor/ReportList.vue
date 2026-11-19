<template>
  <div class="report-page">
    <PageHeader back title="评估报告" :subtitle="subtitle">
      <template #extra>
        <el-button :icon="Refresh" :loading="listLoading || researchLoading" @click="refreshCurrent">刷新</el-button>
        <el-button
          v-if="view === 'legacy'"
          :icon="Download"
          :disabled="items.length === 0"
          @click="exportCsv"
        >
          导出 CSV
        </el-button>
      </template>
    </PageHeader>

    <DataCard padding="none">
      <el-tabs v-model="view" class="report-tabs" @tab-change="onViewChange">
        <el-tab-pane label="旧规则报告" name="legacy" />
        <el-tab-pane v-if="canViewResearch" label="研究验证报告" name="research" />
      </el-tabs>

      <div v-if="view === 'legacy'" class="filter">
        <el-radio-group v-model="filter" size="small" @change="loadPage(1)">
          <el-radio-button value="ALL">全部</el-radio-button>
          <el-radio-button value="PENDING">待复核</el-radio-button>
          <el-radio-button value="ACKNOWLEDGED">已确认</el-radio-button>
        </el-radio-group>
        <el-select
          v-model="riskFilter"
          class="filter__select"
          size="small"
          clearable
          placeholder="风险等级"
          @change="loadPage(1)"
        >
          <el-option label="高风险" value="HIGH" />
          <el-option label="中风险" value="MEDIUM" />
          <el-option label="低风险" value="LOW" />
          <el-option label="不可用" value="UNAVAILABLE" />
        </el-select>
        <el-input
          v-model="subjectFilter"
          class="filter__input"
          size="small"
          clearable
          placeholder="受试者 ID"
          @keyup.enter="loadPage(1)"
          @clear="loadPage(1)"
        />
        <span class="filter__count">共 {{ total }} 条 · 当前页 {{ items.length }} 条</span>
      </div>

      <template v-if="view === 'legacy'">
      <el-skeleton v-if="listLoading && items.length === 0" :rows="5" animated />

      <EmptyState
        v-else-if="listError"
        :icon="WarningFilled"
        title="评估列表加载失败"
        :description="listError"
        action-text="重试"
        @action="loadPage(page)"
      />

      <EmptyState
        v-else-if="items.length === 0"
        :icon="Document"
        :title="filter === 'ALL' ? '还没有评估结果' : '没有符合条件的评估'"
        description="研究员发起评估后，结果会自动出现在这里。"
      />

      <el-table
        v-else
        :data="items"
        class="table"
        row-key="assessmentId"
        @row-click="openReport"
        @keydown.enter="onRowKeydown"
      >
        <el-table-column prop="assessmentId" label="评估 ID" min-width="100" />
        <el-table-column label="受试者" min-width="150">
          <template #default="{ row }">
            {{ row.subjectAlias || row.subjectCode || `#${row.subjectId}` }}
          </template>
        </el-table-column>
        <el-table-column label="风险概率" min-width="120">
          <template #default="{ row }">{{ formatScore(row) }}</template>
        </el-table-column>
        <el-table-column label="等级" min-width="120">
          <template #default="{ row }">
            <StatusTag :type="row.riskLevel || 'UNAVAILABLE'" />
          </template>
        </el-table-column>
        <el-table-column label="复核状态" min-width="140">
          <template #default="{ row }">
            <StatusTag :type="row.reviewStatus" :label="statusLabel(row.reviewStatus)" />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click.stop="openReport(row)">查看报告</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="total > pageSize" class="pager">
        <el-pagination
          v-model:current-page="page"
          :page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
          background
          @current-change="loadPage"
        />
      </div>
      </template>

      <template v-else>
        <div class="filter">
          <el-select
            v-model="researchStatus"
            class="filter__select"
            size="small"
            @change="loadResearchPage(1)"
          >
            <el-option label="全部状态" value="ALL" />
            <el-option label="已完成" value="COMPLETED" />
            <el-option label="被阻断" value="BLOCKED" />
            <el-option label="失败" value="FAILED" />
            <el-option label="结果未知" value="OUTCOME_UNKNOWN" />
          </el-select>
          <span class="filter__count">
            共 {{ researchTotal }} 条 · 当前页 {{ researchItems.length }} 条
          </span>
        </div>

        <el-skeleton v-if="researchLoading && researchItems.length === 0" :rows="5" animated />
        <EmptyState
          v-else-if="researchError"
          :icon="WarningFilled"
          title="研究验证报告加载失败"
          :description="researchError"
          action-text="重试"
          @action="loadResearchPage(researchPage)"
        />
        <EmptyState
          v-else-if="researchItems.length === 0"
          :icon="Document"
          title="还没有研究验证报告"
          description="研究员完成研究模型评估后，结果会出现在这里。"
        />
        <el-table
          v-else
          :data="researchItems"
          class="table"
          row-key="taskId"
          @row-click="openResearchReport"
        >
          <el-table-column prop="taskId" label="任务 ID" width="100" />
          <el-table-column label="受试者" min-width="170">
            <template #default="{ row }">
              <div class="subject-cell">
                <strong>{{ row.subjectCode || `#${row.subjectId}` }}</strong>
                <span v-if="row.subjectAlias" class="subject-cell__alias">{{ row.subjectAlias }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }"><StatusTag :type="row.status" /></template>
          </el-table-column>
          <el-table-column label="AD 24m" width="100">
            <template #default="{ row }">{{ researchCif(row, 'adCif24m') }}</template>
          </el-table-column>
          <el-table-column label="其他痴呆 24m" width="120">
            <template #default="{ row }">{{ researchCif(row, 'otherDementiaCif24m') }}</template>
          </el-table-column>
          <el-table-column label="复核状态" width="120">
            <template #default="{ row }">
              <StatusTag :type="row.reviewStatus" :label="statusLabel(row.reviewStatus)" />
            </template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="150">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" @click.stop="openResearchReport(row)">查看报告</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="researchTotal > researchPageSize" class="pager">
          <el-pagination
            v-model:current-page="researchPage"
            :page-size="researchPageSize"
            :total="researchTotal"
            layout="total, prev, pager, next"
            background
            @current-change="loadResearchPage"
          />
        </div>
      </template>
    </DataCard>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user.js'
import { Document, Download, Refresh, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { listAssessments } from '@/api/assessments.js'
import { listResearchReports } from '@/api/research.js'
import { downloadCsv } from '@/utils/csv.js'
import { useTableKeyboard } from '@/composables/useTableKeyboard.js'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const canViewResearch = computed(() => userStore.hasRole(['DOCTOR', 'RESEARCHER']))

const items = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const filter = ref('ALL')
const riskFilter = ref('')
const subjectFilter = ref('')
const listLoading = ref(true)
const listError = ref('')
const view = ref('legacy')
const subtitle = computed(() =>
  view.value === 'research'
    ? '研究模型输出的双原因 24 个月累积发生率；仅用于研究验证，不构成临床诊断。'
    : '按时间倒序选择评估，进入独立的报告页查看 / 打印 / 导出。'
)
const researchItems = ref([])
const researchTotal = ref(0)
const researchPage = ref(1)
const researchPageSize = ref(20)
const researchStatus = ref('ALL')
const researchLoading = ref(false)
const researchError = ref('')
let listSeq = 0
let researchSeq = 0

const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit'
})

function formatTime(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : timeFormatter.format(date)
}

function statusLabel(status) {
  return (
    {
      PENDING: '待复核',
      ACKNOWLEDGED: '已确认',
      CONFIRMED: '已确认',
      NEEDS_DATA: '要求补充数据',
      REJECTED: '已驳回'
    }[status] || '—'
  )
}

function formatScore(row) {
  const value = typeof row?.riskScore === 'number' ? row.riskScore : null
  return value === null ? '不可用' : `${(value * 100).toFixed(1)}%`
}

async function loadPage(target = page.value) {
  const seq = ++listSeq
  listLoading.value = true
  listError.value = ''
  try {
    const data = await listAssessments({
      page: target,
      pageSize: pageSize.value,
      reviewStatus: filter.value === 'ALL' ? '' : filter.value,
      riskLevel: riskFilter.value,
      subjectId: subjectFilter.value.trim()
    })
    if (seq !== listSeq) return
    items.value = Array.isArray(data?.items) ? data.items : []
    total.value = Number(data?.total ?? items.value.length)
    page.value = Number(data?.page ?? target)
    if (items.value.length === 0 && total.value > 0 && page.value > 1) {
      page.value -= 1
      await loadPage(page.value)
    }
  } catch (problem) {
    if (seq !== listSeq) return
    items.value = []
    total.value = 0
    listError.value = problem?.message || '请稍后重试'
  } finally {
    if (seq === listSeq) listLoading.value = false
  }
}

function openReport(row) {
  router.push(`/reports/${row.assessmentId}`)
}

const { onRowKeydown } = useTableKeyboard(items, openReport)

async function loadResearchPage(target = researchPage.value) {
  const seq = ++researchSeq
  researchLoading.value = true
  researchError.value = ''
  try {
    const data = await listResearchReports({
      status: researchStatus.value,
      page: target,
      pageSize: researchPageSize.value
    })
    if (seq !== researchSeq) return
    researchItems.value = Array.isArray(data?.items) ? data.items : []
    researchTotal.value = Number(data?.total ?? researchItems.value.length)
    researchPage.value = Number(data?.page ?? target)
  } catch (problem) {
    if (seq !== researchSeq) return
    researchItems.value = []
    researchTotal.value = 0
    researchError.value = problem?.message || '请稍后重试'
  } finally {
    if (seq === researchSeq) researchLoading.value = false
  }
}

function openResearchReport(row) {
  router.push(`/reports/research/${row.taskId}`)
}

function researchCif(row, key) {
  const value = row?.result?.prediction?.[key]
  return typeof value === 'number' ? `${(value * 100).toFixed(2)}%` : '—'
}

function onViewChange(name) {
  if (name === 'research' && researchItems.value.length === 0) {
    loadResearchPage(1)
  } else if (name === 'legacy' && items.value.length === 0) {
    loadPage(1)
  }
}

function refreshCurrent() {
  if (view.value === 'research') loadResearchPage(researchPage.value)
  else loadPage(page.value)
}

function exportCsv() {
  const rows = items.value.map((item) => [
    item.assessmentId,
    item.subjectId,
    formatScore(item),
    item.riskLevel || '',
    statusLabel(item.reviewStatus),
    formatTime(item.createdAt)
  ])
  downloadCsv(
    `评估报告清单-${new Date().toISOString().slice(0, 10)}.csv`,
    ['评估ID', '受试者ID', '风险概率', '风险等级', '复核状态', '创建时间'],
    rows
  )
}

onMounted(async () => {
  // 兼容旧深链 /reports?assessmentId=xx → 直接进入独立报告页
  const legacyId = Number(route.query.assessmentId)
  if (Number.isInteger(legacyId) && legacyId > 0) {
    router.replace(`/reports/${legacyId}`)
    return
  }
  if (route.query.tab === 'research' && canViewResearch.value) {
    view.value = 'research'
    await loadResearchPage(1)
    return
  }
  await loadPage(1)
})
</script>

<style scoped>
.report-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.report-tabs {
  padding: 0 18px;
}

.subject-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.subject-cell strong {
  font-weight: 600;
}

.subject-cell__alias {
  color: var(--muted);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.filter {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 12px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.filter__select {
  width: 140px;
}

.filter__input {
  width: 150px;
}

.filter__count {
  margin-left: auto;
  font-size: 12px;
  color: var(--muted);
}

.table {
  width: 100%;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 14px 18px;
  border-top: 1px solid var(--line);
}
</style>
