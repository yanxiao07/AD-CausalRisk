<template>
  <div class="report-page">
    <PageHeader back title="评估报告" subtitle="按时间倒序选择评估，进入独立的报告页查看 / 打印 / 导出。">
      <template #extra>
        <el-button :icon="Refresh" :loading="listLoading" @click="loadPage(page)">刷新</el-button>
        <el-button :icon="Download" :disabled="items.length === 0" @click="exportCsv">
          导出 CSV
        </el-button>
      </template>
    </PageHeader>

    <DataCard padding="none">
      <div class="filter">
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
    </DataCard>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Document, Download, Refresh, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { listAssessments } from '@/api/assessments.js'
import { downloadCsv } from '@/utils/csv.js'
import { useTableKeyboard } from '@/composables/useTableKeyboard.js'

const route = useRoute()
const router = useRouter()

const items = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const filter = ref('ALL')
const riskFilter = ref('')
const subjectFilter = ref('')
const listLoading = ref(true)
const listError = ref('')
let listSeq = 0

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
  await loadPage(1)
})
</script>

<style scoped>
.report-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
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
