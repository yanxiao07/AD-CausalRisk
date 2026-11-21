<template>
  <div class="qc-page">
    <PageHeader
      back
      title="数据质控"
      subtitle="查看资料检查结果与缺项。被阻断时请补齐或更正，再重新检查。"
      :terms="['quality', 'testSnapshot', 'dataVersion']"
    >
      <template #extra>
        <el-radio-group v-model="status" size="small" @change="reload">
          <el-radio-button value="BLOCKED">被阻断</el-radio-button>
          <el-radio-button value="WARN">需关注</el-radio-button>
          <el-radio-button value="PASS">通过</el-radio-button>
          <el-radio-button value="">全部</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" @click="reload">刷新</el-button>
      </template>
    </PageHeader>

    <DataCard padding="none">
      <div class="filter">
        <el-input
          v-model="keyword"
          class="filter__input"
          size="small"
          clearable
          placeholder="档案编号 / 别名关键词"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-input
          v-model="subjectFilter"
          class="filter__input filter__input--narrow"
          size="small"
          clearable
          placeholder="受试者 ID"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-button size="small" :icon="Search" @click="reload">查询</el-button>
        <div class="batch-bar">
          <span v-if="selectedRows.length" class="batch-bar__count">已选 {{ selectedRows.length }} 条</span>
          <el-button
            type="primary"
            size="small"
            :disabled="selectedRows.length === 0"
            @click="openBatch"
          >
            生成测试快照
          </el-button>
        </div>
      </div>

      <p class="batch-hint">
        批量生成只读取每条档案最新一次访视的 MMSE / CDR-SB，其余变量标记为
        <code>NOT_COLLECTED</code>；BLOCKED 记录需先补录数据，不能直接生成。
      </p>

      <el-skeleton v-if="loading" :rows="4" animated />

      <EmptyState
        v-else-if="error"
        :icon="WarningFilled"
        title="质控列表加载失败"
        :description="error"
        action-text="重试"
        @action="reload"
      />

      <EmptyState
        v-else-if="items.length === 0"
        :icon="Checked"
        title="没有符合条件的质控记录"
        description="导入或补录数据后，系统会按规则自动生成检查结果；被阻断的记录会阻止发起评估。"
      />

      <template v-else>
        <el-table
          :data="items"
          class="table"
          row-key="id"
          @selection-change="onSelectionChange"
        >
          <el-table-column type="selection" width="48" :selectable="canSelect" />
          <el-table-column label="受试者" min-width="180">
            <template #default="{ row }">
              <span class="subject">{{ row.subjectAlias || '—' }}</span>
              <span class="subject__code">{{ row.subjectCode || `#${row.subjectId}` }}</span>
            </template>
          </el-table-column>
          <el-table-column label="质控状态" width="120">
            <template #default="{ row }">
              <StatusTag :type="row.qualityStatus" />
            </template>
          </el-table-column>
          <el-table-column label="问题明细" min-width="320">
            <template #default="{ row }">
              <span v-if="issuesOf(row).length === 0" class="empty-cell">无异常</span>
              <ul v-else class="issues">
                <li v-for="(issue, index) in issuesOf(row)" :key="index">{{ issue }}</li>
              </ul>
            </template>
          </el-table-column>
          <el-table-column label="规则版本" width="140">
            <template #default="{ row }">
              <span class="mono">{{ row.ruleVersion || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="检查时间" width="170">
            <template #default="{ row }">{{ formatTime(row.checkedAt) }}</template>
          </el-table-column>
          <el-table-column label="数据版本" width="140">
            <template #default="{ row }">
              <span class="mono">{{ row.dataVersion || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>

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

    <el-dialog v-model="batchOpen" title="批量生成测试快照" width="640px" :close-on-click-modal="!batchRunning">
      <div v-if="!batchResult" class="batch-confirm">
        <p>
          将从 {{ selectedRows.length }} 个受试者的最新访视生成快照。缺失变量会按
          <code>NOT_COLLECTED</code> 记录，不会补 0 或均值。
        </p>
        <el-checkbox v-model="batchEvaluate">
          生成后立即提交研究模型评估
        </el-checkbox>
        <p class="batch-confirm__hint">
          提交后结果会写入研究任务历史，并可从研究档案的「模型验证结果」查看。
        </p>
      </div>

      <div v-else class="batch-result">
        <p class="batch-result__summary">
          成功 {{ batchResult.succeeded ?? 0 }} 条，失败 {{ batchResult.failed ?? 0 }} 条。
        </p>
        <el-table :data="batchResult.items || []" size="small" max-height="360">
          <el-table-column label="受试者" width="90">
            <template #default="{ row }">#{{ row.subjectId }}</template>
          </el-table-column>
          <el-table-column label="快照" width="80">
            <template #default="{ row }">{{ row.snapshotId ? `#${row.snapshotId}` : '—' }}</template>
          </el-table-column>
          <el-table-column label="同步" width="100">
            <template #default="{ row }">
              <el-tag v-if="row.hostSyncStatus" size="small" :type="row.hostSyncStatus === 'SYNCED' ? 'success' : 'warning'">
                {{ row.hostSyncStatus === 'SYNCED' ? '已同步' : row.hostSyncStatus }}
              </el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="任务" width="90">
            <template #default="{ row }">
              <span v-if="row.taskId">#{{ row.taskId }}</span>
              <StatusTag v-else-if="row.taskStatus" :type="row.taskStatus" />
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="180">
            <template #default="{ row }">{{ row.message || '—' }}</template>
          </el-table-column>
        </el-table>
      </div>

      <template #footer>
        <el-button v-if="batchResult" @click="closeBatch">关闭</el-button>
        <el-button v-if="batchResult" type="primary" @click="reload">刷新列表</el-button>
        <template v-else>
          <el-button :disabled="batchRunning" @click="batchOpen = false">取消</el-button>
          <el-button type="primary" :loading="batchRunning" @click="runBatch">
            确认生成
          </el-button>
        </template>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Checked, Refresh, Search, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { listQualityChecks } from '@/api/qc.js'
import { batchResearchSnapshots } from '@/api/research.js'

const items = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const status = ref('')
const keyword = ref('')
const subjectFilter = ref('')
const loading = ref(true)
const error = ref('')
const selectedRows = ref([])
const batchOpen = ref(false)
const batchRunning = ref(false)
const batchEvaluate = ref(false)
const batchResult = ref(null)

/** 请求序号守卫：翻页/切筛选时只认最后一次查询 */
let requestSeq = 0

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

/** issues 在库里是 JSON 字符串（如 ["缺失 MMSE"]），兼容已是数组的情况。 */
function issuesOf(row) {
  const raw = row?.issues
  if (Array.isArray(raw)) return raw.filter(Boolean)
  if (typeof raw !== 'string' || !raw.trim()) return []
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter(Boolean) : [raw]
  } catch {
    return [raw]
  }
}

function canSelect(row) {
  return row?.qualityStatus !== 'BLOCKED'
}

function onSelectionChange(rows) {
  selectedRows.value = Array.isArray(rows) ? rows : []
}

function openBatch() {
  if (!selectedRows.value.length) {
    ElMessage.warning('请先选择需要生成快照的受试者')
    return
  }
  batchResult.value = null
  batchEvaluate.value = false
  batchOpen.value = true
}

async function runBatch() {
  batchRunning.value = true
  try {
    const subjectIds = selectedRows.value.map((row) => row.subjectId).filter(Boolean)
    const data = await batchResearchSnapshots({ subjectIds, evaluate: batchEvaluate.value })
    batchResult.value = data || { items: [], succeeded: 0, failed: 0 }
    if ((data?.failed ?? 0) === 0) {
      ElMessage.success('快照已生成')
    } else {
      ElMessage.warning(`有 ${data.failed} 条生成失败，请查看明细`)
    }
  } catch (problem) {
    ElMessage.error(problem?.message || '批量生成失败，请稍后重试')
  } finally {
    batchRunning.value = false
  }
}

function closeBatch() {
  batchOpen.value = false
  batchResult.value = null
}

async function load() {
  // 请求序号：翻页/切筛选很快时，旧页面的响应不能覆盖当前页
  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  try {
    const data = await listQualityChecks({
      status: status.value || undefined,
      keyword: keyword.value.trim() || undefined,
      subjectId: subjectFilter.value.trim() || undefined,
      page: page.value,
      pageSize: pageSize.value
    })
    if (seq !== requestSeq) return
    items.value = Array.isArray(data?.items) ? data.items : []
    total.value = Number(data?.total ?? items.value.length)
  } catch (problem) {
    if (seq !== requestSeq) return
    items.value = []
    total.value = 0
    error.value = problem?.message || '请稍后重试'
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

function reload() {
  page.value = 1
  load()
}

onMounted(load)
</script>

<style scoped>
.qc-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.filter {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.filter__input {
  width: 220px;
}

.filter__input--narrow {
  width: 130px;
}

.table {
  width: 100%;
}

.subject {
  font-weight: 600;
  color: var(--ink);
}

.subject__code {
  margin-left: 8px;
  font-size: 12px;
  color: var(--muted);
  font-family: var(--font-display);
}

.issues {
  margin: 0;
  padding-left: 16px;
  font-size: 13px;
  color: var(--ink);
  line-height: 1.6;
}

.empty-cell {
  font-size: 13px;
  color: var(--soft);
}

.mono {
  font-family: var(--font-display);
  font-size: 12px;
  color: var(--muted);
}

.batch-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
}

.batch-bar__count {
  font-size: 12px;
  color: var(--muted);
}

.batch-hint {
  margin: 0;
  padding: 0 18px 12px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--soft);
}

.batch-hint code,
.batch-confirm code {
  font-family: var(--font-display);
  font-size: 11px;
  padding: 1px 4px;
  border-radius: 4px;
  background: var(--brand-50);
  color: var(--brand-700);
}

.batch-confirm p {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink);
}

.batch-confirm__hint {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--soft);
}

.batch-result__summary {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--ink);
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 14px 18px;
  border-top: 1px solid var(--line);
}
</style>
