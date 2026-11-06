<template>
  <div class="task-page">
    <PageHeader back title="评估任务" subtitle="按时间倒序展示评估任务历史；点击任意一行查看阶段耗时。">
      <template #extra>
        <el-button :icon="Refresh" :loading="loading" @click="loadPage(page)">刷新</el-button>
      </template>
    </PageHeader>

    <DataCard padding="none">
      <div class="filter">
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
        title="还没有评估任务"
        description="在受试者档案里发起评估后，任务历史会出现在这里。"
      />

      <el-table v-else :data="items" class="table" row-key="taskId" @row-click="openTask">
        <el-table-column prop="taskId" label="任务 ID" width="110" />
        <el-table-column prop="subjectId" label="受试者" width="110" />
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

        <div class="actions">
          <el-button :disabled="!detail.assessmentId" @click="goReport">查看报告</el-button>
          <el-button type="primary" :disabled="!detail.assessmentId" @click="goReview">去复核</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Cpu, Refresh, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { getAssessmentTask, listAssessmentTasks } from '@/api/assessments.js'

const route = useRoute()
const router = useRouter()

const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const items = ref([])

const loading = ref(false)
const firstLoading = ref(true)
const loadError = ref('')

const drawerOpen = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

/** 列表请求序号：翻页竞态时只认最新一次响应 */
let requestSeq = 0

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
    const data = await listAssessmentTasks({ page: target, pageSize: pageSize.value })
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
  detailLoading.value = true
  drawerOpen.value = true
  try {
    detail.value = await getAssessmentTask(row.taskId)
  } catch {
    // request.js 已提示；保留空详情
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

function goReport() {
  router.push({ path: '/reports', query: { assessmentId: detail.value.assessmentId } })
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
  justify-content: space-between;
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

.actions {
  display: flex;
  gap: 10px;
  margin-top: 18px;
}
</style>
