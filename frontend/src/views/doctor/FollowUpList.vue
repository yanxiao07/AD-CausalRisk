<template>
  <div class="followup-page">
    <PageHeader back title="随访计划" subtitle="按受试者查看随访，或汇总所有待办复诊。">
      <template #extra>
        <el-radio-group v-model="view" size="small" @change="onViewChange">
          <el-radio-button value="BY_SUBJECT">按受试者</el-radio-button>
          <el-radio-button value="ALL">全部待办</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" :loading="loading || allLoading" @click="reload">刷新</el-button>
        <el-button :icon="Download" :disabled="!canExport" @click="exportCsv">导出 CSV</el-button>
        <el-button
          type="primary"
          :disabled="view !== 'BY_SUBJECT' || !subjectId"
          @click="dialogOpen = true"
        >
          新建随访
        </el-button>
      </template>
    </PageHeader>

    <!-- 视图一：全部待办（服务端分页：GET /api/follow-ups?status=PLANNED&due=...） -->
    <DataCard v-if="view === 'ALL'" padding="none">
      <div class="filter">
        <el-select v-model="due" class="filter__select" size="small" @change="loadAll(1)">
          <el-option label="全部待办" value="" />
          <el-option label="已逾期" value="overdue" />
          <el-option label="今天" value="today" />
          <el-option label="未来 7 天" value="next7" />
        </el-select>
        <span class="filter__count">共 {{ allTotal }} 条待办</span>
      </div>

      <el-skeleton v-if="allLoading && allItems.length === 0" :rows="4" animated />

      <EmptyState
        v-else-if="allError"
        :icon="WarningFilled"
        title="待办加载失败"
        :description="allError"
        action-text="重试"
        @action="loadAll(todoPage)"
      />

      <EmptyState
        v-else-if="allItems.length === 0"
        :icon="Timer"
        title="没有待处理的随访"
        description="当前时间窗口内没有计划中的随访；可以换一个时间范围，或切回「按受试者」单独查看。"
      />

      <el-table v-else :data="allItems" class="table" row-key="id">
        <el-table-column label="计划时间" width="200">
          <template #default="{ row }">
            <span :class="{ 'is-overdue': isOverdue(row) }">{{ formatTime(row.scheduledAt) }}</span>
            <el-tag v-if="isOverdue(row)" type="danger" size="small" class="overdue-tag">已逾期</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="受试者" min-width="160">
          <template #default="{ row }">
            {{ row.subjectAlias || '—' }}（{{ row.subjectCode || `#${row.subjectId}` }}）
          </template>
        </el-table-column>
        <el-table-column prop="purpose" label="目的" min-width="200" show-overflow-tooltip />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              text
              type="primary"
              :loading="updating === row.id"
              @click="changeStatus(row, 'COMPLETED')"
            >
              标记完成
            </el-button>
            <el-button
              text
              type="danger"
              :loading="updating === row.id"
              @click="changeStatus(row, 'CANCELLED')"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="allTotal > todoPageSize" class="pager">
        <el-pagination
          v-model:current-page="todoPage"
          :page-size="todoPageSize"
          :total="allTotal"
          layout="total, prev, pager, next"
          background
          @current-change="loadAll"
        />
      </div>
    </DataCard>

    <!-- 视图二：按受试者（契约的原始查询方式） -->
    <template v-else>
      <DataCard>
        <div class="picker">
          <span class="picker__label">受试者</span>
          <el-select
            v-model="subjectId"
            filterable
            clearable
            placeholder="按编号或别名搜索"
            class="picker__select"
            :loading="subjectsLoading"
            @change="loadFollowUps"
          >
            <el-option
              v-for="item in subjects"
              :key="item.id"
              :label="`${item.alias}（${item.subjectCode}）`"
              :value="item.id"
            />
          </el-select>
          <span class="picker__hint">
            {{
              subjectsError ||
              '契约里随访按受试者查询，所以先选人再看计划；跨受试者的待办看「全部待办」。'
            }}
          </span>
        </div>
      </DataCard>

      <DataCard title="随访安排" :subtitle="subjectId ? '按计划时间升序' : '请先选择受试者'">
        <el-skeleton v-if="loading" :rows="3" animated />

        <EmptyState
          v-else-if="error"
          :icon="WarningFilled"
          title="随访加载失败"
          :description="error"
          action-text="重试"
          @action="loadFollowUps"
        />

        <EmptyState
          v-else-if="!subjectId"
          :icon="User"
          title="还没有选择受试者"
          description="从上方选择一个受试者，即可查看/安排他的随访计划。"
        />

        <EmptyState
          v-else-if="items.length === 0"
          :icon="Timer"
          title="这位受试者还没有随访计划"
          description="点击右上角「新建随访」安排第一次复诊。"
        />

        <el-table v-else :data="items" class="table" row-key="id">
          <el-table-column label="计划时间" width="200">
            <template #default="{ row }">
              <span :class="{ 'is-overdue': isOverdue(row) }">{{ formatTime(row.scheduledAt) }}</span>
              <el-tag v-if="isOverdue(row)" type="danger" size="small" class="overdue-tag">已逾期</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="purpose" label="目的" min-width="220" show-overflow-tooltip />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <StatusTag :type="row.status" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button
                text
                type="primary"
                :disabled="row.status !== 'PLANNED'"
                :loading="updating === row.id"
                @click="changeStatus(row, 'COMPLETED')"
              >
                标记完成
              </el-button>
              <el-button
                text
                type="danger"
                :disabled="row.status === 'CANCELLED'"
                :loading="updating === row.id"
                @click="changeStatus(row, 'CANCELLED')"
              >
                取消
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </DataCard>
    </template>

    <FollowUpDialog
      v-if="subjectId"
      v-model="dialogOpen"
      :subject-id="subjectId"
      @created="loadFollowUps"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Refresh, Timer, User, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import FollowUpDialog from '@/components/followup/FollowUpDialog.vue'
