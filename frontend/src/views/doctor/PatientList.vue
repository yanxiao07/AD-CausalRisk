<template>
  <div class="patient-list">
    <PageHeader
      title="受试者档案"
      subtitle="仅展示授权范围内的受试者；点击任意一行查看档案概览。"
    >
      <template #extra>
        <el-input
          v-model="keyword"
          class="search"
          placeholder="在当前页搜索编号 / 别名"
          clearable
          :prefix-icon="Search"
        />
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <DataCard padding="none">
      <!-- 首次加载才用骨架屏；后续刷新保留表格，只叠一层遮罩，避免表格反复卸载重建 -->
      <div v-if="firstLoading" class="skeleton" aria-busy="true" aria-live="polite">
        <div v-for="row in 6" :key="row" class="skeleton__row">
          <el-skeleton-item variant="text" style="width: 14%" />
          <el-skeleton-item variant="text" style="width: 20%" />
          <el-skeleton-item variant="text" style="width: 10%" />
          <el-skeleton-item variant="text" style="width: 16%" />
          <el-skeleton-item variant="text" style="width: 12%" />
        </div>
      </div>

      <!-- 失败态：不是"空数据"，必须分开表达 -->
      <EmptyState
        v-else-if="error"
        :icon="WarningFilled"
        title="档案加载失败"
        :description="error.message || '请稍后重试；如果持续失败，请联系管理员确认授权。'"
        action-text="重新加载"
        @action="load"
      />

      <!-- 空态 -->
      <EmptyState
        v-else-if="items.length === 0"
        :icon="User"
        title="授权范围内还没有档案"
        description="档案由医生建档或研究员导入后出现在这里。"
      />

      <template v-else>
        <!-- 研究员批量评估：勾选后一次发起 -->
        <div v-if="canBatch" class="toolbar">
          <span class="toolbar__hint">
            已选 {{ selectedRows.length }} 位受试者
          </span>
          <el-button
            type="primary"
            :disabled="selectedRows.length === 0 || batch.running"
            :loading="batch.running"
            @click="startBatch"
          >
            批量发起评估
          </el-button>
          <el-button v-if="batch.running" text @click="batch.visible = true">查看进度</el-button>
        </div>

        <div class="table-wrap">
          <el-table
            :data="filteredItems"
            class="table"
            row-key="id"
            highlight-current-row
            @row-click="openDrawer"
            @selection-change="onSelectionChange"
          >
            <el-table-column v-if="canBatch" type="selection" width="46" />
            <el-table-column prop="subjectCode" label="档案编号" min-width="160" />
            <el-table-column prop="alias" label="别名" min-width="140" />
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <StatusTag :type="row.status" />
              </template>
            </el-table-column>
            <el-table-column prop="dataVersion" label="数据版本" min-width="140" />
            <el-table-column label="建档时间" width="180">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button text type="primary" @click.stop="openDrawer(row)">查看</el-button>
              </template>
            </el-table-column>

            <template #empty>
              <div class="table-empty">
                当前页没有匹配的档案（关键词只过滤本页，清空后可看整页）
              </div>
            </template>
          </el-table>

          <!-- 刷新遮罩：不卸载表格，滚动位置与选中行都不会丢 -->
          <div v-if="loading" class="table-mask" aria-live="polite">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>正在加载…</span>
          </div>
        </div>

        <div class="pager">
          <el-pagination
            v-model:current-page="page"
            :page-size="pageSize"
            :total="total"
            layout="total, prev, pager, next"
            background
            @current-change="load"
          />
        </div>
      </template>
    </DataCard>

    <!-- 抽屉：基础信息占位，详情/因果图 P3 接入 -->
    <el-drawer
      v-model="drawerOpen"
      :title="selected?.alias || '档案概览'"
      direction="rtl"
      size="720px"
      destroy-on-close
      class="patient-drawer"
    >
      <template v-if="selected">
        <div class="drawer__head">
          <StatusTag :type="selected.status" />
          <span class="drawer__code">{{ selected.subjectCode }}</span>
        </div>

        <el-descriptions :column="2" border class="drawer__desc">
          <el-descriptions-item label="别名">{{ selected.alias }}</el-descriptions-item>
          <el-descriptions-item label="档案编号">{{ selected.subjectCode }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <StatusTag :type="selected.status" />
          </el-descriptions-item>
          <el-descriptions-item label="数据版本">{{ selected.dataVersion }}</el-descriptions-item>
          <el-descriptions-item label="建档时间" :span="2">
            {{ formatTime(selected.createdAt) }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="drawer__section">
          <AssessmentPanel :subject-id="selected.id" @reviewed="onReviewed" />
        </div>

        <div class="drawer__section">
          <section class="followup">
            <header class="followup__head">
              <h3 class="followup__title">随访计划</h3>
              <el-button size="small" type="primary" @click="followUpDialogOpen = true">
                新建随访
              </el-button>
            </header>

            <el-skeleton v-if="followUpLoading" :rows="2" animated />

            <EmptyState
              v-else-if="followUps.length === 0"
              :icon="Timer"
              title="还没有随访安排"
              description="点击「新建随访」安排复诊时间与目的。"
            />

            <el-table v-else :data="followUps" size="small" class="followup__table" max-height="220">
              <el-table-column label="计划时间" width="150">
                <template #default="{ row }">{{ formatTime(row.scheduledAt) }}</template>
              </el-table-column>
              <el-table-column prop="purpose" label="目的" min-width="160" show-overflow-tooltip />
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <StatusTag :type="row.status" />
                </template>
              </el-table-column>
            </el-table>
          </section>
        </div>

        <div class="drawer__section">
          <VisitHistory :visits="visits" :loading="detailLoading" />
        </div>
      </template>
    </el-drawer>

    <FollowUpDialog
      v-if="selected"
      v-model="followUpDialogOpen"
      :subject-id="selected.id"
      @created="loadFollowUps"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading, Refresh, Search, Timer, User, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import AssessmentPanel from '@/components/doctor/AssessmentPanel.vue'
import VisitHistory from '@/components/doctor/VisitHistory.vue'
import FollowUpDialog from '@/components/followup/FollowUpDialog.vue'
import { getSubjectDetail, listSubjects } from '@/api/subjects.js'
import { listFollowUps } from '@/api/followups.js'
import { useUserStore } from '@/stores/user.js'
import { useBatchAssessmentStore } from '@/stores/batch.js'

const userStore = useUserStore()
const batch = useBatchAssessmentStore()

const items = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(true)
const error = ref(null)
const keyword = ref('')

const drawerOpen = ref(false)
const selected = ref(null)
const visits = ref([])
const detailLoading = ref(false)
const followUps = ref([])
const followUpLoading = ref(false)
const followUpDialogOpen = ref(false)

/** 只有研究员需要批量评估：表格才显示多选列与批量工具条。 */
const canBatch = computed(() => userStore.primaryRole === 'RESEARCHER')
const selectedRows = ref([])

function onSelectionChange(rows) {
  selectedRows.value = rows
}

function startBatch() {
  if (!canBatch.value || selectedRows.value.length === 0) return
  const ids = selectedRows.value.map((row) => row.id)
  batch.start(ids)
  ElMessage.success(`已发起 ${ids.length} 个评估任务，进度见右上角面板`)
}

/**
 * 契约里 GET /api/subjects 只有 page / pageSize，没有 keyword。
 * 因此这里的关键词只过滤"当前页"，界面上也明确写清楚，避免让用户误以为在全局搜索。
 */
const filteredItems = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return items.value
  return items.value.filter(
    (row) =>
      String(row.subjectCode || '').toLowerCase().includes(q) ||
      String(row.alias || '').toLowerCase().includes(q)
  )
})

