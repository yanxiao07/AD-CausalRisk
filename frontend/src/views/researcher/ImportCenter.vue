<template>
  <div class="import-center">
    <PageHeader back
      title="数据导入"
      subtitle="上传 CSV → 预检 → 确认入库。预检不写库，确认后才落库。"
    >
      <template #extra>
        <el-button v-if="job" :icon="RefreshLeft" @click="reset">重新上传</el-button>
      </template>
    </PageHeader>

    <DataCard title="上传 CSV" subtitle="单文件，最大 64 KB；列名与示例文件保持一致">
      <el-upload
        drag
        class="dropzone"
        accept=".csv,text/csv"
        :show-file-list="false"
        :disabled="uploading"
        :http-request="customUpload"
      >
        <div class="dropzone__inner">
          <el-icon class="dropzone__icon"><UploadFilled /></el-icon>
          <p class="dropzone__title">
            {{ uploading ? '正在预检…' : '把 CSV 拖到这里，或点击选择文件' }}
          </p>
          <p class="dropzone__hint">
            上传时自动带上 Idempotency-Key（UUID），同一文件重复提交不会产生重复任务。
          </p>
        </div>
      </el-upload>

      <p v-if="uploadError" class="upload-error">{{ uploadError }}</p>
    </DataCard>

    <DataCard title="预检结果" subtitle="确认入库前可以反复检查错误明细">
      <EmptyState
        v-if="!job"
        :icon="DocumentAdd"
        title="还没有上传文件"
        description="上传 CSV 后，这里会显示总行数、可入库行数与逐行错误。"
      />

      <div v-else class="result">
        <div class="stats">
          <div class="stat">
            <span class="stat__label">总计</span>
            <strong class="stat__value">{{ job.totalRows }}</strong>
            <span class="stat__unit">行</span>
          </div>
          <div class="stat stat--ok">
            <span class="stat__label">可入库</span>
            <strong class="stat__value">{{ job.validRows }}</strong>
            <span class="stat__unit">行</span>
          </div>
          <div class="stat stat--bad">
            <span class="stat__label">错误</span>
            <strong class="stat__value">{{ job.invalidRows }}</strong>
            <span class="stat__unit">行</span>
          </div>
          <div class="stat stat--meta">
            <span class="stat__label">任务</span>
            <span class="stat__meta">#{{ job.jobId }}</span>
            <span class="stat__meta">{{ job.dataVersion }}</span>
          </div>
        </div>

        <div class="status-row">
          <StatusTag :type="job.status" />
          <span v-if="job.status === 'BLOCKED'" class="status-hint">
            文件被阻断：请修正后在下方错误明细中逐条处理。
          </span>
          <span v-else-if="job.status === 'FAILED'" class="status-hint">
            预检失败，请检查文件编码与列名。
          </span>
          <span v-else-if="job.status === 'COMMITTED'" class="status-hint">
            已入库 {{ job.validRows }} 行。可继续上传下一个文件。
          </span>
        </div>

        <el-alert
          v-if="job.invalidRows > 0"
          class="alert"
          type="warning"
          :closable="false"
          show-icon
          :title="`仅 ${job.validRows} 行会被写入，${job.invalidRows} 行将被跳过`"
          description="错误行不会入库，修正 CSV 后可重新上传；本次预检结果仍可入库其余行。"
        />

        <el-table v-if="job.errors?.length" :data="job.errors" class="errors" max-height="320">
          <el-table-column prop="rowNumber" label="行号" width="90" />
          <el-table-column prop="field" label="字段" width="140" />
          <el-table-column prop="message" label="错误说明" min-width="240" />
        </el-table>
      </div>

      <template v-if="job" #footer>
          <div class="commit">
            <span class="commit__hint">
              {{ canCommit ? '入库会写入通过预检的行，操作不可撤销。' : '当前状态不可入库。' }}
            </span>
            <el-button
              type="primary"
              size="large"
              :loading="committing"
              :disabled="!canCommit"
              @click="commit"
            >
              确认入库
            </el-button>
          </div>
      </template>
    </DataCard>

    <!-- 历史导入记录：GET /api/import-jobs，详情与错误 CSV 按 jobId 取 -->
    <DataCard title="历史导入记录" subtitle="每次预检/入库都会留下任务记录；错误明细可导出 CSV。">
      <template #header-extra>
        <el-select
          v-model="historyStatus"
          class="history-filter"
          size="small"
          clearable
          placeholder="状态"
          @change="loadHistory(1)"
        >
          <el-option label="预检就绪" value="PREVIEW_READY" />
          <el-option label="已入库" value="COMMITTED" />
          <el-option label="被阻断" value="BLOCKED" />
          <el-option label="失败" value="FAILED" />
        </el-select>
        <el-input
          v-model="historyKeyword"
          class="history-filter"
          size="small"
          clearable
          placeholder="任务 ID / 关键词"
          @keyup.enter="loadHistory(1)"
          @clear="loadHistory(1)"
        />
        <el-button text :icon="Refresh" :loading="historyLoading" @click="loadHistory(historyPage)">
          刷新
        </el-button>
      </template>

      <el-skeleton v-if="historyFirstLoading" :rows="4" animated />

      <EmptyState
        v-else-if="historyError"
        :icon="WarningFilled"
        title="导入记录加载失败"
        :description="historyError"
        action-text="重试"
        @action="loadHistory(historyPage)"
      />

      <EmptyState
        v-else-if="historyItems.length === 0"
        :icon="DocumentAdd"
        title="还没有导入记录"
        description="上传并预检 CSV 后，任务会出现在这里。"
      />

      <template v-else>
        <el-table :data="historyItems" size="small" class="history" row-key="jobId">
          <el-table-column prop="jobId" label="任务" width="90" />
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <StatusTag :type="row.status" />
            </template>
          </el-table-column>
          <el-table-column prop="dataVersion" label="数据版本" min-width="150" show-overflow-tooltip />
          <el-table-column label="行数" min-width="150">
            <template #default="{ row }">
              {{ row.totalRows }} 行 · 可入库 {{ row.validRows }} · 错误 {{ row.invalidRows }}
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="createdBy" label="创建人" width="130" show-overflow-tooltip />
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" @click="openJobDetail(row)">详情</el-button>
              <el-button
                text
                type="primary"
                :disabled="!row.invalidRows"
                :loading="exportingId === row.jobId"
                @click="exportErrors(row)"
              >
                导出错误 CSV
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="historyTotal > historyPageSize" class="history-pager">
          <el-pagination
            v-model:current-page="historyPage"
            :page-size="historyPageSize"
            :total="historyTotal"
            layout="total, prev, pager, next"
            background
            @current-change="loadHistory"
          />
        </div>
      </template>
    </DataCard>

    <!-- 导入任务详情 -->
    <el-dialog
      v-model="detailOpen"
      :title="detailItem ? `导入任务 #${detailItem.jobId}` : '导入任务详情'"
      width="720px"
      destroy-on-close
      append-to-body
    >
      <el-skeleton v-if="detailLoading" :rows="5" animated />
      <template v-else-if="detailItem">
        <dl class="detail-facts">
          <div><dt>状态</dt><dd><StatusTag :type="detailItem.status" /></dd></div>
          <div><dt>数据版本</dt><dd>{{ detailItem.dataVersion || '—' }}</dd></div>
          <div><dt>总行数</dt><dd>{{ detailItem.totalRows ?? '—' }}</dd></div>
          <div><dt>可入库</dt><dd>{{ detailItem.validRows ?? '—' }}</dd></div>
          <div><dt>错误</dt><dd>{{ detailItem.invalidRows ?? '—' }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ formatTime(detailItem.createdAt) }}</dd></div>
        </dl>

        <el-table v-if="detailItem.errors?.length" :data="detailItem.errors" size="small" max-height="320">
          <el-table-column prop="rowNumber" label="行号" width="90" />
          <el-table-column prop="field" label="字段" width="140" />
          <el-table-column prop="message" label="错误说明" min-width="240" />
        </el-table>
        <p v-else class="detail-empty">该任务没有错误行。</p>
      </template>
      <template #footer>
        <el-button @click="detailOpen = false">关闭</el-button>
        <el-button
          type="primary"
          :disabled="!detailItem?.invalidRows"
          @click="exportErrors(detailItem)"
        >
          导出错误 CSV
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  DocumentAdd,
  Refresh,
  RefreshLeft,
  UploadFilled,
  WarningFilled
} from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import {
  commitImportJob,
  createImportJob,
  downloadImportErrors,
  getImportJob,
  listImportJobs
} from '@/api/imports.js'

