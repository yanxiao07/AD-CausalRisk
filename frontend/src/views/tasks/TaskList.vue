<template>
  <div class="page">
    <!-- 毛玻璃背景装饰 -->
    <div class="glass-bg">
      <div class="orb orb-1"></div>
      <div class="orb orb-2"></div>
      <div class="orb orb-3"></div>
    </div>

    <!-- 统计卡片 -->
    <a-row :gutter="16" class="stat-row">
      <a-col :xs="12" :sm="8" :md="6" v-for="s in statCards" :key="s.key">
        <a-card :bordered="false" class="glass-card stat-card" :class="'stat-' + s.key">
          <a-statistic :value="s.value" :title="s.label">
            <template #prefix>
              <component :is="s.icon" class="stat-icon" />
            </template>
          </a-statistic>
        </a-card>
      </a-col>
    </a-row>

    <!-- 工具栏 -->
    <a-card :bordered="false" class="glass-card toolbar-card">
      <div class="toolbar">
        <div class="toolbar-left">
          <a-button v-permission="['DOCTOR', 'RESEARCHER']" type="primary" class="primary-gradient" @click="openCreate">
            <template #icon><icon-plus /></template>
            发起新评估
          </a-button>
          <a-button :loading="loading" @click="fetchList">
            <template #icon><icon-refresh /></template>
            刷新
          </a-button>
        </div>
        <div class="toolbar-right">
          <a-radio-group v-model="filterStatus" type="button" size="small" @change="onStatusChange">
            <a-radio value="">全部</a-radio>
            <a-radio value="PENDING">待处理</a-radio>
            <a-radio value="RUNNING">运行中</a-radio>
            <a-radio value="COMPLETED">已完成</a-radio>
          </a-radio-group>
        </div>
      </div>
    </a-card>

    <!-- 表格 -->
    <a-card :bordered="false" class="glass-card table-card">
      <a-table
        row-key="taskId"
        :data="list"
        :loading="loading"
        :pagination="pagination"
        :bordered="false"
        :scroll="{ x: 760 }"
        @page-change="onPageChange"
      >
        <template #columns>
          <a-table-column title="任务ID" data-index="taskId" :width="110">
            <template #cell="{ record }">
              <span class="mono">#{{ record.taskId }}</span>
            </template>
          </a-table-column>
          <a-table-column title="受试者" data-index="subjectCode" :width="130">
            <template #cell="{ record }">
              <span>{{ record.subjectCode }}</span>
            </template>
          </a-table-column>
          <a-table-column title="状态" data-index="status" :width="110">
            <template #cell="{ record }">
              <a-tag :color="statusColor(record.status)" class="status-tag">{{ statusText(record.status) }}</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="创建时间" data-index="createdAt" :width="180">
            <template #cell="{ record }">
              <span class="mono">{{ formatTime(record.createdAt) }}</span>
            </template>
          </a-table-column>
          <a-table-column title="操作" :width="230">
            <template #cell="{ record }">
              <a-button
                v-if="record.assessmentId"
                type="text"
                size="small"
                @click="openDetail(record)"
              >
                <template #icon><icon-eye /></template>
                查看结果
              </a-button>
              <a-tooltip v-else content="任务尚未完成" position="top">
                <a-button type="text" size="small" disabled>
                  <template #icon><icon-eye /></template>
                  查看结果
                </a-button>
              </a-tooltip>
            </template>
          </a-table-column>
        </template>
        <template #empty>
          <div class="empty-wrap">
            <icon-apps class="empty-icon" />
            <p>暂无评估任务</p>
          </div>
        </template>
      </a-table>
    </a-card>

    <!-- 新建任务弹窗 -->
    <a-modal
      v-model:visible="createVisible"
      title="发起新评估"
      :width="520"
      :confirm-loading="creating"
      :mask-closable="false"
      @ok="submitCreate"
      @cancel="closeCreate"
      class="glass-modal"
    >
      <a-form :model="createForm" layout="vertical" :rules="createRules" ref="createFormRef">
        <a-form-item label="受试者" field="subjectId" required>
          <a-select
            v-model="selectedSubjectId"
            placeholder="请选择受试者"
            :options="subjectOptions"
            :loading="subjectsLoading"
            :filter-option="filterSubjectOption"
            allow-search
          />
        </a-form-item>
        <a-form-item label="模型版本" field="modelVersion" required>
          <a-select v-model="createForm.modelVersion" placeholder="选择模型版本">
            <a-option value="deepagent-causal-warning-v1">deepagent-causal-warning-v1（因果警告模型）</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="预测时间窗（月）" field="predictionWindowMonths" required>
          <a-input-number
            v-model="createForm.predictionWindowMonths"
            :min="6"
            :max="60"
            :step="6"
            style="width: 100%"
          />
          <template #extra>预测未来 6-60 个月内发生 AD 风险的概率</template>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 详情弹窗 -->
    <a-modal
      v-model:visible="detailVisible"
      title="评估结果详情"
      :width="680"
      :footer="false"
      :mask-closable="true"
      class="glass-modal"
    >
      <div v-if="detail" class="detail-body">
        <div class="detail-head">
          <a-space size="large">
            <div class="detail-metric">
              <div class="metric-label">受试者</div>
              <div class="metric-value">{{ detail.subjectCode }}</div>
            </div>
            <div class="detail-metric">
              <div class="metric-label">风险得分</div>
              <div class="metric-value score">{{ (detail.riskScore * 100).toFixed(0) }}%</div>
            </div>
            <div class="detail-metric">
              <div class="metric-label">复核状态</div>
              <a-tag :color="statusColor(detail.reviewStatus)">{{ statusText(detail.reviewStatus) }}</a-tag>
            </div>
          </a-space>
        </div>

        <a-divider />
        <div class="section-title">
          <icon-bulb /> 四阶段解释
        </div>
        <div class="stage-list">
          <div
            v-for="(txt, idx) in detail.agentResult.fourStageExplanation"
            :key="idx"
            class="stage-item"
          >
            <span class="stage-idx">{{ idx + 1 }}</span>
            <span class="stage-txt">{{ txt }}</span>
          </div>
        </div>

        <template v-if="detail.agentResult.stages && detail.agentResult.stages.length">
          <a-divider />
          <div class="section-title">
            <icon-thunderbolt /> 运行阶段
          </div>
          <a-table :data="detail.agentResult.stages" :pagination="false" :bordered="false" size="small">
            <template #columns>
              <a-table-column title="阶段" data-index="stage" />
              <a-table-column title="状态" data-index="status" :width="90">
                <template #cell="{ record }">
                  <a-tag :color="stageStatusColor(record.status)">{{ record.status }}</a-tag>
                </template>
              </a-table-column>
              <a-table-column title="耗时(ms)" data-index="durationMs" :width="100" />
              <a-table-column title="摘要" data-index="summary" />
            </template>
          </a-table>
        </template>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import { IconApps, IconLoading, IconCheckCircle, IconExclamationCircle, IconBulb } from '@arco-design/web-vue/es/icon'
