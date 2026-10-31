<template>
  <div class="audit" :class="{ 'audit--dark': dark }">
    <div v-if="error" class="audit__error">
      <el-icon><WarningFilled /></el-icon>
      <span>{{ error }}</span>
      <el-button v-if="showRefresh" size="small" text @click="emit('refresh')">重试</el-button>
    </div>

    <el-skeleton v-else-if="loading" :rows="4" animated />

    <div v-else-if="events.length === 0" class="audit__empty">
      暂无审计事件。只有接入了 @AuditLog 的操作才会写入这里。
    </div>

    <el-table v-else :data="rows" class="audit__table" max-height="320" size="small">
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ formatTime(row.timestamp) }}</template>
      </el-table-column>
      <el-table-column prop="eventType" label="事件" width="180" />
      <el-table-column prop="summary" label="摘要" min-width="220" show-overflow-tooltip />
      <el-table-column label="操作人" width="100">
        <template #default="{ row }">{{ row.actorId ?? '—' }}</template>
      </el-table-column>
      <el-table-column prop="traceId" label="traceId" width="150" show-overflow-tooltip />
    </el-table>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { WarningFilled } from '@element-plus/icons-vue'

const props = defineProps({
  /** AuditEvent[]：{ id, actorId, eventType, traceId, summary, timestamp } */
  events: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  /** 大屏用深色样式 */
  dark: { type: Boolean, default: false },
  /** 最多展示多少条（大屏只留最近几条） */
  limit: { type: Number, default: 0 },
  showRefresh: { type: Boolean, default: false }
})

const emit = defineEmits(['refresh'])

const rows = computed(() => (props.limit > 0 ? props.events.slice(0, props.limit) : props.events))

const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit'
})

function formatTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : timeFormatter.format(date)
}
</script>

<style scoped>
.audit__error {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: var(--danger-soft);
  color: var(--danger);
  font-size: 13px;
}

.audit__empty {
  padding: 28px 12px;
  text-align: center;
  font-size: 13px;
  color: var(--muted);
}

.audit__table {
  width: 100%;
}

/* 深色大屏 */
.audit--dark .audit__empty {
  color: #7fa6c4;
}

.audit--dark :deep(.el-table),
.audit--dark :deep(.el-table__inner-wrapper),
.audit--dark :deep(.el-table tr),
.audit--dark :deep(.el-table th.el-table__cell),
.audit--dark :deep(.el-table td.el-table__cell) {
  background: transparent;
  color: #dcecfb;
  border-bottom-color: rgba(120, 190, 235, 0.12);
}

.audit--dark :deep(.el-table th.el-table__cell) {
  color: #7fa6c4;
  font-weight: 600;
}

.audit--dark :deep(.el-table__row:hover > td.el-table__cell) {
  background: rgba(79, 195, 247, 0.08);
}

.audit--dark :deep(.el-table__empty-block) {
  background: transparent;
}
</style>
