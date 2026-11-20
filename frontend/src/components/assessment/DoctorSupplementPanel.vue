<template>
  <DataCard title="补件交接" padding="lg">
    <p v-if="loading" role="status">正在读取补件状态…</p>
    <el-alert v-else-if="loadError" type="error" :closable="false" :title="loadError" />
    <template v-else-if="item">
      <p v-if="item.status === 'OPEN'">补件单 #{{ item.id }}已提交，等待有档案权限的研究员领取。</p>
      <p v-else-if="item.status === 'CLAIMED'">
        补件单 #{{ item.id }}由{{ item.assigneeName || '领取的研究员' }}处理。
        <el-button :loading="saving" @click="release">退回待领取</el-button>
      </p>
      <p v-else>
        补件单 #{{ item.id }}已回传。
        <RouterLink :to="`/reports/research/${item.replacementTaskId}`">查看新核验报告 #{{ item.replacementTaskId }}</RouterLink>
      </p>
      <p class="supplement-note">{{ item.note }}</p>
    </template>
    <el-form v-else label-position="top" @submit.prevent="submit">
      <el-form-item label="请研究员核对哪些资料">
        <el-input v-model="note" type="textarea" :rows="3" maxlength="1000" show-word-limit
          placeholder="例如：请核对访视量表的完成记录和原始来源。" />
      </el-form-item>
      <p v-if="task.status === 'BLOCKED'">保存时会附上本次核验列出的资料问题。</p>
      <el-button native-type="submit" type="primary" :disabled="!note.trim()" :loading="saving">
        发送补件单
      </el-button>
    </el-form>
    <el-button v-if="loadError" @click="load">重试</el-button>
    <p v-if="actionError" role="alert" class="supplement-error">{{ actionError }}</p>
  </DataCard>
</template>

<script setup>
import { ref, watch } from 'vue'
import DataCard from '@/components/common/DataCard.vue'
import { createResearchSupplement, listResearchSupplements, updateResearchSupplement } from '@/api/researchSupplements.js'

const props = defineProps({ task: { type: Object, required: true } })
const loading = ref(true)
const loadError = ref('')
const actionError = ref('')
const item = ref(null)
const note = ref('')
const saving = ref(false)
let request = 0

async function load() {
  const sequence = ++request
  loading.value = true
  loadError.value = ''
  try {
    const page = await listResearchSupplements({ originalTaskId: props.task.taskId, pageSize: 1 })
    if (sequence === request) item.value = page.items[0] || null
  } catch (error) {
    if (sequence === request) loadError.value = error.message || '补件状态暂时无法读取'
  } finally {
    if (sequence === request) loading.value = false
  }
}

async function write(operation) {
  if (saving.value) return
  const sequence = request
  saving.value = true
  actionError.value = ''
  try {
    const result = await operation()
    if (sequence === request) item.value = result
  } catch (error) {
    if (sequence === request) actionError.value = error.message || '无法保存，请重试'
  } finally {
    saving.value = false
  }
}

function submit() {
  const text = note.value.trim()
  if (text) write(() => createResearchSupplement(props.task.taskId, text))
}

function release() {
  if (item.value) write(() => updateResearchSupplement(item.value.id, 'RELEASE'))
}

watch(() => props.task.taskId, () => {
  item.value = null
  note.value = ''
  actionError.value = ''
  load()
}, { immediate: true })
</script>

<style scoped>
.supplement-note { white-space: pre-wrap; overflow-wrap: anywhere; }
.supplement-error { color: var(--el-color-danger); }
</style>
