<template>
  <el-dialog
    :model-value="modelValue"
    :title="isEdit ? '编辑档案' : '新建档案'"
    width="480px"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" @submit.prevent>
      <el-form-item label="档案编号" prop="subjectCode">
        <el-input
          v-model="form.subjectCode"
          :disabled="isEdit"
          maxlength="50"
          placeholder="DEMO-2026-001（必须以 DEMO- 开头）"
        />
        <p class="form-hint">
          {{ isEdit ? '档案编号创建后不可修改。' : '格式：DEMO- 加字母、数字或短横线，最长 50 位。' }}
        </p>
      </el-form-item>
      <el-form-item label="别名" prop="alias">
        <el-input v-model="form.alias" maxlength="60" placeholder="例如：张样本" />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="form.status" placeholder="选择状态" style="width: 100%">
          <el-option label="在管（ACTIVE）" value="ACTIVE" />
          <el-option label="归档（ARCHIVED）" value="ARCHIVED" />
        </el-select>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">
        {{ isEdit ? '保存修改' : '创建档案' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createSubject, updateSubject } from '@/api/subjects.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 'create' | 'edit' */
  mode: { type: String, default: 'create' },
  /** 编辑时的档案行（含 id / subjectCode / alias / status） */
  subject: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const formRef = ref(null)
const submitting = ref(false)
const form = reactive({ subjectCode: '', alias: '', status: 'ACTIVE' })

const isEdit = computed(() => props.mode === 'edit')

const rules = {
  subjectCode: [
    { required: true, message: '请填写档案编号', trigger: 'blur' },
    {
      pattern: /^DEMO-[A-Za-z0-9-]{1,45}$/,
      message: '必须以 DEMO- 开头，后面只能是字母、数字或短横线',
      trigger: 'blur'
    }
  ],
  alias: [{ required: true, message: '请填写别名', trigger: 'blur' }]
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    if (isEdit.value && props.subject) {
      form.subjectCode = props.subject.subjectCode || ''
      form.alias = props.subject.alias || ''
      form.status = props.subject.status || 'ACTIVE'
    } else {
      form.subjectCode = ''
      form.alias = ''
      form.status = 'ACTIVE'
    }
    formRef.value?.clearValidate()
  }
)

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const payload = {
      subjectCode: form.subjectCode.trim(),
      alias: form.alias.trim(),
      status: form.status
    }
    const saved = isEdit.value
      ? await updateSubject(props.subject.id, payload)
      : await createSubject(payload)
    ElMessage.success(isEdit.value ? '档案已更新' : '档案已创建')
    emit('saved', { mode: props.mode, subject: saved })
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
