<template>
  <div class="review-page">
    <PageHeader title="待办复核" subtitle="核对研究员的评估结果，并给出面向患者的说明。">
      <template #extra>
        <el-input
          v-model="inputId"
          class="id-input"
          placeholder="输入评估 ID"
          clearable
          @keyup.enter="loadById"
        />
        <el-button type="primary" :loading="loading" @click="loadById">载入</el-button>
      </template>
    </PageHeader>

    <el-alert
      class="note"
      type="info"
      :closable="false"
      show-icon
      title="后端暂未提供「评估列表」接口"
      description="契约里只有 POST /api/assessment-tasks、GET /api/assessment-tasks/{id}、GET /api/assessments/{id}。所以这里按评估 ID 载入后在前端按 reviewStatus 过滤；列表接口补上后，把 loadById 换成列表请求即可。"
    />

    <DataCard padding="none">
      <div class="filter">
        <el-radio-group v-model="filter" size="small">
          <el-radio-button label="ALL">全部</el-radio-button>
          <el-radio-button label="PENDING">待复核</el-radio-button>
        </el-radio-group>
        <span class="filter__count">已载入 {{ items.length }} 条</span>
      </div>

      <EmptyState
        v-if="items.length === 0"
        :icon="Document"
        title="还没有载入评估"
        description="输入评估 ID 后载入；也可以从受试者档案抽屉里复核后回来查看。"
      />

      <el-table v-else :data="visibleItems" class="table" row-key="assessmentId">
        <el-table-column prop="assessmentId" label="评估 ID" width="110" />
        <el-table-column prop="subjectId" label="受试者" width="110" />
        <el-table-column label="风险概率" width="120">
          <template #default="{ row }">{{ formatScore(row) }}</template>
        </el-table-column>
        <el-table-column label="等级" width="120">
          <template #default="{ row }">
            <StatusTag :type="row.agentResult?.riskLevel || 'UNAVAILABLE'" />
          </template>
        </el-table-column>
        <el-table-column label="复核状态" width="150">
          <template #default="{ row }">
            <StatusTag :type="row.reviewStatus" :label="reviewLabel(row.reviewStatus)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="openReview(row)">复核</el-button>
            <el-button text @click="openReport(row)">报告</el-button>
          </template>
        </el-table-column>
      </el-table>
    </DataCard>

    <!-- 复核抽屉 -->
    <el-drawer v-model="drawerOpen" title="人工复核" direction="rtl" size="680px" destroy-on-close>
      <template v-if="current">
        <div class="score">
          <div>
            <p class="score__label">风险概率</p>
            <p class="score__value">{{ formatScore(current) }}</p>
            <p class="score__meta">
              预测窗口 {{ current.agentResult?.predictionWindowMonths ?? '—' }} 个月 ·
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
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Document } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import CausalGraph from '@/components/charts/CausalGraph.vue'
import { getAssessment, reviewAssessment } from '@/api/assessments.js'

const route = useRoute()
const router = useRouter()

const inputId = ref('')
const loading = ref(false)
const items = ref([])
const filter = ref('ALL')

const drawerOpen = ref(false)
const current = ref(null)
const comment = ref('')
const submitting = ref('')

const visibleItems = computed(() =>
  filter.value === 'PENDING'
    ? items.value.filter((item) => item.reviewStatus === 'PENDING')
    : items.value
)

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

function formatScore(item) {
  const value = item?.agentResult?.riskScore
  return typeof value === 'number' ? `${(value * 100).toFixed(1)}%` : '不可用'
}

async function loadById() {
  const id = Number(inputId.value)
  if (!Number.isInteger(id) || id <= 0) {
    ElMessage.warning('请输入有效的评估 ID')
    return
  }
  loading.value = true
  try {
    const assessment = await getAssessment(id)
    const index = items.value.findIndex((item) => item.assessmentId === assessment.assessmentId)
    if (index >= 0) items.value[index] = assessment
    else items.value.unshift(assessment)
    inputId.value = ''
    ElMessage.success(`已载入评估 #${assessment.assessmentId}`)
  } catch {
    // 404/400/403 由 request.js 统一提示
  } finally {
    loading.value = false
  }
}

function openReport(row) {
  router.push({ path: '/reports', query: { assessmentId: row.assessmentId } })
}

function openReview(row) {
  current.value = row
  comment.value = ''
  drawerOpen.value = true
}

async function submitReview(decision) {
  if (!current.value) return
  if (!comment.value.trim()) {
    ElMessage.warning('复核意见不能为空')
    return
  }
  submitting.value = decision
  try {
    await reviewAssessment(current.value.assessmentId, decision, comment.value.trim())
    const updated = { ...current.value, reviewStatus: decision }
    const index = items.value.findIndex((item) => item.assessmentId === updated.assessmentId)
    if (index >= 0) items.value[index] = updated
    current.value = updated
    ElMessage.success('复核已记录')
  } catch {
    // request.js 已提示
  } finally {
    submitting.value = ''
  }
}

onMounted(() => {
  const fromQuery = route.query.assessmentId
  if (fromQuery) {
    inputId.value = String(fromQuery)
    loadById()
  }
})
</script>

<style scoped>
.review-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.id-input {
  width: 180px;
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

.filter__count {
  font-size: 12px;
  color: var(--muted);
}

.table {
  width: 100%;
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
