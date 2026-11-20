<template>
  <section class="assessment-request" aria-label="预测评估">
    <h3>预测评估</h3>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="busy" role="status">正在提交评估，请稍候…</p>
    <p v-else-if="loading" role="status">正在读取评估状态…</p>
    <p v-else-if="status" role="status">{{ description }}</p>
    <div v-if="!loading" class="request-actions">
      <el-button v-if="status?.state === 'READY'" type="primary" :loading="busy" @click="submit">
        申请评估
      </el-button>
      <template v-else-if="status?.state === 'NEEDS_QUALITY' && status.qualityStatus !== 'ARCHIVED'">
        <el-button @click="emit('visit')">录入访视量表</el-button>
        <el-button @click="emit('quality')">检查当前资料</el-button>
      </template>
      <el-button v-else-if="['NEEDS_SNAPSHOT', 'NEEDS_SYNC'].includes(status?.state)" @click="emit('snapshot')">
        核对测试模型资料
      </el-button>
      <RouterLink v-if="status?.taskId" :to="`/reports/research/${status.taskId}`">查看评估报告</RouterLink>
      <el-button v-if="error || ['RESEARCH_REVIEW', 'RUNNING', 'NEEDS_SYNC'].includes(status?.state)"
        :disabled="busy" @click="load">刷新状态</el-button>
    </div>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { getAssessmentRequest, requestAssessment } from '@/api/assessmentRequests.js'

const props = defineProps({ subjectId: { type: Number, required: true }, revision: { type: Number, default: 0 } })
const emit = defineEmits(['visit', 'quality', 'snapshot'])
const status = ref(null)
const error = ref('')
const loading = ref(false)
const busy = ref(false)
let loadSequence = 0

const description = computed(() => {
  const current = status.value
  if (current?.qualityStatus === 'ARCHIVED' && current.state === 'NEEDS_QUALITY') return '该档案已归档。'
  if (current?.state === 'READY') {
    return current.dataScope === 'CONTROLLED_RESEARCH'
      ? '提交后由有档案权限的研究员核对来源及授权。'
      : '当前测试资料已齐备，提交后自动评估并进入待复核。'
  }
  const messages = {
    NEEDS_QUALITY: '请保存最近访视的 MMSE 和 CDR-SB，并对当前资料发起质控。',
    NEEDS_SNAPSHOT: '请保存与最近访视日期及量表分数一致的测试模型资料。',
    NEEDS_SYNC: '资料已保存，暂未同步到评估服务。',
    RESEARCH_REVIEW: '申请已提交，等待研究员核对来源及执行授权。',
    RUNNING: '评估正在运行。',
    REVIEW_PENDING: '评估已完成，请复核报告。',
    REVIEWED: '报告已有复核记录。',
    BLOCKED: '评估因资料问题阻断，可在报告查看缺项。',
    FAILED: '评估未完成，请查看报告或联系研究员。',
    OUTCOME_UNKNOWN: '评估尚未返回可确认的结果，请查看原任务。',
  }
  return messages[current?.state] || '暂时无法确认评估状态。'
})

async function load() {
  const sequence = ++loadSequence
  loading.value = true
  error.value = ''
  try {
    const result = await getAssessmentRequest(props.subjectId)
    if (sequence === loadSequence) status.value = result
  } catch (problem) {
    if (sequence === loadSequence) error.value = problem.message || '无法读取评估状态'
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

async function submit() {
  busy.value = true
  error.value = ''
  try {
    ++loadSequence
    status.value = await requestAssessment(props.subjectId)
  } catch (problem) {
    await load()
    error.value = problem.message || '评估提交失败，请查看最新状态'
  } finally {
    busy.value = false
  }
}

watch(() => props.revision, load, { immediate: true })
</script>

<style scoped>
.assessment-request { border: 1px solid var(--el-border-color-light); border-radius: 10px; padding: 16px; }
.assessment-request h3 { margin: 0; font-size: 16px; }
.assessment-request p { line-height: 1.55; }
.request-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.request-actions .el-button { margin: 0; min-height: 44px; }
</style>
