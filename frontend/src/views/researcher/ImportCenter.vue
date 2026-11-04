<template>
  <div class="import-center">
    <PageHeader
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
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { DocumentAdd, RefreshLeft, UploadFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { commitImportJob, createImportJob } from '@/api/imports.js'

/** 后端 multipart 限制：max-file-size 64KB / max-request-size 128KB */
const MAX_FILE_BYTES = 64 * 1024

const uploading = ref(false)
const committing = ref(false)
const uploadError = ref('')
const job = ref(null)

/** 只有 PREVIEW_READY 能入库（后端 commit 会校验状态，重复提交返回 400）。 */
const canCommit = computed(() => job.value?.status === 'PREVIEW_READY')

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

@media (max-width: 900px) {
  .stats {
    grid-template-columns: repeat(2, minmax(120px, 1fr));
  }
}
</style>
