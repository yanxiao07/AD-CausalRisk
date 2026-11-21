<template>
  <el-dialog
    :model-value="modelValue"
    title="用户设置"
    width="480px"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
    @open="load"
  >
    <el-skeleton v-if="loading" :rows="4" animated />
    <el-form v-else label-width="96px" class="pref-form">
      <el-form-item label="界面字号">
        <el-radio-group v-model="form.fontSize">
          <el-radio-button :value="14">标准</el-radio-button>
          <el-radio-button :value="16">大</el-radio-button>
          <el-radio-button :value="18">特大</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="界面密度">
        <el-radio-group v-model="form.density">
          <el-radio-button value="comfortable">舒适</el-radio-button>
          <el-radio-button value="compact">紧凑</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="站内通知">
        <el-switch v-model="form.notifications" active-text="开启" inactive-text="关闭" />
      </el-form-item>
      <p class="pref-form__hint">
        偏好会保存到当前账号；换设备登录后从服务端恢复。字号与密度立即生效。
      </p>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button :loading="saving" type="primary" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getMyPreferences, saveMyPreferences } from '@/api/system.js'
import { applyPreferences, defaultPreferences, readLocalPreferences } from '@/utils/preferences.js'

defineProps({
  modelValue: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue'])

const loading = ref(false)
const saving = ref(false)
const form = ref(defaultPreferences())

async function load() {
  loading.value = true
  try {
    const data = await getMyPreferences()
    form.value = { ...defaultPreferences(), ...(data || {}) }
  } catch {
    // 服务端不可用时退回本机缓存
    form.value = { ...defaultPreferences(), ...(readLocalPreferences() || {}) }
  } finally {
    loading.value = false
  }
}

async function save() {
  if (saving.value) return
  saving.value = true
  const payload = {
    fontSize: form.value.fontSize,
    density: form.value.density,
    notifications: form.value.notifications
  }
  try {
    await saveMyPreferences(payload)
    applyPreferences(payload)
    ElMessage.success('偏好已保存')
    emit('update:modelValue', false)
  } catch {
    // 保存失败仍然让本机生效，避免设置面板完全不可用
    applyPreferences(payload)
    ElMessage.warning('服务端保存失败，已在本机生效')
    emit('update:modelValue', false)
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  const local = readLocalPreferences()
  if (local) form.value = { ...defaultPreferences(), ...local }
})
</script>

<style scoped>
.pref-form__hint {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--soft);
}
</style>
