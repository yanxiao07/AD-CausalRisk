<template>
  <div class="doctor-home">
    <PageHeader title="医生工作台" subtitle="先处理待复核，再查看高危患者与今日随访。">
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

    <div class="board">
      <aside class="rail">
        <button
          v-for="metric in metrics"
          :key="metric.key"
          type="button"
          class="metric-card"
          :class="{ 'metric-card--primary': metric.primary }"
          @click="metric.action"
        >
          <span class="metric-card__label">
            <el-icon><component :is="metric.icon" /></el-icon>
            {{ metric.label }}
          </span>
          <strong class="metric-card__value">{{ loading ? '…' : (metric.value ?? '—') }}</strong>
          <span class="metric-card__hint">{{ metric.hint }}</span>
          <el-icon class="metric-card__arrow"><ArrowRight /></el-icon>
        </button>
      </aside>

      <main class="feed">
        <section class="panel research-metrics">
          <header class="panel__head">
            <div>
              <h2>研究验证</h2>
              <p>研究模型任务独立统计，不与旧规则评估混算。</p>
            </div>
            <el-button text type="primary" @click="goResearchReviews">进入研究复核</el-button>
          </header>
          <div class="research-metrics__grid">
            <div><span>今日任务</span><strong>{{ researchMetrics?.tasksToday ?? '—' }}</strong></div>
            <div><span>已完成</span><strong>{{ researchMetrics?.completed ?? '—' }}</strong></div>
            <div><span>待复核</span><strong>{{ researchMetrics?.pendingReview ?? '—' }}</strong></div>
            <div><span>已同步快照</span><strong>{{ researchMetrics?.syncedSnapshots ?? '—' }}</strong></div>
          </div>
          <p v-if="researchMetrics" class="research-metrics__note">
            阻断 {{ researchMetrics.blocked }} · 失败 {{ researchMetrics.failed }} · 平均耗时
            {{ researchMetrics.averageSeconds }} 秒
          </p>
          <p v-else class="research-metrics__note">研究指标暂不可用，请稍后刷新。</p>
        </section>

        <section class="panel">
          <header class="panel__head">
            <div>
              <h2>高危患者</h2>
              <p>风险等级 HIGH 的评估记录，优先复核。</p>
            </div>
            <el-button text type="primary" @click="goReviews">全部待复核</el-button>
          </header>

          <div v-if="highUnique.length" class="risk-cards">
            <article v-for="item in highUnique" :key="item.assessmentId" class="risk-card">
              <div class="risk-card__top">
                <el-tag type="danger" effect="dark" size="small">HIGH</el-tag>
                <span class="risk-card__score">{{ formatScore(item.riskScore) }}</span>
              </div>
              <h3>{{ item.subjectAlias || item.subjectCode || `受试者 ${item.subjectId}` }}</h3>
              <p class="risk-card__meta">
                {{ item.subjectCode || '—' }}
                <template v-if="item.predictionWindowMonths"> · {{ item.predictionWindowMonths }} 个月</template>
              </p>
              <p class="risk-card__time">{{ formatTime(item.createdAt) }}</p>
              <el-button size="small" type="primary" plain @click="openAssessment(item)">
                查看复核
              </el-button>
            </article>
          </div>
          <EmptyState
            v-else-if="!loading"
            title="暂无高危评估"
            description="当前授权范围内没有风险等级 HIGH 的评估记录。"
            :icon="Warning"
          />
          <el-skeleton v-else :rows="3" animated />
        </section>

        <section class="panel">
          <header class="panel__head">
            <div>
              <h2>研究模型待复核</h2>
              <p>研究验证结果需要医生确认后归档。</p>
            </div>
            <el-button text type="primary" @click="goResearchReviews">进入研究复核</el-button>
          </header>

          <div v-if="researchPending.length" class="pending-list">
            <div v-for="item in researchPending" :key="item.taskId" class="pending-row">
              <div class="pending-row__main">
                <strong>{{ item.subjectCode || `#${item.subjectId}` }}</strong>
                <span>{{ item.subjectAlias || '—' }} · {{ formatTime(item.createdAt) }}</span>
              </div>
              <span class="pending-row__score">{{ researchCif(item, 'adCif24m') }}</span>
              <el-button size="small" type="primary" plain @click="goResearchReviews">复核</el-button>
            </div>
          </div>
          <EmptyState
            v-else-if="!loading"
            title="没有研究模型待复核"
            description="当前没有等待确认的研究验证结果。"
            :icon="Checked"
          />
          <el-skeleton v-else :rows="2" animated />
        </section>

        <section class="panel">
          <header class="panel__head">
            <div>
              <h2>今日待复核</h2>
              <p>确认后结果才会对患者可见。</p>
            </div>
            <el-button text type="primary" @click="goReviews">进入复核队列</el-button>
          </header>

          <div v-if="pendingAssessments.length" class="pending-list">
            <div v-for="item in pendingAssessments" :key="item.assessmentId" class="pending-row">
              <div class="pending-row__main">
                <strong>{{ item.subjectAlias || item.subjectCode || `受试者 ${item.subjectId}` }}</strong>
                <span>{{ item.subjectCode || '—' }} · {{ formatTime(item.createdAt) }}</span>
              </div>
              <span class="pending-row__score">{{ formatScore(item.riskScore) }}</span>
              <el-button size="small" type="primary" @click="openAssessment(item)">复核</el-button>
            </div>
          </div>
          <EmptyState
            v-else-if="!loading"
            title="没有待复核评估"
            description="当前没有等待确认的评估结果。"
            :icon="Checked"
          />
          <el-skeleton v-else :rows="3" animated />
        </section>

        <section class="panel">
          <header class="panel__head">
            <div>
              <h2>今日随访</h2>
              <p>按计划时间排列；逾期的会标红。</p>
            </div>
            <el-button text type="primary" @click="goFollowUps">查看全部随访</el-button>
          </header>

          <p v-if="overdueTotal > 0" class="followup-warning">
            另有 {{ overdueTotal }} 条随访已逾期，请尽快确认。
          </p>

          <div v-if="followups.length" class="followup-list">
            <div v-for="item in followups" :key="item.id ?? `${item.subjectId}-${item.scheduledAt}`" class="followup-row">
              <div class="followup-row__time">{{ formatTime(item.scheduledAt) }}</div>
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
        </section>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Bell, Checked, Refresh, Timer, User, Warning } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { listAssessments } from '@/api/assessments.js'
