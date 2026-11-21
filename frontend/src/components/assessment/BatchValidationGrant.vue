<template>
  <el-button type="primary" plain @click="open = true">批量登记授权</el-button>
  <el-dialog v-model="open" title="批量登记验证授权" width="min(720px, 94vw)" append-to-body
    :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
    <el-form label-position="top" :disabled="saving" @submit.prevent="submit">
      <p class="batch-help">为同一项目和研究员登记多份已获准的患者档案。</p>
      <div class="batch-fields">
        <el-form-item label="项目编号"><el-input v-model.trim="form.project" maxlength="50" /></el-form-item>
        <el-form-item label="研究员账号"><el-input v-model.trim="form.researcher" maxlength="50" /></el-form-item>
        <el-form-item label="授权依据编号"><el-input v-model.trim="form.authorizationRef" maxlength="150" /></el-form-item>
      </div>
      <el-form-item label="患者档案编号">
        <el-input v-model="form.subjectCodes" type="textarea" :rows="5"
          placeholder="粘贴档案编号，每行一个，也可用逗号分隔" />
      </el-form-item>
      <p v-if="entries.length" role="status">识别到 {{ subjectCodes.length }} 份不同档案<span v-if="duplicates">，已合并 {{ duplicates }} 个重复编号</span>。</p>
      <el-button type="primary" native-type="submit" :disabled="!canSubmit" :loading="saving && !retrying">
        登记 {{ subjectCodes.length }} 份授权
      </el-button>
    </el-form>

    <div v-if="batch" class="batch-receipt">
      <h3>登记结果</h3>
      <p>{{ batch.scope.project }} · {{ batch.scope.researcher }} · 授权依据 {{ batch.scope.authorizationRef }}</p>
      <p role="status">已处理 {{ finished }} / {{ batch.rows.length }} 份，成功 {{ succeeded }}，失败 {{ failed.length }}。</p>
      <el-table :data="displayRows" row-key="subjectCode" size="small">
        <el-table-column prop="subjectCode" label="档案编号" min-width="145" />
        <el-table-column label="状态" width="92">
          <template #default="{ row }">{{ statuses[row.state] }}</template>
        </el-table-column>
        <el-table-column prop="message" label="说明" min-width="145" />
      </el-table>
      <div class="batch-result-actions">
        <el-button v-if="failed.length" :disabled="saving" :loading="retrying" @click="retry">
          重试失败项（{{ failed.length }}）
        </el-button>
        <el-button v-if="failed.length && !saving" @click="editFailed">将失败编号填回表单</el-button>
        <el-pagination v-if="batch.rows.length > pageSize" v-model:current-page="page" small
          layout="prev, pager, next" :page-size="pageSize" :total="batch.rows.length" />
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { grantValidationCase } from '@/api/validationCohort.js'

const emit = defineEmits(['saved'])
const parallelism = 4
const pageSize = 8
const statuses = { waiting: '等待', sending: '登记中', succeeded: '已登记', failed: '失败' }
const open = ref(false)
const batch = ref(null)
const page = ref(1)
const saving = ref(false)
const retrying = ref(false)
const form = reactive({ project: '', researcher: '', authorizationRef: '', subjectCodes: '' })
const entries = computed(() => form.subjectCodes.split(/[\n\r\t,，;；]+/u).map(value => value.trim()).filter(Boolean))
const subjectCodes = computed(() => [...new Set(entries.value)])
const duplicates = computed(() => entries.value.length - subjectCodes.value.length)
const canSubmit = computed(() => form.project && form.researcher && form.authorizationRef && subjectCodes.value.length)
const finished = computed(() => batch.value?.rows.filter(row => row.state === 'succeeded' || row.state === 'failed').length ?? 0)
const succeeded = computed(() => batch.value?.rows.filter(row => row.state === 'succeeded').length ?? 0)
const failed = computed(() => batch.value?.rows.filter(row => row.state === 'failed') ?? [])
const displayRows = computed(() => batch.value?.rows.slice((page.value - 1) * pageSize, page.value * pageSize) ?? [])
let mounted = true
onBeforeUnmount(() => { mounted = false })

async function submit() {
  if (saving.value || !canSubmit.value) return
  const { project, researcher, authorizationRef } = form
  batch.value = {
    scope: { project: project.toUpperCase(), researcher, authorizationRef },
    rows: subjectCodes.value.map(subjectCode => ({ subjectCode, state: 'waiting', message: '' }))
  }
  page.value = 1
  await persist(batch.value.rows)
}

async function retry() {
  if (saving.value || !failed.value.length) return
  retrying.value = true
  try {
    await persist(failed.value)
  } finally {
    retrying.value = false
  }
}

function editFailed() {
  Object.assign(form, batch.value.scope, { subjectCodes: failed.value.map(row => row.subjectCode).join('\n') })
}

async function persist(rows) {
  saving.value = true
  const scope = batch.value.scope
  let savedAny = false
  try {
    for (const row of rows) {
      row.state = 'waiting'
      row.message = ''
    }
    for (let offset = 0; mounted && offset < rows.length; offset += parallelism) {
      const block = rows.slice(offset, offset + parallelism)
      await Promise.all(block.map(async row => {
        row.state = 'sending'
        try {
          await grantValidationCase({ ...scope, subjectCode: row.subjectCode })
          row.state = 'succeeded'
          savedAny = true
        } catch (problem) {
          row.state = 'failed'
          row.message = problem?.message || '暂时无法登记，请重试。'
        }
      }))
    }
    const firstFailure = batch.value.rows.findIndex(row => row.state === 'failed')
    if (firstFailure >= 0) page.value = Math.floor(firstFailure / pageSize) + 1
  } finally {
    saving.value = false
    if (mounted && savedAny) emit('saved')
  }
}
</script>

<style scoped>
.batch-help { margin-top: 0; }
.batch-fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 156px), 1fr)); gap: 0 12px; }
.batch-receipt { margin-top: 20px; }
.batch-result-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; margin-top: 12px; }
</style>
