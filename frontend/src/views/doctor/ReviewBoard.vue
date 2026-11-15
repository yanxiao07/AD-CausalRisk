<template>
  <div class="review-page">
    <PageHeader back title="待办复核" subtitle="按时间倒序展示评估结果，最新的在前。">
      <template #extra>
        <el-radio-group v-model="filter" size="small" @change="loadPage(1)">
          <el-radio-button value="ALL">全部</el-radio-button>
          <el-radio-button value="PENDING">待复核</el-radio-button>
        </el-radio-group>
        <el-select
          v-model="riskFilter"
          class="header-filter"
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
          class="header-filter"
          size="small"
          clearable
          placeholder="受试者 ID"
          @keyup.enter="loadPage(1)"
          @clear="loadPage(1)"
        />
        <el-button :icon="Refresh" :loading="loading" @click="loadPage(page)">刷新</el-button>
      </template>
    </PageHeader>

    <DataCard padding="none">
      <div class="filter">
        <span class="filter__count">共 {{ total }} 条 · 当前页 {{ items.length }} 条</span>
        <span class="filter__hint">筛选由服务端完成</span>
      </div>

      <el-skeleton v-if="firstLoading" :rows="5" animated />

      <EmptyState
        v-else-if="loadError"
        :icon="WarningFilled"
        title="评估列表加载失败"
        :description="loadError"
        action-text="重试"
        @action="loadPage(page)"
      />

      <EmptyState
        v-else-if="items.length === 0"
        :icon="Document"
        :title="filter === 'PENDING' ? '没有待复核的评估' : '还没有评估结果'"
        :description="
          filter === 'PENDING'
            ? '所有评估都已复核；可以切换到「全部」查看历史结果。'
            : '研究员发起评估后，结果会自动出现在这里。'
        "
      />

      <el-table v-else :data="items" class="table" row-key="assessmentId" @keydown.enter="onRowKeydown">
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
        <el-table-column label="复核状态" min-width="150">
          <template #default="{ row }">
            <StatusTag :type="row.reviewStatus" :label="reviewLabel(row.reviewStatus)" />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button
              text
              type="primary"
              :loading="openingId === row.assessmentId"
              @click.stop="openReview(row)"
            >
              复核
            </el-button>
            <el-button text @click.stop="openReport(row)">报告</el-button>
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

    <!-- 复核抽屉：列表行只有摘要，打开时再拉完整结果（含因果图） -->
    <el-drawer
      v-model="drawerOpen"
      title="人工复核"
      direction="rtl"
      size="680px"
      destroy-on-close
      :close-on-press-escape="true"
      @keydown.esc="drawerOpen = false"
    >
      <el-skeleton v-if="detailLoading" :rows="8" animated />
      <template v-else-if="current">
        <div class="score">
          <div>
            <p class="score__label">风险概率</p>
            <p class="score__value">{{ formatScore(current) }}</p>
            <p class="score__meta">
              预测窗口 {{ currentWindow ?? '—' }} 个月 ·
              {{ current.agentResult?.dataVersion || '—' }}
            </p>
          </div>
          <div class="score__side">
            <StatusTag :type="current.agentResult?.riskLevel || 'UNAVAILABLE'" />
            <StatusTag :type="current.reviewStatus" :label="reviewLabel(current.reviewStatus)" />
          </div>
        </div>

        <CausalGraph v-if="current.agentResult?.graph" :graph="current.agentResult.graph" :height="360" />

        <div v-if="current.reviewStatus === 'PENDING'" class="review-form">
          <el-input
            v-model="comment"
            type="textarea"
            :rows="3"
            maxlength="2000"
            show-word-limit
            placeholder="复核意见（必填）：例如已核对量表与影像，同意该风险分层。"
          />
          <div class="review-form__actions">
            <el-button
              type="primary"
              :loading="submitting === 'ACKNOWLEDGED'"
              @click="submitReview('ACKNOWLEDGED')"
            >
              确认复核
            </el-button>
            <el-button
              :loading="submitting === 'NEEDS_DATA'"
              @click="submitReview('NEEDS_DATA')"
            >
              要求补充数据
            </el-button>
          </div>
        </div>

        <el-alert
          v-else
          type="success"
          :closable="false"
          show-icon
          :title="`该评估已复核：${reviewLabel(current.reviewStatus)}`"
        />
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Document, Refresh, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import CausalGraph from '@/components/charts/CausalGraph.vue'
import { getAssessment, getAssessmentTaskInput, listAssessments, reviewAssessment } from '@/api/assessments.js'
import { useTableKeyboard } from '@/composables/useTableKeyboard.js'
import { useEscapeToClose } from '@/composables/useEscapeToClose.js'

const route = useRoute()
const router = useRouter()

const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const items = ref([])
const filter = ref('ALL')
const riskFilter = ref('')
const subjectFilter = ref('')

const loading = ref(false)
const firstLoading = ref(true)
const loadError = ref('')
const openingId = ref(null)

const drawerOpen = ref(false)
const current = ref(null)
/** 预测窗口来自任务输入快照（AgentResult 不含该字段） */
const currentWindow = ref(null)

async function loadCurrentWindow() {
  currentWindow.value = null
  const taskId = current.value?.taskId
  if (!taskId) return
  try {
    const input = await getAssessmentTaskInput(taskId)
    currentWindow.value = input?.request?.predictionWindowMonths ?? null
  } catch {
    currentWindow.value = null
  }
}
const detailLoading = ref(false)
const comment = ref('')
const submitting = ref('')

/** 列表请求序号：翻页/刷新竞态时只接受最新一次响应 */
let requestSeq = 0

const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit'
})

function formatTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : timeFormatter.format(date)
}

