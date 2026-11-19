<template>
  <el-dialog
    v-model="visible"
    title="追加访视更正"
    width="640px"
    :close-on-click-modal="false"
    @closed="reset"
  >
    <p class="correction__intro">
      更正不会覆盖原记录。保存后生成新的访视版本，原版本保留在历史中供审计追溯。
    </p>

    <el-form ref="formRef" :model="form" :rules="rules" label-width="92px">
      <el-form-item label="访视编号">
        <el-input :model-value="visit?.visitCode || ''" disabled />
      </el-form-item>
      <el-form-item label="访视日期" prop="visitDate">
        <el-date-picker
          v-model="form.visitDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择访视日期"
          style="width: 100%"
        />
      </el-form-item>
      <div class="correction__grid">
        <el-form-item label="MMSE">
          <el-input-number
            v-model="form.mmse"
            :min="0"
            :max="30"
            :precision="2"
            :step="0.5"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="MoCA">
          <el-input-number
            v-model="form.moca"
            :min="0"
            :max="30"
            :precision="2"
            :step="0.5"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="CDR-SB">
          <el-input-number
            v-model="form.cdrSb"
            :min="0"
            :max="18"
            :precision="2"
            :step="0.5"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
      </div>
      <el-form-item label="更正原因" prop="reason">
        <el-input
          v-model="form.reason"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="请写明更正依据，例如核对原始量表、补录漏项等"
        />
      </el-form-item>
    </el-form>

    <section class="correction__history">
      <header class="correction__history-head">
        <h4>版本历史</h4>
        <el-button link :loading="historyLoading" @click="loadHistory">刷新</el-button>
      </header>
      <el-skeleton v-if="historyLoading" :rows="2" animated />
      <el-table v-else :data="history" size="small" max-height="220">
        <el-table-column prop="version" label="版本" width="65" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.current ? 'success' : 'info'" size="small">
              {{ row.current ? '当前' : '已更正' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="visitDate" label="日期" width="105" />
        <el-table-column label="MMSE" width="70">
          <template #default="{ row }">{{ score(row.mmse) }}</template>
        </el-table-column>
        <el-table-column label="MoCA" width="70">
          <template #default="{ row }">{{ score(row.moca) }}</template>
        </el-table-column>
        <el-table-column label="CDR-SB" width="80">
          <template #default="{ row }">{{ score(row.cdrSb) }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="更正原因" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.reason || '原始记录' }}</template>
        </el-table-column>
      </el-table>
    </section>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存更正</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { correctSubjectVisit, getVisitHistory } from '@/api/subjects.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  subjectId: { type: [Number, String], default: null },
  visit: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})

const formRef = ref(null)
const saving = ref(false)
const historyLoading = ref(false)
const history = ref([])
const form = reactive({ visitDate: '', mmse: null, moca: null, cdrSb: null, reason: '' })
const rules = {
  visitDate: [{ required: true, message: '请选择访视日期', trigger: 'change' }],
  reason: [
    { required: true, message: '请填写更正原因', trigger: 'blur' },
    { min: 1, max: 500, message: '更正原因不超过 500 字', trigger: 'blur' }
  ]
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      resetForm()
      loadHistory()
    }
  }
)

function resetForm() {
  form.visitDate = props.visit?.visitDate || ''
  form.mmse = props.visit?.mmse ?? null
  form.moca = props.visit?.moca ?? null
  form.cdrSb = props.visit?.cdrSb ?? null
  form.reason = ''
  formRef.value?.clearValidate()
}

function reset() {
  formRef.value?.clearValidate()
  history.value = []
}

function score(value) {
  return typeof value === 'number' ? String(value) : '—'
}

async function loadHistory() {
  if (!props.subjectId || !props.visit?.id) return
  historyLoading.value = true
  try {
    const list = await getVisitHistory(props.subjectId, props.visit.id)
    history.value = Array.isArray(list) ? list : []
  } catch {
    history.value = []
  } finally {
    historyLoading.value = false
  }
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid || !props.subjectId || !props.visit?.id) return
  saving.value = true
  try {
    await correctSubjectVisit(props.subjectId, props.visit.id, {
      visitDate: form.visitDate,
      mmse: form.mmse,
      moca: form.moca,
      cdrSb: form.cdrSb,
      reason: form.reason.trim()
    })
    ElMessage.success('已追加新版本，原记录保留在版本历史中')
    emit('saved')
    visible.value = false
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.correction__intro {
  margin: 0 0 14px;
  padding: 10px 12px;
  border-radius: var(--r-sm);
  background: #f2f8fd;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.correction__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 10px;
}

.correction__history {
  margin-top: 8px;
  padding-top: 12px;
  border-top: 1px dashed var(--line);
}

.correction__history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.correction__history-head h4 {
  margin: 0;
  font-size: 13px;
  font-weight: 700;
}

@media (max-width: 640px) {
  .correction__grid {
    grid-template-columns: 1fr;
  }
}
</style>
