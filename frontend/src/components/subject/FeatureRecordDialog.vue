<template>
  <el-dialog
    :model-value="modelValue"
    title="补录检验和影像结果"
    width="min(520px, 94vw)"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p class="form-hint">有检验或影像报告时，按报告补录。未检查或结果未回时可先留空。</p>
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
      <el-form-item label="指标" prop="featureName">
        <el-select v-model="form.featureName" placeholder="选择报告中的指标" aria-label="指标" @change="form.featureValue = null">
          <el-option v-for="(field, code) in featureFields" :key="code" :value="code" :label="field.label" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="field" label="报告结果" prop="featureValue">
        <el-select v-if="isCount" v-model="form.featureValue" placeholder="选择报告中的数值" aria-label="报告结果">
          <el-option v-for="value in [0, 1, 2]" :key="value" :value="value" :label="`${value} 个`" />
        </el-select>
        <el-input v-else v-model.trim="form.featureValue" inputmode="decimal" aria-label="报告结果" />
        <p v-if="isCount" class="form-hint">填报告确认的 ε4 个数。结果未知时留空。</p>
        <p v-else class="form-hint">此处只接收经影像处理得到的 0–1 归一化值。报告只有 mm³ 或 cm³ 时请留空，由影像团队提供归一化结果。</p>
      </el-form-item>
      <el-form-item label="检验或检查日期" prop="recordedAt">
        <el-date-picker
          v-model="form.recordedAt"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择日期"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="报告来源" prop="source">
        <el-input v-model="form.source" maxlength="200" placeholder="例如：本院检验报告，报告号 ···" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存结果</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createSubjectObservation } from '@/api/subjects.js'
import { featureFields, isValidFeatureValue } from './featureFields.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  subjectId: { type: [Number, String], required: true },
  initialFeature: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const formRef = ref(null)
const submitting = ref(false)
const form = reactive({ featureName: '', featureValue: null, recordedAt: '', source: '' })
const field = computed(() => featureFields[form.featureName])
const isCount = computed(() => field.value?.unit === 'count')

const rules = {
  featureName: [{ required: true, message: '请选择指标', trigger: 'change' }],
  featureValue: [{
    required: true,
    validator: (_rule, value, callback) => {
      const valid = isValidFeatureValue(field.value?.unit, value)
      callback(valid ? undefined : new Error(isCount.value ? '请选择报告确认的个数' : '请输入 0–1 的归一化值，最多六位小数'))
    },
    trigger: 'change'
  }],
  recordedAt: [{ required: true, message: '请选择检验或检查日期', trigger: 'change' }],
  source: [{ required: true, whitespace: true, message: '请填写报告来源', trigger: 'blur' }]
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    form.featureName = props.initialFeature
    form.featureValue = null
    form.recordedAt = ''
    form.source = ''
    formRef.value?.clearValidate()
  }
)

async function submit() {
  if (submitting.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || !field.value || submitting.value) return

  submitting.value = true
  try {
    const saved = await createSubjectObservation(props.subjectId, {
      kind: 'feature',
      featureName: form.featureName,
      featureValue: Number(form.featureValue),
      recordedAt: form.recordedAt,
      unit: field.value.unit,
      source: form.source.trim()
    })
    ElMessage.success('结果已保存。需要继续办理时，请重新质控。')
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
.el-select { width: 100%; }
</style>
