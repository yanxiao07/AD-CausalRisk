<template>
  <div class="admin-home">
    <PageHeader
      title="管理工作台"
      subtitle="处理患者账号申请，查看评估服务和操作记录。"
      :terms="['archiveBinding', 'doctorAssignment', 'audit']"
    >
      <template #extra>
        <span class="admin-updated">数据截至 {{ lastUpdatedText }}</span>
        <el-button @click="router.push({ name: 'ValidationAuthorization' })">验证资料授权</el-button>
        <el-button :icon="Refresh" :loading="refreshing" @click="manualRefresh">
          手动刷新
        </el-button>
      </template>
    </PageHeader>

    <article class="panel">
      <header class="panel__head">
        <div>
          <h2>患者账号待发放</h2>
          <p>{{ requestsFailed ? '申请列表暂不可用，请打开账号管理重试。'
            : pendingAccounts == null ? '正在读取申请…' : `当前有 ${pendingAccounts} 份待处理申请` }}</p>
        </div>
        <el-button type="primary" @click="router.push('/admin/users?tab=patientRequests')">处理申请</el-button>
      </header>
    </article>

    <div class="admin-grid admin-grid--metrics">
      <article v-for="metric in metrics" :key="metric.key" class="panel metric">
        <p class="metric__label">{{ metric.label }}</p>
        <p class="metric__value" :class="{ 'is-empty': metric.value === null }">
          {{ metric.value === null ? '—' : metric.value }}
        </p>
        <p class="metric__hint">{{ metric.hint }}</p>
        <span class="metric__spark" aria-hidden="true" :class="metric.tone" />
      </article>
    </div>

    <article class="panel research-metrics">
      <header class="panel__head">
        <div>
          <h2>评估服务</h2>
          <p>查看服务配置及研究任务运行统计。</p>
        </div>
        <el-button link type="primary" @click="router.push('/admin/system')">系统设置</el-button>
      </header>
      <div class="research-metrics__grid">
        <div><span>今日任务</span><strong>{{ researchMetrics?.tasksToday ?? '—' }}</strong></div>
        <div><span>已完成</span><strong>{{ researchMetrics?.completed ?? '—' }}</strong></div>
        <div><span>待复核</span><strong>{{ researchMetrics?.pendingReview ?? '—' }}</strong></div>
        <div><span>已准备的评估资料</span><strong>{{ researchMetrics?.syncedSnapshots ?? '—' }}</strong></div>
      </div>
      <p v-if="researchMetrics" class="research-metrics__note">
        阻断 {{ researchMetrics.blocked }} · 失败 {{ researchMetrics.failed }} · 运行中
        {{ researchMetrics.running }} · 平均耗时 {{ researchMetrics.averageSeconds }} 秒
      </p>
      <p v-else class="research-metrics__note">评估统计暂不可用，请刷新重试。</p>
    </article>

    <div class="admin-grid admin-grid--charts">
      <article class="panel chart">
        <header class="panel__head">
          <h2>近 7 天建档趋势</h2>
        </header>
        <EChart v-if="hasSummary" :option="trendOption" :height="240" />
        <div v-else class="chart__empty">
          <el-icon><TrendCharts /></el-icon>
          <p>{{ summaryError || '暂无建档数据' }}</p>
        </div>
      </article>

      <article class="panel chart">
        <header class="panel__head">
          <h2>档案状态分布</h2>
        </header>
        <EChart v-if="hasSummary" :option="statusOption" :height="240" />
        <div v-else class="chart__empty">
          <el-icon><PieChart /></el-icon>
          <p>{{ summaryError || '暂无档案数据' }}</p>
        </div>
      </article>
    </div>

    <article class="panel audit">
      <header class="panel__head">
        <h2>实时审计</h2>
        <el-button link type="primary" @click="router.push('/admin/audit')">查看全部日志</el-button>
      </header>
      <AuditStream
        :events="auditEvents"
        :loading="auditLoading"
        :error="auditError"
        :limit="AUDIT_LIMIT"
      />
    </article>

  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { PieChart, Refresh, TrendCharts } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import { CONTROLLED_RESEARCH } from '@/utils/researchResults.js'
import EChart from '@/components/charts/EChart.vue'
import AuditStream from '@/components/admin/AuditStream.vue'
import { getDashboardSummary } from '@/api/dashboard.js'
import { getResearchMetrics } from '@/api/research.js'
import { listAuditLogs } from '@/api/audit.js'
import { listPatientAccountRequests } from '@/api/patientAccountRequests.js'
import { useRouter } from 'vue-router'

const AUDIT_LIMIT = 8
const router = useRouter()
const pendingAccounts = ref(null)
const requestsFailed = ref(false)
/** 15 秒：复核/质控刚提交完，回到大屏就能看到计数变化，不用等一分钟。 */
const REFRESH_MS = 15000
/** 审计日志没有分页接口、每次全量返回，因此每 4 个 tick（≈60 秒）才拉一次。 */
const AUDIT_REFRESH_EVERY = 4

