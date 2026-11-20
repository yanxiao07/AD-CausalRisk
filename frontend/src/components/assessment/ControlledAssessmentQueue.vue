<template>
  <DataCard title="医生提交的评估申请" padding="lg">
    <template #header-extra>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </template>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-else-if="loading" role="status">正在读取评估申请…</p>
    <p v-else-if="!items.length">没有您有权处理的评估申请。</p>
    <article v-for="item in items" :key="item.id" class="assessment-item">
      <strong>{{ item.alias || item.subjectCode }}</strong>
      <p>{{ message(item) }}</p>
      <div class="assessment-actions">
        <RouterLink :to="{ path: '/subjects', query: { subjectId: item.subjectId, dataScope: 'CONTROLLED_RESEARCH' } }">
          查看档案
        </RouterLink>
        <el-button v-if="item.canExecute" type="primary" :loading="busyId === item.id" @click="execute(item)">
          确认并运行评估
        </el-button>
        <RouterLink v-if="item.taskId" :to="`/reports/research/${item.taskId}`">查看报告</RouterLink>
      </div>
    </article>
    <el-pagination v-if="total > pageSize" v-model:current-page="page" :page-size="pageSize"
      :total="total" layout="prev, pager, next" @current-change="load" />
  </DataCard>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import DataCard from '@/components/common/DataCard.vue'
import { executeAssessmentRequest, getAssessmentRequests } from '@/api/assessmentRequests.js'

const items = ref([])
const error = ref('')
const loading = ref(false)
const busyId = ref(null)
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
let sequence = 0

function message(item) {
  const text = {
    NEEDS_QUALITY: '资料质控已变化，请医生重新核对后提交。',
    RESEARCH_REVIEW: item.canExecute ? '已有匹配的执行授权，请在核实来源后确认。' : '等待来源证据及执行授权。',
    RUNNING: '正在评估。',
    REVIEW_PENDING: '评估已完成，等待医生复核。',
    REVIEWED: '医生已复核。',
    BLOCKED: '资料核验阻断，请查看报告。',
    FAILED: '评估失败，请查看原报告。',
    OUTCOME_UNKNOWN: '评估结果待确认，请查看原报告。',
  }
  return text[item.state] || '正在核对状态。'
}

async function load() {
  const request = ++sequence
  loading.value = true
  error.value = ''
  try {
    const result = await getAssessmentRequests(page.value)
    if (request !== sequence) return
    items.value = result.items
    total.value = result.total
    pageSize.value = result.pageSize
  } catch (problem) {
    if (request === sequence) error.value = problem.message || '无法读取评估申请'
  } finally {
    if (request === sequence) loading.value = false
  }
}

async function execute(item) {
  busyId.value = item.id
  try {
    await executeAssessmentRequest(item.id)
    await load()
  } catch (problem) {
    await load()
    error.value = problem.message || '评估无法提交'
  } finally {
    busyId.value = null
  }
}

onMounted(load)
</script>

<style scoped>
.assessment-item { padding: 14px 0; border-top: 1px solid var(--el-border-color-light); }
.assessment-actions { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
.assessment-actions .el-button { min-height: 44px; }
</style>
