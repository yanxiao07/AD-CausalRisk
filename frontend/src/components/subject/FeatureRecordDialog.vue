<template>
  <el-dialog
    :model-value="modelValue"
    title="补录特征"
    width="480px"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="96px" @submit.prevent>
      <el-form-item label="特征名称" prop="featureName">
        <el-input v-model="form.featureName" maxlength="80" placeholder="例如：APOE4 携带状态 / 海马体积" />
      </el-form-item>
      <el-form-item label="特征值">
        <el-input-number v-model="form.featureValue" :precision="4" :step="0.1" style="width: 100%" />
        <p class="form-hint">数值型特征才需要填；分类特征请用 0/1 等编码。</p>
      </el-form-item>
      <el-form-item label="记录日期" prop="recordedAt">
        <el-date-picker
          v-model="form.recordedAt"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择日期"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="单位" prop="unit">
        <el-input v-model="form.unit" maxlength="30" placeholder="例如：cm3 / 分 / 无" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存特征</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createSubjectFeature } from '@/api/subjects.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  subjectId: { type: [Number, String], required: true }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const formRef = ref(null)
const submitting = ref(false)
const form = reactive({ featureName: '', featureValue: null, recordedAt: '', unit: '' })

const rules = {
  featureName: [{ required: true, message: '请填写特征名称', trigger: 'blur' }],
  recordedAt: [{ required: true, message: '请选择记录日期', trigger: 'change' }],
  unit: [{ required: true, message: '请填写单位（无单位填“无”）', trigger: 'blur' }]
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    form.featureName = ''
    form.featureValue = null
    form.recordedAt = ''
    form.unit = ''
    formRef.value?.clearValidate()
  }
)

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const saved = await createSubjectFeature(props.subjectId, {
      featureName: form.featureName.trim(),
      featureValue: form.featureValue,
      recordedAt: form.recordedAt,
      unit: form.unit.trim()
    })
    ElMessage.success('特征已补录')
    emit('saved', saved)
    emit('update:modelValue', false)
  } catch {
    // request.js 已统一提示
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.form-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--muted);
}
</style>
