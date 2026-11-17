<template>
  <div class="audit-page">
    <PageHeader back title="审计日志" subtitle="谁在什么时候做了什么；仅管理员可见，按时间倒序。">
      <template #extra>
        <el-button :icon="Refresh" :loading="loading" @click="load(page)">刷新</el-button>
      </template>
    </PageHeader>

    <DataCard padding="none">
      <div class="filter">
        <el-input
          v-model="eventType"
          class="filter__input"
          size="small"
          clearable
          placeholder="事件类型（如 ASSESSMENT_REVIEW）"
          @keyup.enter="search"
        />
        <el-input
          v-model="actorId"
          class="filter__input filter__input--narrow"
          size="small"
          clearable
          placeholder="操作人 ID"
          @keyup.enter="search"
        />
        <el-input
          v-model="keyword"
          class="filter__input"
          size="small"
          clearable
          placeholder="摘要关键词"
          @keyup.enter="search"
        />
        <el-input
          v-model="traceId"
          class="filter__input filter__input--narrow"
          size="small"
          clearable
          placeholder="traceId"
          @keyup.enter="search"
        />
        <el-button size="small" :icon="Search" @click="search">查询</el-button>
        <span class="filter__count">共 {{ total }} 条</span>
      </div>

      <AuditStream
        :events="items"
        :loading="loading"
        :error="error"
        show-refresh
        @refresh="load(page)"
        @row-click="openDetail"
      />

      <div v-if="total > pageSize" class="pager">
        <el-pagination
          v-model:current-page="page"
          :page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
          background
          @current-change="load"
        />
      </div>
    </DataCard>

    <p class="audit-page__foot">
      分页与筛选由服务端完成（GET /api/audit-logs/page）；事件类型需要精确匹配，操作人与摘要关键词支持筛选。
    </p>

    <el-drawer v-model="detailOpen" title="审计详情" size="560px">
      <template v-if="selected">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="事件 ID">{{ selected.id }}</el-descriptions-item>
          <el-descriptions-item label="时间">{{ formatDetailTime(selected.timestamp) }}</el-descriptions-item>
          <el-descriptions-item label="事件类型">
            <el-tag size="small" effect="plain">{{ selected.eventType }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="操作人">{{ selected.actorId ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="TraceId">
            <span class="mono">{{ selected.traceId || '—' }}</span>
            <el-button v-if="selected.traceId" link type="primary" @click="copyTrace">复制</el-button>
          </el-descriptions-item>
          <el-descriptions-item label="摘要">{{ selected.summary || '—' }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          class="detail-note"
          type="info"
          :closable="false"
          show-icon
          title="当前审计表未持久化完整请求体与响应体"
          description="audit_log 只记录事件类型、TraceId、操作人、摘要和时间。下方 payload 是由已记录字段重建的审计快照；如后续需要完整请求/响应，需要扩展审计表和写入逻辑。"
        />

        <h3 class="detail-title">审计快照（payload）</h3>
        <pre class="detail-json">{{ payloadText }}</pre>

        <h3 class="detail-title">返回结果</h3>
        <pre class="detail-json">{{ resultText }}</pre>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import AuditStream from '@/components/admin/AuditStream.vue'
import { listAuditLogsPaged } from '@/api/audit.js'
import { formatDateTime } from '@/utils/datetime.js'

const items = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const eventType = ref('')
const actorId = ref('')
const keyword = ref('')
const traceId = ref('')
const loading = ref(false)
const error = ref('')
const detailOpen = ref(false)
const selected = ref(null)

const payloadText = computed(() => {
  if (!selected.value) return ''
  return JSON.stringify({
    auditId: selected.value.id,
    eventType: selected.value.eventType,
    actorId: selected.value.actorId,
    traceId: selected.value.traceId,
    timestamp: selected.value.timestamp,
    summary: selected.value.summary
  }, null, 2)
})

const resultText = computed(() => selected.value?.summary || '当前审计记录没有单独的返回体')

function openDetail(row) {
  selected.value = row
  detailOpen.value = true
}

function formatDetailTime(value) {
  return formatDateTime(value)
}

async function copyTrace() {
  if (!selected.value?.traceId) return
  try {
    await navigator.clipboard.writeText(selected.value.traceId)
    ElMessage.success('TraceId 已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择 TraceId')
  }
}

async function load(target = page.value) {
  loading.value = true
  error.value = ''
  try {
    const data = await listAuditLogsPaged({
      page: target,
      pageSize: pageSize.value,
      eventType: eventType.value.trim(),
      actorId: actorId.value.trim(),
      traceId: traceId.value.trim(),
      keyword: keyword.value.trim()
    })
    items.value = Array.isArray(data?.items) ? data.items : []
    total.value = Number(data?.total ?? items.value.length)
    page.value = Number(data?.page ?? target)
    if (items.value.length === 0 && total.value > 0 && page.value > 1) {
      page.value -= 1
      await load(page.value)
    }
  } catch (problem) {
    items.value = []
    total.value = 0
    error.value = problem?.code === 403 ? '仅管理员可查看审计日志' : '审计接口不可用'
  } finally {
    loading.value = false
  }
}

function search() {
  load(1)
}

onMounted(() => load(1))
</script>

<style scoped>
.audit-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.filter {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.filter__input {
  width: 220px;
}

.filter__input--narrow {
  width: 130px;
}

.filter__count {
  margin-left: auto;
  font-size: 12px;
  color: var(--muted);
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 14px 18px;
  border-top: 1px solid var(--line);
}

.audit-page__foot {
  margin: 0;
  font-size: 12px;
  color: var(--muted);
}

.mono {
  font-family: Consolas, "SFMono-Regular", monospace;
  word-break: break-all;
}

.detail-note {
  margin-top: 16px;
}

.detail-title {
  margin: 20px 0 8px;
  font-size: 14px;
  color: var(--ink);
}

.detail-json {
  margin: 0;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: var(--bg-soft);
  border: 1px solid var(--line);
  color: var(--ink-700);
  font-family: Consolas, "SFMono-Regular", monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
