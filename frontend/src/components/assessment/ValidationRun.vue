<template>
  <DataCard title="运行验证" subtitle="按研究项目和模型版本汇总所有获授权病例。">
    <p v-if="scopesLoading" role="status">正在读取可验证的项目和模型版本…</p>
    <el-alert v-else-if="scopeError" :title="scopeError" type="error" :closable="false" />
    <el-empty v-else-if="!options.length" description="授权队列暂无已生成概率的病例。完成预测后可选择对应版本。" />
    <template v-else>
      <el-form label-position="top" @submit.prevent="run">
        <el-form-item label="研究项目和模型版本">
          <el-select v-model="chosen" class="scope-select" :disabled="running">
            <el-option v-for="item in options" :key="item.key" :value="item.key" :label="item.label" />
          </el-select>
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="running" :disabled="!selected">运行 24 个月验证</el-button>
      </el-form>
      <p v-if="loading" role="status">正在读取已保存报告…</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <div v-if="report" class="report" aria-live="polite">
        <h3>验证报告</h3>
        <p>{{ labels[report.status] }} · 冻结 {{ report.includedCases }} 条预测 · {{ formatDateTime(report.createdAt) }}</p>
        <p v-if="report.reason">{{ reasons[report.reason] }}</p>
        <dl class="result-grid">
          <div><dt>模型产物摘要</dt><dd>{{ report.artifactSha256 }}</dd></div>
          <div><dt>验证快照摘要</dt><dd>{{ report.snapshotSha256 }}</dd></div>
          <template v-if="report.metrics">
            <div><dt>已知 / 未知结局</dt><dd>{{ report.metrics.knownCases }} / {{ report.metrics.unknownCases }}</dd></div>
            <div><dt>已知结局 AUC</dt><dd>{{ number(report.metrics.knownSubsetAuc) }}</dd></div>
            <div><dt>已知结局 Brier</dt><dd>{{ number(report.metrics.knownSubsetBrier) }}</dd></div>
            <div><dt>平均预测概率</dt><dd>{{ percent(report.metrics.meanPrediction) }}</dd></div>
            <div><dt>实际 AD 率可达范围</dt><dd>{{ range(report.metrics.observedRateBounds, percent) }}</dd></div>
            <div><dt>全组 Brier 可达范围</dt><dd>{{ range(report.metrics.brierBounds, number) }}</dd></div>
          </template>
        </dl>
        <p v-if="report.metrics">AUC 只使用结局已知的病例。可达范围反映未知结局的不同取值，供演练核查。</p>
        <h3>版本复评</h3>
        <p>等待独立验证队列，以及现用版本和候选版本在同批病例上的预测。</p>
        <RouterLink :to="{ name: 'ResearchEvidence', query: { tab: 'catalog' } }">查看模型版本与证据</RouterLink>
      </div>
    </template>
  </DataCard>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import DataCard from '@/components/common/DataCard.vue'
import { getValidationReport, listValidationScopes, runValidationReport } from '@/api/validationCohort.js'
import { formatDateTime } from '@/utils/datetime.js'

const props = defineProps({
  revision: { type: Number, required: true },
  pageCases: { type: Array, required: true }
})
const scopes = ref([])
const usePageScopes = ref(false)
const scopeError = ref('')
const scopesLoading = ref(true)
const options = computed(() => {
  const source = usePageScopes.value ? props.pageCases.filter(row => row.artifactSha256 && row.adRisk != null) : scopes.value
  return [...new Map(source.map(row => {
  const key = row.project + '/' + row.artifactSha256
  return [key, { key, label: `${row.project} · ${row.model} · ${row.artifactSha256.slice(0, 12)}`,
    scope: { project: row.project, artifactSha256: row.artifactSha256 } }]
  })).values()]
})
const chosen = ref('')
const selected = computed(() => options.value.find(item => item.key === chosen.value))
const loading = ref(false)
const running = ref(false)
const report = ref(null)
const error = ref('')
let sequence = 0

const labels = { COMPLETED: '演练计算完成', SUPPRESSED: '样本或分组不足', BLOCKED: '待补独立验证资料' }
const reasons = {
  SMALL_OUTCOME_RISK_CELL: '当前病例不足以发布指标。任一非空的结局与风险分组少于 5 例时，指标保持空白。',
  INDEPENDENT_PROTOCOL_REQUIRED: '真实验证需要已核准的目标队列、独立性和结局方案。'
}
const number = value => value == null ? '未估计' : value.toFixed(3)
const percent = value => value == null ? '未估计' : (value * 100).toFixed(2) + '%'
const range = (value, format) => value ? `${format(value.lower)} ～ ${format(value.upper)}` : '未估计'

watch(() => props.revision, async (_, previous, onCleanup) => {
  if (usePageScopes.value) return
  let current = true
  onCleanup(() => { current = false })
  scopeError.value = ''
  try {
    const values = await listValidationScopes()
    if (current) scopes.value = values
  } catch (problem) {
    if (!current) return
    if (problem.httpStatus === 404) usePageScopes.value = true
    else scopeError.value = problem.message || '读取可验证项目失败，可刷新队列重试。'
  } finally {
    if (current) scopesLoading.value = false
  }
}, { immediate: true })

watch(options, items => {
  if (!items.some(item => item.key === chosen.value)) chosen.value = items[0]?.key || ''
}, { immediate: true })

watch([chosen, () => props.revision], async () => {
  const request = ++sequence
  report.value = null
  error.value = ''
  loading.value = Boolean(selected.value)
  if (!selected.value) return
  try {
    const value = await getValidationReport(selected.value.scope)
    if (request === sequence) report.value = value
  } catch (problem) {
    if (request === sequence) error.value = problem.httpStatus === 404
      ? '当前后端版本尚未提供批次验证，请打开使用新版后端的实例。'
      : (problem.message || '读取验证报告失败。')
  } finally {
    if (request === sequence) loading.value = false
  }
}, { immediate: true })

async function run() {
  if (running.value || !selected.value) return
  const request = ++sequence
  running.value = true
  loading.value = false
  error.value = ''
  try {
    const value = await runValidationReport(selected.value.scope)
    if (request === sequence) report.value = value
  } catch (problem) {
    if (request === sequence) error.value = problem.message || '运行验证失败。'
  } finally {
    running.value = false
  }
}
</script>

<style scoped>
.scope-select { width: min(100%, 420px); }
.report { margin-top: 20px; }
.result-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 200px), 1fr)); gap: 12px; }
.result-grid dt { font-weight: 600; }
.result-grid dd { margin: 0; overflow-wrap: anywhere; }
</style>