import {
  getTaskList, createAssessmentTask, getAssessmentDetail
} from '@/api/task.js'
import { getSubjectList } from '@/api/subject.js'

const loading = ref(false)
const list = ref([])
const total = ref(0)
const filterStatus = ref('')

const pagination = reactive({
  current: 1,
  pageSize: 10,
  showTotal: true,
  total: 0,
  showPageSize: true
})

// ---- 新建任务弹窗 ----
const createVisible = ref(false)
const creating = ref(false)
const createFormRef = ref()
const selectedSubjectId = ref(undefined)
const createForm = reactive({
  modelVersion: 'deepagent-causal-warning-v1',
  predictionWindowMonths: 24
})
const createRules = {
  modelVersion: [{ required: true, message: '请选择模型版本' }],
  predictionWindowMonths: [{ required: true, message: '请输入预测时间窗' }]
}
const subjectOptions = ref([])
const subjectsLoading = ref(false)

// ---- 详情弹窗 ----
const detailVisible = ref(false)
const detail = ref(null)

const statCards = computed(() => [
  { key: 'total', label: '任务总数', value: total.value, icon: IconApps },
  { key: 'running', label: '运行中', value: runningCount.value, icon: IconLoading },
  { key: 'completed', label: '已完成', value: completedCount.value, icon: IconCheckCircle },
  { key: 'failed', label: '失败/阻断', value: failedCount.value, icon: IconExclamationCircle }
])

