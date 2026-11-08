<template>
  <div class="followup-page">
    <PageHeader back title="随访计划" subtitle="按受试者查看随访，或汇总所有待办复诊。">
      <template #extra>
        <el-radio-group v-model="view" size="small" @change="onViewChange">
          <el-radio-button label="BY_SUBJECT">按受试者</el-radio-button>
          <el-radio-button label="ALL">全部待办</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" :loading="loading || allLoading" @click="reload">刷新</el-button>
        <el-button :icon="Download" :disabled="exportRows.length === 0" @click="exportCsv">
          导出 CSV
        </el-button>
        <el-button
          type="primary"
          :disabled="view !== 'BY_SUBJECT' || !subjectId"
          @click="dialogOpen = true"
        >
          新建随访
        </el-button>
      </template>
    </PageHeader>

    <!-- 视图一：全部待办（前端聚合：受试者列表 + 逐个查随访，契约没有跨受试者接口） -->
    <DataCard v-if="view === 'ALL'" padding="none">
      <div class="filter">
        <span class="filter__count">
          待办 {{ todos.length }} 条 · 已加载 {{ loadedSubjects }}/{{ totalSubjects }} 个受试者
        </span>
        <span v-if="failedSubjects" class="filter__hint">
          {{ failedSubjects }} 个受试者的随访读取失败，可稍后刷新
        </span>
        <span v-else class="filter__hint">受试者超过 100 条时只汇总前 100 条</span>
      </div>

      <el-skeleton v-if="allLoading && allItems.length === 0" :rows="4" animated />

      <EmptyState
        v-else-if="allError"
        :icon="WarningFilled"
        title="待办加载失败"
        :description="allError"
        action-text="重试"
        @action="loadAllTodos"
      />

      <EmptyState
        v-else-if="visibleTodos.length === 0"
        :icon="Timer"
        title="没有待处理的随访"
        description="授权范围内没有计划中的随访；也可以切回「按受试者」单独查看。"
      />

      <el-table v-else :data="visibleTodos" class="table" row-key="id">
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

      <div v-if="todos.length > todoPageSize" class="pager">
        <el-pagination
          v-model:current-page="todoPage"
          :page-size="todoPageSize"
          :total="todos.length"
          layout="total, prev, pager, next"
          background
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
            契约里随访接口按受试者查询，所以先选人再看计划；跨受试者的待办看「全部待办」。
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
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Refresh, Timer, User, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import FollowUpDialog from '@/components/followup/FollowUpDialog.vue'
import { listSubjects } from '@/api/subjects.js'
import { listFollowUps, updateFollowUp } from '@/api/followups.js'
import { downloadCsv } from '@/utils/csv.js'

/** 全部待办的并发上限：100 个受试者串行会明显卡，限 5 路并发足够快也不打爆后端 */
const FETCH_CONCURRENCY = 5

const view = ref('BY_SUBJECT')

const subjects = ref([])
const subjectsLoading = ref(false)
const subjectId = ref(null)

const items = ref([])
const loading = ref(false)
const error = ref('')
const updating = ref(null)
const dialogOpen = ref(false)

// ---- 全部待办（前端聚合）----
const allItems = ref([])
const allLoading = ref(false)
const allError = ref('')
const totalSubjects = ref(0)
const loadedSubjects = ref(0)
const failedSubjects = ref(0)
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

const todos = computed(() => allItems.value.filter((item) => item.status === 'PLANNED'))

// 标记完成后待办变少时，当前页可能越界，回退到最后一个有效页
watch(todos, () => {
  const maxPage = Math.max(1, Math.ceil(todos.value.length / todoPageSize.value))
  if (todoPage.value > maxPage) todoPage.value = maxPage
})

/** 导出当前视图的数据：全部待办 → 待办列表；按受试者 → 该受试者的全部随访。 */
const exportRows = computed(() => (view.value === 'ALL' ? todos.value : items.value))

const currentSubject = computed(
  () => subjects.value.find((item) => item.id === subjectId.value) || null
)

