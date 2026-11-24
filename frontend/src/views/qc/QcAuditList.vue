<template>
  <div class="page">
    <a-row :gutter="16" class="stat-row">
      <a-col :xs="12" :sm="8" :md="6" v-for="s in statCards" :key="s.key">
        <a-card :bordered="false" class="stat-card" :class="'stat-' + s.key">
          <a-statistic :value="s.value" :title="s.label">
            <template #suffix>
              <span class="stat-unit">{{ s.unit }}</span>
            </template>
          </a-statistic>
        </a-card>
      </a-col>
    </a-row>

    <a-card :bordered="false" class="toolbar-card">
      <div class="toolbar">
        <div class="toolbar-left">
          <a-button
            v-permission="['ADMIN', 'RESEARCHER']"
            type="primary"
            :loading="loading"
            @click="fetchList"
          >
            <template #icon><icon-refresh /></template>
            刷新
          </a-button>
        </div>
        <div class="toolbar-right">
          <a-select
            v-model="filterStatus"
            placeholder="审核状态"
            style="width: 140px"
            allow-clear
            @change="fetchList"
          >
            <a-option value="PENDING">待审核</a-option>
            <a-option value="APPROVED">已通过</a-option>
            <a-option value="REJECTED">已驳回</a-option>
          </a-select>
        </div>
      </div>
    </a-card>

    <a-card :bordered="false" class="main-card">
      <div class="table-head">
        <a-space>
          <span class="card-title">质控审核记录</span>
          <a-tag color="arcoblue" size="small">{{ total }} 条记录</a-tag>
        </a-space>
      </div>

      <a-alert
        v-if="error"
        type="error"
        :message="'加载失败：' + error"
        class="alert-error"
        show-icon
      />

      <a-table
        :data="list"
        :loading="loading"
        :bordered="false"
        size="medium"
        row-key="id"
        stripe
        :pagination="pagination"
        @page-change="onPageChange"
        class="qc-table"
      >
        <template #columns>
          <a-table-column title="受试者编号" :width="140">
            <template #cell="{ record }">
              <a-link @click="handleDetail(record)">
                {{ record.subjectCode || '—' }}
              </a-link>
            </template>
          </a-table-column>
          <a-table-column title="访视编号" :width="110" align="center">
            <template #cell="{ record }">
              {{ record.visitCode || '—' }}
            </template>
          </a-table-column>
          <a-table-column title="异常项" :min-width="220">
            <template #cell="{ record }">
              {{ formatItems(record.abnormalItems) }}
            </template>
          </a-table-column>
          <a-table-column title="审核人" :width="120" align="center">
            <template #cell="{ record }">
              {{ record.auditorName || '—' }}
            </template>
          </a-table-column>
          <a-table-column title="状态" :width="110" align="center">
            <template #cell="{ record }">
              <a-tag :color="statusColor(record.status)" size="small">
                {{ statusText(record.status) }}
              </a-tag>
            </template>
          </a-table-column>
          <a-table-column title="操作" :width="120" align="center">
            <template #cell="{ record }">
              <a-link
                v-if="record.status === 'PENDING'"
                v-permission="['ADMIN', 'RESEARCHER']"
                status="primary"
                @click="openAudit(record)"
              >
                审核
              </a-link>
              <span v-else class="no-op">—</span>
            </template>
          </a-table-column>
        </template>
      </a-table>
    </a-card>

    <a-modal
      v-model:visible="auditVisible"
      title="质控审核"
      :closable="true"
      :mask-closable="false"
      @before-ok="handleAuditOk"
      @cancel="resetAudit"
    >
      <a-descriptions v-if="auditTarget" :column="1" size="small" bordered>
        <a-descriptions-item label="受试者编号">{{ auditTarget.subjectCode || '—' }}</a-descriptions-item>
        <a-descriptions-item label="访视编号">{{ auditTarget.visitCode || '—' }}</a-descriptions-item>
        <a-descriptions-item label="异常项">{{ formatItems(auditTarget.abnormalItems) }}</a-descriptions-item>
      </a-descriptions>

      <a-form layout="vertical" class="audit-form" :model="auditForm">
        <a-form-item label="审核结论" required>
          <a-radio-group v-model="auditForm.status">
            <a-radio value="APPROVED">通过</a-radio>
            <a-radio value="REJECTED">驳回</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item label="审核意见" required>
          <a-textarea
            v-model="auditForm.auditComment"
            :max-length="200"
            placeholder="请输入审核意见"
            allow-clear
          />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import { getQcList, getQcStats, submitAudit } from '@/api/qc.js'