const summary = ref(null)
const researchMetrics = ref(null)
const summaryError = ref('')
const auditEvents = ref([])
const loading = ref(true)
const auditLoading = ref(true)
const auditError = ref('')
/** 手动刷新时的按钮 loading（自动刷新不闪按钮） */
const refreshing = ref(false)
const lastUpdated = ref(null)
let timer = null
let inFlight = false
/** 自动刷新计数：用于把审计日志降频到每 60 秒一次 */
let ticksSinceAudit = 0
/** 上一轮还没结束就到的 tick，结束后立即补跑，避免慢请求把审计窗口跳过 */
let pendingTick = false

const lastUpdatedText = computed(() => {
  if (!lastUpdated.value) return '—'
  const date = lastUpdated.value
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
})

const hasSummary = computed(() => Boolean(summary.value))

function number(value) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

const metrics = computed(() => [
  {
    key: 'subjects',
    label: '档案总数',
    value: loading.value ? null : number(summary.value?.subjects),
    hint: summaryError.value || '授权范围内的全部档案',
    tone: 'is-blue'
  },
  {
    key: 'pendingReview',
    label: '待复核评估',
    value: researchMetrics.value?.pendingReview ?? null,
    hint: summaryError.value || '等待人工复核的风险评估',
    tone: 'is-amber'
  },
  {
    key: 'failed',
    label: '评估失败',
    value: researchMetrics.value?.failed ?? null,
    hint: '可在系统设置检查评估服务',
    tone: 'is-red'
  },
  {
    key: 'todayFollowups',
    label: '今日随访',
    value: summaryError.value ? null : number(summary.value?.todayFollowups),
    hint: summaryError.value || `逾期 ${number(summary.value?.overdueFollowups) ?? '—'} 条`,
    tone: 'is-green'
  }
])

/** 近 7 天建档趋势：日期骨架本地生成，计数用 dashboard 的 trend（date→total）。 */
const trendOption = computed(() => {
  const days = []
  const today = new Date()
  for (let offset = 6; offset >= 0; offset -= 1) {
    const day = new Date(today)
    day.setDate(today.getDate() - offset)
    days.push(day.toISOString().slice(0, 10))
  }
  const counts = new Map(days.map((day) => [day, 0]))
  for (const row of Array.isArray(summary.value?.trend) ? summary.value.trend : []) {
    const day = String(row?.date || '').slice(0, 10)
    if (counts.has(day)) counts.set(day, Number(row?.total) || 0)
  }
  return {
    backgroundColor: 'transparent',
    grid: { left: 36, right: 16, top: 24, bottom: 28 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: days.map((day) => day.slice(5)),
      axisLine: { lineStyle: { color: 'rgba(17,52,75,0.18)' } },
      axisLabel: { color: '#637f91', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: 'rgba(17,52,75,0.08)' } },
      axisLabel: { color: '#637f91', fontSize: 11 }
    },
    series: [
      {
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        data: days.map((day) => counts.get(day)),
        lineStyle: { width: 2.4, color: '#0a73ad' },
        itemStyle: { color: '#0a73ad' },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(10,115,173,0.28)' },
              { offset: 1, color: 'rgba(10,115,173,0.02)' }
            ]
          }
        }
      }
    ]
  }
})

/** 档案状态分布：直接用 dashboard 的 subjectStates（后端 GROUP BY status）。 */
const statusOption = computed(() => {
  const rows = Array.isArray(summary.value?.subjectStates) ? summary.value.subjectStates : []
  const LABEL = { ACTIVE: '在组', ARCHIVED: '已归档' }
  const COLOR = { ACTIVE: '#0a73ad', ARCHIVED: '#8ca5b3' }
  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, textStyle: { color: '#637f91', fontSize: 11 } },
    series: [
      {
        type: 'pie',
        radius: ['46%', '68%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#ffffff', borderWidth: 2 },
        label: { color: '#11344b', fontSize: 11 },
        data: rows.map((row) => ({
          value: Number(row?.total) || 0,
          name: LABEL[row?.status] || row?.status || '未知',
          itemStyle: { color: COLOR[row?.status] || '#8ca5b3' }
        }))
      }
    ]
  }
})

async function loadSummary() {
  summaryError.value = ''
  try {
    const [dashboard, research, requests] = await Promise.all([
      getDashboardSummary(),
      getResearchMetrics(CONTROLLED_RESEARCH).catch(() => null),
      listPatientAccountRequests().catch(() => null)
    ])
    summary.value = dashboard
    researchMetrics.value = research
    requestsFailed.value = !Array.isArray(requests)
    pendingAccounts.value = requestsFailed.value ? null : requests.filter(item => item.status === 'PENDING').length
    return true
  } catch (problem) {
    summary.value = null
    summaryError.value = problem?.code === 403 ? '无权限读取运营汇总' : '汇总接口不可用'
    return false
  }
}