import { listFollowUpsPaged } from '@/api/followups.js'
import { getResearchMetrics, listResearchReviews } from '@/api/research.js'
import { listSubjects } from '@/api/subjects.js'
import { formatDateTime } from '@/utils/datetime.js'

const router = useRouter()
const loading = ref(true)
const loadError = ref('')
const pendingTotal = ref(null)
const pendingAssessments = ref([])
const highRisk = ref(null)
const highAssessments = ref([])
const followups = ref([])
const todayTotal = ref(0)
const overdueTotal = ref(0)
const subjectTotal = ref(null)
const researchPending = ref([])
const researchPendingTotal = ref(0)
const researchMetrics = ref(null)

/** 同一个受试者只保留最新一条高危评估，避免卡片流出现重复患者。 */
const highUnique = computed(() => {
  const seen = new Set()
  const unique = []
  for (const item of highAssessments.value) {
    const key = item?.subjectId ?? item?.subjectCode ?? item?.assessmentId
    if (seen.has(key)) continue
    seen.add(key)
    unique.push(item)
  }
  return unique
})

const metrics = computed(() => [
  {
    key: 'pending',
    label: '待复核',
    value: pendingTotal.value,
    hint: pendingTotal.value ? `${pendingTotal.value} 条等待确认` : '当前没有待办',
    icon: Checked,
    primary: true,
    action: goReviews
  },
  {
    key: 'high-risk',
    label: '高危预警',
    value: highRisk.value,
    hint: '风险等级 HIGH',
    icon: Bell,
    action: goReviews
  },
  {
    key: 'followups',
    label: '今日随访',
    value: todayTotal.value,
    hint: overdueTotal.value ? `另有 ${overdueTotal.value} 条逾期` : '今日到期',
    icon: Timer,
    action: goFollowUps
  },
  {
    key: 'subjects',
    label: '在管受试者',
    value: subjectTotal.value,
    hint: '授权范围内的档案',
    icon: User,
    action: () => router.push('/subjects')
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

function formatTime(value) {
  if (!value) return '时间待定'
  return formatDateTime(value)
}

function formatScore(value) {
  const score = Number(value)
  if (!Number.isFinite(score)) return '—'
  return `${(score * 100).toFixed(1)}%`
}

async function load() {
  loading.value = true
  loadError.value = ''
  const [pending, high, subjects, today, overdue, research, researchStats] = await Promise.allSettled([
    listAssessments({ page: 1, pageSize: 6, reviewStatus: 'PENDING' }),
    listAssessments({ page: 1, pageSize: 6, riskLevel: 'HIGH' }),
    listSubjects({ page: 1, pageSize: 1 }),
    listFollowUpsPaged({ page: 1, pageSize: 6, due: 'today' }),
    listFollowUpsPaged({ page: 1, pageSize: 1, due: 'overdue' }),
    listResearchReviews({ status: 'PENDING', page: 1, pageSize: 5 }),
    getResearchMetrics()
  ])

  const failed = [pending, high, subjects, today, overdue, research, researchStats].filter(
    (item) => item.status === 'rejected'
  )
  if (failed.length === 7) {
    loadError.value = '工作台数据读取失败，请稍后刷新重试。'
  } else if (failed.length > 0) {
    loadError.value = '部分数据读取失败，已展示可用的内容。'
  }

  if (pending.status === 'fulfilled') {
    pendingTotal.value = Number(pending.value?.total ?? 0)
    pendingAssessments.value = Array.isArray(pending.value?.items) ? pending.value.items : []
  }
  if (high.status === 'fulfilled') {
    highRisk.value = Number(high.value?.total ?? 0)
    highAssessments.value = Array.isArray(high.value?.items) ? high.value.items : []
  }
  if (subjects.status === 'fulfilled') subjectTotal.value = Number(subjects.value?.total ?? 0)
  if (today.status === 'fulfilled') {
    followups.value = Array.isArray(today.value?.items) ? today.value.items : []
    todayTotal.value = Number(today.value?.total ?? followups.value.length)
  }
  if (overdue.status === 'fulfilled') overdueTotal.value = Number(overdue.value?.total ?? 0)
  if (research.status === 'fulfilled') {
    researchPending.value = Array.isArray(research.value?.items) ? research.value.items : []
    researchPendingTotal.value = Number(research.value?.total ?? researchPending.value.length)
  }
  if (researchStats.status === 'fulfilled') researchMetrics.value = researchStats.value
  loading.value = false
}

function researchCif(item, key) {
  const value = item?.result?.prediction?.[key]
  return typeof value === 'number' ? `${(value * 100).toFixed(2)}%` : '—'
}

function goResearchReviews() {
  router.push('/reviews?tab=research')
}

function goReviews() {
  const query = { status: 'PENDING' }
  const first = pendingAssessments.value[0]
  const firstId = first?.assessmentId ?? first?.id
  if (firstId) query.assessmentId = String(firstId)
  router.push({ path: '/reviews', query })
}

function openAssessment(item) {
  const assessmentId = item?.assessmentId ?? item?.id
  const query = {}
  if (assessmentId) query.assessmentId = String(assessmentId)
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

.board {
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}

.rail {
  display: flex;
  flex-direction: column;
  gap: 12px;
  position: sticky;
  top: 16px;
}

.metric-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 16px 18px;
  text-align: left;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-card);
  cursor: pointer;
  transition: border-color var(--dur-base) var(--ease-out), box-shadow var(--dur-base) var(--ease-out);
}

.metric-card:hover {
  border-color: rgba(10, 115, 173, 0.3);
  box-shadow: var(--shadow-hover);
}

.metric-card--primary {
  border-color: rgba(10, 115, 173, 0.28);
  background: linear-gradient(135deg, #ffffff 0%, #f1fbff 100%);
}

.metric-card__label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--muted);
}

.metric-card__value {
  font-family: var(--font-display);
  font-size: 30px;
  line-height: 1.05;
  font-weight: 700;
  color: var(--ink);
}

.metric-card__hint {
  font-size: 11px;
  color: var(--soft);
}

.metric-card__arrow {
  position: absolute;
  right: 14px;
  top: 16px;
  font-size: 12px;
  color: var(--soft);
}

.feed {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.panel {
  padding: 18px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-card);
}

.panel__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.panel__head h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
}

