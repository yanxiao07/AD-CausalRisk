<template>
  <div class="research-page">
    <PageHeader
      back
      title="研究证据"
      subtitle="证据目录只读；研究表格评估按受试者加载冻结快照，支持多选后批量提交并查看进度。"
    >
      <template #extra>
        <el-button v-if="canRegister" :icon="Plus" @click="openRegister">手动登记快照</el-button>
        <el-button :icon="Refresh" :loading="loading || refreshingBatch" @click="refreshAll">刷新全部</el-button>
      </template>
    </PageHeader>

    <el-tabs v-model="activeTab" class="tabs">
      <el-tab-pane label="证据目录" name="catalog">
        <EmptyState
          v-if="error"
          :icon="WarningFilled"
          title="研究证据加载失败"
          :description="error"
          action-text="重试"
          @action="load"
        />

        <el-skeleton v-else-if="loading && !catalog" :rows="5" animated />

        <template v-else-if="catalog">
          <DataCard title="证据版本与准入" padding="md">
            <div class="facts">
              <div><dt>证据版本</dt><dd>{{ catalog.evidenceVersion || '—' }}</dd></div>
              <div>
                <dt>产物目录</dt>
                <dd>{{ catalog.lookupConfigured ? '已配置' : '未配置（仅展示登记信息）' }}</dd>
              </div>
              <div><dt>预测准入</dt><dd>{{ admissionLabel(catalog.predictionAdmission) }}</dd></div>
              <div><dt>监测准入</dt><dd>{{ admissionLabel(catalog.monitoringAdmission) }}</dd></div>
            </div>

            <el-alert
              v-if="(catalog.limitations || []).length"
              class="note"
              type="warning"
              :closable="false"
              show-icon
              title="使用边界"
            >
              <ul class="note__list">
                <li v-for="item in catalog.limitations" :key="item">{{ item }}</li>
              </ul>
            </el-alert>
          </DataCard>

          <DataCard title="封存报告" subtitle="点击查看原文（Markdown 快照）" padding="none">
            <el-empty v-if="!(catalog.reports || []).length" description="没有登记的研究报告" />
            <el-table v-else :data="catalog.reports" class="table" row-key="key" @row-click="openReport">
              <el-table-column prop="title" label="标题" min-width="260" show-overflow-tooltip />
              <el-table-column prop="source" label="来源" min-width="240" show-overflow-tooltip />
              <el-table-column prop="sha256" label="SHA-256" min-width="180" show-overflow-tooltip />
              <el-table-column label="操作" width="110" fixed="right">
                <template #default="{ row }">
                  <el-button text type="primary" @click.stop="openReport(row)">查看</el-button>
                </template>
              </el-table-column>
            </el-table>
          </DataCard>

          <DataCard title="模型产物" :subtitle="`共 ${(catalog.models || []).length} 个登记产物`" padding="none">
            <el-table :data="catalog.models || []" class="table" row-key="id">
              <el-table-column prop="id" label="产物 ID" min-width="220" />
              <el-table-column prop="family" label="家族" min-width="160" />
              <el-table-column prop="relativePath" label="相对路径" min-width="320" show-overflow-tooltip />
              <el-table-column label="文件存在" width="110">
                <template #default="{ row }">
                  <el-tag :type="row.located ? 'success' : 'info'" size="small">
                    {{ row.located ? '是' : '否' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="admission" label="准入" min-width="220" />
            </el-table>
          </DataCard>
        </template>
      </el-tab-pane>

      <el-tab-pane label="研究表格评估" name="table">
        <div class="stack">
          <DataCard
            title="选择受试者"
            subtitle="快照按受试者授权查询；先选受试者，再选该受试者下已登记的冻结快照。"
          >
            <el-form label-width="110px" @submit.prevent>
              <el-form-item label="受试者" required>
                <el-select
                  v-model="tableSubjectId"
                  class="field subject-select"
                  filterable
                  remote
                  clearable
                  reserve-keyword
                  :remote-method="searchSubjects"
                  :loading="subjectLoading"
                  placeholder="输入档案编号或别名搜索"
                  no-data-text="没有匹配的受试者"
                >
                  <el-option
                    v-for="subject in subjectOptions"
                    :key="subject.id"
                    :label="subject.subjectCode"
                    :value="subject.id"
                  >
                    <span class="subject-option__code">{{ subject.subjectCode }}</span>
                    <span class="subject-option__alias">{{ subject.alias || '未命名' }}</span>
                  </el-option>
                </el-select>
              </el-form-item>
            </el-form>
            <p class="table-hint">
              研究表格评估只对研究员/管理员开放，且要求当前账号拥有该档案的原始数据权限。
            </p>
          </DataCard>

          <ResearchBatchAssessment v-if="validSubjectId" ref="batchRef" :subject-id="tableSubjectId" />
          <EmptyState
            v-else
            title="先选择受试者"
            description="可按档案编号或别名搜索；选中后会加载该受试者下当前账号获准评估的冻结快照，可多选后批量评估。"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-drawer
      v-model="drawerOpen"
      :title="activeReport?.title || '研究说明'"
      direction="rtl"
      size="760px"
      destroy-on-close
      :close-on-press-escape="true"
    >
      <el-skeleton v-if="reportLoading" :rows="8" animated />
      <template v-else-if="activeReport">
        <p class="meta">
          来源 {{ activeReport.source || '—' }} · SHA-256 {{ activeReport.sha256 || '—' }}
        </p>
        <div class="markdown-body" v-html="renderedMarkdown"></div>
      </template>
    </el-drawer>

    <el-dialog
      v-model="registerOpen"
      title="手动登记研究快照"
      width="760px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-form label-width="110px" @submit.prevent>
        <el-form-item label="受试者 ID" required>
          <el-input-number
            v-model="registerSubjectId"
            class="field"
            :min="1"
            :controls="false"
            placeholder="输入受试者 ID"
          />
        </el-form-item>
        <el-form-item label="索引日期" required>
          <el-date-picker
            v-model="registerIndexDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="YYYY-MM-DD"
            class="field"
          />
        </el-form-item>
        <el-form-item label="七变量数据（可留空）">
          <div class="feature-grid">
            <div v-for="field in featureFields" :key="field.key" class="feature-field">
              <span class="feature-field__label">{{ field.label }}</span>
              <el-input-number
                v-model="registerFeatures[field.key]"
                class="feature-field__input"
                :min="field.min"
                :max="field.max"
                :step="field.step"
                :precision="field.precision"
                :controls="false"
              />
              <span class="feature-field__unit">{{ field.unit }}</span>
            </div>
          </div>
          <p class="register-hint">
            留空字段会以 <code>NOT_COLLECTED</code> 记录缺失原因，不会补 0 或均值；缺失过多时模型可能返回 BLOCKED。
          </p>
        </el-form-item>
        <el-alert type="info" :closable="false" show-icon class="register-note">
          登记后系统会自动将快照同步至 Python 研究宿主。若同步失败，请在列表中点击重试。
        </el-alert>
        <p v-if="registerError" class="register-error">{{ registerError }}</p>
      </el-form>
      <template #footer>
        <el-button @click="registerOpen = false">取消</el-button>
        <el-button type="primary" :loading="registering" @click="submitRegister">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ResearchBatchAssessment from '@/components/assessment/ResearchBatchAssessment.vue'
import {
  getResearchEvidence,
  getResearchReport,
  registerResearchSnapshot
} from '@/api/research.js'
import { getSubjectDetail, listSubjects } from '@/api/subjects.js'
import { useUserStore } from '@/stores/user.js'
import { renderMarkdown } from '@/utils/markdown.js'
import { useEscapeToClose } from '@/composables/useEscapeToClose.js'

const catalog = ref(null)
const loading = ref(false)
const error = ref('')

const drawerOpen = ref(false)
const reportLoading = ref(false)
const activeReport = ref(null)

// ---- 研究表格评估 ----
const route = useRoute()
const activeTab = ref('catalog')
const tableSubjectId = ref(null)
const subjectOptions = ref([])
const subjectLoading = ref(false)
const refreshingBatch = ref(false)
const batchRef = ref(null)
let subjectSearchTimer = null

const userStore = useUserStore()
const canRegister = computed(() => userStore.hasRole(['ADMIN', 'RESEARCHER']))

const registerOpen = ref(false)
const registerSubjectId = ref(null)
const registerIndexDate = ref(new Date().toISOString().slice(0, 10))
const registerError = ref('')
const registering = ref(false)

const featureFields = [
  { key: 'age_years', label: '年龄', unit: '岁', min: 18, max: 120, step: 1, precision: 0 },
  { key: 'education_years', label: '受教育年限', unit: '年', min: 0, max: 30, step: 1, precision: 0 },
  { key: 'mmse', label: 'MMSE', unit: '分', min: 0, max: 30, step: 1, precision: 0 },
  { key: 'cdr_sb', label: 'CDR-SB', unit: '分', min: 0, max: 18, step: 0.5, precision: 1 },
  { key: 'cdr_global', label: 'CDR 全局', unit: '分', min: 0, max: 3, step: 0.5, precision: 1 },
  { key: 'adas__total13', label: 'ADAS-Cog13', unit: '分', min: 0, max: 85, step: 1, precision: 0 },
  { key: 'faq__faqtotal', label: 'FAQ 总分', unit: '分', min: 0, max: 30, step: 1, precision: 0 }
]

const registerFeatures = reactive(Object.fromEntries(featureFields.map((field) => [field.key, null])))

const validSubjectId = computed(() => {
  const value = Number(tableSubjectId.value)
  return Number.isInteger(value) && value > 0
})

let subjectSearchSeq = 0

function addSubjectOption(subject) {
  if (!subject?.id) return
  if (subjectOptions.value.some((item) => item.id === subject.id)) return
  subjectOptions.value = [subject, ...subjectOptions.value].slice(0, 30)
}

async function runSubjectSearch(query) {
  const seq = ++subjectSearchSeq
  subjectLoading.value = true
  try {
    const page = await listSubjects({ page: 1, pageSize: 20, keyword: String(query || '').trim() })
    if (seq !== subjectSearchSeq) return
    subjectOptions.value = Array.isArray(page?.items) ? page.items : []
  } catch {
    if (seq === subjectSearchSeq) subjectOptions.value = []
  } finally {
    if (seq === subjectSearchSeq) subjectLoading.value = false
  }
}

function searchSubjects(query) {
  clearTimeout(subjectSearchTimer)
  subjectSearchTimer = setTimeout(() => runSubjectSearch(query), 250)
}

async function loadSubjectOption(subjectId) {
  try {
    const subject = await getSubjectDetail(subjectId)
    addSubjectOption(subject)
  } catch {
    // Keep the raw id; the batch panel will report authorization problems.
  }
}

async function refreshAll() {
  refreshingBatch.value = true
  await Promise.allSettled([load(), batchRef.value?.reload()])
  refreshingBatch.value = false
}

function openRegister() {
  registerError.value = ''
  registerOpen.value = true
  registerSubjectId.value = validSubjectId.value ? Number(tableSubjectId.value) : null
  if (!registerIndexDate.value) {
    registerIndexDate.value = new Date().toISOString().slice(0, 10)
  }
}

async function submitRegister() {
  registerError.value = ''
  if (!registerSubjectId.value) {
    registerError.value = '请填写受试者 ID'
    return
  }
  if (!registerIndexDate.value) {
    registerError.value = '请选择索引日期'
    return
  }
  const missing = featureFields.filter((field) => registerFeatures[field.key] === null
    || registerFeatures[field.key] === undefined || Number.isNaN(registerFeatures[field.key]))
  if (missing.length === featureFields.length) {
    registerError.value = '至少填写一项真实变量；留空字段会按 NOT_COLLECTED 记录'
    return
  }
  registering.value = true
  try {
    const result = await registerResearchSnapshot({
      subjectId: registerSubjectId.value,
      indexDate: registerIndexDate.value,
      features: Object.fromEntries(featureFields.map((field) => [field.key, registerFeatures[field.key]]))
    })
    ElMessage.success(`已登记研究快照 #${result.snapshotId}`)
    registerOpen.value = false
    if (Number(tableSubjectId.value) === Number(registerSubjectId.value)) {
      batchRef.value?.reload()
    } else {
      tableSubjectId.value = Number(registerSubjectId.value)
    }
  } catch (error) {
    registerError.value =
      error?.httpStatus === 503
        ? '当前未配置研究宿主，请联系管理员配置研究宿主。'
        : error?.message || '登记失败'
  } finally {
    registering.value = false
  }
}

/** Markdown → 安全 HTML（工具已做转义，链接仅 http(s)） */
const renderedMarkdown = computed(() =>
  renderMarkdown(activeReport.value?.markdown || '（没有可显示的正文）')
)

function admissionLabel(value) {
  if (value === 'NO_GO_INDEPENDENT_EVIDENCE_INCOMPLETE') return '暂不纳入（个体证据不完整）'
  if (value === 'QUALIFIED_REFERENCE_AND_CURRENT_SNAPSHOTS_REQUIRED') {
    return '需参考与当前快照同时满足'
  }
  return value || '—'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    catalog.value = await getResearchEvidence()
  } catch (problem) {
    catalog.value = null
    error.value =
      problem?.code === 403 ? '当前账号无权查看研究证据' : problem?.message || '请稍后重试'
  } finally {
    loading.value = false
  }
}

async function openReport(row) {
  drawerOpen.value = true
  reportLoading.value = true
  activeReport.value = null
  try {
    activeReport.value = await getResearchReport(row.key)
  } catch (problem) {
    ElMessage.error(problem?.message || '研究说明读取失败')
    drawerOpen.value = false
  } finally {
    reportLoading.value = false
  }
}

useEscapeToClose(drawerOpen, () => {
  drawerOpen.value = false
})

onMounted(() => {
  load()
  searchSubjects('')
  const subjectId = Number(route.query.subjectId)
  if (Number.isInteger(subjectId) && subjectId > 0) {
    tableSubjectId.value = subjectId
    activeTab.value = 'table'
    loadSubjectOption(subjectId)
  } else if (route.query.tab === 'table') {
    activeTab.value = 'table'
  }
})
</script>

<style scoped>
.research-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}

.stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.field {
  max-width: 520px;
}

.subject-select {
  width: 100%;
}

.subject-option__code {
  font-family: var(--font-display);
  font-weight: 600;
  margin-right: 8px;
}

.subject-option__alias {
  color: var(--muted);
}

.table-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
}

.facts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px 20px;
  margin: 0;
}

.facts dt {
  font-size: 11px;
  letter-spacing: 0.4px;
  text-transform: uppercase;
  color: var(--soft);
}

.facts dd {
  margin: 2px 0 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
  font-family: var(--font-display);
}

.note {
  margin-top: 14px;
}

.note__list {
  margin: 6px 0 0;
  padding-left: 18px;
  line-height: 1.7;
}

.table {
  width: 100%;
}

.meta {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--soft);
  word-break: break-all;
}

.markdown-body {
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--bg-soft);
  font-size: 13px;
  line-height: 1.75;
  color: var(--ink-700);
  word-break: break-word;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 18px 0 8px;
  color: var(--ink);
  line-height: 1.4;
}

.markdown-body :deep(h1) {
  font-size: 20px;
}

.markdown-body :deep(h2) {
  font-size: 17px;
}

.markdown-body :deep(h3) {
  font-size: 15px;
}

.markdown-body :deep(h4) {
  font-size: 14px;
}

