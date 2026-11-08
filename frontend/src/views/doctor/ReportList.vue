<template>
  <div class="report-page">
    <PageHeader back title="评估报告" subtitle="从评估列表选择一条，生成可打印的个体化报告。">
      <template #extra>
        <el-button :icon="Refresh" :loading="listLoading" @click="loadPage(page)">刷新</el-button>
        <el-button :icon="Download" :disabled="visibleItems.length === 0" @click="exportCsv">
          导出 CSV
        </el-button>
        <el-button type="primary" :icon="Printer" :disabled="!report" @click="print">打印报告</el-button>
      </template>
    </PageHeader>

    <!-- 列表：数据来自 GET /api/assessments（摘要，不含 agentResult） -->
    <DataCard class="no-print" padding="none">
      <div class="filter">
        <el-radio-group v-model="filter" size="small">
          <el-radio-button label="ALL">全部</el-radio-button>
          <el-radio-button label="PENDING">待复核</el-radio-button>
          <el-radio-button label="ACKNOWLEDGED">已确认</el-radio-button>
        </el-radio-group>
        <span class="filter__count">共 {{ total }} 条 · 当前页 {{ items.length }} 条</span>
      </div>

      <el-skeleton v-if="listLoading && items.length === 0" :rows="4" animated />

      <EmptyState
        v-else-if="listError"
        :icon="WarningFilled"
        title="评估列表加载失败"
        :description="listError"
        action-text="重试"
        @action="loadPage(page)"
      />

      <EmptyState
        v-else-if="visibleItems.length === 0"
        :icon="Document"
        :title="filter === 'ALL' ? '还没有评估结果' : '当前页没有符合条件的评估'"
        description="研究员发起评估后，结果会自动出现在这里。"
      />

      <el-table v-else :data="visibleItems" class="table" row-key="assessmentId" @row-click="selectReport" @keydown.enter="onRowKeydown">
        <el-table-column prop="assessmentId" label="评估 ID" width="100" />
        <el-table-column prop="subjectId" label="受试者" width="100" />
        <el-table-column label="风险概率" width="120">
          <template #default="{ row }">{{ formatScore(row) }}</template>
        </el-table-column>
        <el-table-column label="等级" width="120">
          <template #default="{ row }">
            <StatusTag :type="row.riskLevel || 'UNAVAILABLE'" />
          </template>
        </el-table-column>
        <el-table-column label="复核状态" width="140">
          <template #default="{ row }">
            <StatusTag :type="row.reviewStatus" :label="statusLabel(row.reviewStatus)" />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click.stop="selectReport(row)">查看报告</el-button>
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

    <EmptyState
      v-if="loadError"
      :icon="WarningFilled"
      title="报告载入失败"
      :description="loadError"
      action-text="重试"
      @action="load(selectedId)"
    />

    <EmptyState
      v-else-if="!report"
      :icon="Document"
      title="请选择一条评估"
      description="点击上方列表中的「查看报告」，或从「待办复核」页跳转过来。"
    />

    <article v-else id="report-sheet" class="sheet">
      <header class="sheet__head">
        <div>
          <p class="sheet__brand">AD-CausalRisk</p>
          <h1 class="sheet__title">个体化风险评估报告</h1>
          <p class="sheet__sub">
            评估编号 #{{ report.assessmentId }} · 任务 #{{ report.taskId }} ·
            生成于 {{ formatTime(new Date().toISOString()) }}
          </p>
        </div>
        <div class="sheet__stamp">
          <StatusTag :type="report.agentResult?.riskLevel || 'UNAVAILABLE'" />
          <StatusTag :type="report.reviewStatus" :label="reviewLabel" />
        </div>
      </header>

      <section class="sheet__block">
        <h2 class="sheet__h2">受试者信息</h2>
        <dl class="kv">
          <div class="kv__item">
            <dt>档案编号</dt>
            <dd>{{ subject?.subjectCode || `#${report.subjectId}` }}</dd>
          </div>
          <div class="kv__item">
            <dt>别名</dt>
            <dd>{{ subject?.alias || '—' }}</dd>
          </div>
          <div class="kv__item">
            <dt>数据版本</dt>
            <dd>{{ report.agentResult?.dataVersion || subject?.dataVersion || '—' }}</dd>
          </div>
          <div class="kv__item">
            <dt>预测窗口</dt>
            <dd>{{ report.agentResult?.predictionWindowMonths ?? '—' }} 个月</dd>
          </div>
        </dl>
      </section>

      <section class="sheet__block">
        <h2 class="sheet__h2">风险结论</h2>
        <div class="verdict">
          <div>
            <p class="verdict__label">AD 风险概率</p>
            <p class="verdict__value" :class="{ 'is-empty': riskScore === null }">
              {{ riskScore === null ? '不可用（数据不足）' : `${(riskScore * 100).toFixed(1)}%` }}
            </p>
            <p class="verdict__meta">
              模型 {{ report.agentResult?.modelVersion || '—' }} ·
              质控 {{ report.agentResult?.qualityStatus || '—' }}
            </p>
          </div>
          <div class="verdict__side">
            <StatusTag :type="report.agentResult?.riskLevel || 'UNAVAILABLE'" />
            <span class="verdict__hint">
              {{
                report.agentResult?.riskLevel === 'HIGH'
                  ? '建议纳入专项随访队列'
                  : report.agentResult?.riskLevel === 'MEDIUM'
                    ? '建议年度复查并关注认知变化'
                    : report.agentResult?.riskLevel === 'LOW'
                      ? '常规随访即可'
                      : '本次未给出可用风险值'
              }}
            </span>
          </div>
        </div>
      </section>

      <section v-if="explanations.length" class="sheet__block">
        <h2 class="sheet__h2">关键解释</h2>
        <ul class="bullets">
          <li v-for="(item, index) in explanations" :key="index">
            <strong>{{ item.label || item.feature }}</strong>
            <span>{{ item.interpretation || item.message }}</span>
          </li>
        </ul>
      </section>

      <section v-if="report.agentResult?.graph" class="sheet__block sheet__block--graph">
        <h2 class="sheet__h2">个体因果网络</h2>
        <CausalGraph :graph="report.agentResult.graph" :height="320" />
      </section>

      <section v-if="warnings.length || limitations.length" class="sheet__block">
        <h2 class="sheet__h2">提示与局限</h2>
        <ul class="bullets bullets--muted">
          <li v-for="(item, index) in warnings" :key="`w${index}`">{{ item }}</li>
          <li v-for="(item, index) in limitations" :key="`l${index}`">{{ item }}</li>
        </ul>
      </section>

      <footer class="sheet__foot">
        <p>
          本报告由因果风险模型生成，<strong>仅供科研与临床辅助参考，不构成诊断</strong>；
          连线表示条件性关联，不是病因证明。最终解释以医生复核意见为准。
        </p>
        <p class="sheet__foot-meta">
          复核状态：{{ reviewLabel }} ·
          runId {{ report.agentResult?.runId || '—' }} ·
          契约 {{ report.agentResult?.contractVersion || '—' }} /
          {{ report.agentResult?.workflowVersion || '—' }}
        </p>
      </footer>
    </article>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Document, Download, Printer, Refresh, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import CausalGraph from '@/components/charts/CausalGraph.vue'