.panel__head p {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--muted);
}

.research-metrics__grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.research-metrics__grid > div {
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: #f6f9fc;
  border: 1px solid #e6eef6;
}

.research-metrics__grid span {
  display: block;
  font-size: 12px;
  color: var(--muted);
}

.research-metrics__grid strong {
  display: block;
  margin-top: 4px;
  font-size: 24px;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}

.research-metrics__note {
  margin: 10px 2px 0;
  font-size: 12px;
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

.risk-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 12px;
}

.risk-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px;
  border: 1px solid #f0d9d6;
  border-radius: var(--r-md);
  background: linear-gradient(160deg, #fffdfc 0%, #fff7f5 100%);
}

.risk-card__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.risk-card__score {
  font-family: var(--font-display);
  font-size: 20px;
  font-weight: 700;
  color: #c2413a;
}

.risk-card h3 {
  margin: 4px 0 0;
  font-size: 14px;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.risk-card__meta,
.risk-card__time {
  margin: 0;
  font-size: 11px;
  color: var(--soft);
}

.risk-card .el-button {
  margin-top: 8px;
  align-self: flex-start;
}

.pending-list {
  display: flex;
  flex-direction: column;
}

.pending-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 84px auto;
  align-items: center;
  gap: 12px;
  padding: 10px 2px;
  border-top: 1px solid var(--line);
}

