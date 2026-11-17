<template>
  <el-dialog
    :model-value="modelValue"
    title="发起质控检查"
    width="640px"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p class="qc-hint">
      按当前规则检查该档案的输入数据（人口学、量表、访视等），结果会落库并影响能否发起评估：
      PASS 通过、WARN 需关注、BLOCKED 阻断评估。
    </p>

    <div class="qc-actions">
      <el-button type="primary" :loading="running" :icon="FirstAidKit" @click="run">
        {{ result ? '重新检查' : '开始检查' }}
      </el-button>
      <span class="qc-subject">{{ subjectLabel }}</span>
    </div>

    <el-skeleton v-if="running" :rows="3" animated />

    <template v-else-if="result">
      <div class="qc-result">
        <StatusTag :type="result.qualityStatus" />
        <span class="qc-meta">规则版本 {{ result.ruleVersion || '—' }}</span>
        <span class="qc-meta">{{ formatTime(result.checkedAt) }}</span>
      </div>

      <el-alert
        v-if="result.qualityStatus === 'BLOCKED'"
        class="qc-alert"
        type="error"
        :closable="false"
        show-icon
        title="该档案当前被阻断"
        description="在问题修复前，发起评估会返回 BLOCKED，系统不会给出风险分数。"
      />
      <el-alert
        v-else-if="result.qualityStatus === 'WARN'"
        class="qc-alert"
        type="warning"
        :closable="false"
        show-icon
        title="存在需要关注的问题"
        description="可以继续发起评估，但结果解释里会带上这些数据质量提示。"
      />

      <div class="qc-issues">
        <div class="qc-issues__title">问题明细（{{ issues.length }}）</div>
        <ul v-if="issues.length" class="qc-issues__list">
          <li v-for="(issue, index) in issues" :key="index">{{ issue }}</li>
        </ul>
        <p v-else class="qc-issues__empty">没有发现问题。</p>
      </div>

      <el-collapse class="qc-json">
        <el-collapse-item title="原始返回（JSON）" name="json">
          <pre class="qc-pre">{{ pretty }}</pre>
        </el-collapse-item>
      </el-collapse>
    </template>

    <EmptyState
      v-else-if="error"
      :icon="WarningFilled"
      title="质控检查没有完成"
      :description="error"
    />

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { FirstAidKit, WarningFilled } from '@element-plus/icons-vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { runQualityCheck } from '@/api/subjects.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  subjectId: { type: [Number, String], default: null },
  subjectLabel: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue'])

const running = ref(false)
const result = ref(null)
const error = ref('')

const issues = computed(() => {
  const raw = result.value?.issues
  if (Array.isArray(raw)) return raw.filter(Boolean)
  if (typeof raw !== 'string' || !raw.trim()) return []
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter(Boolean) : [raw]
  } catch {
    return [raw]
  }
})

const pretty = computed(() => {
  try {
    return JSON.stringify(result.value, null, 2)
  } catch {
    return String(result.value)
  }
})

function formatTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN')
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    // 每次打开都重新开始，避免带上一位受试者的结果
    result.value = null
    error.value = ''
  }
)

async function run() {
  if (!props.subjectId) {
    error.value = '没有选中受试者'
    return
  }
  running.value = true
  error.value = ''
  try {
    result.value = await runQualityCheck(props.subjectId)
  } catch (problem) {
    result.value = null
    error.value = problem?.message || '请稍后重试'
  } finally {
    running.value = false
  }
}
</script>

<style scoped>
.qc-hint {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--muted);
}

.qc-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.qc-subject {
  font-size: 13px;
  color: var(--muted);
}

.qc-result {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.qc-meta {
  font-size: 12px;
  color: var(--muted);
  font-family: var(--font-display);
}

.qc-alert {
  margin-bottom: 12px;
}

.qc-issues {
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--bg-soft);
}

.qc-issues__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 8px;
}

.qc-issues__list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink);
}

.qc-issues__empty {
  margin: 0;
  font-size: 13px;
  color: var(--soft);
}

.qc-json {
  margin-top: 12px;
}

.qc-pre {
  margin: 0;
  max-height: 240px;
  overflow: auto;
  padding: 12px;
  border-radius: var(--r-md);
  background: #0f1c2e;
  color: #d6e4f0;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  line-height: 1.6;
}
</style>