import { listAssessments } from '@/api/assessments.js'
import { getReport } from '@/api/reports.js'
import { getSubjectDetail } from '@/api/subjects.js'
import { downloadCsv } from '@/utils/csv.js'
import { useTableKeyboard } from '@/composables/useTableKeyboard.js'

const route = useRoute()

// ---- 评估列表（GET /api/assessments，摘要，不含 agentResult）----
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const items = ref([])
const filter = ref('ALL')
const listLoading = ref(false)
const listError = ref('')
/** 列表请求序号：翻页竞态时只认最新一次响应 */
let listSeq = 0

const visibleItems = computed(() =>
  filter.value === 'ALL'
    ? items.value
    : items.value.filter((item) => item.reviewStatus === filter.value)
)

// ---- 报告（GET /api/reports/{assessmentId}）----
const selectedId = ref(null)
const loading = ref(false)
const report = ref(null)
const subject = ref(null)
/** 载入失败的可读原因（空串 = 没有错误） */
const loadError = ref('')

/** 每次 load 自增；只有最新一次请求的响应才写状态。 */
let requestSeq = 0

const riskScore = computed(() => {
  const value = report.value?.agentResult?.riskScore
  return typeof value === 'number' ? value : null
})

const explanations = computed(() =>
  (report.value?.agentResult?.explanations || []).filter((item) => item.interpretation || item.message)
)
const warnings = computed(() => report.value?.agentResult?.warnings || [])
const limitations = computed(() => report.value?.agentResult?.limitations || [])

const reviewLabel = computed(
  () =>
    ({
      PENDING: '待复核',
      ACKNOWLEDGED: '已确认',
      NEEDS_DATA: '要求补充数据',
      REJECTED: '已驳回'
    })[report.value?.reviewStatus] || '—'
)

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

/** 列表行是摘要（riskScore 在顶层）；报告对象是完整 Assessment（分数在 agentResult 里）。 */
function formatScore(row) {
  const value =
    typeof row?.riskScore === 'number' ? row.riskScore : row?.agentResult?.riskScore
  return typeof value === 'number' ? `${(value * 100).toFixed(1)}%` : '不可用'
}

