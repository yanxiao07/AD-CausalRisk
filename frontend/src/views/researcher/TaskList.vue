<template>
  <div class="task-page">
    <PageHeader back title="评估任务" subtitle="按时间倒序展示旧规则评估任务历史；研究模型评估在独立入口提交并回读。">
      <template #extra>
        <el-button :icon="Refresh" :loading="loading" @click="loadPage(page)">刷新</el-button>
        <el-button :icon="Download" :disabled="items.length === 0" @click="exportCsv">
          导出 CSV
        </el-button>
        <el-button v-if="canResearch" type="primary" :icon="Promotion" @click="openSubmit">
          研究模型评估
        </el-button>
      </template>
    </PageHeader>

    <DataCard padding="none">
      <div class="filter">
        <el-select
          v-model="statusFilter"
          class="filter__select"
          size="small"
          clearable
          placeholder="任务状态"
          @change="loadPage(1)"
        >
          <el-option label="已完成" value="COMPLETED" />
          <el-option label="失败" value="FAILED" />
          <el-option label="排队中" value="PENDING" />
          <el-option label="进行中" value="RUNNING" />
        </el-select>
        <el-input
          v-model="keywordFilter"
          class="filter__input"
          size="small"
          clearable
          placeholder="任务 ID / 关键词"
          @keyup.enter="applyFilters"
          @clear="applyFilters"
        />
        <el-input
          v-model="subjectFilter"
          class="filter__input filter__input--narrow"
          size="small"
          clearable
          placeholder="受试者 ID"
          @keyup.enter="applyFilters"
          @clear="applyFilters"
        />
        <el-button size="small" :icon="Search" @click="applyFilters">查询</el-button>
        <span class="filter__count">共 {{ total }} 条 · 当前页 {{ items.length }} 条</span>
      </div>

      <el-skeleton v-if="firstLoading" :rows="5" animated />

      <EmptyState
        v-else-if="loadError"
        :icon="WarningFilled"
        title="任务列表加载失败"
        :description="loadError"
        action-text="重试"
        @action="loadPage(page)"
      />

      <EmptyState
        v-else-if="items.length === 0"
        :icon="Cpu"
        :title="statusFilter ? '没有符合条件任务' : '还没有评估任务'"
        :description="
          statusFilter
            ? '换一个状态筛选，或清空筛选查看全部任务。'
            : '旧规则评估已停用；研究模型评估请使用右上角入口，或在受试者档案中发起。'
        "
      />

      <el-table v-else :data="items" class="table" row-key="taskId" @row-click="openTask" @keydown.enter="onRowKeydown">
        <el-table-column prop="taskId" label="任务 ID" width="110" />
        <el-table-column label="受试者" min-width="140">
          <template #default="{ row }">
            {{ row.subjectAlias || row.subjectCode || `#${row.subjectId}` }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <StatusTag :type="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="评估结果" width="120">
          <template #default="{ row }">
            <span v-if="row.assessmentId">#{{ row.assessmentId }}</span>
            <span v-else class="is-muted">未生成</span>
          </template>
        </el-table-column>
        <el-table-column label="错误" min-width="220">
          <template #default="{ row }">
            <span v-if="row.errorCode" class="is-error">
              {{ row.errorCode }}：{{ row.message || '—' }}
            </span>
            <span v-else class="is-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click.stop="openTask(row)">详情</el-button>
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

    <!-- 阶段详情：列表行不含 stages，打开时再拉单条 -->
    <el-drawer
      v-model="drawerOpen"
      :title="detail ? `任务 #${detail.taskId}` : '任务详情'"
      direction="rtl"
      size="620px"
      destroy-on-close
      :close-on-press-escape="true"
      @keydown.esc="drawerOpen = false"
    >
      <el-skeleton v-if="detailLoading" :rows="8" animated />
      <template v-else-if="detail">
        <dl class="facts">
          <div><dt>受试者</dt><dd>#{{ detail.subjectId }}</dd></div>
          <div><dt>状态</dt><dd><StatusTag :type="detail.status" /></dd></div>
          <div><dt>runId</dt><dd>{{ detail.runId || '—' }}</dd></div>
          <div><dt>评估结果</dt><dd>{{ detail.assessmentId ? `#${detail.assessmentId}` : '未生成' }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ formatTime(detail.createdAt) }}</dd></div>
          <div><dt>更新时间</dt><dd>{{ formatTime(detail.updatedAt) }}</dd></div>
          <div v-if="detail.errorCode" class="facts__wide">
            <dt>错误</dt><dd class="is-error">{{ detail.errorCode }}：{{ detail.message || '—' }}</dd>
          </div>
        </dl>

        <el-table v-if="detail.stages?.length" :data="detail.stages" class="table" size="small">
          <el-table-column label="阶段" width="160">
            <template #default="{ row }">{{ stageLabel(row.stage) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <StatusTag :type="row.status" />
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="120">
            <template #default="{ row }">{{ Math.round(row.durationMs) }} ms</template>
          </el-table-column>
          <el-table-column label="摘要" min-width="220">
            <template #default="{ row }">{{ summarize(row.summary) }}</template>
          </el-table-column>
        </el-table>
        <EmptyState
          v-else
          :icon="Cpu"
          title="还没有阶段数据"
          description="任务仍在排队或运行时，stages 可能为空。"
        />

        <section class="snapshot">
          <div class="snapshot__head">
            <h3 class="snapshot__title">输入快照</h3>
            <el-button
              v-if="inputSnapshot?.available"
              text
              size="small"
              @click="showSnapshotJson = !showSnapshotJson"
            >
              {{ showSnapshotJson ? '收起完整 JSON' : '查看完整 JSON' }}
            </el-button>
          </div>
          <el-skeleton v-if="inputLoading" :rows="3" animated />
          <el-alert
            v-else-if="inputError"
            :title="inputError"
            type="warning"
            :closable="false"
            show-icon
          />
          <template v-else-if="inputSnapshot?.available">
            <dl class="facts">
              <div><dt>数据版本</dt><dd>{{ inputSnapshot.dataVersion || '—' }}</dd></div>
              <div><dt>采集时间</dt><dd>{{ formatTime(inputSnapshot.capturedAt) }}</dd></div>
              <div>
                <dt>质控记录</dt>
                <dd>{{ inputSnapshot.qualityCheckId ? `#${inputSnapshot.qualityCheckId}` : '—' }}</dd>
              </div>
              <div>
                <dt>预测窗口</dt>
                <dd>{{ inputSnapshot.request?.predictionWindowMonths ?? '—' }} 个月</dd>
              </div>
              <div>
                <dt>访视 / 特征</dt>
                <dd>
                  {{ inputSnapshot.request?.visits?.length ?? 0 }} /
                  {{ inputSnapshot.request?.features?.length ?? 0 }} 条
                </dd>
              </div>
              <div class="facts__wide">
                <dt>输入指纹</dt>
                <dd class="mono">{{ inputSnapshot.inputDigest || '—' }}</dd>
              </div>
            </dl>
            <pre v-if="showSnapshotJson" class="snapshot__json">{{ snapshotJson }}</pre>
          </template>
          <p v-else class="snapshot__empty">
            {{ inputSnapshot?.message || '该任务没有保存可回看的输入快照（旧规则任务或任务尚未运行）。' }}
          </p>
        </section>

        <div class="actions">
          <el-button :disabled="!detail.assessmentId" @click="goReport">查看报告</el-button>
          <el-button type="primary" :disabled="!detail.assessmentId" @click="goReview">去复核</el-button>
          <el-button
            v-if="canCancel"
            type="danger"
            plain
            :loading="cancelling"
            @click="cancelTask"
          >
            取消任务
          </el-button>
        </div>
      </template>
    </el-drawer>

    <!-- 研究模型评估：POST /api/research/table-assessment-tasks（同步返回并落库） -->
    <el-dialog
      v-model="submitOpen"
      title="研究模型评估"
      width="560px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form label-width="96px" @submit.prevent>
        <el-form-item label="受试者 ID" required>
          <el-input
            v-model="submitSubjectId"
            placeholder="输入受试者 ID，例如 22（可在档案页地址栏看到）"
            clearable
          />
        </el-form-item>
      </el-form>
      <p class="submit-hint">
        研究评估任务不进入旧规则任务列表；提交与回读都在下方卡片内完成。旧规则评分通道已停用。
      </p>
      <ResearchAssessmentPanel
        v-if="validSubmitSubjectId"
        :subject-id="submitSubjectId"
        @completed="onResearchCompleted"
      />
      <el-empty v-else description="先输入受试者 ID" :image-size="72" />
      <template #footer>
        <el-button @click="submitOpen = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Cpu, Download, Promotion, Refresh, Search, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ResearchAssessmentPanel from '@/components/assessment/ResearchAssessmentPanel.vue'
