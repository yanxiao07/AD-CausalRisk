<template>
  <el-dialog
    :model-value="modelValue"
    title="补录观测"
    width="520px"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="96px" @submit.prevent>
      <el-form-item label="观测类型" prop="kind">
        <el-radio-group v-model="form.kind">
          <el-radio-button value="visit">访视型</el-radio-button>
          <el-radio-button value="feature">特征型</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <template v-if="form.kind === 'visit'">
        <el-form-item label="访视编号" prop="visitCode">
          <el-input v-model="form.visitCode" maxlength="50" placeholder="例如：V2026-04" />
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
      </template>

      <template v-else>
        <el-form-item label="特征名称" prop="featureName">
          <el-input v-model="form.featureName" maxlength="80" placeholder="例如：海马体积" />
        </el-form-item>
        <el-form-item label="特征值" prop="featureValue">
          <el-input-number v-model="form.featureValue" :precision="4" :step="0.1" style="width: 100%" />
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
          <el-input v-model="form.unit" maxlength="30" placeholder="例如：cm3" />
        </el-form-item>
      </template>

      <el-form-item label="数据来源" prop="source">
        <el-input v-model="form.source" maxlength="200" placeholder="必填：例如 随访手工补录 / 院外化验单" />
      </el-form-item>
      <el-form-item label="缺失原因">
        <el-input
          v-model="form.missingReason"
          type="textarea"
          :rows="2"
          maxlength="500"
          show-word-limit
          placeholder="可空：某项指标缺失时说明原因"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存观测</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createSubjectObservation } from '@/api/subjects.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  subjectId: { type: [Number, String], required: true }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const formRef = ref(null)
const submitting = ref(false)
const form = reactive({
  kind: 'visit',
  visitCode: '',
  visitDate: '',
  mmse: null,
  moca: null,
  cdrSb: null,
  featureName: '',
  featureValue: null,
  recordedAt: '',
  unit: '',
  source: '',
  missingReason: ''
})

const rules = {
  kind: [{ required: true, message: '请选择观测类型', trigger: 'change' }],
  source: [{ required: true, message: '请填写数据来源', trigger: 'blur' }],
  visitCode: [{ required: true, message: '请填写访视编号', trigger: 'blur' }],
  visitDate: [{ required: true, message: '请选择访视日期', trigger: 'change' }],
  featureName: [{ required: true, message: '请填写特征名称', trigger: 'blur' }],
  featureValue: [{ required: true, message: '请填写特征值', trigger: 'change' }],
  recordedAt: [{ required: true, message: '请选择记录日期', trigger: 'change' }],
  unit: [{ required: true, message: '请填写单位（无单位填“无”）', trigger: 'blur' }]
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    Object.assign(form, {
      kind: 'visit',
      visitCode: '',
      visitDate: '',
      mmse: null,
      moca: null,
      cdrSb: null,
      featureName: '',
      featureValue: null,
      recordedAt: '',
      unit: '',
      source: '',
      missingReason: ''
    })
    formRef.value?.clearValidate()
  }
)

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const isVisit = form.kind === 'visit'
    const payload = {
      kind: form.kind,
      source: form.source.trim(),
      missingReason: form.missingReason.trim() || null,
      ...(isVisit
        ? {
            visitCode: form.visitCode.trim(),
            visitDate: form.visitDate,
            mmse: form.mmse,
            moca: form.moca,
            cdrSb: form.cdrSb
          }
        : {
            featureName: form.featureName.trim(),
            featureValue: form.featureValue,
            recordedAt: form.recordedAt,
            unit: form.unit.trim()
          })
    }
    const saved = await createSubjectObservation(props.subjectId, payload)
    ElMessage.success('观测已补录')
    emit('saved', saved)
    emit('update:modelValue', false)
  } catch {
    // request.js 已统一提示
  } finally {
    submitting.value = false
  }
}
</script>
