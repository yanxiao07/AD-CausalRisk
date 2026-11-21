<template>
  <div class="research-report">
    <PageHeader
      back
      :title="isDoctor ? '结果与依据' : '评估报告'"
      :subtitle="task ? `报告 #${task.taskId} · ${formatDateTime(task.createdAt)}` : ''"
      :terms="['source', 'dataVersion', 'review', 'cif']"
    >
      <template #extra>
        <el-dropdown :disabled="!task || exporting" @command="exportReport">
          <el-button :icon="Download" :disabled="!task">
            {{ isDoctor ? '导出研究报告' : '导出报告' }}
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
            <h2 class="report-title">
              {{ subjectText }}
              <StatusTag :type="task.status" />
            </h2>
            <p v-if="task.status === 'COMPLETED'" class="report-review">
              复核状态：{{ reviewLabel }}
              <template v-if="task.reviewComment"> · {{ task.reviewComment }}</template>
            </p>
          </div>
          <div class="report-actions">
            <el-button type="primary" plain @click="goSubject">打开档案</el-button>
            <el-button v-if="canArrangeFollowUp" @click="followUpOpen = true">安排复诊</el-button>
            <el-button v-if="isDoctor && task.status === 'COMPLETED' && task.reviewStatus === 'PENDING'"
              type="primary" @click="router.push({path:'/reviews', query:{taskId:task.taskId,dataScope:task.dataScope || result.dataScope}})">
              去复核
            </el-button>
          </div>
        </div>

        <dl v-if="isDoctor" class="care-summary">
          <div><dt>评估访视日期</dt><dd>{{ result.indexDate || '缺少访视日期' }}</dd></div>
          <div>
            <dt>资料类型</dt>
            <dd>{{ scopeLabel }}</dd>
          </div>
          <div><dt>资料版本</dt><dd>{{ result.dataVersion || '未返回' }}</dd></div>
          <div><dt>来源核对</dt><dd>{{ result.sourceAuthenticityCertified === true ? '已核证' : '本次结果未提供来源核证' }}</dd></div>
          <div><dt>量表完整性</dt><dd>{{ missingInputs.length ? `需核对 ${missingInputs.join('、')}` : result.admission ? '本次快照未报告缺项' : '暂无核验回执' }}</dd></div>
          <div>
            <dt>下一步</dt>
            <dd>{{ doctorNextStep }}</dd>
          </div>
        </dl>

        <el-alert
          v-if="clinicalOnly && task.status === 'COMPLETED'"
          class="report-alert"
          type="warning"
          :closable="false"
          show-icon
          title="评估结果仅供研究参考"
          description="请结合已有访视资料进行研究复核。模型估计仅对应报告所列的资料和时间窗。"
        />

        <el-alert
          v-if="task.status !== 'COMPLETED'"
          class="report-alert"
          :type="task.status === 'BLOCKED' ? 'warning' : 'error'"
          :closable="false"
          show-icon
          :title="statusTitle"
          :description="isDoctor ? statusDescription : task.errorCode || statusDescription"
        />
        <p v-if="!isDoctor && task.status !== 'COMPLETED'" class="section-note">资料类型 · {{ scopeLabel }}</p>
        <el-table v-if="admissionIssues.length" :data="admissionIssues" size="small">
          <el-table-column label="资料">
            <template #default="{ row }">{{ featureLabel(row.feature) }}</template>
          </el-table-column>
          <el-table-column label="资料问题">
            <template #default="{ row }">{{ issueLabel(row.code) }}</template>
          </el-table-column>
          <el-table-column label="级别">
            <template #default="{ row }">{{ row.severity === 'BLOCK' ? '阻断' : '提示' }}</template>
          </el-table-column>
        </el-table>
        <ResearchAdmissionGuidance v-if="task.status === 'BLOCKED' && task.dataScope === CONTROLLED_RESEARCH"
          :issues="admissionIssues" />
      </DataCard>

      <DoctorSupplementPanel
        v-if="isDoctor && (task.status === 'BLOCKED' || task.reviewStatus === 'NEEDS_DATA')"
        :task="task"
      />

      <PatientPublication v-if="canArrangeFollowUp && task.reviewStatus === 'CONFIRMED'" :task-id="task.taskId" :key="task.taskId" />

      <DataCard v-if="task.status === 'COMPLETED'" padding="lg">
        <details :open="resultExpanded" class="report-evidence" @toggle="resultExpanded = $event.currentTarget.open">
          <summary>结果解读<span v-if="isDoctor"> · 研究模型详情</span></summary>
          <template v-if="resultExpanded">
            <p class="section-note">下列百分比为模型对 {{ windowMonths }} 个月累计发生率的估计，仅供研究参考。</p>
            <div class="report-grid" :class="{ 'report-grid--research': clinicalOnly }">
              <div class="report-stat">
                <span>阿尔茨海默病</span>
                <strong>{{ cif(prediction.adCif24m) }}</strong>
              </div>
              <div class="report-stat">
                <span>其他痴呆</span>
                <strong>{{ cif(prediction.otherDementiaCif24m) }}</strong>
              </div>
              <div class="report-stat">
                <span><TermHelp name="window" /></span>
                <strong>{{ windowMonths }} 个月</strong>
              </div>
              <div class="report-stat">
                <span>数据范围</span>
                <strong>{{ scopeLabel }}</strong>
              </div>
            </div>
            <template v-if="contributions.length">
              <h3 class="section-title">本次预测的<TermHelp name="attribution" label="分组贡献" /></h3>
              <ResearchAttributionGraph :contributions="contributions" />
              <el-table :data="contributions" size="small">
                <el-table-column label="因素" min-width="180">
                  <template #default="{ row }">{{ featureLabel(row.group) }}</template>
                </el-table-column>
                <el-table-column label="对阿尔茨海默病估计的影响" min-width="150">
                  <template #default="{ row }">{{ signed(row.ad) }}</template>
                </el-table-column>
                <el-table-column label="对其他痴呆估计的影响" min-width="150">
                  <template #default="{ row }">{{ signed(row.other) }}</template>
                </el-table-column>
              </el-table>
            </template>
          </template>
        </details>
      </DataCard>

      <FollowUpDialog
        v-if="canArrangeFollowUp"
        v-model="followUpOpen"
        :subject-id="task.subjectId"
      />

      <DataCard padding="lg">
        <el-collapse v-model="technicalSections">
          <el-collapse-item title="模型与技术详情" name="technical">
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
          <p v-if="contributions.length" class="section-note">
          方法：{{ explanation.method || '—' }}；重构误差 {{ explanation.reconstructionMaxError ?? '—' }}。
          缺失值影响已解释：{{ explanation.missingnessEffectsExplained === true ? '是' : '否' }}。
          </p>
          </el-collapse-item>
          <el-collapse-item title="查看完整结构化结果" name="json">
            <pre class="json">{{ pretty }}</pre>
          </el-collapse-item>
        </el-collapse>
      </DataCard>
    </template>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown, Download, Refresh, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import TermHelp from '@/components/common/TermHelp.vue'