import { listSubjects } from '@/api/subjects.js'
import { listFollowUps, listFollowUpsPaged, updateFollowUp } from '@/api/followups.js'
import { downloadCsv } from '@/utils/csv.js'

const view = ref('BY_SUBJECT')

const subjects = ref([])
const subjectsLoading = ref(false)
const subjectsError = ref('')
const subjectId = ref(null)

const items = ref([])
/** items 实际属于哪个受试者：导出与展示都以它为准，避免"切换中导出"串人 */
const loadedSubjectId = ref(null)
const loading = ref(false)
const error = ref('')
const updating = ref(null)
const dialogOpen = ref(false)

// ---- 全部待办（服务端分页）----
const allItems = ref([])
const allTotal = ref(0)
const allLoading = ref(false)
const allError = ref('')
const due = ref('')
const todoPage = ref(1)
const todoPageSize = ref(20)

/** 请求序号守卫：切换受试者/视图时只认最后一次查询 */
let requestSeq = 0
let allSeq = 0

const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
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

function isOverdue(row) {
  if (row?.status !== 'PLANNED' || !row?.scheduledAt) return false
  const date = new Date(row.scheduledAt)
  return !Number.isNaN(date.getTime()) && date.getTime() < Date.now()
}

/** 已加载数据所属的档案（不是下拉框当前选择），导出文案用它。 */
const loadedSubject = computed(
  () => subjects.value.find((item) => item.id === loadedSubjectId.value) || null
)

/**
 * 导出可用条件：
 *  - 全部待办：不在加载中、且确实有数据；
 *  - 按受试者：不在加载中，且已加载数据就是当前选中档案（切换期间禁用，避免串人）。
 */
const canExport = computed(() => {
  if (view.value === 'ALL') return !allLoading.value && allItems.value.length > 0
  return (
    !loading.value &&
    loadedSubjectId.value !== null &&
    loadedSubjectId.value === subjectId.value &&
    items.value.length > 0
  )
})

function exportCsv() {
  if (!canExport.value) return
  const rows =
    view.value === 'ALL'
      ? allItems.value.map((item) => [
          item.subjectAlias || '',
          item.subjectCode || '',
          formatTime(item.scheduledAt),
          item.purpose || '',
          item.status
        ])
      : items.value.map((item) => [
          loadedSubject.value?.alias || '',
          loadedSubject.value?.subjectCode || '',
          formatTime(item.scheduledAt),
          item.purpose || '',
          item.status
        ])
  downloadCsv(
    `随访计划-${new Date().toISOString().slice(0, 10)}.csv`,
    ['受试者', '档案编号', '计划时间', '目的', '状态'],
    rows
  )
}