function exportCsv() {
  const rows = exportRows.value.map((item) => {
    const alias = view.value === 'ALL' ? item.subjectAlias || '' : currentSubject.value?.alias || ''
    const code =
      view.value === 'ALL' ? item.subjectCode || '' : currentSubject.value?.subjectCode || ''
    return [alias, code, formatTime(item.scheduledAt), item.purpose || '', item.status]
  })
  downloadCsv(
    `随访计划-${new Date().toISOString().slice(0, 10)}.csv`,
    ['受试者', '档案编号', '计划时间', '目的', '状态'],
    rows
  )
}
const visibleTodos = computed(() => {
  const start = (todoPage.value - 1) * todoPageSize.value
  return todos.value.slice(start, start + todoPageSize.value)
})

async function loadSubjects() {
  subjectsLoading.value = true
  try {
    const data = await listSubjects({ page: 1, pageSize: 100 })
    subjects.value = Array.isArray(data?.items) ? data.items : []
    totalSubjects.value = subjects.value.length
  } catch {
    subjects.value = []
  } finally {
    subjectsLoading.value = false
  }
}

async function loadFollowUps() {
  if (!subjectId.value) {
    items.value = []
    return
  }
  // 请求序号：快速切换受试者时，慢的旧响应不能覆盖新受试者的随访表
  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  try {
    const data = await listFollowUps(subjectId.value)
    if (seq !== requestSeq) return
    items.value = (Array.isArray(data) ? data : [])
      .slice()
      .sort((a, b) => new Date(a.scheduledAt) - new Date(b.scheduledAt))
  } catch (problem) {
    if (seq !== requestSeq) return
    items.value = []
    error.value = problem?.message || '请稍后重试'
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

/** 有上限的并发映射：不引入依赖，也不一次性打 100 个请求。 */
async function mapWithConcurrency(list, limit, worker) {
  let cursor = 0
  const runners = Array.from({ length: Math.min(limit, list.length) }, async () => {
    while (cursor < list.length) {
      const index = cursor
      cursor += 1
      await worker(list[index], index)
    }
  })
  await Promise.all(runners)
}

async function loadAllTodos() {
  const seq = ++allSeq
  allLoading.value = true
  allError.value = ''
  allItems.value = []
  failedSubjects.value = 0
  loadedSubjects.value = 0
  todoPage.value = 1

  try {
    if (subjects.value.length === 0) await loadSubjects()
    if (seq !== allSeq) return
    const list = subjects.value
    totalSubjects.value = list.length

    const collected = []
    await mapWithConcurrency(list, FETCH_CONCURRENCY, async (subject) => {
      if (seq !== allSeq) return
      try {
        const followUps = await listFollowUps(subject.id)
        if (seq !== allSeq) return
        for (const item of Array.isArray(followUps) ? followUps : []) {
          collected.push({
            ...item,
            subjectAlias: subject.alias,
            subjectCode: subject.subjectCode
          })
        }
      } catch {
        if (seq === allSeq) failedSubjects.value += 1
      } finally {
        if (seq === allSeq) loadedSubjects.value += 1
      }
    })

    if (seq !== allSeq) return
    allItems.value = collected.sort(
      (a, b) => new Date(a.scheduledAt) - new Date(b.scheduledAt)
    )
  } catch (problem) {
    if (seq !== allSeq) return
    allItems.value = []
    allError.value = problem?.message || '请稍后重试'
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
  } catch {
    // request.js 已提示（例如已取消的随访不可再变更）
  } finally {
    updating.value = null
  }
}

function onViewChange(next) {
  if (next === 'ALL' && allItems.value.length === 0 && !allLoading.value) loadAllTodos()
}

function reload() {
  if (view.value === 'ALL') loadAllTodos()
  else if (subjectId.value) loadFollowUps()
  else loadSubjects()
}

onMounted(loadSubjects)
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

.filter__count {
  font-size: 12px;
  color: var(--muted);
}

.filter__hint {
  font-size: 12px;
  color: var(--soft);
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
