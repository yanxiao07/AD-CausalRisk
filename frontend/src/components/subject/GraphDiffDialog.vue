<template>
  <el-dialog
    :model-value="modelValue"
    title="图谱对比"
    width="720px"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p class="diff-hint">
      选择同一受试者的两次评估，比较两次因果图的结构差异。仅比较图结构与观测状态，不证明个体因果变化。
    </p>

    <el-form :inline="true" class="diff-form" @submit.prevent>
      <el-form-item label="基准评估">
        <el-select v-model="from" placeholder="选择较早的评估" style="width: 220px">
          <el-option
            v-for="item in options"
            :key="item.assessmentId"
            :label="optionLabel(item)"
            :value="item.assessmentId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="对比评估">
        <el-select v-model="to" placeholder="选择较晚的评估" style="width: 220px">
          <el-option
            v-for="item in options"
            :key="item.assessmentId"
            :label="optionLabel(item)"
            :value="item.assessmentId"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" :disabled="!canQuery" @click="query">
          开始对比
        </el-button>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="options.length < 2"
      type="info"
      :closable="false"
      show-icon
      title="至少需要两次评估才能对比"
      description="请先在档案里完成两次风险评估，或换一位有历史评估的受试者。"
    />

    <el-skeleton v-if="loading" :rows="4" animated />

    <template v-else-if="result">
      <div class="diff-stats">
        <div class="diff-stat diff-stat--add"><span>新增节点</span><strong>{{ result.addedNodes?.length || 0 }}</strong></div>
        <div class="diff-stat diff-stat--remove"><span>删除节点</span><strong>{{ result.removedNodes?.length || 0 }}</strong></div>
        <div class="diff-stat diff-stat--change"><span>变化节点</span><strong>{{ result.changedNodes?.length || 0 }}</strong></div>
        <div class="diff-stat diff-stat--add"><span>新增关联</span><strong>{{ result.addedEdges?.length || 0 }}</strong></div>
        <div class="diff-stat diff-stat--change"><span>变化关联</span><strong>{{ result.changedEdges?.length || 0 }}</strong></div>
        <div class="diff-stat diff-stat--remove"><span>删除关联</span><strong>{{ result.removedEdges?.length || 0 }}</strong></div>
      </div>

      <el-alert
        v-if="result.limitation"
        class="diff-limit"
        type="warning"
        :closable="false"
        show-icon
        :title="result.limitation"
      />

      <el-collapse class="diff-json">
        <el-collapse-item title="原始对比结果（JSON）" name="json">
          <pre class="diff-pre">{{ pretty }}</pre>
        </el-collapse-item>
      </el-collapse>
    </template>

    <EmptyState
      v-else-if="!loading && queried"
      :icon="WarningFilled"
      title="没有拿到对比结果"
      description="检查两次评估是否属于同一受试者，或稍后重试。"
    />

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { WarningFilled } from '@element-plus/icons-vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { getGraphDiff } from '@/api/subjects.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  subjectId: { type: [Number, String], required: true },
  /** 该受试者的评估列表（reportLibrary），用于下拉选择 from/to */
  assessments: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue'])

const from = ref(null)
const to = ref(null)
const loading = ref(false)
const queried = ref(false)
const result = ref(null)

const RISK = { HIGH: '高风险', MEDIUM: '中风险', LOW: '低风险', UNAVAILABLE: '不可用' }

const options = computed(() =>
  (props.assessments || [])
    .filter((item) => item?.assessmentId)
    .slice()
    .sort((a, b) => new Date(a.createdAt || 0) - new Date(b.createdAt || 0))
)

function optionLabel(item) {
  const time = item.createdAt ? new Date(item.createdAt).toLocaleString('zh-CN') : '—'
  return `#${item.assessmentId} · ${time} · ${RISK[item.riskLevel] || item.riskLevel || '—'}`
}

const canQuery = computed(
  () => from.value && to.value && String(from.value) !== String(to.value)
)

const pretty = computed(() => {
  try {
    return JSON.stringify(result.value, null, 2)
  } catch {
    return String(result.value)
  }
})

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    // 默认选中最早与最晚两次，减少手点次数
    const list = options.value
    from.value = list.length ? list[0].assessmentId : null
    to.value = list.length > 1 ? list[list.length - 1].assessmentId : null
    result.value = null
    queried.value = false
  }
)

async function query() {
  if (!canQuery.value) {
    ElMessage.warning('请选择两次不同的评估')
    return
  }
  loading.value = true
  queried.value = true
  try {
    result.value = await getGraphDiff(props.subjectId, from.value, to.value)
  } catch {
    result.value = null
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.diff-hint {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--muted);
}

.diff-form {
  margin-bottom: 4px;
}

.diff-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
  gap: 10px;
  margin: 12px 0;
}

.diff-stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--bg-soft);
}

.diff-stat span {
  font-size: 12px;
  color: var(--muted);
}

.diff-stat strong {
  font-family: var(--font-display);
  font-size: 22px;
  color: var(--ink);
}

.diff-stat--add strong { color: var(--success); }
.diff-stat--remove strong { color: var(--danger); }
.diff-stat--change strong { color: var(--brand-600); }

.diff-limit {
  margin-bottom: 10px;
}

.diff-pre {
  margin: 0;
  max-height: 260px;
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