import { cancelAssessmentTask, getAssessmentTask, getAssessmentTaskInput, listAssessmentTasks } from '@/api/assessments.js'
import { downloadCsv } from '@/utils/csv.js'
import { useTableKeyboard } from '@/composables/useTableKeyboard.js'
import { useEscapeToClose } from '@/composables/useEscapeToClose.js'
import { useUserStore } from '@/stores/user.js'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const items = ref([])
/** 服务端状态筛选（后端 /api/assessment-tasks 支持 status） */
const statusFilter = ref('')
/** 服务端关键词与受试者筛选（契约支持 keyword / subjectId） */
const keywordFilter = ref('')
const subjectFilter = ref('')

const loading = ref(false)
const firstLoading = ref(true)
const loadError = ref('')

const drawerOpen = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const cancelling = ref(false)
const inputSnapshot = ref(null)
const inputLoading = ref(false)
const inputError = ref('')
const showSnapshotJson = ref(false)
/** 后端只允许取消 PENDING 任务（RUNNING 不可取消） */
const canCancel = computed(() => detail.value?.status === 'PENDING')

// ---- 研究模型评估（POST /api/research/table-assessment-tasks） ----
const canResearch = computed(() => userStore.hasRole(['ADMIN', 'RESEARCHER']))
const submitOpen = ref(false)
const submitSubjectId = ref('')
const validSubmitSubjectId = computed(() => {
  const value = Number(submitSubjectId.value)
  return Number.isInteger(value) && value > 0
})

