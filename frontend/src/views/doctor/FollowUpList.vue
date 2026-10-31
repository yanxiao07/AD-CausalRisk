<template>
  <div class="followup-page">
    <PageHeader title="随访计划" subtitle="为受试者安排复诊，并把已完成的随访标记掉。">
      <template #extra>
        <el-button :icon="Refresh" @click="reload">刷新</el-button>
        <el-button type="primary" :disabled="!subjectId" @click="dialogOpen = true">
          新建随访
        </el-button>
      </template>
    </PageHeader>

    <DataCard>
      <div class="picker">
        <span class="picker__label">受试者</span>
        <el-select
          v-model="subjectId"
          filterable
          clearable
          placeholder="按编号或别名搜索"
          class="picker__select"
          :loading="subjectsLoading"
          @change="loadFollowUps"
        >
          <el-option
            v-for="item in subjects"
            :key="item.id"
            :label="`${item.alias}（${item.subjectCode}）`"
            :value="item.id"
          />
        </el-select>
        <span class="picker__hint">
          契约里随访接口按受试者查询（GET /api/subjects/{subjectId}/follow-ups），所以先选人再看计划。
        </span>
      </div>
    </DataCard>

    <DataCard title="随访安排" :subtitle="subjectId ? '按计划时间升序' : '请先选择受试者'">
      <el-skeleton v-if="loading" :rows="3" animated />

      <EmptyState
        v-else-if="error"
        :icon="WarningFilled"
        title="随访加载失败"
        :description="error"
        action-text="重试"
        @action="loadFollowUps"
      />

      <EmptyState
        v-else-if="!subjectId"
        :icon="User"
        title="还没有选择受试者"
        description="从上方选择一个受试者，即可查看/安排他的随访计划。"
      />

      <EmptyState
        v-else-if="items.length === 0"
        :icon="Timer"
        title="这位受试者还没有随访计划"
        description="点击右上角「新建随访」安排第一次复诊。"
      />

      <el-table v-else :data="items" class="table" row-key="id">
        <el-table-column label="计划时间" width="180">
          <template #default="{ row }">{{ formatTime(row.scheduledAt) }}</template>
        </el-table-column>
        <el-table-column prop="purpose" label="目的" min-width="220" show-overflow-tooltip />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <StatusTag :type="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              text
              type="primary"
              :disabled="row.status !== 'PLANNED'"
              :loading="updating === row.id"
              @click="changeStatus(row, 'COMPLETED')"
            >
              标记完成
            </el-button>
            <el-button
              text
              type="danger"
              :disabled="row.status === 'CANCELLED'"
              :loading="updating === row.id"
              @click="changeStatus(row, 'CANCELLED')"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </DataCard>

    <FollowUpDialog
      v-if="subjectId"
      v-model="dialogOpen"
      :subject-id="subjectId"
      @created="loadFollowUps"
    />
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Timer, User, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import FollowUpDialog from '@/components/followup/FollowUpDialog.vue'
import { listSubjects } from '@/api/subjects.js'
import { listFollowUps, updateFollowUp } from '@/api/followups.js'

const subjects = ref([])
const subjectsLoading = ref(false)
const subjectId = ref(null)

const items = ref([])
const loading = ref(false)
const error = ref('')
const updating = ref(null)
const dialogOpen = ref(false)

const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
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

async function loadSubjects() {
  subjectsLoading.value = true
  try {
    const data = await listSubjects({ page: 1, pageSize: 100 })
    subjects.value = Array.isArray(data?.items) ? data.items : []
  } catch {
    subjects.value = []
  } finally {
    subjectsLoading.value = false
  }
}

async function loadFollowUps() {
  if (!subjectId.value) {
    items.value = []
    return
  }
  loading.value = true
  error.value = ''
  try {
    const data = await listFollowUps(subjectId.value)
    items.value = (Array.isArray(data) ? data : []).slice().sort(
      (a, b) => new Date(a.scheduledAt) - new Date(b.scheduledAt)
    )
  } catch (problem) {
    items.value = []
    error.value = problem?.message || '请稍后重试'
  } finally {
    loading.value = false
  }
}

async function changeStatus(row, status) {
  const label = status === 'COMPLETED' ? '标记为已完成' : '取消这条随访'
  try {
    await ElMessageBox.confirm(`确定要${label}吗？`, '随访状态', {
      confirmButtonText: '确定',
      cancelButtonText: '返回',
      type: status === 'COMPLETED' ? 'success' : 'warning'
    })
  } catch {
    return
  }

  updating.value = row.id
  try {
    const updated = await updateFollowUp(row.id, { status })
    const index = items.value.findIndex((item) => item.id === row.id)
    if (index >= 0) items.value[index] = { ...items.value[index], ...updated }
    ElMessage.success(status === 'COMPLETED' ? '已标记完成' : '已取消')
  } catch {
    // request.js 已提示（例如已取消的随访不可再变更）
  } finally {
    updating.value = null
  }
}

function reload() {
  if (subjectId.value) loadFollowUps()
  else loadSubjects()
}

onMounted(loadSubjects)
</script>

<style scoped>
.followup-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.picker {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.picker__label {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-700);
}

.picker__select {
  width: 320px;
}

.picker__hint {
  font-size: 12px;
  color: var(--soft);
}

.table {
  width: 100%;
}
</style>
