<template>
  <div class="task-page">
    <PageHeader title="评估任务" subtitle="按任务 ID 查看四阶段进度与耗时。">
      <template #extra>
        <el-input
          v-model="inputId"
          class="id-input"
          placeholder="输入任务 ID"
          clearable
          @keyup.enter="load"
        />
        <el-button type="primary" :loading="loading" @click="load">查询</el-button>
        <el-button :icon="Refresh" :disabled="!task" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <el-alert
      class="note"
      type="info"
      :closable="false"
      show-icon
      title="后端暂未提供任务列表接口"
      description="契约只有 POST /api/assessment-tasks 与 GET /api/assessment-tasks/{id}，所以这里按任务 ID 查询；批量评估的进度请用「受试者档案」页右上角的进度面板。"
    />

    <EmptyState
      v-if="!task"
      :icon="Cpu"
      title="还没有查询任务"
      description="输入任务 ID 后查看状态与四个阶段的耗时。"
    />

    <template v-else>
      <DataCard :title="`任务 #${task.taskId}`" :subtitle="`受试者 #${task.subjectId}`">
        <template #header-extra>
          <StatusTag :type="task.status" />
        </template>

        <dl class="facts">
          <div><dt>runId</dt><dd>{{ task.runId || '—' }}</dd></div>
          <div><dt>评估结果</dt><dd>{{ task.assessmentId ? `#${task.assessmentId}` : '未生成' }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ formatTime(task.createdAt) }}</dd></div>
          <div><dt>更新时间</dt><dd>{{ formatTime(task.updatedAt) }}</dd></div>
          <div v-if="task.errorCode" class="facts__wide">
            <dt>错误</dt><dd class="is-error">{{ task.errorCode }}：{{ task.message || '—' }}</dd>
          </div>
        </dl>

        <el-table v-if="task.stages?.length" :data="task.stages" class="table" size="small">
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

        <template #footer>
          <div class="actions">
            <el-button :disabled="!task.assessmentId" @click="goReport">查看报告</el-button>
            <el-button type="primary" :disabled="!task.assessmentId" @click="goReview">去复核</el-button>
          </div>
        </template>
      </DataCard>
    </template>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Cpu, Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { getAssessmentTask } from '@/api/assessments.js'

const route = useRoute()
const router = useRouter()

const inputId = ref('')
const loading = ref(false)
const task = ref(null)

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

async function load() {
  const id = Number(inputId.value)
  if (!Number.isInteger(id) || id <= 0) {
    ElMessage.warning('请输入有效的任务 ID')
    return
  }
  loading.value = true
  try {
    task.value = await getAssessmentTask(id)
  } catch {
    // request.js 已提示
  } finally {
    loading.value = false
  }
}

function goReport() {
  router.push({ path: '/reports', query: { assessmentId: task.value.assessmentId } })
}

function goReview() {
  router.push({ path: '/reviews', query: { assessmentId: task.value.assessmentId } })
}

onMounted(() => {
  const fromQuery = route.query.taskId
  if (fromQuery) {
    inputId.value = String(fromQuery)
    load()
  }
})
</script>

<style scoped>
.task-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.id-input {
  width: 160px;
}

.note {
  border-radius: var(--r-md);
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

.facts dd.is-error {
  color: var(--danger);
}

.table {
  width: 100%;
}

.actions {
  display: flex;
  gap: 10px;
}
</style>
