<template>
  <div class="report-page">
    <PageHeader back title="评估报告" subtitle="查看授权档案的模型评估。24 个月预测仅供研究参考。"
      :terms="['review', 'controlled', 'constructed', 'window']">
      <template #extra>
        <el-button :icon="Refresh" :loading="researchLoading" @click="loadResearchPage()">刷新</el-button>
      </template>
    </PageHeader>

    <el-alert v-if="showSampleReportLink" type="info" :closable="false" show-icon title="当前列表没有已完成的预测报告">
      <router-link class="el-button is-link el-button--primary" :to="completedSampleReports">
        查看已完成测试报告
      </router-link>
    </el-alert>

    <DataCard padding="none">
      <div class="filter">
        <ResearchScopeSelect v-if="!doctorView" :model-value="scope" @update:model-value="setScope" />
        <el-select
          v-model="researchStatus"
          class="filter__select"
          size="small"
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

      <el-skeleton v-if="researchLoading" :rows="5" animated />
      <EmptyState
        v-else-if="researchError"
        :icon="WarningFilled"
        title="报告加载失败"
        :description="researchError"
        action-text="重试"
        @action="loadResearchPage(researchPage)"
      />
      <EmptyState
        v-else-if="researchItems.length === 0"
        :icon="Document"
        title="还没有评估报告"
        description="评估完成后，可在这里查看结果。"
      />
      <el-table
        v-else
        :data="researchItems"
        class="table"
        row-key="taskId"
        @row-click="openResearchReport"
      >
        <el-table-column prop="taskId" label="报告编号" width="100" />
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
        <el-table-column label="阿尔茨海默病" min-width="125">
          <template #default="{ row }">{{ researchCif(row, 'adCif24m') }}</template>
        </el-table-column>
        <el-table-column label="其他痴呆" width="115">
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
    </DataCard>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Document, Refresh, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ResearchScopeSelect from '@/components/assessment/ResearchScopeSelect.vue'
import { listResearchReports } from '@/api/research.js'
import { CONTROLLED_RESEARCH, completedSampleReports, researchScope } from '@/utils/researchResults.js'
import { useUserStore } from '@/stores/user.js'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const doctorView = computed(() => userStore.primaryRole === 'DOCTOR')
const scope = computed(() => researchScope(route.query.dataScope))
const researchItems = ref([])
const researchTotal = ref(0)
const researchPage = ref(1)
const researchPageSize = ref(20)
const reportStatuses = ['ALL', 'COMPLETED', 'BLOCKED', 'FAILED', 'OUTCOME_UNKNOWN']
const researchStatus = computed({
  get: () => reportStatuses.includes(route.query.status) ? route.query.status : 'ALL',
  set: status => router.replace({ query: { ...route.query, status: status === 'ALL' ? undefined : status } })
})
const researchLoading = ref(false)
const researchError = ref('')
const showSampleReportLink = computed(() => !doctorView.value && scope.value === CONTROLLED_RESEARCH && !researchLoading.value
  && !researchError.value && researchItems.value.every(row => row.status !== 'COMPLETED'))
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
      CONFIRMED: '已确认',
      NEEDS_DATA: '要求补充数据',
      REJECTED: '已驳回'
    }[status] || '—'
  )
}

async function loadResearchPage(target = researchPage.value) {
  const seq = ++researchSeq
  researchLoading.value = true
  researchError.value = ''
  try {
    const data = await listResearchReports({
      status: researchStatus.value,
      page: target,
      pageSize: researchPageSize.value,
      dataScope: doctorView.value ? undefined : scope.value
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

function setScope(value) {
  router.replace({ query: { dataScope: value === CONTROLLED_RESEARCH ? undefined : value } })
}

watch([scope, researchStatus], () => loadResearchPage(1), { immediate: true })
</script>

<style scoped>
.report-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
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
  flex-wrap: wrap;
  gap: 12px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.filter__select {
  width: 140px;
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