async function loadPage(target = page.value) {
  const seq = ++listSeq
  listLoading.value = true
  listError.value = ''
  try {
    const data = await listAssessments({ page: target, pageSize: pageSize.value })
    if (seq !== listSeq) return
    items.value = Array.isArray(data?.items) ? data.items : []
    total.value = Number(data?.total ?? items.value.length)
    page.value = Number(data?.page ?? target)
    // 删到空页时自动退回上一页
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

function selectReport(row) {
  load(row.assessmentId)
}

const { onRowKeydown } = useTableKeyboard(visibleItems, selectReport)

function exportCsv() {
  const rows = visibleItems.value.map((item) => [
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

async function load(id) {
  const target = Number(id)
  if (!Number.isInteger(target) || target <= 0) return
  selectedId.value = target

  // 请求序号守卫：连续点两条评估时，慢的那次返回会覆盖快的那次（"串报告"）。
  const seq = ++requestSeq
  loading.value = true
  loadError.value = ''
  report.value = null
  subject.value = null

  try {
    const data = await getReport(target)
    if (seq !== requestSeq) return
    report.value = data

    // 受试者信息单独取一次；读不到也不影响报告主体
    if (data?.subjectId) {
      try {
        const detail = await getSubjectDetail(data.subjectId)
        if (seq !== requestSeq) return
        subject.value = detail?.subject ?? null
      } catch {
        if (seq !== requestSeq) return
        subject.value = null
      }
    }
  } catch (problem) {
    if (seq !== requestSeq) return
    // 契约边界：不存在的 ID 是 400（不是 404），无权限是 403 —— 都落到页面上说明白
    loadError.value =
      problem?.errorCode === 'NETWORK_ERROR'
        ? '无法连接服务器，请确认后端已启动。'
        : problem?.code === 403
          ? '当前账号无权读取该评估的报告。'
          : problem?.message || `评估 #${target} 的报告载入失败，请稍后重试。`
  } finally {
    // 只有最新请求能关闭 loading，避免旧请求把新请求的转圈提前关掉
    if (seq === requestSeq) loading.value = false
  }
}

function print() {
  window.print()
}

onMounted(async () => {
  await loadPage(1)
  // 深链：从复核页/任务页带 assessmentId 过来，直接打开对应报告
  const fromQuery = Number(route.query.assessmentId)
  if (Number.isInteger(fromQuery) && fromQuery > 0) {
    const row = items.value.find((item) => item.assessmentId === fromQuery)
    if (row) selectReport(row)
    else load(fromQuery)
  }
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
  justify-content: space-between;
  gap: 12px;
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

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 14px 18px;
  border-top: 1px solid var(--line);
}

.sheet {
  max-width: 900px;
  margin: 0 auto;
  padding: 36px 40px 28px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-card);
}

.sheet__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 18px;
  border-bottom: 2px solid var(--brand-600);
}

.sheet__brand {
  margin: 0 0 4px;
  font-family: var(--font-display);
  font-size: 12px;
  letter-spacing: 2px;
  text-transform: uppercase;
  color: var(--brand-600);
}

.sheet__title {
  margin: 0;
  font-size: 26px;
  font-weight: 700;
  color: var(--ink);
}

.sheet__sub {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--muted);
}

.sheet__stamp {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
}

.sheet__block {
  margin-top: 22px;
}

.sheet__h2 {
  margin: 0 0 10px;
  font-size: 15px;
  font-weight: 700;
  color: var(--ink-700);
  padding-left: 10px;
  border-left: 3px solid var(--brand-600);
}

.kv {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 24px;
  margin: 0;
}

.kv__item {
  display: flex;
  gap: 10px;
  padding: 8px 12px;
  background: var(--bg-soft);
  border-radius: var(--r-sm);
}

.kv dt {
  font-size: 13px;
  color: var(--muted);
  min-width: 72px;
}

.kv dd {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}

.verdict {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: linear-gradient(120deg, #f2fbff 0%, #ffffff 100%);
}

.verdict__label {
  margin: 0;
  font-size: 12px;
  color: var(--muted);
}

.verdict__value {
  margin: 6px 0 4px;
  font-family: var(--font-display);
  font-size: 46px;
  line-height: 1.05;
  font-weight: 700;
  color: var(--brand-700);
  font-variant-numeric: tabular-nums;
}

.verdict__value.is-empty {
  font-size: 24px;
  color: var(--muted);
}

.verdict__meta {
  margin: 0;
  font-size: 12px;
  color: var(--soft);
}

.verdict__side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
  text-align: right;
}

.verdict__hint {
  font-size: 12px;
  color: var(--muted);
}

.bullets {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.bullets li {
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-700);
}

.bullets strong {
  margin-right: 6px;
  color: var(--ink);
}

.bullets--muted li {
  color: var(--muted);
}

.sheet__foot {
  margin-top: 26px;
  padding-top: 14px;
  border-top: 1px dashed var(--line-strong);
}

.sheet__foot p {
  margin: 0 0 6px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
}

.sheet__foot-meta {
  color: var(--soft);
  font-family: var(--font-display);
}

/* 打印：隐藏外壳与按钮，只留报告纸 */
@media print {
  @page {
    margin: 14mm;
  }

  .sheet {
    max-width: none;
    margin: 0;
    padding: 0;
    border: none;
    border-radius: 0;
    box-shadow: none;
  }
}
</style>

<!-- 全局打印规则：本页在 SidebarLayout 里，需要隐藏外壳（作用域样式够不到祖先节点） -->
<style>
@media print {
  .shell__aside,
  .topbar,
  .page-header,
  .el-message,
  .no-print {
    display: none !important;
  }

  .shell__main {
    padding: 0 !important;
  }

  body,
  #app {
    background: #fff !important;
  }
}
</style>
