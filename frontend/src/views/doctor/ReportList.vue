<template>
  <div class="report-page">
    <PageHeader title="评估报告" subtitle="按评估 ID 生成可打印的个体化报告。">
      <template #extra>
        <el-input
          v-model="inputId"
          class="id-input"
          placeholder="输入评估 ID"
          clearable
          @keyup.enter="load"
        />
        <el-button type="primary" :loading="loading" @click="load">生成报告</el-button>
        <el-button :icon="Printer" :disabled="!report" @click="print">打印报告</el-button>
      </template>
    </PageHeader>

    <EmptyState
      v-if="!report"
      :icon="Document"
      title="还没有生成报告"
      description="输入评估 ID 后生成；也可以从「待办复核」页直接跳转过来。"
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
import { ElMessage } from 'element-plus'
import { Document, Printer } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import CausalGraph from '@/components/charts/CausalGraph.vue'
import { getReport } from '@/api/reports.js'
import { getSubjectDetail } from '@/api/subjects.js'

const route = useRoute()

const inputId = ref('')
const loading = ref(false)
const report = ref(null)
const subject = ref(null)

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

async function load() {
  const id = Number(inputId.value)
  if (!Number.isInteger(id) || id <= 0) {
    ElMessage.warning('请输入有效的评估 ID')
    return
  }
  loading.value = true
  report.value = null
  subject.value = null
  try {
    const data = await getReport(id)
    report.value = data
    // 受试者信息单独取一次；读不到也不影响报告主体
    if (data?.subjectId) {
      try {
        const detail = await getSubjectDetail(data.subjectId)
        subject.value = detail?.subject ?? null
      } catch {
        subject.value = null
      }
    }
  } catch {
    // request.js 已提示
  } finally {
    loading.value = false
  }
}

function print() {
  window.print()
}

onMounted(() => {
  const fromQuery = route.query.assessmentId
  if (fromQuery) {
    inputId.value = String(fromQuery)
    load()
  }
})
</script>

<style scoped>
.report-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.id-input {
  width: 180px;
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
  .el-message {
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
