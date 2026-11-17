<template>
  <el-dialog
    :model-value="modelValue"
    title="补录访视"
    width="480px"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
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

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  subjectId: { type: [Number, String], required: true }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const formRef = ref(null)
const submitting = ref(false)
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
    form.visitDate = ''
    form.mmse = null
    form.moca = null
    form.cdrSb = null
    formRef.value?.clearValidate()
  }
)

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const saved = await createSubjectVisit(props.subjectId, {
      visitCode: form.visitCode.trim(),
      visitDate: form.visitDate,
      mmse: form.mmse,
      moca: form.moca,
      cdrSb: form.cdrSb
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
