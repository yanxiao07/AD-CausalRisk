<template>
  <div class="research-report">
    <PageHeader
      back
      title="模型验证报告"
      :subtitle="task ? `研究任务 #${task.taskId} · 快照 #${task.snapshotId}` : '读取该受试者的研究验证结果'"
    >
      <template #extra>
        <el-dropdown :disabled="!task || exporting" @command="exportReport">
          <el-button :icon="Download" :disabled="!task">
            导出报告
            <el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="pdf">PDF</el-dropdown-item>
              <el-dropdown-item command="html">HTML</el-dropdown-item>
              <el-dropdown-item command="json">JSON</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <el-skeleton v-if="loading" :rows="8" animated />

    <DataCard v-else-if="error" padding="lg">
      <EmptyState
        :icon="WarningFilled"
        title="报告加载失败"
        :description="error"
        action-text="重试"
        @action="load"
      />
    </DataCard>

    <template v-else-if="task">
      <DataCard padding="lg">
        <div class="report-head">
          <div>
            <p class="report-eyebrow">研究验证结果</p>
            <h2 class="report-title">
              {{ subjectText }}
              <StatusTag :type="task.status" />
            </h2>
            <p class="report-note">
              该结果来自已登记冻结快照，由 Python 研究宿主计算；不是临床诊断，也不构成个体因果结论。
            </p>
            <p class="report-review">
              复核状态：{{ reviewLabel }}
              <template v-if="task.reviewComment"> · {{ task.reviewComment }}</template>
            </p>
          </div>
          <el-button type="primary" plain @click="goSubject">打开受试者档案</el-button>
        </div>

        <el-alert
          v-if="clinicalOnly"
          class="report-alert"
          type="warning"
          :closable="false"
          show-icon
          title="研究验证用途，不可用于临床决策"
          description="该结果标记为不可用于临床；百分比为未校准的 24 个月累积发生率，不等同于临床风险或个体因果效应。"
        />

        <div class="report-grid" :class="{ 'report-grid--research': clinicalOnly }">
          <div class="report-stat">
            <span>AD 24 个月风险</span>
            <strong>{{ cif(prediction.adCif24m) }}</strong>
          </div>
          <div class="report-stat">
            <span>其他痴呆 24 个月风险</span>
            <strong>{{ cif(prediction.otherDementiaCif24m) }}</strong>
          </div>
          <div class="report-stat">
            <span>预测窗口</span>
            <strong>{{ windowMonths }} 个月</strong>
          </div>
          <div class="report-stat">
            <span>数据范围</span>
            <strong>{{ scopeLabel }}</strong>
          </div>
        </div>

        <el-alert
          v-if="task.status !== 'COMPLETED'"
          class="report-alert"
          :type="task.status === 'BLOCKED' ? 'warning' : 'error'"
          :closable="false"
          show-icon
          :title="statusTitle"
          :description="task.errorCode || statusDescription"
        />

        <el-alert
          v-else-if="explanation.isCausalEffect === false"
          class="report-alert"
          type="info"
          :closable="false"
          show-icon
          title="这是同次模型归因，不是个体因果效应"
          description="分组贡献解释模型为何给出该风险，不能理解为干预该因素会改变风险。"
        />
      </DataCard>

      <DataCard padding="lg">
        <h3 class="section-title">模型与来源</h3>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="模型">
            {{ model.family || '—' }} / {{ model.specification || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="预测策略">
            {{ model.predictionPolicy || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="runId" :span="2">
            <span class="mono">{{ task.runId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="artifact SHA-256" :span="2">
            <span class="mono">{{ shortHash(model.artifactSha256) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ formatDateTime(task.createdAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="更新时间">
            {{ formatDateTime(task.updatedAt) }}
          </el-descriptions-item>
        </el-descriptions>
      </DataCard>

      <DataCard v-if="contributions.length" padding="lg">
        <h3 class="section-title">分组贡献</h3>
        <el-table :data="contributions" size="small">
          <el-table-column prop="group" label="分组" min-width="180" />
          <el-table-column label="AD 贡献" min-width="120">
            <template #default="{ row }">{{ signed(row.ad) }}</template>
          </el-table-column>
          <el-table-column label="其他痴呆贡献" min-width="140">
            <template #default="{ row }">{{ signed(row.other) }}</template>
          </el-table-column>
        </el-table>
        <p class="section-note">
          方法：{{ explanation.method || '—' }}；重构误差 {{ explanation.reconstructionMaxError ?? '—' }}。
          缺失值影响未解释：{{ explanation.missingnessEffectsExplained === true ? '是' : '否' }}。
        </p>
      </DataCard>

      <DataCard padding="lg">
        <el-collapse>
          <el-collapse-item title="查看完整结构化结果" name="json">
            <pre class="json">{{ pretty }}</pre>
          </el-collapse-item>
        </el-collapse>
      </DataCard>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown, Download, Refresh, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import { downloadResearchReport, getResearchTableTask } from '@/api/research.js'
import { formatDateTime } from '@/utils/datetime.js'

const route = useRoute()
const router = useRouter()
const task = ref(null)
const loading = ref(true)
const error = ref('')
const exporting = ref(false)

const result = computed(() => task.value?.result || {})
const model = computed(() => result.value.model || {})
const prediction = computed(() => result.value.prediction || {})
const clinicalOnly = computed(
  () => result.value.clinicalUse === false || result.value.admission?.clinicalUse === false
)
const explanation = computed(() => result.value.explanation || {})
const windowMonths = computed(() => result.value.predictionWindowMonths ?? 24)
const scopeLabel = computed(() => {
  const labels = {
    CONSTRUCTED_VALIDATION: '人工验证数据',
    CONTROLLED_RESEARCH: '受控研究数据'
  }
  return labels[result.value.dataScope] || result.value.dataScope || '—'
})
const subjectText = computed(() => {
  const subjectId = task.value?.subjectId
  return subjectId ? `受试者 #${subjectId}` : '研究验证结果'
})
const reviewLabel = computed(
  () =>
    ({
      PENDING: '待复核',
      CONFIRMED: '已确认',
      NEEDS_DATA: '需补充数据',
      REJECTED: '已驳回'
    })[task.value?.reviewStatus] || '—'
)

async function exportReport(format) {
  if (!task.value || exporting.value) return
  exporting.value = true
  try {
    const { blob, filename } = await downloadResearchReport(task.value.taskId, format)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
    ElMessage.success('报告已导出')
  } catch (problem) {
    ElMessage.error(problem?.message || '报告导出失败，请稍后重试')
  } finally {
    exporting.value = false
  }
}

const statusTitle = computed(() => {
  return {
    BLOCKED: '该快照被模型阻断，未产生风险分数',
    FAILED: '研究宿主返回失败',
    OUTCOME_UNKNOWN: '研究宿主结果未知'
  }[task.value?.status] || '结果不可用'
})
const statusDescription = computed(() => {
  return {
    BLOCKED: '请先补齐关键变量或处理质控问题后重新登记快照。',
    FAILED: '请查看任务错误码，修正后重试。',
    OUTCOME_UNKNOWN: '宿主超时或连接中断；可用同一快照重试，不会产生重复任务。'
  }[task.value?.status] || ''
})

const contributions = computed(() => {
  const groups = explanation.value.groups
  const values = explanation.value.contributions
  if (!Array.isArray(groups) || !Array.isArray(values)) return []
  // Python 返回 [[[ad, other], ...]]：外层是参考组批次，取第一组。
  const row = Array.isArray(values[0]) && Array.isArray(values[0][0]) ? values[0] : values
  return groups.map((group, index) => {
    const pair = Array.isArray(row[index]) ? row[index] : []
    return {
      group: typeof group === 'string' ? group : group?.label || group?.name || `分组 ${index + 1}`,
      ad: pair[0],
      other: pair[1]
    }
  })
})

const pretty = computed(() => JSON.stringify(result.value, null, 2))

function cif(value) {
  return typeof value === 'number' && Number.isFinite(value)
    ? `${(value * 100).toFixed(2)}%`
    : '—'
}

function signed(value) {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—'
  return `${value >= 0 ? '+' : ''}${value.toFixed(6)}`
}

function shortHash(value) {
  return typeof value === 'string' && value.length > 16
    ? `${value.slice(0, 12)}…${value.slice(-8)}`
    : value || '—'
}

function goSubject() {
  const subjectId = task.value?.subjectId
  if (subjectId) router.push({ path: '/subjects', query: { subjectId: String(subjectId) } })
}

async function load() {
  const taskId = Number(route.params.taskId)
  if (!Number.isInteger(taskId) || taskId <= 0) {
    error.value = '研究任务 ID 无效'
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    task.value = await getResearchTableTask(taskId)
  } catch (problem) {
    task.value = null
    error.value = problem?.message || '该研究结果不存在或当前账号无权查看'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.research-report {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.report-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
}

.report-eyebrow {
  margin: 0 0 4px;
  color: var(--ad-muted);
  font-size: 12px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.report-title {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 0;
  font-size: 22px;
}

.report-note {
  max-width: 760px;
  margin: 8px 0 0;
  color: var(--ad-muted);
  line-height: 1.7;
}

.report-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-top: 20px;
}

.report-stat {
  padding: 16px;
  border: 1px solid var(--ad-border);
  border-radius: 14px;
  background: var(--ad-surface);
}

.report-stat span {
  display: block;
  color: var(--ad-muted);
  font-size: 12px;
}

.report-stat strong {
  display: block;
  margin-top: 8px;
  font-size: 24px;
  font-variant-numeric: tabular-nums;
  line-height: 1.25;
  overflow-wrap: anywhere;
}

.report-grid--research .report-stat strong {
  color: var(--ad-muted);
  font-size: 16px;
  font-weight: 500;
}

.report-alert {
  margin-top: 16px;
}

.section-title {
  margin: 0 0 14px;
  font-size: 16px;
}

.section-note {
  margin: 12px 0 0;
  color: var(--ad-muted);
  font-size: 12px;
  line-height: 1.7;
}

.mono {
  font-family: var(--ad-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
  word-break: break-all;
}

.json {
  max-height: 420px;
  margin: 0;
  overflow: auto;
  padding: 12px;
  border-radius: 10px;
  background: var(--ad-surface-muted);
  font-size: 12px;
  line-height: 1.6;
}

@media (max-width: 900px) {
  .report-head {
    flex-direction: column;
  }

  .report-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
