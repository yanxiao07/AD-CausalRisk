<template>
  <section class="drawer__section">
    <h3>随访结局记录</h3>
    <p>按随访或诊断记录填写首次结局日期。尚未观察到结局时填最后一次确认日期。</p>
    <p v-if="message" role="status">{{ message }}</p>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <el-button v-if="!loaded" :loading="loading" @click="load">读取随访结局</el-button>
    <el-form v-else label-position="top" @submit.prevent="save">
      <el-form-item label="随访结局">
        <el-radio-group v-model="form.outcome">
          <el-radio v-for="(label, value) in options" :key="value" :value="value">{{ label }}</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="记录日期">
        <el-date-picker v-model="form.observedDate" type="date" value-format="YYYY-MM-DD" placeholder="请选择日期" />
      </el-form-item>
      <el-form-item label="随访或诊断记录编号">
        <el-input v-model.trim="form.sourceRef" maxlength="150" />
      </el-form-item>
      <el-button type="primary" native-type="submit" :loading="saving"
        :disabled="saving || !form.outcome || !form.observedDate || !form.sourceRef">保存随访结局</el-button>
    </el-form>
  </section>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { getValidationFollowUp, saveValidationFollowUp } from '@/api/validationCohort.js'

const props = defineProps({ subjectId: { type: Number, required: true } })
const options = { AD: '已记录 AD', OTHER_DEMENTIA: '已记录其他痴呆', NO_EVENT: '截至该日未记录 AD 或其他痴呆', UNKNOWN: '尚无法确认' }
const form = reactive({ outcome: '', observedDate: '', sourceRef: '' })
const error = ref('')
const message = ref('')
const loaded = ref(false)
const loading = ref(false)
const saving = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const existing = await getValidationFollowUp(props.subjectId)
    if (existing) Object.assign(form, existing)
    loaded.value = true
  } catch (problem) {
    error.value = problem.message || '随访结局读取失败，请重试。'
  } finally {
    loading.value = false
  }
}

async function save() {
  if (saving.value || !form.outcome || !form.observedDate || !form.sourceRef) return
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    Object.assign(form, await saveValidationFollowUp(props.subjectId, form))
    message.value = '随访结局已保存。'
  } catch (problem) {
    error.value = problem.message || '随访结局保存失败，请重试。'
  } finally {
    saving.value = false
  }
}
</script>
