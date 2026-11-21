<template>
  <DataCard title="发给患者的说明">
    <p v-if="loading" role="status">正在读取发布状态…</p>
    <p v-else-if="error" role="alert">{{ error }} <button type="button" @click="load">重试</button></p>
    <template v-else>
      <p role="status">{{ published ? '患者可查看已发布的说明。' : '尚未向患者发布说明。' }}</p>
      <label for="patient-publication-message">请写患者需要知道的下一步</label>
      <textarea id="patient-publication-message" v-model="message" rows="3" maxlength="1000" />
      <p>可填写复诊或补资料事项。内部复核意见、模型分数和图谱留在研究报告中。</p>
      <el-button type="primary" :loading="busy" :disabled="!message.trim()" @click="change(true)">
        {{ published ? '更新患者说明' : '发布给患者' }}
      </el-button>
      <el-button v-if="published" :disabled="busy" @click="change(false)">撤回说明</el-button>
    </template>
  </DataCard>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import DataCard from '@/components/common/DataCard.vue'
import { getPatientPublication, publishPatientMessage, withdrawPatientMessage } from '@/api/research.js'

const props = defineProps({ taskId: { type: Number, required: true } })
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const published = ref(false)
const message = ref('')

function apply(result) {
  published.value = result.published
  message.value = result.message || ''
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    apply(await getPatientPublication(props.taskId))
  } catch (problem) {
    error.value = problem?.message || '暂时无法读取发布状态'
  } finally {
    loading.value = false
  }
}

async function change(publish) {
  const text = message.value.trim()
  try {
    await ElMessageBox.confirm(publish ? '提交后，绑定此档案的患者将能看到说明。' : '撤回后，患者将看不到这条说明。',
      publish ? '确认发布' : '确认撤回', { type: 'info' })
  } catch { return }
  busy.value = true
  try {
    apply(await (publish ? publishPatientMessage(props.taskId, text) : withdrawPatientMessage(props.taskId)))
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<style scoped>
textarea { display: block; width: 100%; max-width: 720px; box-sizing: border-box; padding: .65rem; font: inherit; }
</style>
