<template>
  <div class="doctor-home">
    <PageHeader title="医生工作台" subtitle="今天需要你处理的事项，以及正在进行中的随访。">
      <template #extra>
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

    <!-- 今日待复核：本页最重要的动作，给最大视觉权重 -->
    <DataCard class="review-hero" padding="lg">
      <div class="review-hero__inner">
        <div>
          <p class="review-hero__eyebrow">今日待复核</p>
          <p class="review-hero__count" :class="{ 'is-empty': !loading && !pendingReview }" aria-live="polite">
            {{ loading ? '…' : pendingReview }}
          </p>
          <p class="review-hero__hint">
            {{ loading ? '正在读取待复核评估…' : pendingReview ? '条评估结果等待你确认，确认后才会对患者可见。' : '当前没有待复核的评估结果。' }}
          </p>
        </div>
        <div class="review-hero__actions">
          <el-button type="primary" size="large" :disabled="loading || !pendingReview" @click="goReviews">
            去复核
          </el-button>
          <span class="review-hero__note">{{ loading ? '数据加载中' : pendingReview ? `待处理 ${pendingReview} 条` : '暂无待办' }}</span>
        </div>
      </div>
    </DataCard>

    <div class="metric-grid">
      <DataCard v-for="metric in metrics" :key="metric.key" hover>
        <div class="metric">
          <div class="metric__head">
            <el-icon class="metric__icon"><component :is="metric.icon" /></el-icon>
            <span class="metric__label">{{ metric.label }}</span>
          </div>
          <p class="metric__value" :class="{ 'is-empty': metric.value === null }">
            {{ loading ? '…' : metric.value ?? '—' }}
          </p>
          <p class="metric__hint">{{ metric.hint }}</p>
        </div>
      </DataCard>
    </div>

    <DataCard title="今日随访" subtitle="按计划时间排列；逾期的会标红。">
      <template #header-extra>
        <el-button text type="primary" @click="goFollowUps">查看全部随访</el-button>
      </template>

      <p v-if="overdueTotal > 0" class="followup-warning">
        另有 {{ overdueTotal }} 条随访已逾期，请尽快确认。
      </p>

      <div v-if="followups.length" class="followup-list">
        <div v-for="item in followups" :key="item.id ?? `${item.subjectId}-${item.scheduledAt}`" class="followup-row">
          <div class="followup-row__time">{{ formatFollowUp(item.scheduledAt) }}</div>
          <div class="followup-row__main">
            <strong>{{ item.subjectAlias || item.subjectCode || `受试者 ${item.subjectId}` }}</strong>
            <span>
              {{ item.subjectCode || '—' }}
              <template v-if="item.purpose"> · {{ item.purpose }}</template>
            </span>
          </div>
          <el-tag size="small" :type="statusTag(item.status)" effect="plain">
            {{ statusLabel(item.status) }}
          </el-tag>
        </div>
      </div>

      <EmptyState
        v-else-if="!loading"
        title="今天没有待处理随访"
        description="随访计划为空或今日没有到期安排；可前往「随访计划」查看全部安排。"
        :icon="Timer"
      />
      <el-skeleton v-else :rows="3" animated />
    </DataCard>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Bell, Refresh, Timer, User } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { listAssessments } from '@/api/assessments.js'
import { listFollowUpsPaged } from '@/api/followups.js'
import { listSubjects } from '@/api/subjects.js'
import { formatDateTime } from '@/utils/datetime.js'

const router = useRouter()
const loading = ref(true)
const loadError = ref('')
const pendingReview = ref(null)
const pendingFirstId = ref(null)
const followups = ref([])
const todayTotal = ref(0)
const overdueTotal = ref(0)
const highRisk = ref(null)
const subjectTotal = ref(null)

const metrics = computed(() => [
  {
    key: 'followups',
    label: '今日随访',
    value: todayTotal.value,
    hint: '今日到期；逾期在下方单独提示',
    icon: Timer
  },
  {
    key: 'high-risk',
    label: '高危预警',
    value: highRisk.value,
    hint: '风险等级 HIGH 的评估记录',
    icon: Bell
  },
  {
    key: 'subjects',
    label: '在管受试者',
    value: subjectTotal.value,
    hint: '当前授权范围内的档案',
    icon: User
  }
])

const statusLabels = {
  PLANNED: '计划中',
  COMPLETED: '已完成',
  CANCELLED: '已取消'
}
const statusTags = {
  PLANNED: 'primary',
  COMPLETED: 'success',
  CANCELLED: 'info'
}