/** 后端 multipart 限制：max-file-size 64KB / max-request-size 128KB */
const MAX_FILE_BYTES = 64 * 1024

const uploading = ref(false)
const committing = ref(false)
const uploadError = ref('')
const job = ref(null)

/** 只有 PREVIEW_READY 能入库（后端 commit 会校验状态，重复提交返回 400）。 */
const canCommit = computed(() => job.value?.status === 'PREVIEW_READY')

// ---- 历史导入记录 ----
const historyItems = ref([])
const historyPage = ref(1)
const historyPageSize = ref(10)
const historyTotal = ref(0)
const historyLoading = ref(false)
const historyFirstLoading = ref(true)
const historyError = ref('')
const historyStatus = ref('')
const historyKeyword = ref('')

const detailOpen = ref(false)
const detailLoading = ref(false)
const detailItem = ref(null)
const exportingId = ref(null)

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

async function loadHistory(target = historyPage.value) {
  historyLoading.value = true
  historyError.value = ''
  try {
    const data = await listImportJobs({
      page: target,
      pageSize: historyPageSize.value,
      status: historyStatus.value,
      keyword: historyKeyword.value.trim()
    })
    historyItems.value = Array.isArray(data?.items) ? data.items : []
    historyTotal.value = Number(data?.total ?? historyItems.value.length)
    historyPage.value = target
  } catch (problem) {
    historyError.value = problem?.message || '请确认已登录研究员账号，并检查后端是否在线。'
    historyItems.value = []
    historyTotal.value = 0
  } finally {
    historyLoading.value = false
    historyFirstLoading.value = false
  }
}

