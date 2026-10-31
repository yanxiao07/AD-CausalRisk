<template>
  <div class="qc-page">
    <PageHeader title="数据质控" subtitle="导入后的异常项由系统列出，研究员或管理员逐条通过/驳回。">
      <template #extra>
        <el-radio-group v-model="status" size="small" @change="reload">
          <el-radio-button label="PENDING">待审</el-radio-button>
          <el-radio-button label="APPROVED">已通过</el-radio-button>
          <el-radio-button label="REJECTED">已驳回</el-radio-button>
          <el-radio-button label="">全部</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" @click="reload">刷新</el-button>
      </template>
    </PageHeader>

    <DataCard padding="none">
      <el-skeleton v-if="loading" :rows="4" animated />

      <EmptyState
        v-else-if="error"
        :icon="WarningFilled"
        title="质控列表加载失败"
        :description="error"
        action-text="重试"
        @action="reload"
      />

      <EmptyState
        v-else-if="items.length === 0"
        :icon="Checked"
        :title="status === 'PENDING' ? '没有待审的质控记录' : '没有符合条件的质控记录'"
        description="导入 CSV 后，发现异常的行会进入这里等待人工处理。"
      />

      <template v-else>
        <el-table :data="items" class="table" row-key="id">
          <el-table-column label="受试者" min-width="180">
            <template #default="{ row }">
              <span class="subject">{{ row.subjectAlias || '—' }}</span>
              <span class="subject__code">{{ row.subjectCode || `#${row.subjectId}` }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="visitCode" label="访视" width="120" />
          <el-table-column prop="abnormalItems" label="异常项" min-width="220" show-overflow-tooltip />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <StatusTag :type="row.status" />
            </template>
          </el-table-column>
          <el-table-column label="提交时间" width="170">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="170" fixed="right">
            <template #default="{ row }">
              <template v-if="row.status === 'PENDING'">
                <el-button
                  text
                  type="primary"
                  :loading="auditing === row.id"
                  @click="audit(row, 'APPROVED')"
                >
                  通过
                </el-button>
                <el-button
                  text
                  type="danger"
                  :loading="auditing === row.id"
                  @click="audit(row, 'REJECTED')"
                >
                  驳回
                </el-button>
              </template>
              <span v-else class="done">{{ row.auditorName || '已处理' }}</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager">
          <el-pagination
            v-model:current-page="page"
            :page-size="pageSize"
            :total="total"
            layout="total, prev, pager, next"
            background
            @current-change="load"
          />
        </div>
      </template>
    </DataCard>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Checked, Refresh, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { listQcRecords, submitQcAudit } from '@/api/qc.js'

const items = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const status = ref('PENDING')
const loading = ref(true)
const error = ref('')
const auditing = ref(null)

const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
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

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await listQcRecords({ status: status.value || undefined, page: page.value, pageSize: pageSize.value })
    items.value = Array.isArray(data?.items) ? data.items : []
    total.value = Number(data?.total ?? items.value.length)
  } catch (problem) {
    items.value = []
    total.value = 0
    error.value = problem?.message || '请稍后重试'
  } finally {
    loading.value = false
  }
}

function reload() {
  page.value = 1
  load()
}

async function audit(row, decision) {
  const label = decision === 'APPROVED' ? '通过' : '驳回'
  let comment = ''
  try {
    const result = await ElMessageBox.prompt(
      `确定${label}这条质控记录吗？可填写说明（可留空）。`,
      `${label}质控`,
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: decision === 'REJECTED' ? '请写明驳回原因（必填）' : '请填写审核意见（必填）',
        // 后端 QcSubmitRequest.auditComment 是 @NotBlank：空提交会 400，这里必须拦住
        inputValidator: (value) => {
          const text = (value || '').trim()
          if (!text) return '复核意见不能为空（后端要求每次决定都有人类说明）'
          if (text.length > 500) return '不能超过 500 字'
          return true
        }
      }
    )
    comment = (result.value || '').trim()
  } catch {
    return
  }

  auditing.value = row.id
  try {
    const updated = await submitQcAudit(row.id, {
      status: decision,
      auditComment: comment,
      abnormalItems: row.abnormalItems || undefined
    })
    const index = items.value.findIndex((item) => item.id === row.id)
    if (index >= 0) {
      // 当前筛选是"待审"时，处理完就从列表里移除
      if (status.value === 'PENDING') items.value.splice(index, 1)
      else items.value[index] = { ...items.value[index], ...updated }
    }
    ElMessage.success(`已${label}`)
  } catch {
    // request.js 已提示（例如记录不是 PENDING）
  } finally {
    auditing.value = null
  }
}

onMounted(load)
</script>

<style scoped>
.qc-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.table {
  width: 100%;
}

.subject {
  font-weight: 600;
  color: var(--ink);
}

.subject__code {
  margin-left: 8px;
  font-size: 12px;
  color: var(--muted);
  font-family: var(--font-display);
}

.done {
  font-size: 12px;
  color: var(--soft);
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 14px 18px;
  border-top: 1px solid var(--line);
}
</style>