function statusLabel(status) {
  return statusLabels[status] || status || '—'
}

function statusTag(status) {
  return statusTags[status] || 'info'
}

function formatFollowUp(value) {
  if (!value) return '时间待定'
  return formatDateTime(value)
}

async function load() {
  loading.value = true
  loadError.value = ''
  const [pending, high, subjects, today, overdue] = await Promise.allSettled([
    listAssessments({ page: 1, pageSize: 1, reviewStatus: 'PENDING' }),
    listAssessments({ page: 1, pageSize: 1, riskLevel: 'HIGH' }),
    listSubjects({ page: 1, pageSize: 1 }),
    listFollowUpsPaged({ page: 1, pageSize: 6, due: 'today' }),
    listFollowUpsPaged({ page: 1, pageSize: 1, due: 'overdue' })
  ])

  const failed = [pending, high, subjects, today, overdue].filter((item) => item.status === 'rejected')
  if (failed.length === 5) {
    loadError.value = '工作台数据读取失败，请稍后刷新重试。'
  } else if (failed.length > 0) {
    loadError.value = '部分数据读取失败，已展示可用的内容。'
  }

  if (pending.status === 'fulfilled') {
    pendingReview.value = Number(pending.value?.total ?? 0)
    const first = pending.value?.items?.[0]
    pendingFirstId.value = first?.assessmentId ?? first?.id ?? null
  }
  if (high.status === 'fulfilled') highRisk.value = Number(high.value?.total ?? 0)
  if (subjects.status === 'fulfilled') subjectTotal.value = Number(subjects.value?.total ?? 0)
  if (today.status === 'fulfilled') {
    followups.value = Array.isArray(today.value?.items) ? today.value.items : []
    todayTotal.value = Number(today.value?.total ?? followups.value.length)
  }
  if (overdue.status === 'fulfilled') overdueTotal.value = Number(overdue.value?.total ?? 0)
  loading.value = false
}

function goReviews() {
  const query = { status: 'PENDING' }
  if (pendingFirstId.value) query.assessmentId = String(pendingFirstId.value)
  router.push({ path: '/reviews', query })
}

function goFollowUps() {
  router.push('/followups')
}

onMounted(load)
</script>

<style scoped>
.doctor-home {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.load-alert {
  border-radius: var(--r-lg);
}

.review-hero {
  border-color: rgba(10, 115, 173, 0.22);
  background: linear-gradient(120deg, #ffffff 0%, #f2fbff 100%);
}

.review-hero__inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  flex-wrap: wrap;
}

.review-hero__eyebrow {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.4px;
  color: var(--brand-600);
}

.review-hero__count {
  margin: 0;
  font-family: var(--font-display);
  font-size: clamp(40px, 5vw, 56px);
  line-height: 1.05;
  font-weight: 700;
  color: var(--ink);
}

.review-hero__count.is-empty {
  font-size: 26px;
  color: var(--muted);
  letter-spacing: 0;
}

.review-hero__hint {
  margin: 8px 0 0;
  font-size: 14px;
  color: var(--muted);
}

.review-hero__actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
}

.review-hero__note {
  font-size: 12px;
  color: var(--soft);
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
}

.metric__head {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: 13px;
  font-weight: 600;
}

.metric__icon {
  color: var(--brand-600);
}

.metric__value {
  margin: 10px 0 4px;
  font-family: var(--font-display);
  font-size: 32px;
  font-weight: 700;
  color: var(--ink);
}

.metric__value.is-empty {
  font-size: 18px;
  color: var(--soft);
}

.metric__hint {
  margin: 0;
  font-size: 12px;
  color: var(--soft);
}

.followup-warning {
  margin: 0 0 12px;
  padding: 8px 12px;
  border-radius: var(--r-md);
  background: #fff7ed;
  color: #b45309;
  font-size: 13px;
}

.followup-list {
  display: flex;
  flex-direction: column;
}

.followup-row {
  display: grid;
  grid-template-columns: 150px minmax(0, 1fr) auto;
  align-items: center;
  gap: 16px;
  padding: 14px 4px;
  border-top: 1px solid var(--line);
}

.followup-row:first-child {
  border-top: 0;
}

.followup-row__time {
  color: var(--muted);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.followup-row__main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.followup-row__main strong {
  font-size: 14px;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.followup-row__main span {
  font-size: 12px;
  color: var(--soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 640px) {
  .followup-row {
    grid-template-columns: 1fr auto;
    gap: 8px;
  }

  .followup-row__time {
    grid-column: 1 / -1;
  }
}
</style>