import DataCard from '@/components/common/DataCard.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import { downloadResearchReport, getResearchReportTask } from '@/api/research.js'
import { getSubjectCapabilities } from '@/api/subjects.js'
import ResearchAttributionGraph from '@/components/assessment/ResearchAttributionGraph.vue'
import ResearchAdmissionGuidance from '@/components/assessment/ResearchAdmissionGuidance.vue'
import FollowUpDialog from '@/components/followup/FollowUpDialog.vue'
import DoctorSupplementPanel from '@/components/assessment/DoctorSupplementPanel.vue'
import PatientPublication from '@/components/assessment/PatientPublication.vue'
import {
  CONTROLLED_RESEARCH, modelContributions, researchFeatureLabel as featureLabel, researchIssueLabel as issueLabel,
  researchScopeLabel, signedContribution as signed
} from '@/utils/researchResults.js'
import { formatDateTime } from '@/utils/datetime.js'
import { useUserStore } from '@/stores/user.js'

const route = useRoute()
const router = useRouter()
const isDoctor = useUserStore().primaryRole === 'DOCTOR'
const technicalSections = ref(isDoctor ? [] : ['technical'])
const resultExpanded = ref(!isDoctor)
const followUpOpen = ref(false)
const canArrangeFollowUp = ref(false)
const task = ref(null)
const loading = ref(true)
const error = ref('')
const exporting = ref(false)
let requestId = 0