/** 详情走 GET /api/import-jobs/{jobId}：列表摘要不含 errors[]。 */
async function openJobDetail(row) {
  if (!row?.jobId) return
  detailOpen.value = true
  detailLoading.value = true
  detailItem.value = null
  try {
    detailItem.value = await getImportJob(row.jobId)
  } catch {
    detailOpen.value = false
  } finally {
    detailLoading.value = false
  }
}

async function exportErrors(row) {
  if (!row?.jobId) return
  exportingId.value = row.jobId
  try {
    const filename = await downloadImportErrors(row.jobId)
    ElMessage.success(`已导出 ${filename}`)
  } catch (problem) {
    ElMessage.error(problem?.message || '导出失败')
  } finally {
    exportingId.value = null
  }
}

onMounted(() => loadHistory(1))

async function customUpload(options) {
  const file = options?.file
  if (!file) return

  uploadError.value = ''
  if (!/\.csv$/i.test(file.name)) {
    uploadError.value = '只支持 .csv 文件。'
    ElMessage.warning(uploadError.value)
    return
  }
  if (file.size > MAX_FILE_BYTES) {
    uploadError.value = `文件 ${(file.size / 1024).toFixed(1)} KB，超过 64 KB 限制。`
    ElMessage.warning(uploadError.value)
    return
  }

  uploading.value = true
  job.value = null
  try {
    // 每次上传生成新的幂等键：失败重试视为新任务，避免命中上一次的结果
    const idempotencyKey = crypto.randomUUID()
    job.value = await createImportJob(file, idempotencyKey)
    if (job.value?.status === 'PREVIEW_READY') {
      ElMessage.success(`预检完成：${job.value.validRows} 行可入库`)
    }
    // 新任务进入历史列表
    loadHistory(1)
  } catch {
    // request.js 已统一提示；这里只保留页面内错误态
    uploadError.value = '上传失败，请确认后端已启动并已登录研究员账号。'
  } finally {
    uploading.value = false
  }
}

async function commit() {
  if (!job.value || !canCommit.value) return
  committing.value = true
  try {
    job.value = await commitImportJob(job.value.jobId)
    ElMessage.success(`入库成功：${job.value.validRows} 行已写入`)
    loadHistory(historyPage.value)
  } catch {
    // 400（例如已入库）会由 request.js 提示
  } finally {
    committing.value = false
  }
}

function reset() {
  job.value = null
  uploadError.value = ''
}
</script>

<style scoped>
.import-center {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.dropzone :deep(.el-upload-dragger) {
  padding: 36px 24px;
  border-radius: var(--r-lg);
  border: 2px dashed var(--line-strong);
  background: var(--bg-soft);
  transition: border-color var(--dur-base) var(--ease-out), background var(--dur-base) var(--ease-out);
}

.dropzone :deep(.el-upload-dragger:hover) {
  border-color: var(--brand-600);
  background: var(--brand-50);
}

.dropzone__inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.dropzone__icon {
  font-size: 38px;
  color: var(--brand-600);
}

.dropzone__title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
}

.dropzone__hint {
  margin: 0;
  font-size: 13px;
  color: var(--muted);
}

.upload-error {
  margin: 12px 0 0;
  font-size: 13px;
  color: var(--danger);
}

.stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(120px, 1fr)) minmax(160px, 1.2fr);
  gap: 12px;
  margin-bottom: 14px;
}

.stat {
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--bg-soft);
}

.stat__label {
  display: block;
  font-size: 12px;
  color: var(--muted);
}

.stat__value {
  font-family: var(--font-display);
  font-size: 28px;
  font-weight: 700;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
  margin-right: 4px;
}

.stat__unit {
  font-size: 12px;
  color: var(--soft);
}

.stat--ok .stat__value {
  color: var(--success);
}

.stat--bad .stat__value {
  color: var(--danger);
}

.stat--meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  justify-content: center;
}

.stat__meta {
  font-size: 13px;
  color: var(--ink-700);
  font-family: var(--font-display);
}

.status-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.status-hint {
  font-size: 13px;
  color: var(--muted);
}

.alert {
  margin-bottom: 14px;
  border-radius: var(--r-md);
}

.errors {
  width: 100%;
  border-radius: var(--r-md);
}

.commit {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.commit__hint {
  font-size: 13px;
  color: var(--muted);
}

.history {
  width: 100%;
}

.history-filter {
  width: 150px;
}

.history-pager {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
}

.detail-facts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 10px 18px;
  margin: 0 0 14px;
}

.detail-facts > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.detail-facts dt {
  font-size: 11px;
  letter-spacing: 0.4px;
  text-transform: uppercase;
  color: var(--soft);
}

.detail-facts dd {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}

.detail-empty {
  margin: 0;
  font-size: 13px;
  color: var(--muted);
}

@media (max-width: 900px) {
  .stats {
    grid-template-columns: repeat(2, minmax(120px, 1fr));
  }
}
</style>
