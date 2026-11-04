<template>
  <div class="admin-home">
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

    <div class="admin-grid admin-grid--charts">
      <article class="panel chart">
        <header class="panel__head">
          <h2>近 7 天建档趋势</h2>
          <span class="panel__tag">GET /api/subjects</span>
        </header>
        <EChart v-if="hasSubjects" :option="trendOption" :height="240" />
        <div v-else class="chart__empty">
          <el-icon><TrendCharts /></el-icon>
          <p>{{ subjectsError || '暂无档案数据' }}</p>
        </div>
      </article>

      <article class="panel chart">
        <header class="panel__head">
          <h2>档案状态分布</h2>
          <span class="panel__tag">实时</span>
        </header>
        <EChart v-if="hasSubjects" :option="statusOption" :height="240" />
        <div v-else class="chart__empty">
          <el-icon><PieChart /></el-icon>
          <p>{{ subjectsError || '暂无档案数据' }}</p>
        </div>
      </article>
    </div>

    <article class="panel audit">
      <header class="panel__head">
        <h2>实时审计</h2>
        <span class="panel__tag">GET /api/audit-logs · 最近 {{ AUDIT_LIMIT }} 条</span>
      </header>
      <AuditStream
        :events="auditEvents"
        :loading="auditLoading"
        :error="auditError"
        :limit="AUDIT_LIMIT"
        dark
      />
    </article>

    <p class="admin-home__foot">
      统计口径：档案数据取自 GET /api/subjects（最多 100 条），质控取自 GET /api/qc/stats，
      审计取自 GET /api/audit-logs；页面每 60 秒自动刷新。
    </p>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { PieChart, TrendCharts } from '@element-plus/icons-vue'
import EChart from '@/components/charts/EChart.vue'
import AuditStream from '@/components/admin/AuditStream.vue'
import { listSubjects } from '@/api/subjects.js'
import { getQcStats } from '@/api/qc.js'
import { listAuditLogs } from '@/api/audit.js'

const AUDIT_LIMIT = 8
const REFRESH_MS = 60000
const PAGE_SIZE = 100

const subjects = ref([])
const total = ref(0)
const stats = ref(null)
const auditEvents = ref([])
const loading = ref(true)
const subjectsError = ref('')
const statsError = ref('')
const auditLoading = ref(true)
const auditError = ref('')
let timer = null

const hasSubjects = computed(() => subjects.value.length > 0)

const metrics = computed(() => [
  {
    key: 'total',
    label: '档案总数',
    value: loading.value ? null : total.value,
    hint: subjectsError.value || '授权范围内全部档案',
    tone: 'is-blue'
  },
  {
    key: 'pending',
    label: '待审质控',
    value: statsError.value ? null : (stats.value?.pendingCount ?? null),
    hint: statsError.value || `已通过 ${stats.value?.approvedCount ?? '—'}`,
    tone: 'is-amber'
  },
  {
    key: 'rejected',
    label: '质控拦截',
    value: statsError.value ? null : (stats.value?.rejectedCount ?? null),
    hint: statsError.value || '被质控拒绝的访视',
    tone: 'is-red'
  },
  {
    key: 'audit',
    label: '审计事件',
    value: auditError.value ? null : auditEvents.value.length,
    hint: auditError.value || '最近一次读取到的条数',
    tone: 'is-green'
  }
])

/** 近 7 天建档趋势：按 createdAt 的日期分组，全部来自真实档案。 */
const trendOption = computed(() => {
  const days = []
  const today = new Date()
  for (let offset = 6; offset >= 0; offset -= 1) {
    const day = new Date(today)
    day.setDate(today.getDate() - offset)
    days.push(day.toISOString().slice(0, 10))
  }
  const counts = new Map(days.map((day) => [day, 0]))
  for (const subject of subjects.value) {
    const day = String(subject.createdAt || '').slice(0, 10)
    if (counts.has(day)) counts.set(day, counts.get(day) + 1)
  }
  return {
    backgroundColor: 'transparent',
    grid: { left: 36, right: 16, top: 24, bottom: 28 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: days.map((day) => day.slice(5)),
      axisLine: { lineStyle: { color: 'rgba(120,190,235,0.25)' } },
      axisLabel: { color: '#7fa6c4', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: 'rgba(120,190,235,0.12)' } },
      axisLabel: { color: '#7fa6c4', fontSize: 11 }
    },
    series: [
      {
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        data: days.map((day) => counts.get(day)),
        lineStyle: { width: 2.4, color: '#4fc3f7' },
        itemStyle: { color: '#4fc3f7' },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(79,195,247,0.35)' },
              { offset: 1, color: 'rgba(79,195,247,0.02)' }
            ]
          }
        }
      }
    ]
  }
})