function openSubmit() {
  submitSubjectId.value = ''
  submitOpen.value = true
}

function onResearchCompleted(task) {
  ElMessage.success(`研究任务 #${task?.taskId ?? ''} 已完成`)
}

/** 列表请求序号：翻页竞态时只认最新一次响应 */
let requestSeq = 0
/** 详情请求序号：先点 11 再点 22 时，迟到的 11 不能覆盖 22 */
let detailSeq = 0

const STAGE_LABELS = {
  DATA_FUSION: '数据融合',
  CAUSAL_GRAPH: '因果图谱',
  WARNING_MODEL: '预警模型',
  EXPLANATION: '个体解释'
}

const stageLabel = (key) => STAGE_LABELS[key] || key

const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit'
})

function formatTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : timeFormatter.format(date)
}

function summarize(summary) {
  if (!summary || typeof summary !== 'object') return '—'
  const entries = Object.entries(summary).slice(0, 3)
  if (!entries.length) return '—'
  return entries.map(([key, value]) => `${key}=${value}`).join(' · ')
}

async function loadPage(target = page.value) {
  const seq = ++requestSeq
  loading.value = true
  loadError.value = ''
  try {
    const data = await listAssessmentTasks({
      page: target,
      pageSize: pageSize.value,
      status: statusFilter.value,
      keyword: keywordFilter.value.trim(),
      subjectId: subjectFilter.value.trim()
    })
    if (seq !== requestSeq) return
    items.value = Array.isArray(data?.items) ? data.items : []
    total.value = Number(data?.total ?? items.value.length)
    page.value = Number(data?.page ?? target)
    if (items.value.length === 0 && total.value > 0 && page.value > 1) {
      page.value -= 1
      await loadPage(page.value)
    }
  } catch (problem) {
    if (seq !== requestSeq) return
    items.value = []
    total.value = 0
    loadError.value = problem?.message || '请稍后重试'
  } finally {
    if (seq === requestSeq) {
      loading.value = false
      firstLoading.value = false
    }
  }
}

async function openTask(row) {
  const seq = ++detailSeq
  // 先清空：新的一行在加载时不应继续显示上一条的 runId / 跳转目标
  detail.value = null
  inputSnapshot.value = null
  inputError.value = ''
  showSnapshotJson.value = false
  detailLoading.value = true
  drawerOpen.value = true
  // 打开的任务写入 URL，返回时可以直接恢复这个抽屉
  router.replace({ query: { ...route.query, taskId: String(row.taskId) } })
  loadInputSnapshot(row.taskId, seq)
  try {
    const data = await getAssessmentTask(row.taskId)
    // 迟到的响应（用户已经点了别的任务）必须丢掉，否则抽屉内容和按钮会指向旧任务
    if (seq !== detailSeq) return
    detail.value = data
  } catch {
    if (seq !== detailSeq) return
    // request.js 已提示；保留空详情
    detail.value = null
  } finally {
    if (seq === detailSeq) detailLoading.value = false
  }
}