const USE_MOCK = import.meta.env.VITE_USE_MOCK !== 'false'

const loading = ref(false)
const error = ref('')
const list = ref([])
const total = ref(0)
const filterStatus = ref('')

const pagination = reactive({
  current: 1,
  pageSize: 10,
  showTotal: true,
  total: 0
})

const stats = reactive({ pendingCount: 0, approvedCount: 0, rejectedCount: 0 })

const statCards = computed(() => [
  { key: 'pending', label: '待审核', value: stats.pendingCount, unit: '' },
  { key: 'approved', label: '已通过', value: stats.approvedCount, unit: '' },
  { key: 'rejected', label: '已驳回', value: stats.rejectedCount, unit: '' },
])

function statusColor(s) {
  return { PENDING: 'orange', APPROVED: 'green', REJECTED: 'red' }[s] || 'gray'
}

function statusText(s) {
  return { PENDING: '待审核', APPROVED: '已通过', REJECTED: '已驳回' }[s] || s || '—'
}

function formatItems(raw) {
  if (!raw) return '—'
  try {
    const arr = JSON.parse(raw)
    if (Array.isArray(arr)) return arr.join('；')
    return String(raw)
  } catch {
    return String(raw)
  }
}

async function fetchStats() {
  try {
    const data = await getQcStats()
    stats.pendingCount = data?.pendingCount ?? 0
    stats.approvedCount = data?.approvedCount ?? 0
    stats.rejectedCount = data?.rejectedCount ?? 0
  } catch (err) {
    // 统计失败不阻塞列表
  }
}

async function fetchList() {
  loading.value = true
  error.value = ''
  try {
    const data = await getQcList({
      page: pagination.current,
      pageSize: pagination.pageSize,
      status: filterStatus.value || undefined
    })
    list.value = data?.items || []
    total.value = data?.total ?? list.value.length
    pagination.total = data?.total ?? list.value.length
  } catch (err) {
    error.value = err?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function onPageChange(page) {
  pagination.current = page
  fetchList()
}

function handleDetail(record) {
  Message.info('受试者 ' + (record.subjectCode || record.id) + '：详情页待开发')
}

const auditVisible = ref(false)
const auditTarget = ref(null)
const auditForm = reactive({ status: 'APPROVED', auditComment: '' })

function openAudit(record) {
  auditTarget.value = record
  auditForm.status = 'APPROVED'
  auditForm.auditComment = ''
  auditVisible.value = true
}

function resetAudit() {
  auditVisible.value = false
  auditTarget.value = null
  auditForm.status = 'APPROVED'
  auditForm.auditComment = ''
}

async function handleAuditOk() {
  if (!auditTarget.value) return
  if (!auditForm.auditComment || !auditForm.auditComment.trim()) {
    Message.warning('请填写审核意见')
    return false
  }
  try {
    await submitAudit(auditTarget.value.id, {
      status: auditForm.status,
      auditComment: auditForm.auditComment.trim()
    })
    Message.success('审核提交成功')
    resetAudit()
    fetchStats()
    fetchList()
    return true
  } catch (err) {
    Message.error(err?.message || '审核提交失败')
    return false
  }
}

onMounted(() => {
  fetchStats()
  fetchList()
})
</script>

<style scoped>
.qc-table :deep(.no-op) {
  color: var(--color-text-4, #c9cdd4);
  font-size: 13px;
}

.audit-form {
  margin-top: 16px;
}
</style>