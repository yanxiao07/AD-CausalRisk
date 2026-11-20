<template>
  <el-dialog
    class="visit-model-dialog"
    :model-value="modelValue"
    title="录入访视量表"
    width="min(620px, 94vw)"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p v-if="initial">已预填测试快照的日期及格式符合要求的 MMSE、CDR-SB。请核对并填写访视编号后保存。</p>
    <p v-else>填写本次访视实际完成的量表。评估质控需要最近访视的 MMSE 和 CDR-SB，未测的项目留空。</p>
    <el-form ref="formRef" :model="form" :rules="rules" label-width="96px" @submit.prevent>
      <el-form-item label="访视编号" prop="visitCode">
        <el-input v-model="form.visitCode" maxlength="50" placeholder="例如：V2026-03" />
      </el-form-item>
      <el-form-item label="访视日期" prop="visitDate">
        <el-date-picker
          v-model="form.visitDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择日期"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="MMSE">
        <el-input-number v-model="form.mmse" :min="0" :max="30" :precision="0" style="width: 100%" />
      </el-form-item>
      <el-form-item label="MoCA">
        <el-input-number v-model="form.moca" :min="0" :max="30" :precision="0" style="width: 100%" />
      </el-form-item>
      <el-form-item label="CDR-SB">
        <el-input-number v-model="form.cdrSb" :min="0" :max="18" :precision="1" :step="0.5" style="width: 100%" />
      </el-form-item>
      <VisitModelInputFields v-if="modelInputsEnabled" v-model="modelInputs" :errors="modelErrors"
        @clear="delete modelErrors[$event]" />
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存访视</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createSubjectVisit } from '@/api/subjects.js'
import VisitModelInputFields from './VisitModelInputFields.vue'
import { newVisitModelDraft, visitModelErrors, visitModelPayload } from './visitModelInputs.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  subjectId: { type: [Number, String], required: true },
  initial: { type: Object, default: null },
  modelInputsEnabled: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const formRef = ref(null)
const submitting = ref(false)
const modelInputs = ref(newVisitModelDraft())
const modelErrors = ref({})
const form = reactive({
  visitCode: '',
  visitDate: '',
  mmse: null,
  moca: null,
  cdrSb: null
})

const rules = {
  visitCode: [{ required: true, message: '请填写访视编号', trigger: 'blur' }],
  visitDate: [{ required: true, message: '请选择访视日期', trigger: 'change' }]
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    form.visitCode = ''
    form.visitDate = props.initial?.visitDate || ''
    form.mmse = props.initial?.mmse ?? null
    form.moca = null
    form.cdrSb = props.initial?.cdrSb ?? null
    modelInputs.value = newVisitModelDraft()
    modelErrors.value = {}
    formRef.value?.clearValidate()
  }
)

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  modelErrors.value = visitModelErrors(modelInputs.value)
  if (!valid || Object.keys(modelErrors.value).length) return

  submitting.value = true
  try {
    const saved = await createSubjectVisit(props.subjectId, {
      visitCode: form.visitCode.trim(),
      visitDate: form.visitDate,
      mmse: form.mmse,
      moca: form.moca,
      cdrSb: form.cdrSb,
      ...(props.modelInputsEnabled ? { modelInputs: visitModelPayload(modelInputs.value) } : {})
    })
    ElMessage.success('访视已补录')
    emit('saved', saved)
    emit('update:modelValue', false)
  } catch {
    // request.js 已统一提示
  } finally {
    submitting.value = false
  }
}
</script>