/** 首次加载 = 还没有任何数据时的加载，只有这种状态才显示骨架屏。 */
const firstLoading = computed(() => loading.value && items.value.length === 0 && !error.value)

/**
 * 请求序号：快速连点刷新 / 翻页时，先发出去的请求可能后回来。
 * 只接受"最新一次请求"的响应，避免旧数据覆盖新数据（表现为列表回退或错乱）。
 */
let requestSeq = 0

/**
 * 防御性去重：每次都用后端返回的数组整体覆盖，而不是追加；
 * 同时按 id 去掉可能出现的重复行，保证 row-key 唯一。
 */
function dedupe(rows) {
  const seen = new Set()
  const unique = []
  for (const row of rows) {
    const key = row?.id ?? row?.subjectCode
    if (key === undefined || key === null) {
      unique.push(row)
      continue
    }
    if (seen.has(key)) continue
    seen.add(key)
    unique.push(row)
  }
  return unique
}

async function load() {
  const seq = (requestSeq += 1)
  loading.value = true
  error.value = null
  try {
    const data = await listSubjects({ page: page.value, pageSize: pageSize.value })
    if (seq !== requestSeq) return // 已有更新的请求，丢弃这次结果
    items.value = dedupe(Array.isArray(data?.items) ? data.items : [])
    total.value = Number(data?.total ?? 0)
  } catch (problem) {
    if (seq !== requestSeq) return
    // request.js 已经弹过全局提示，这里只记录状态用于页面内呈现
    error.value = problem ?? { message: '' }
    items.value = []
    total.value = 0
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

function openDrawer(row) {
  selected.value = row
  drawerOpen.value = true
  loadDetail(row.id)
  loadFollowUps()
}

/** 抽屉里的随访：GET /api/subjects/{subjectId}/follow-ups */
async function loadFollowUps() {
  const subjectId = selected.value?.id
  if (!subjectId) return
  followUpLoading.value = true
  try {
    const list = await listFollowUps(subjectId)
    followUps.value = (Array.isArray(list) ? list : []).slice().sort(
      (a, b) => new Date(a.scheduledAt) - new Date(b.scheduledAt)
    )
  } catch {
    followUps.value = []
  } finally {
    followUpLoading.value = false
  }
}

/** 抽屉里的访视：GET /api/subjects/{subjectId} 的 visits 字段。 */
async function loadDetail(subjectId) {
  visits.value = []
  detailLoading.value = true
  try {
    const detail = await getSubjectDetail(subjectId)
    visits.value = Array.isArray(detail?.visits) ? detail.visits : []
  } catch {
    // request.js 已统一提示；这里保持空列表，页面显示"还没有访视记录"
    visits.value = []
  } finally {
    detailLoading.value = false
  }
}

/** 复核成功：刷新列表，保证列表与抽屉状态一致。 */
function onReviewed() {
  load()
}

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

onMounted(load)
</script>

<style scoped>
.patient-list {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.search {
  width: 260px;
}

.skeleton {
  padding: 18px 20px;
}

.skeleton__row {
  display: flex;
  gap: 24px;
  align-items: center;
  height: 48px;
  border-bottom: 1px solid var(--line);
}

.skeleton__row:last-child {
  border-bottom: none;
}

.table {
  width: 100%;
}

/* 刷新遮罩：盖在表格上，不卸载表格 */
.table-wrap {
  position: relative;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 18px;
  border-bottom: 1px solid var(--line);
  background: var(--bg-soft);
}

.toolbar__hint {
  font-size: 13px;
  color: var(--muted);
}

.toolbar :deep(.el-button) {
  margin-left: auto;
}

.toolbar :deep(.el-button + .el-button) {
  margin-left: 8px;
}

.followup {
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: #fff;
}

.followup__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.followup__title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
}

.followup__table {
  width: 100%;
}

.table-mask {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: rgba(255, 255, 255, 0.55);
  -webkit-backdrop-filter: blur(1px);
  backdrop-filter: blur(1px);
  color: var(--muted);
  font-size: 13px;
}

.table-empty {
  padding: 24px;
  text-align: center;
  font-size: 13px;
  color: var(--muted);
}

.table :deep(.el-table__row) {
  cursor: pointer;
}

.table :deep(.el-table__row:hover > td) {
  background: var(--brand-50);
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 14px 20px;
  border-top: 1px solid var(--line);
}

.drawer__head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.drawer__code {
  font-family: var(--font-display);
  font-size: 14px;
  color: var(--muted);
}

.drawer__desc :deep(.el-descriptions__label) {
  width: 96px;
  color: var(--muted);
}

.drawer__section {
  margin-top: 16px;
}
</style>
