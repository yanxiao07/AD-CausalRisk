<template>
  <span class="status-tag" :class="`status-tag--${tone}`" :title="text">
    <i class="status-tag__dot" aria-hidden="true" />
    {{ text }}
  </span>
</template>

<script setup>
import { computed } from 'vue'

/** 后端枚举 -> 中文标签 + 色调。tone 决定颜色，label 决定文案。 */
const PRESETS = {
  // 质控
  PASS: { label: '通过', tone: 'success' },
  WARN: { label: '需关注', tone: 'warning' },
  BLOCKED: { label: '被阻断', tone: 'danger' },
  // 任务
  PENDING: { label: '排队中', tone: 'info' },
  RUNNING: { label: '计算中', tone: 'info' },
  COMPLETED: { label: '已完成', tone: 'success' },
  FAILED: { label: '失败', tone: 'danger' },
  // 导入任务
  PREVIEW_READY: { label: '预检完成', tone: 'info' },
  COMMITTED: { label: '已入库', tone: 'success' },
  // 复核
  ACKNOWLEDGED: { label: '已确认', tone: 'success' },
  NEEDS_DATA: { label: '需补充资料', tone: 'warning' },
  REJECTED: { label: '已驳回', tone: 'danger' },
  // 随访
  PLANNED: { label: '已计划', tone: 'info' },
  CANCELLED: { label: '已取消', tone: 'neutral' },
  // 档案
  ACTIVE: { label: '在组', tone: 'success' },
  ARCHIVED: { label: '已归档', tone: 'neutral' },
  // 风险等级
  HIGH: { label: '高风险', tone: 'danger' },
  MEDIUM: { label: '中风险', tone: 'warning' },
  LOW: { label: '低风险', tone: 'success' },
  UNAVAILABLE: { label: '不可用', tone: 'neutral' }
}

const props = defineProps({
  /** 后端返回的枚举值，如 PASS / BLOCKED / COMPLETED */
  type: { type: String, required: true },
  /** 覆盖默认文案 */
  label: { type: String, default: '' },
  /** 强制指定色调（success/warning/danger/info/neutral） */
  tone: { type: String, default: '' }
})

const preset = computed(() => PRESETS[props.type] ?? { label: props.type, tone: 'neutral' })
const text = computed(() => props.label || preset.value.label)
const tone = computed(() => props.tone || preset.value.tone)
</script>

<style scoped>
.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 26px;
  padding: 0 12px;
  border-radius: var(--r-pill);
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
  border: 1px solid transparent;
}

.status-tag__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
}

.status-tag--success {
  color: var(--success);
  background: var(--success-soft);
  border-color: rgba(19, 125, 91, 0.16);
}

.status-tag--warning {
  color: var(--warning);
  background: var(--warning-soft);
  border-color: rgba(183, 121, 31, 0.18);
}

.status-tag--danger {
  color: var(--danger);
  background: var(--danger-soft);
  border-color: rgba(197, 60, 70, 0.16);
}

.status-tag--info {
  color: var(--info);
  background: var(--info-soft);
  border-color: rgba(74, 87, 184, 0.16);
}

.status-tag--neutral {
  color: var(--muted);
  background: #f1f6f9;
  border-color: var(--line);
}
</style>