.markdown-body :deep(p) {
  margin: 8px 0;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 8px 0;
  padding-left: 20px;
}

.markdown-body :deep(li) {
  margin: 4px 0;
}

.markdown-body :deep(strong) {
  color: var(--ink);
}

.markdown-body :deep(code) {
  padding: 1px 5px;
  border-radius: 4px;
  background: rgba(10, 115, 173, 0.08);
  font-family: var(--font-display);
  font-size: 12px;
}

.markdown-body :deep(pre) {
  margin: 10px 0;
  padding: 12px 14px;
  overflow-x: auto;
  border-radius: var(--r-sm);
  background: #0f2533;
  color: #e6f4fb;
}

.markdown-body :deep(pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
}

.markdown-body :deep(blockquote) {
  margin: 10px 0;
  padding: 6px 14px;
  border-left: 3px solid var(--brand-600);
  color: var(--muted);
  background: rgba(10, 115, 173, 0.04);
}

.markdown-body :deep(hr) {
  margin: 16px 0;
  border: 0;
  border-top: 1px dashed var(--line-strong);
}

.markdown-body :deep(table) {
  width: 100%;
  margin: 10px 0;
  border-collapse: collapse;
  font-size: 12px;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  padding: 6px 10px;
  border: 1px solid var(--line);
  text-align: left;
  vertical-align: top;
}

.markdown-body :deep(th) {
  background: var(--brand-50);
  color: var(--ink);
  font-weight: 600;
}

.markdown-body :deep(a) {
  color: var(--brand-600);
  text-decoration: underline;
}

.mono :deep(textarea) {
  font-family: var(--font-mono, 'Cascadia Code', Consolas, monospace);
  font-size: 12px;
  line-height: 1.55;
}

.register-note {
  margin-top: 4px;
}

.register-hint {
  margin: 8px 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.register-hint code {
  padding: 1px 4px;
  border-radius: 4px;
  background: var(--surface-muted, #f2f5f8);
  font-family: var(--font-mono, 'Cascadia Code', Consolas, monospace);
}

.feature-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(220px, 1fr));
  gap: 10px 18px;
  width: 100%;
}

.feature-field {
  display: flex;
  align-items: center;
  gap: 8px;
}

.feature-field__label {
  width: 86px;
  flex: 0 0 auto;
  color: var(--ink);
  font-size: 13px;
}

.feature-field__input {
  flex: 1 1 auto;
}

.feature-field__unit {
  color: var(--muted);
  font-size: 12px;
}

.register-error {
  margin: 10px 0 0 110px;
  color: var(--danger, #c0392b);
  font-size: 12px;
}
</style>