const result = computed(() => task.value?.result || {})
const admissionIssues = computed(() => task.value?.status === 'BLOCKED' ? result.value.admission?.issues || [] : [])
const missingInputs = computed(() => Object.entries(result.value.admission?.missingFeatureCounts || {})
  .filter(([, count]) => count > 0).map(([key]) => featureLabel(key)))
const model = computed(() => result.value.model || {})
const prediction = computed(() => result.value.prediction || {})
const clinicalOnly = computed(
  () => result.value.clinicalUse === false || result.value.admission?.clinicalUse === false
)
const explanation = computed(() => result.value.explanation || {})
const windowMonths = computed(() => result.value.predictionWindowMonths ?? 24)
const scopeLabel = computed(() => researchScopeLabel(task.value?.dataScope || result.value.dataScope))
const subjectText = computed(() => {
  return [task.value?.subjectAlias, task.value?.subjectCode].filter(Boolean).join(' · ') || '评估结果'
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

const doctorNextStep = computed(() => {
  if (task.value?.status === 'BLOCKED') {
    return '请研究员核对下方资料问题与原始来源。需要复诊时，可先安排复诊。'
  }
  if (task.value?.status !== 'COMPLETED') {
    return '请研究员核对评估任务状态。复诊安排可以继续处理。'
  }
  return ({
    PENDING: '核对患者档案和本次评估依据，随后记录复核意见。',
    NEEDS_DATA: '按已记录的复核意见，与研究员确认需要补充的资料。',
    CONFIRMED: '复核已记录。可以另行发布患者说明或安排复诊。',
    REJECTED: '请查看复核意见。如有需要，可安排复诊。'
  })[task.value.reviewStatus] || '请查看患者档案。'
})

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
    BLOCKED: '资料核验未通过，本次未运行模型预测',
    FAILED: '研究宿主返回失败',
    OUTCOME_UNKNOWN: '研究宿主结果未知'
  }[task.value?.status] || '结果不可用'
})
const statusDescription = computed(() => {
  return {
    BLOCKED: '下表列出本次核验发现的问题。',
    FAILED: isDoctor ? '请研究员核对失败原因。' : '请查看任务错误码，修正后重试。',
    OUTCOME_UNKNOWN: isDoctor ? '请研究员查看原任务是否已经返回结果。' : '宿主超时或连接中断，请先查询原任务，再按原快照重试。'
  }[task.value?.status] || ''
})

const contributions = computed(() => modelContributions(task.value))

const pretty = computed(() => JSON.stringify(result.value, null, 2))

function cif(value) {
  return typeof value === 'number' && Number.isFinite(value)
    ? `${(value * 100).toFixed(2)}%`
    : '—'
}

function shortHash(value) {
  return typeof value === 'string' && value.length > 16
    ? `${value.slice(0, 12)}…${value.slice(-8)}`
    : value || '—'
}

function goSubject() {
  const subjectId = task.value?.subjectId
  if (subjectId) {
    router.push({ path: '/subjects', query: {
      subjectId: String(subjectId),
      section: isDoctor ? 'research' : undefined,
      dataScope: isDoctor ? undefined : task.value.dataScope || result.value.dataScope
    } })
  }
}

async function load() {
  const sequence = ++requestId
  const taskId = Number(route.params.taskId)
  followUpOpen.value = false
  canArrangeFollowUp.value = false
  resultExpanded.value = !isDoctor
  task.value = null
  if (!Number.isInteger(taskId) || taskId <= 0) {
    error.value = '研究任务 ID 无效'
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    const response = await getResearchReportTask(taskId)
    if (sequence !== requestId) return
    task.value = response
    if (isDoctor && response?.subjectId) {
      const capabilities = await getSubjectCapabilities(response.subjectId).catch(() => null)
      if (sequence === requestId) canArrangeFollowUp.value = capabilities?.writeClinical === true
    }
  } catch (problem) {
    if (sequence === requestId) error.value = problem?.message || '该研究结果不存在或当前账号无权查看'
  } finally {
    if (sequence === requestId) loading.value = false
  }
}

watch(() => route.params.taskId, load, { immediate: true })
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

.report-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.report-actions .el-button {
  margin: 0;
}

.care-summary {
  display: grid;
  gap: 12px;
  margin: 16px 0;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  line-height: 1.6;
}

.care-summary dt {
  font-weight: 700;
}

.care-summary dd {
  margin: 0;
}

.report-evidence summary {
  padding: 4px 0;
  font-weight: 700;
  cursor: pointer;
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
