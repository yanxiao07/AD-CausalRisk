<template>
  <div class="research-page">
    <PageHeader
      back
      title="研究证据"
      subtitle="证据目录只读；研究表格评估按受试者加载冻结快照，支持多选后批量提交并查看进度。"
    >
      <template #extra>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
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
              <el-form-item label="受试者 ID" required>
                <el-input-number
                  v-model="tableSubjectId"
                  class="field"
                  :min="1"
                  :controls="false"
                  placeholder="输入受试者 ID（正整数）"
                />
              </el-form-item>
            </el-form>
            <p class="table-hint">
              研究表格评估只对研究员/管理员开放，且要求当前账号拥有该档案的原始数据权限。
            </p>
          </DataCard>

          <ResearchBatchAssessment v-if="validSubjectId" :subject-id="tableSubjectId" />
          <EmptyState
            v-else
            title="先输入受试者 ID"
            description="输入后会加载该受试者下当前账号获准评估的冻结快照，可多选后批量评估。"
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
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ResearchBatchAssessment from '@/components/assessment/ResearchBatchAssessment.vue'
import { getResearchEvidence, getResearchReport } from '@/api/research.js'
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

const validSubjectId = computed(() => {
  const value = Number(tableSubjectId.value)
  return Number.isInteger(value) && value > 0
})

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
  const subjectId = Number(route.query.subjectId)
  if (Number.isInteger(subjectId) && subjectId > 0) {
    tableSubjectId.value = subjectId
    activeTab.value = 'table'
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
</style>