function reviewLabel(status) {
  return (
    {
      PENDING: '待复核',
      ACKNOWLEDGED: '已确认',
      NEEDS_DATA: '要求补充数据',
      REJECTED: '已驳回'
    }[status] || '—'
  )
}

/** 列表行是摘要（riskScore 在顶层）；抽屉里是完整 Assessment（分数在 agentResult 里）。 */
function formatScore(item) {
  const value =
    typeof item?.riskScore === 'number' ? item.riskScore : item?.agentResult?.riskScore
  return typeof value === 'number' ? `${(value * 100).toFixed(1)}%` : '不可用'
}

async function loadPage(target = page.value) {
  const seq = ++requestSeq
  loading.value = true
  loadError.value = ''
  try {
    const data = await listAssessments({
      page: target,
      pageSize: pageSize.value,
      // 服务端筛选：后端 /api/assessments 支持 reviewStatus / riskLevel / keyword 等
      reviewStatus: filter.value === 'ALL' ? '' : filter.value,
      riskLevel: riskFilter.value,
      subjectId: subjectFilter.value.trim()
    })
    if (seq !== requestSeq) return
    items.value = Array.isArray(data?.items) ? data.items : []
    total.value = Number(data?.total ?? items.value.length)
    page.value = Number(data?.page ?? target)
    // 删到空页时自动退回上一页
    if (items.value.length === 0 && total.value > 0 && page.value > 1) {
      page.value -= 1
      await loadPage(page.value)
    }
  } catch (problem) {
    if (seq !== requestSeq) return
    items.value = []
    total.value = 0
    loadError.value = problem?.message || '请稍后重试'
  } finally {
    if (seq === requestSeq) {
      loading.value = false
      firstLoading.value = false
    }
  }
}

function openReport(row) {
  router.push(`/reports/${row.assessmentId}`)
}

async function openReview(row) {
  openingId.value = row.assessmentId
  detailLoading.value = true
  try {
    const detail = await getAssessment(row.assessmentId)
    current.value = detail
    comment.value = ''
    drawerOpen.value = true
    loadCurrentWindow()
    // 打开哪条复核就写到 URL：从报告页返回时能直接回到这个抽屉
    router.replace({ query: { ...route.query, assessmentId: String(row.assessmentId) } })
  } catch {
    // request.js 已提示（403/400）
  } finally {
    detailLoading.value = false
    openingId.value = null
  }
}

const { onRowKeydown } = useTableKeyboard(items, openReview)

useEscapeToClose(drawerOpen, () => {
  drawerOpen.value = false
})

// 关闭时清掉 URL 上的 assessmentId
watch(drawerOpen, (open) => {
  if (open || !route.query.assessmentId) return
  const query = { ...route.query }
  delete query.assessmentId
  router.replace({ query })
})

async function submitReview(decision) {
  if (!current.value) return
  if (!comment.value.trim()) {
    ElMessage.warning('复核意见不能为空')
    return
  }
  // 固定本次提交的评估：等响应期间用户可能已打开另一条，响应只允许写到这一条上
  const target = current.value
  submitting.value = decision
  try {
    await reviewAssessment(target.assessmentId, decision, comment.value.trim())
    const updated = { ...target, reviewStatus: decision }

    // 列表按 assessmentId 精确定位，不会误伤其它行
    const index = items.value.findIndex((item) => item.assessmentId === updated.assessmentId)
    if (index >= 0) items.value[index] = { ...items.value[index], reviewStatus: decision }

    // 抽屉里显示的仍是同一条才更新；已经切到别的评估就保持不动
    if (current.value?.assessmentId === updated.assessmentId) {
      current.value = updated
      drawerOpen.value = false
    }
    ElMessage.success('复核已记录')
  } catch {
    // request.js 已提示
  } finally {
    submitting.value = ''
  }
}

onMounted(async () => {
  const statusFromQuery = String(route.query.status || '').toUpperCase()
  if (['PENDING', 'ACKNOWLEDGED'].includes(statusFromQuery)) {
    filter.value = statusFromQuery
  }
  await loadPage(1)
  // 深链：从任务页/档案抽屉带过来的评估 ID，直接打开该条复核（不依赖列表所在页）
  const fromQuery = Number(route.query.assessmentId)
  if (Number.isInteger(fromQuery) && fromQuery > 0) {
    const row = items.value.find((item) => item.assessmentId === fromQuery)
    if (row) {
      openReview(row)
    } else {
      detailLoading.value = true
      try {
        current.value = await getAssessment(fromQuery)
        comment.value = ''
        drawerOpen.value = true
        loadCurrentWindow()
      } catch {
        // 该评估不可读或不存在，保持列表视图
      } finally {
        detailLoading.value = false
      }
    }
  }
})
</script>

<style scoped>
.review-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.note {
  border-radius: var(--r-md);
}

.filter {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.header-filter {
  width: 140px;
}

.filter__count {
  font-size: 12px;
  color: var(--muted);
}

.filter__hint {
  font-size: 12px;
  color: var(--soft);
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

.score {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 18px;
  margin-bottom: 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: linear-gradient(120deg, #f2fbff 0%, #ffffff 100%);
}

.score__label {
  margin: 0;
  font-size: 12px;
  color: var(--muted);
}

.score__value {
  margin: 6px 0 4px;
  font-family: var(--font-display);
  font-size: 40px;
  line-height: 1.1;
  font-weight: 700;
  color: var(--brand-700);
  font-variant-numeric: tabular-nums;
}

.score__meta {
  margin: 0;
  font-size: 12px;
  color: var(--soft);
}

.score__side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
}

.review-form {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.review-form__actions {
  display: flex;
  gap: 10px;
}
</style>
