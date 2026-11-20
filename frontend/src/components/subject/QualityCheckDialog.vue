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
      检查当前档案的访视量表、记录日期和已录入特征。检查结果随资料版本保存。
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
        title="档案资料暂不能通过本次质控"
        description="请修正下方问题并重新检查，再继续办理。"
      />
      <el-alert
        v-else-if="result.qualityStatus === 'WARN'"
        class="qc-alert"
        type="warning"
        :closable="false"
        show-icon
        title="存在需要关注的问题"
        description="请查看下方提示。开通账号还要核对核心量表，研究评估还有自己的来源与用途要求。"
      />

      <div class="qc-issues">
        <div class="qc-issues__title">资料问题（{{ primaryIssues.length }}）</div>
        <ul v-if="primaryIssues.length" class="qc-issues__list">
          <li v-for="(issue, index) in primaryIssues" :key="index">{{ issue }}</li>
        </ul>
        <p v-else class="qc-issues__empty">未发现其他资料问题。</p>
        <el-button v-if="canRecordVisit && missingVisit" type="primary" plain @click="recordVisit">
          去录入访视量表
        </el-button>
        <p v-if="optionalMissing.length" class="qc-hint">
          未录入的其他特征：{{ optionalMissing.join('、') }}。它们的缺失只作为本次档案质控提示。
        </p>
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
  subjectLabel: { type: String, default: '' },
  canRecordVisit: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'checked', 'record-visit'])

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
const optionalMissingNames = {
  'APOE_E4_COUNT 缺失': 'APOE ε4',
  'MRI_HIPPOCAMPUS_L 缺失': '左侧海马影像指标',
  'MRI_HIPPOCAMPUS_R 缺失': '右侧海马影像指标'
}
const primaryIssues = computed(() => issues.value.filter(issue => !optionalMissingNames[issue]))
const optionalMissing = computed(() => issues.value.map(issue => optionalMissingNames[issue]).filter(Boolean))
const missingVisit = computed(() => issues.value.some(issue => issue.startsWith('最近访视没有可用的核心认知观测')))

function recordVisit() {
  emit('update:modelValue', false)
  emit('record-visit')
}

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
    emit('checked', result.value)
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