const runningCount = computed(() => list.value.filter(t => ['PENDING', 'RUNNING'].includes(t.status)).length)
const completedCount = computed(() => list.value.filter(t => t.status === 'COMPLETED').length)
const failedCount = computed(() => list.value.filter(t => ['BLOCKED', 'FAILED'].includes(t.status)).length)

function statusColor(s) {
  return {
    PENDING: 'orange',
    RUNNING: 'arcoblue',
    COMPLETED: 'green',
    BLOCKED: 'red',
    FAILED: 'red',
    CANCELLED: 'gray'
  }[s] || 'gray'
}

function statusText(s) {
  return {
    PENDING: '待处理',
    RUNNING: '运行中',
    COMPLETED: '已完成',
    BLOCKED: '已阻断',
    FAILED: '失败',
    CANCELLED: '已取消'
  }[s] || s || '—'
}

function stageStatusColor(s) {
  return s === 'DONE' || s === 'SUCCESS' ? 'green' : s === 'RUNNING' ? 'arcoblue' : 'orange'
}

function formatTime(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// ---- 数据加载 ----
async function fetchList() {
  loading.value = true
  try {
    const params = { page: pagination.current, pageSize: pagination.pageSize }
    if (filterStatus.value) params.status = filterStatus.value
    const res = await getTaskList(params)
    list.value = res.items || []
    total.value = res.total || 0
    pagination.total = res.total || 0
  } catch (e) {
    Message.error(e?.message || '加载任务失败')
  } finally {
    loading.value = false
  }
}

function onStatusChange() {
  pagination.current = 1
  fetchList()
}

function onPageChange(page) {
  pagination.current = page
  fetchList()
}

// ---- 新建任务 ----
async function openCreate() {
  createVisible.value = true
  await loadSubjects()
}

function closeCreate() {
  createVisible.value = false
}

function filterSubjectOption(searchValue, option) {
  const label = String(option?.label || option?.value || '')
  return label.toLowerCase().includes((searchValue || '').toLowerCase())
}

async function loadSubjects() {
  subjectsLoading.value = true
  try {
    const res = await getSubjectList({ page: 1, pageSize: 100 })
    const items = res.items || []
    subjectOptions.value = items.map(s => ({
      label: `${s.subjectCode}（${s.alias || '—'}）`,
      value: s.id
    }))
  } catch (e) {
    Message.error(e?.message || '加载受试者失败')
  } finally {
    subjectsLoading.value = false
  }
}

async function submitCreate() {
  // 前端拦截：未选受试者直接提示，不发请求
  if (!selectedSubjectId.value) {
    Message.warning('请先选择受试者')
    return
  }
  if (!createForm.modelVersion) {
    Message.warning('请选择模型版本')
    return
  }
  const payload = {
    subjectId: selectedSubjectId.value,
    modelVersion: createForm.modelVersion,
    predictionWindowMonths: createForm.predictionWindowMonths
  }
  console.log('[TaskList] submitCreate payload:', payload)
  const valid = await createFormRef.value?.validate().catch(() => false)
  if (valid === false) return
  creating.value = true
  try {
    await createAssessmentTask(payload)
    Message.success('评估任务已发起')
    createVisible.value = false
    fetchList()
  } catch (e) {
    Message.error(e?.message || '发起任务失败')
  } finally {
    creating.value = false
  }
}

// ---- 查看结果 ----
async function openDetail(record) {
  try {
    const res = await getAssessmentDetail(record.assessmentId)
    detail.value = res || null
    detailVisible.value = true
  } catch (e) {
    Message.error(e?.message || '获取评估结果失败')
  }
}

onMounted(fetchList)
</script>

<style scoped>
.page {
  position: relative;
  min-height: 100%;
  padding: 6px;
}

/* 毛玻璃背景装饰 */
.glass-bg {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  overflow: hidden;
}
.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.45;
  animation: float 12s ease-in-out infinite;
}
.orb-1 { width: 340px; height: 340px; background: radial-gradient(circle at 30% 30%, #22b8ff, #1a6bff); top: -80px; left: -60px; }
.orb-2 { width: 280px; height: 280px; background: radial-gradient(circle at 60% 40%, #38d9ff, #25a5f2); bottom: -60px; right: -40px; animation-delay: -4s; }
.orb-3 { width: 200px; height: 200px; background: radial-gradient(circle at 40% 60%, #59a8ff, #7a5cff); top: 40%; left: 50%; animation-delay: -8s; }
@keyframes float {
  0%, 100% { transform: translateY(0) translateX(0) scale(1); }
  50% { transform: translateY(-24px) translateX(20px) scale(1.05); }
}

/* 玻璃卡片 */
.glass-card {
  position: relative;
  z-index: 1;
  background: rgba(255, 255, 255, 0.72) !important;
  backdrop-filter: blur(16px) saturate(160%);
  -webkit-backdrop-filter: blur(16px) saturate(160%);
  border: 1px solid rgba(255, 255, 255, 0.6) !important;
  box-shadow: 0 8px 32px rgba(31, 108, 200, 0.12);
  border-radius: 16px;
}

.stat-row { margin-bottom: 16px; position: relative; z-index: 1; }
.stat-card { padding: 6px 4px; }
.stat-icon { font-size: 20px; color: #1a6bff; margin-right: 4px; }
.stat-card.stat-total .stat-icon { color: #1a6bff; }
.stat-card.stat-running .stat-icon { color: #2b72f2; }
.stat-card.stat-completed .stat-icon { color: #10b981; }
.stat-card.stat-failed .stat-icon { color: #f53f3f; }

.toolbar-card { margin-bottom: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }

.primary-gradient {
  background: linear-gradient(135deg, #22b8ff 0%, #1a6bff 50%, #6a5cff 100%) !important;
  border: none !important;
  box-shadow: 0 6px 20px rgba(26, 107, 255, 0.35);
}

.table-card :deep(.arco-table-th) { background: rgba(240, 245, 255, 0.6) !important; color: #1d2129; }
.mono { font-family: 'SFMono-Regular', Consolas, monospace; font-size: 13px; color: #4e5969; }
.status-tag { border-radius: 6px; }
.empty-wrap { text-align: center; padding: 30px 0; color: #86909c; }
.empty-icon { font-size: 40px; margin-bottom: 8px; color: #c9cdd4; }

/* 详情弹窗 */
.detail-body { padding: 4px 4px 0; }
.detail-head { display: flex; align-items: center; }
.detail-metric { display: flex; flex-direction: column; gap: 6px; }
.metric-label { font-size: 12px; color: #86909c; }
.metric-value { font-size: 18px; font-weight: 600; color: #1d2129; }
.metric-value.score { color: #1a6bff; font-size: 24px; }
.section-title {
  display: flex; align-items: center; gap: 6px;
  font-weight: 600; color: #1a6bff; margin-bottom: 12px;
}
.stage-list { display: flex; flex-direction: column; gap: 10px; }
.stage-item {
  display: flex; gap: 12px; align-items: flex-start;
  background: rgba(240, 245, 255, 0.55);
  border-radius: 10px; padding: 10px 12px;
  border-left: 3px solid #22b8ff;
}
.stage-idx {
  flex: none; width: 22px; height: 22px; border-radius: 50%;
  background: linear-gradient(135deg, #22b8ff, #1a6bff);
  color: #fff; display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 600; margin-top: 2px;
}
.stage-txt { color: #4e5969; line-height: 1.6; }

.glass-modal :deep(.arco-modal) {
  background: rgba(255, 255, 255, 0.92) !important;
  backdrop-filter: blur(16px);
  border-radius: 16px;
}
</style>