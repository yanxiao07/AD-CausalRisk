<template>
  <div class="graph-page">
    <PageHeader back title="评估贡献图" subtitle="按档案查看最近一次评估的模型贡献。">
      <template #extra>
        <el-button :icon="Refresh" :loading="loading" :disabled="!subjectId" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <DataCard padding="lg">
      <div class="graph-filters">
        <ResearchScopeSelect :model-value="scope" @update:model-value="setScope" />
        <el-select v-model="subjectId" class="subject-select" filterable remote clearable
          :remote-method="searchSubjects" :loading="subjectsLoading" aria-label="筛选档案"
          placeholder="选择档案 · 可按编号搜索" @visible-change="onSubjectPickerOpen">
          <el-option v-for="subject in subjects" :key="subject.id"
            :label="[subject.subjectCode, subject.alias].filter(Boolean).join(' · ')" :value="subject.id" />
        </el-select>
      </div>
    </DataCard>

    <DataCard v-if="!subjectId" padding="lg">
      <EmptyState :icon="Share" title="请先选择档案" description="选择档案后可查看最近一次评估的图谱。" />
    </DataCard>
    <el-skeleton v-else-if="loading" :rows="5" animated />
    <DataCard v-else-if="error" padding="lg">
      <EmptyState :icon="WarningFilled" title="评估加载失败" :description="error" action-text="重试" @action="load" />
    </DataCard>
    <DataCard v-else-if="!task" padding="lg">
      <EmptyState :icon="Share" title="此档案还没有评估"
        description="评估完成后，最近一次结果会出现在这里。" />
    </DataCard>
    <DataCard v-else padding="lg">
      <div class="result-head">
        <div>
          <h2>{{ task.subjectAlias || task.subjectCode || '评估结果' }}</h2>
          <p>{{ task.subjectCode }} · {{ formatDateTime(task.createdAt) }} · {{ researchScopeLabel(task.dataScope) }}</p>
        </div>
        <StatusTag :type="task.status" />
        <el-button type="primary" plain @click="router.push({ name: 'ResearchReportDetail', params: { taskId: task.taskId } })">
          查看本次报告
        </el-button>
      </div>
      <ResearchAttributionGraph v-if="contributions.length" :contributions="contributions" :height="420" />
      <EmptyState v-else :icon="Share" title="本次评估尚无图谱"
        :description="task.status === 'BLOCKED' ? '本次评估因资料条件未满足而阻断。可打开报告查看需核对的资料。' : '本次结果没有可展示的模型解释。可打开报告查看评估状态。'" />
    </DataCard>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Refresh, Share, WarningFilled } from '@element-plus/icons-vue'
import { listResearchReports } from '@/api/research.js'
import { listSubjects } from '@/api/subjects.js'
import ResearchAttributionGraph from '@/components/assessment/ResearchAttributionGraph.vue'
import ResearchScopeSelect from '@/components/assessment/ResearchScopeSelect.vue'
import DataCard from '@/components/common/DataCard.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import { formatDateTime } from '@/utils/datetime.js'
import { CONTROLLED_RESEARCH, modelContributions, researchScope, researchScopeLabel } from '@/utils/researchResults.js'

const route = useRoute()
const router = useRouter()
const scope = computed(() => researchScope(route.query.dataScope))
const subjectId = computed({
  get: () => {
    const id = Number(route.query.subjectId)
    return Number.isSafeInteger(id) && id > 0 ? id : null
  },
  set: value => router.replace({ query: { ...route.query, subjectId: value || undefined } })
})
const subjects = ref([])
const subjectsLoading = ref(false)
const task = ref(null)
const loading = ref(false)
const error = ref('')
const contributions = computed(() => modelContributions(task.value))
let requestId = 0
let searchId = 0

function setScope(value) {
  router.replace({ query: { ...route.query, subjectId: undefined, dataScope: value === CONTROLLED_RESEARCH ? undefined : value } })
}

function onSubjectPickerOpen(open) {
  if (open && !subjects.value.length) searchSubjects('')
}

async function searchSubjects(keyword) {
  const sequence = ++searchId
  subjectsLoading.value = true
  try {
    const result = await listSubjects({ keyword: keyword.trim(), pageSize: 30, dataScope: scope.value })
    if (sequence !== searchId) return
    const selected = subjects.value.find(subject => subject.id === subjectId.value)
    subjects.value = result.items || []
    if (selected && !subjects.value.some(subject => subject.id === selected.id)) subjects.value.unshift(selected)
  } catch {
    // Keep the selected subject available while the user retries the search.
  } finally {
    if (sequence === searchId) subjectsLoading.value = false
  }
}

async function load() {
  const sequence = ++requestId
  error.value = ''
  task.value = null
  const id = subjectId.value
  loading.value = Boolean(id)
  if (!id) return
  try {
    const result = await listResearchReports({ pageSize: 1, dataScope: scope.value, subjectId: id })
    if (sequence !== requestId) return
    const latest = result?.items?.[0] || null
    if (latest && (latest.dataScope !== scope.value || latest.subjectId !== id)) {
      throw new Error('评估资料与所选档案不一致，请刷新重试。')
    }
    task.value = latest
    if (task.value && !subjects.value.some(subject => subject.id === id)) {
      const { subjectCode, subjectAlias } = task.value
      subjects.value.push({ id, subjectCode, alias: subjectAlias })
    }
  } catch (problem) {
    if (sequence === requestId) error.value = problem?.message || '请稍后重试'
  } finally {
    if (sequence === requestId) loading.value = false
  }
}

watch(scope, () => {
  searchId += 1
  subjects.value = []
  subjectsLoading.value = false
})
watch([scope, subjectId], load, { immediate: true })
</script>

<style scoped>
.graph-page { display: grid; gap: 18px; }
.graph-filters, .result-head { display: flex; align-items: center; flex-wrap: wrap; gap: 14px; }
.subject-select { width: min(100%, 310px); }
.result-head { margin-bottom: 20px; }
.result-head > :first-child { margin-right: auto; }
.result-head h2 { margin: 0 0 5px; }
.result-head p { margin: 0; color: var(--muted); font-size: 14px; }
</style>