async function loadAudit() {
  auditError.value = ''
  auditLoading.value = true
  try {
    const data = await listAuditLogs()
    auditEvents.value = Array.isArray(data) ? data : []
    return true
  } catch (problem) {
    auditEvents.value = []
    auditError.value = problem?.code === 403 ? '仅管理员可查看审计日志' : '审计接口不可用'
    return false
  } finally {
    auditLoading.value = false
  }
}

/**
 * 指标刷新：默认 15 秒，但审计日志**每 4 次才拉一次**（≈60 秒）。
 *
 * 审计接口没有分页参数，每次都是全量返回，而页面只展示 8 条；如果跟着 15 秒一起刷，
 * 等于把全量审计的请求频率提高 4 倍。这里按"指标快、审计慢"分开节奏。
 *
 * 慢请求保护：定时器每 15 秒触发一次，但上一轮可能还没结束。此时**不能直接丢掉这个 tick**，
 * 否则连续两次慢请求就会把审计的 60 秒窗口整段跳过。这里把它记成 pending，等当前这轮
 * 结束后立即补跑一次。
 */
async function loadAll({ includeAudit = true, fromTick = false } = {}) {
  if (inFlight) {
    if (fromTick) pendingTick = true
    return
  }
  inFlight = true
  loading.value = true
  try {
    const results = await Promise.all([
      loadSummary(),
      includeAudit ? loadAudit() : Promise.resolve(false)
    ])
    // 审计真的拉成功了才重置计数，避免慢/失败时把下一次审计窗口推得更远
    if (includeAudit && results[1]) ticksSinceAudit = 0
    // 全部失败时不更新"上次更新"，否则页面会显示一个并没有成功的刷新时间
    if (results.some(Boolean)) lastUpdated.value = new Date()
  } finally {
    loading.value = false
    inFlight = false
    if (pendingTick) {
      pendingTick = false
      runTick()
    }
  }
}

/** 一次定时 tick：决定这轮要不要带审计；忙时只排队、不计数，补跑时再算一次。 */
function runTick() {
  if (inFlight) {
    pendingTick = true
    return
  }
  ticksSinceAudit += 1
  loadAll({ includeAudit: ticksSinceAudit >= AUDIT_REFRESH_EVERY, fromTick: true })
}

/** 手动刷新：两路都拉（含审计），按钮上给反馈，结束时间与自动刷新共用同一个时间戳。 */
async function manualRefresh() {
  if (refreshing.value || inFlight) return
  refreshing.value = true
  try {
    pendingTick = false
    await loadAll({ includeAudit: true })
  } finally {
    refreshing.value = false
  }
}

onMounted(() => {
  loadAll({ includeAudit: true })
  timer = window.setInterval(runTick, REFRESH_MS)
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
  timer = null
})
</script>

<style scoped>
.admin-home {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.admin-updated {
  font-size: 12px;
  color: var(--soft);
  font-variant-numeric: tabular-nums;
}

.admin-grid {
  display: grid;
  gap: 16px;
}

.admin-grid--metrics {
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
}

.admin-grid--charts {
  grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr);
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

@media (max-width: 720px) {
  .research-metrics__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .admin-grid--charts {
    grid-template-columns: 1fr;
  }
}

.panel {
  position: relative;
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  /* 与医生/研究员页面同一套白卡 + 双层阴影 */
  background: var(--surface, #ffffff);
  box-shadow: var(--shadow-card);
  overflow: hidden;
}

.panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.panel__head h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.3px;
  color: var(--ink);
}

.panel__tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  color: var(--muted);
  border: 1px solid var(--line);
}

.metric__label {
  margin: 0;
  font-size: 13px;
  color: var(--muted);
  letter-spacing: 0.3px;
}

.metric__value {
  margin: 10px 0 4px;
  font-family: var(--font-display);
  font-size: 34px;
  font-weight: 700;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}

.metric__value.is-empty {
  font-size: 20px;
  color: var(--muted);
}

.metric__hint {
  margin: 0;
  font-size: 12px;
  color: var(--soft);
}

.metric__spark {
  position: absolute;
  right: -20px;
  bottom: -30px;
  width: 120px;
  height: 120px;
  border-radius: 50%;
  filter: blur(34px);
  opacity: 0.16;
}

.metric__spark.is-blue { background: #2f9ad4; }
.metric__spark.is-red { background: #c5484f; }
.metric__spark.is-green { background: #2f9a72; }
.metric__spark.is-amber { background: #b7791f; }

.chart__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 240px;
  color: var(--soft);
  font-size: 13px;
}

.chart__empty .el-icon {
  font-size: 30px;
  color: rgba(10, 115, 173, 0.45);
}

.admin-home__foot {
  margin: 0;
  font-size: 11px;
  line-height: 1.7;
  color: var(--soft);
}
</style>