async function loadSubjects() {
  subjectsLoading.value = true
  subjectsError.value = ''
  try {
    const data = await listSubjects({ page: 1, pageSize: 100 })
    subjects.value = Array.isArray(data?.items) ? data.items : []
  } catch (problem) {
    subjects.value = []
    subjectsError.value = problem?.message || '档案列表加载失败'
  } finally {
    subjectsLoading.value = false
  }
}

async function loadFollowUps() {
  if (!subjectId.value) {
    items.value = []
    loadedSubjectId.value = null
    return
  }
  // 请求序号：快速切换受试者时，慢的旧响应不能覆盖新受试者的随访表
  const seq = ++requestSeq
  const targetSubjectId = subjectId.value
  // 加载期间先摘掉数据身份，导出按钮随之禁用，避免"B 的编号 + A 的随访"
  loadedSubjectId.value = null
  loading.value = true
  error.value = ''
  try {
    const data = await listFollowUps(targetSubjectId)
    if (seq !== requestSeq) return
    items.value = (Array.isArray(data) ? data : [])
      .slice()
      .sort((a, b) => new Date(a.scheduledAt) - new Date(b.scheduledAt))
    loadedSubjectId.value = targetSubjectId
  } catch (problem) {
    if (seq !== requestSeq) return
    items.value = []
    loadedSubjectId.value = null
    error.value = problem?.message || '请稍后重试'
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

async function loadAll(target = 1) {
  const seq = ++allSeq
  allLoading.value = true
  allError.value = ''
  try {
    const data = await listFollowUpsPaged({
      page: target,
      pageSize: todoPageSize.value,
      status: 'PLANNED',
      due: due.value
    })
    if (seq !== allSeq) return
    allItems.value = Array.isArray(data?.items) ? data.items : []
    allTotal.value = Number(data?.total ?? allItems.value.length)
    todoPage.value = Number(data?.page ?? target)
    if (allItems.value.length === 0 && allTotal.value > 0 && todoPage.value > 1) {
      todoPage.value -= 1
      await loadAll(todoPage.value)
    }
  } catch (problem) {
    if (seq !== allSeq) return
    allItems.value = []
    allTotal.value = 0
    allError.value =
      problem?.code === 403 ? '当前账号无权查看跨受试者待办' : problem?.message || '请稍后重试'
  } finally {
    if (seq === allSeq) allLoading.value = false
  }
}

async function changeStatus(row, status) {
  const label = status === 'COMPLETED' ? '标记为已完成' : '取消这条随访'
  try {
    await ElMessageBox.confirm(`确定要${label}吗？`, '随访状态', {
      confirmButtonText: '确定',
      cancelButtonText: '返回',
      type: status === 'COMPLETED' ? 'success' : 'warning'
    })
  } catch {
    return
  }

  updating.value = row.id
  try {
    const updated = await updateFollowUp(row.id, { status })
    const merge = (list) => {
      const index = list.findIndex((item) => item.id === row.id)
      if (index >= 0) list[index] = { ...list[index], ...updated }
    }
    merge(items.value)
    merge(allItems.value)
    ElMessage.success(status === 'COMPLETED' ? '已标记完成' : '已取消')
    // 全部待办按 PLANNED 过滤，标记完成/取消后应从当前页移除并保持分页正确
    if (view.value === 'ALL') await loadAll(todoPage.value)
  } catch {
    // request.js 已提示（例如已取消的随访不可再变更）
  } finally {
    updating.value = null
  }
}

function onViewChange(next) {
  if (next === 'ALL' && allItems.value.length === 0 && !allLoading.value) loadAll(1)
}

function reload() {
  if (view.value === 'ALL') loadAll(todoPage.value)
  else if (subjectId.value) loadFollowUps()
  else loadSubjects()
}

onMounted(async () => {
  await loadSubjects()
})
</script>

<style scoped>
.followup-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.picker {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.picker__label {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-700);
}

.picker__select {
  width: 320px;
}

.picker__hint {
  font-size: 12px;
  color: var(--soft);
}

.filter {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.filter__select {
  width: 160px;
}

.filter__count {
  font-size: 12px;
  color: var(--muted);
}

.table {
  width: 100%;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 14px 18px;
  border-top: 1px solid var(--line);
}

.is-overdue {
  color: var(--danger);
  font-weight: 600;
}

.overdue-tag {
  margin-left: 8px;
}
</style>