/** 档案状态分布（ACTIVE / ARCHIVED）。 */
const statusOption = computed(() => {
  const active = subjects.value.filter((row) => row.status === 'ACTIVE').length
  const archived = subjects.value.filter((row) => row.status === 'ARCHIVED').length
  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, textStyle: { color: '#7fa6c4', fontSize: 11 } },
    series: [
      {
        type: 'pie',
        radius: ['46%', '68%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#0b2233', borderWidth: 2 },
        label: { color: '#dcecfb', fontSize: 11 },
        data: [
          { value: active, name: '在组', itemStyle: { color: '#4fc3f7' } },
          { value: archived, name: '已归档', itemStyle: { color: '#567c99' } }
        ]
      }
    ]
  }
})

async function loadSubjects() {
  subjectsError.value = ''
  try {
    const data = await listSubjects({ page: 1, pageSize: PAGE_SIZE })
    subjects.value = Array.isArray(data?.items) ? data.items : []
    total.value = Number(data?.total ?? subjects.value.length)
  } catch (problem) {
    subjects.value = []
    total.value = 0
    subjectsError.value = problem?.code === 403 ? '无权限读取档案' : '档案接口不可用'
  }
}

async function loadStats() {
  statsError.value = ''
  try {
    stats.value = await getQcStats()
  } catch (problem) {
    stats.value = null
    statsError.value = problem?.code === 403 ? '无权限读取质控' : '质控接口不可用'
  }
}

async function loadAudit() {
  auditError.value = ''
  auditLoading.value = true
  try {
    const data = await listAuditLogs()
    auditEvents.value = Array.isArray(data) ? data : []
  } catch (problem) {
    auditEvents.value = []
    auditError.value = problem?.code === 403 ? '仅管理员可查看审计日志' : '审计接口不可用'
  } finally {
    auditLoading.value = false
  }
}

async function loadAll() {
  loading.value = true
  await Promise.allSettled([loadSubjects(), loadStats(), loadAudit()])
  loading.value = false
}

onMounted(() => {
  loadAll()
  timer = window.setInterval(loadAll, REFRESH_MS)
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

@media (max-width: 900px) {
  .admin-grid--charts {
    grid-template-columns: 1fr;
  }
}

.panel {
  position: relative;
  padding: 18px 20px;
  border: 1px solid rgba(120, 190, 235, 0.18);
  border-radius: var(--r-lg);
  background: linear-gradient(160deg, rgba(22, 54, 80, 0.72), rgba(12, 32, 50, 0.72));
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
  color: #dcecfb;
}

.panel__tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  color: #7fa6c4;
  border: 1px solid rgba(120, 190, 235, 0.24);
}

.metric__label {
  margin: 0;
  font-size: 13px;
  color: #7fa6c4;
  letter-spacing: 0.3px;
}

.metric__value {
  margin: 10px 0 4px;
  font-family: var(--font-display);
  font-size: 34px;
  font-weight: 700;
  color: #eaf6ff;
  font-variant-numeric: tabular-nums;
}

.metric__value.is-empty {
  font-size: 20px;
  color: #7fa6c4;
}

.metric__hint {
  margin: 0;
  font-size: 12px;
  color: #5d86a4;
}

.metric__spark {
  position: absolute;
  right: -20px;
  bottom: -30px;
  width: 120px;
  height: 120px;
  border-radius: 50%;
  filter: blur(28px);
  opacity: 0.5;
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
  color: #5d86a4;
  font-size: 13px;
}

.chart__empty .el-icon {
  font-size: 30px;
  color: rgba(79, 195, 247, 0.55);
}

.admin-home__foot {
  margin: 0;
  font-size: 11px;
  line-height: 1.7;
  color: #4d728e;
}
</style>
