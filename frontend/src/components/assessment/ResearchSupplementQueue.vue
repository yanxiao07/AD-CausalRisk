<template>
  <DataCard title="补件待办" padding="lg">
    <template #header-extra>
      <el-button @click="load()" :loading="loading">刷新补件</el-button>
    </template>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-else-if="loading" role="status">正在读取补件待办…</p>
    <p v-else-if="items.length === 0">本资料范围内还没有补件。</p>
    <article v-for="item in items" :key="item.id" class="supplement">
      <header>
        <strong>{{ item.subjectCode }} · 补件单 #{{ item.id }}</strong>
        <span>原报告 #{{ item.originalTaskId }} · {{ label(item) }}</span>
      </header>
      <p>{{ item.note }}</p>
      <ul v-if="item.issues.length">
        <li v-for="(issue, index) in item.issues" :key="index">
          {{ researchFeatureLabel(issue.feature) }} · {{ researchIssueLabel(issue.code) }}
        </li>
      </ul>
      <div class="supplement-actions">
        <el-button v-if="item.status === 'OPEN'" type="primary" :loading="busyId === item.id"
          @click="change(item, 'CLAIM')">领取补件</el-button>
        <template v-else-if="item.status === 'CLAIMED' && isAssignee(item)">
          <el-button @click="goToSubject(item)">查看档案</el-button>
          <el-button @click="emit('assess', item.subjectId)">提交新快照核验</el-button>
          <el-button type="primary" @click="chooseReplacement(item)">回传核验结果</el-button>
          <el-button :loading="busyId === item.id" @click="change(item, 'RELEASE')">退回待领取</el-button>
        </template>
        <RouterLink v-else-if="item.status === 'RETURNED' && isAssignee(item)"
          :to="`/reports/research/${item.replacementTaskId}`">查看回传报告 #{{ item.replacementTaskId }}</RouterLink>
      </div>
    </article>
    <el-pagination v-if="total > pageSize" v-model:current-page="page" :page-size="pageSize"
      :total="total" layout="prev, pager, next" @current-change="load" />

    <el-dialog v-model="returnOpen" title="回传核验结果" width="min(560px, 94vw)" destroy-on-close>
      <p v-if="replacementError" role="alert">{{ replacementError }}</p>
      <p v-else-if="!replacementLoading && replacements.length === 0">
        请先为该档案登记新快照并完成核验。新核验可以返回完成或资料仍缺失。
      </p>
      <p v-if="replacementLoading" role="status">正在读取已保存的新核验报告…</p>
      <fieldset v-else-if="replacements.length" class="replacement-list">
        <legend>选择新核验报告</legend>
        <label v-for="task in replacements" :key="task.taskId">
          <input v-model="replacementId" type="radio" name="supplement-replacement" :value="task.taskId">
          报告 #{{ task.taskId }} · {{ task.status === 'COMPLETED' ? '已完成' : '资料不足' }} · {{ formatDateTime(task.createdAt) }}
        </label>
      </fieldset>
      <template #footer>
        <el-button @click="returnOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!replacementId" :loading="Boolean(busyId)"
          @click="returnResult">回传医生</el-button>
      </template>
    </el-dialog>
  </DataCard>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import DataCard from '@/components/common/DataCard.vue'
import { listResearchReports } from '@/api/research.js'
import { listResearchSupplements, updateResearchSupplement } from '@/api/researchSupplements.js'
import { useUserStore } from '@/stores/user.js'
import { formatDateTime, parseInstant } from '@/utils/datetime.js'
import { researchFeatureLabel, researchIssueLabel } from '@/utils/researchResults.js'

const props = defineProps({ dataScope: { type: String, required: true } })
const emit = defineEmits(['assess'])
const router = useRouter()
const user = useUserStore()
const items = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const loading = ref(false)
const error = ref('')
const busyId = ref(null)
const returnOpen = ref(false)
const returning = ref(null)
const replacementId = ref(null)
const replacements = ref([])
const replacementLoading = ref(false)
const replacementError = ref('')
let listRequest = 0

function isAssignee(item) {
  return item.assignedTo === user.id
}

function label(item) {
  if (item.status === 'OPEN') return '待领取'
  if (item.status === 'RETURNED') return `已回传 #${item.replacementTaskId}`
  return isAssignee(item) ? '我负责' : `${item.assigneeName || '研究员'}处理中`
}

async function load() {
  const sequence = ++listRequest
  const dataScope = props.dataScope
  loading.value = true
  error.value = ''
  try {
    const result = await listResearchSupplements({ dataScope, page: page.value, pageSize })
    if (sequence === listRequest) {
      items.value = result.items
      total.value = result.total
    }
  } catch (problem) {
    if (sequence === listRequest) error.value = problem.message || '补件待办暂时无法读取'
  } finally {
    if (sequence === listRequest) loading.value = false
  }
}

async function change(item, action, taskId) {
  if (busyId.value) return
  busyId.value = item.id
  error.value = ''
  try {
    await updateResearchSupplement(item.id, action, taskId)
    if (action === 'RETURN' && returning.value?.id === item.id) returnOpen.value = false
    await load()
  } catch (problem) {
    const message = problem.message || '无法保存，请重试'
    if (action === 'RETURN' && returning.value?.id === item.id) replacementError.value = message
    else error.value = message
  } finally {
    busyId.value = null
  }
}

function goToSubject(item) {
  router.push({ path: '/subjects', query: { subjectId: item.subjectId, dataScope: item.dataScope } })
}

async function chooseReplacement(item) {
  returning.value = item
  replacementId.value = null
  replacements.value = []
  replacementError.value = ''
  replacementLoading.value = true
  returnOpen.value = true
  try {
    const page = await listResearchReports({
      subjectId: item.subjectId, dataScope: item.dataScope, pageSize: 100
    })
    if (returning.value?.id === item.id) {
      const created = parseInstant(item.createdAt)
      replacements.value = page.items.filter(task =>
        ['COMPLETED', 'BLOCKED'].includes(task.status) && task.snapshotId > item.originalSnapshotId
        && task.result && parseInstant(task.createdAt) >= created
      )
    }
  } catch (problem) {
    if (returning.value?.id === item.id) replacementError.value = problem.message || '新核验报告暂时无法读取'
  } finally {
    if (returning.value?.id === item.id) replacementLoading.value = false
  }
}

function returnResult() {
  if (returning.value && replacementId.value) change(returning.value, 'RETURN', replacementId.value)
}

watch(() => props.dataScope, () => {
  page.value = 1
  items.value = []
  returning.value = null
  returnOpen.value = false
  load()
}, { immediate: true })
</script>

<style scoped>
.supplement { padding: 14px 0; border-top: 1px solid var(--line); overflow-wrap: anywhere; }
.supplement header, .supplement-actions { display: flex; flex-wrap: wrap; gap: 8px 12px; }
.supplement header span { color: var(--muted); }
.supplement p { white-space: pre-wrap; }
.supplement-actions .el-button { margin: 0; }
.replacement-list { max-height: 260px; overflow: auto; padding: 8px 12px; }
.replacement-list label { display: block; padding: 8px 0; cursor: pointer; }
</style>
