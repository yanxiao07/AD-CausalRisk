<template>
  <div class="doctor-home">
    <PageHeader title="医生工作台" subtitle="查看待复核结果、患者档案和今日随访。" :terms="['review', 'followup']">
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
        <section class="panel">
          <header class="panel__head">
            <div>
              <h2>待复核报告</h2>
              <p>受控研究 · 待医生复核</p>
            </div>
            <el-button text type="primary" @click="goResearchReviews">查看全部待复核</el-button>
          </header>

          <div v-if="researchPending.length" class="pending-list">
            <div v-for="item in researchPending" :key="item.taskId" class="pending-row">
              <div class="pending-row__main">
                <strong>{{ item.subjectCode || `#${item.subjectId}` }}</strong>
                <span>{{ item.subjectAlias || '—' }} · {{ researchScopeLabel(item.dataScope) }} · {{ formatTime(item.createdAt) }}</span>
              </div>

              <el-button size="small" type="primary" plain @click="router.push(`/reports/research/${item.taskId}`)">看报告</el-button>
            </div>
          </div>
          <EmptyState
            v-else-if="!loading"
            title="没有待复核报告"
            description="评估完成后，待复核报告会出现在这里。"
            :icon="Checked"
          />
          <el-skeleton v-else :rows="2" animated />
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
import { ArrowRight, Checked, Refresh, Timer, User } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import { researchScopeLabel } from '@/utils/researchResults.js'
import EmptyState from '@/components/common/EmptyState.vue'
import { listFollowUpsPaged } from '@/api/followups.js'
import { listResearchReviews } from '@/api/research.js'
import { listSubjects } from '@/api/subjects.js'
import { formatDateTime } from '@/utils/datetime.js'

const router = useRouter()
const loading = ref(true)
const loadError = ref('')
const followups = ref([])
const todayTotal = ref(0)
const overdueTotal = ref(0)
const subjectTotal = ref(null)
const researchPending = ref([])
const researchPendingTotal = ref(0)

const metrics = computed(() => [
  {
    key: 'pending',
    label: '待复核',
    value: researchPendingTotal.value,
    hint: researchPendingTotal.value ? `${researchPendingTotal.value} 条等待确认` : '当前没有待办',
    icon: Checked,
    primary: true,
    action: goResearchReviews
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
    label: '在管患者',
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

async function load() {
  loading.value = true
  loadError.value = ''
  const [subjects, today, overdue, research] = await Promise.allSettled([
    listSubjects({ page: 1, pageSize: 1 }),
    listFollowUpsPaged({ page: 1, pageSize: 6, due: 'today' }),
    listFollowUpsPaged({ page: 1, pageSize: 1, due: 'overdue' }),
    listResearchReviews({ status: 'PENDING', page: 1, pageSize: 5 })
  ])

  const failed = [subjects, today, overdue, research].filter(
    (item) => item.status === 'rejected'
  )
  if (failed.length === 4) {
    loadError.value = '工作台数据读取失败，请稍后刷新重试。'
  } else if (failed.length > 0) {
    loadError.value = '部分数据读取失败，已展示可用的内容。'
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
  loading.value = false
}

function goResearchReviews() {
  router.push('/reviews')
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

.risk-card__meta,
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

  .followup-row {
    grid-template-columns: 1fr auto;
  }

  .followup-row__time {
    grid-column: 1 / -1;
  }
}
</style>