.pending-row:first-child {
  border-top: 0;
}

.pending-row__main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.pending-row__main strong {
  font-size: 13px;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pending-row__main span {
  font-size: 11px;
  color: var(--soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pending-row__score {
  font-family: var(--font-display);
  font-size: 14px;
  color: var(--muted);
  text-align: right;
}

.followup-warning {
  margin: 0 0 12px;
  padding: 8px 12px;
  border-radius: var(--r-md);
  background: #fff7ed;
  color: #b45309;
  font-size: 12px;
}

.followup-list {
  display: flex;
  flex-direction: column;
}

.followup-row {
  display: grid;
  grid-template-columns: 140px minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  padding: 10px 2px;
  border-top: 1px solid var(--line);
}

.followup-row:first-child {
  border-top: 0;
}

.followup-row__time {
  color: var(--muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.followup-row__main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.followup-row__main strong {
  font-size: 13px;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.followup-row__main span {
  font-size: 11px;
  color: var(--soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 960px) {
  .board {
    grid-template-columns: 1fr;
  }

  .rail {
    position: static;
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .rail {
    grid-template-columns: 1fr;
  }

  .research-metrics__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .followup-row {
    grid-template-columns: 1fr auto;
  }

  .followup-row__time {
    grid-column: 1 / -1;
  }
}
</style>
