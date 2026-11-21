<template>
  <el-dialog
    :model-value="modelValue"
    :title="initialPlan ? '修改复诊安排' : '安排复诊'"
    width="min(520px, 94vw)"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-width="88px" class="followup-form" @submit.prevent>
      <el-form-item label="复诊时间" required>
        <el-date-picker
          v-model="scheduledAt"
          type="datetime"
          placeholder="选择日期与时间"
          format="YYYY-MM-DD HH:mm"
          value-format="YYYY-MM-DD HH:mm:ss"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="目的" required>
        <el-input
          v-model="purpose"
          type="textarea"
          :rows="3"
          maxlength="1000"
          show-word-limit
          placeholder="例如：认知量表复查 + 影像随访"
        />
      </el-form-item>
      <el-form-item label="就诊地点">
        <el-input v-model="visitLocation" maxlength="255" placeholder="医院、院区、楼层和诊室" />
      </el-form-item>
      <el-form-item label="准备事项">
        <el-input v-model="preparation" type="textarea" :rows="2" maxlength="1000"
          placeholder="患者需要携带的材料或到院要求" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存复诊安排</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createFollowUp, updateFollowUp } from '@/api/followups.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  subjectId: { type: [Number, String], required: true },
  initialPlan: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'created'])

const scheduledAt = ref('')
const purpose = ref('')
const visitLocation = ref('')
const preparation = ref('')
const submitting = ref(false)

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      const plan = props.initialPlan
      const existing = new Date(plan?.scheduledAt || NaN)
      scheduledAt.value = Number.isNaN(existing.getTime()) ? '' : new Date(existing.getTime() - existing.getTimezoneOffset() * 60000)
        .toISOString().slice(0, 19).replace('T', ' ')
      purpose.value = plan?.purpose || ''
      visitLocation.value = plan?.visitLocation || ''
      preparation.value = plan?.preparation || ''
    }
  },
  { immediate: true }
)

async function submit() {
  if (!scheduledAt.value) {
    ElMessage.warning('请选择复诊时间')
    return
  }
  if (!purpose.value.trim()) {
    ElMessage.warning('请填写随访目的')
    return
  }

  submitting.value = true
  try {
    // 后端字段是 java.time.Instant，必须带时区；把本地时间转成 ISO-8601 UTC
    const iso = new Date(scheduledAt.value.replace(' ', 'T')).toISOString()
    const values = { scheduledAt: iso, purpose: purpose.value.trim(), visitLocation: visitLocation.value.trim(),
      preparation: preparation.value.trim() }
    const created = props.initialPlan
      ? await updateFollowUp(props.initialPlan.id, { ...values, appointmentRevision: props.initialPlan.appointmentRevision })
      : await createFollowUp({ ...values, subjectId: props.subjectId })
    ElMessage.success('复诊安排已保存')
    emit('created', created)
    emit('update:modelValue', false)
  } catch {
    // request.js 已统一提示
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.followup-form {
  padding-right: 8px;
}
</style>