async function loadInputSnapshot(taskId, seq) {
  inputLoading.value = true
  inputError.value = ''
  try {
    const data = await getAssessmentTaskInput(taskId)
    if (seq !== detailSeq) return
    inputSnapshot.value = data
  } catch {
    if (seq !== detailSeq) return
    inputSnapshot.value = null
    inputError.value = '输入快照读取失败；任务详情仍可查看。'
  } finally {
    if (seq === detailSeq) inputLoading.value = false
  }
}

const snapshotJson = computed(() => JSON.stringify(inputSnapshot.value, null, 2))

/** 关键词 / 受试者筛选都从第 1 页重新查询 */
function applyFilters() {
  page.value = 1
  loadPage(1)
}

function goReport() {
  router.push(`/reports/${detail.value.assessmentId}`)
}

/** 取消排队中的任务：成功后刷新详情与列表。 */
async function cancelTask() {
  if (!detail.value?.taskId || cancelling.value) return
  try {
    await ElMessageBox.confirm('取消后该任务不会产生评估结果，确定取消吗？', '取消任务', {
      confirmButtonText: '取消任务',
      cancelButtonText: '返回',
      type: 'warning'
    })
  } catch {
    return
  }
  cancelling.value = true
  try {
    detail.value = await cancelAssessmentTask(detail.value.taskId)
    ElMessage.success('任务已取消')
    await loadPage(page.value)
  } catch {
    // request.js 已提示（例如任务已在运行）
  } finally {
    cancelling.value = false
  }
}

const { onRowKeydown } = useTableKeyboard(items, openTask)

useEscapeToClose(drawerOpen, () => {
  detail.value = null
  drawerOpen.value = false
})

// 关闭抽屉时清掉 URL 上的 taskId，避免"看起来关掉了、返回又自己弹出来"
watch(drawerOpen, (open) => {
  if (open || !route.query.taskId) return
  const query = { ...route.query }
  delete query.taskId
  router.replace({ query })
})

function exportCsv() {
  const rows = items.value.map((item) => [
    item.taskId,
    item.subjectId,
    item.status,
    item.assessmentId ?? '',
    item.runId || '',
    item.errorCode || '',
    item.message || '',
    formatTime(item.createdAt)
  ])
  downloadCsv(
    `评估任务-${new Date().toISOString().slice(0, 10)}.csv`,
    ['任务ID', '受试者ID', '状态', '评估ID', 'runId', '错误码', '错误信息', '创建时间'],
    rows
  )
}

function goReview() {
  router.push({ path: '/reviews', query: { assessmentId: detail.value.assessmentId } })
}

onMounted(async () => {
  await loadPage(1)
  // 深链：从批量面板/其他地方带 taskId 过来，直接打开详情
  const fromQuery = Number(route.query.taskId)
  if (Number.isInteger(fromQuery) && fromQuery > 0) {
    openTask({ taskId: fromQuery })
  }
})
</script>

<style scoped>
.task-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.filter {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 12px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.filter__select {
  width: 160px;
}

.filter__input {
  width: 210px;
}

.filter__input--narrow {
  width: 130px;
}

.filter__count {
  margin-left: auto;
  font-size: 12px;
  color: var(--muted);
}

.table {
  width: 100%;
}

.is-muted {
  color: var(--soft);
}

.is-error {
  color: var(--danger);
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 14px 18px;
  border-top: 1px solid var(--line);
}

.facts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 10px 18px;
  margin: 0 0 16px;
}

.facts > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.facts__wide {
  grid-column: 1 / -1;
}

.facts dt {
  font-size: 11px;
  letter-spacing: 0.4px;
  text-transform: uppercase;
  color: var(--soft);
}

.facts dd {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
  font-family: var(--font-display);
}

.submit-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
}

.snapshot {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px dashed var(--line);
}

.snapshot__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.snapshot__title {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink);
}

.snapshot__empty {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--muted);
}

.snapshot__json {
  max-height: 320px;
  margin: 12px 0 0;
  padding: 12px;
  overflow: auto;
  border-radius: 10px;
  background: var(--surface-sunken, #f6f7f9);
  font-size: 11px;
  line-height: 1.6;
}

.mono {
  font-family: var(--font-display);
  font-size: 12px;
  word-break: break-all;
}

.actions {
  display: flex;
  gap: 10px;
  margin-top: 18px;
}
</style>
