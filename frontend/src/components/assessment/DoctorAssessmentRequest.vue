<template>
  <section class="assessment-request" aria-label="预测评估">
    <h3>预测评估</h3>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="busy" role="status">正在提交评估，请稍候…</p>
    <p v-else-if="loading" role="status">正在读取评估状态…</p>
    <p v-else-if="status" id="assessment-request-status" role="status">
      <strong v-if="waitingForModel" class="assessment-state">申请已登记，等待可用模型</strong>
      {{ description }}
    </p>
    <div class="request-actions">
      <el-button v-if="!waitingForModel" type="primary" :disabled="!canSubmit" :loading="busy"
        :aria-describedby="status && !loading && !busy ? 'assessment-request-status' : undefined"
        @click="submit">
        {{ submitLabel }}
      </el-button>
      <el-button v-if="waitingForModel" @click="emit('results')">查看评估结果</el-button>
      <template v-if="!loading && status?.state === 'NEEDS_QUALITY' && status.qualityStatus !== 'ARCHIVED'">
        <el-button @click="emit('visit')">录入访视量表</el-button>
        <el-button v-if="canCheckQuality" @click="emit('quality')">检查当前资料</el-button>
      </template>
      <el-button v-else-if="!loading && status?.state === 'NEEDS_SNAPSHOT'" @click="emit('snapshot', 'prepare')">
        查看缺少的测试输入
      </el-button>
      <el-button v-else-if="!loading && status?.state === 'NEEDS_PREDICTION_INPUTS'" @click="emit('savedVisit')">查看已保存访视</el-button>
      <el-button v-else-if="!loading && status?.state === 'NEEDS_SYNC' && !isCare" @click="emit('snapshot', 'history')">
        查看测试输入状态
      </el-button>
      <RouterLink v-if="status?.taskId" :to="`/reports/research/${status.taskId}`">查看评估报告</RouterLink>
      <el-button v-if="error || ['WAITING_FOR_VALIDATED_MODEL', 'RESEARCH_REVIEW', 'RUNNING', 'NEEDS_SYNC'].includes(status?.state)"
        :disabled="busy || loading" @click="load">刷新状态</el-button>
    </div>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { getAssessmentRequest, requestAssessment } from '@/api/assessmentRequests.js'
import { CARE_WORKFLOW } from '@/utils/researchResults.js'

const props = defineProps({
  subjectId: { type: Number, required: true },
  revision: { type: Number, default: 0 },
  canCheckQuality: { type: Boolean, default: false },
})
const emit = defineEmits(['visit', 'quality', 'snapshot', 'scope', 'results', 'savedVisit'])
const status = ref(null)
const error = ref('')
const loading = ref(false)
const busy = ref(false)
let loadSequence = 0
const isCare = computed(() => status.value?.dataScope === CARE_WORKFLOW)
const retryCare = computed(() => isCare.value && status.value?.state === 'NEEDS_SYNC')
const canSubmit = computed(() => !loading.value && !busy.value
  && (['READY', 'CARE_PREDICTION_READY'].includes(status.value?.state) || retryCare.value))
const submitLabel = computed(() => {
  if (retryCare.value) return '重新提交评估'
  if (status.value?.state === 'CARE_PREDICTION_READY') return '提交预测评估'
  return status.value?.dataScope === 'CONSTRUCTED_VALIDATION' ? '提交测试评估' : '提交评估申请'
})
const waitingForModel = computed(() => status.value?.state === 'WAITING_FOR_VALIDATED_MODEL')

const description = computed(() => {
  const current = status.value
  if (current?.state === 'NEEDS_QUALITY') {
    if (current.qualityStatus === 'ARCHIVED') return '该档案已归档。'
    if (!props.canCheckQuality) return '请由有原始资料查看权限的同事检查当前档案资料。'
    if (current.qualityStatus === 'STALE') return '档案资料已更新，请重新质控后再提交。'
    if (current.qualityStatus === 'BLOCKED') return '当前质控有阻断项。请检查当前资料，按质控结果补齐缺项。'
    return '请保存最近访视的 MMSE 和 CDR-SB，并对当前资料发起质控。'
  }
  if (current?.state === 'READY') {
    if (current.dataScope === CARE_WORKFLOW) return '资料已通过核对。普通患者档案的预测尚未开放，提交后会登记本次申请。'
    return current.dataScope === 'CONTROLLED_RESEARCH'
      ? '提交后由有档案权限的研究员核对来源及授权。'
      : '当前测试资料已齐备，提交后自动评估并进入待复核。'
  }
  const messages = {
    CARE_PREDICTION_READY: '七项资料已齐备。提交后系统自动运行，并在本档案保存研究结果。',
    NEEDS_PREDICTION_INPUTS: '最近访视仍缺补充资料。请打开已保存访视补录，再做本次质控。',
    NEEDS_SNAPSHOT: '最近访视已保存。演练模型还需要年龄、受教育年限和三项量表。访视日期、MMSE、CDR-SB 会自动带入。',
    NEEDS_SYNC: isCare.value ? '输入已保存，评估服务暂未确认。可以重新提交。' : '测试输入已保存，暂未同步到评估服务。请核对服务状态后刷新。',
    RESEARCH_REVIEW: '申请已提交，等待研究员核对来源及执行授权。',
    WAITING_FOR_VALIDATED_MODEL: '普通患者档案的预测尚未开放，本次暂未生成报告。可继续安排随访。',
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
    if (sequence === loadSequence) {
      status.value = result
      emit('scope', { subjectId: props.subjectId, dataScope: result.dataScope })
    }
  } catch (problem) {
    if (sequence === loadSequence) {
      status.value = null
      emit('scope', { subjectId: props.subjectId, dataScope: null })
      error.value = problem.message || '无法读取评估状态'
    }
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

async function submit() {
  if (!canSubmit.value) return
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
.assessment-state { display: block; margin-bottom: 4px; }
.request-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.request-actions .el-button { margin: 0; min-height: 44px; }
</style>
