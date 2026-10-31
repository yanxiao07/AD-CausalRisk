<template>
  <div class="audit-page">
    <PageHeader title="审计日志" subtitle="谁在什么时候做了什么；仅管理员可见，按时间倒序。">
      <template #extra>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <DataCard padding="none">
      <AuditStream :events="events" :loading="loading" :error="error" show-refresh @refresh="load" />
    </DataCard>

    <p class="audit-page__foot">
      共 {{ events.length }} 条。契约里的 data 是数组（无分页），需要分页要先改契约。
    </p>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import AuditStream from '@/components/admin/AuditStream.vue'
import { listAuditLogs } from '@/api/audit.js'

const events = ref([])
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await listAuditLogs()
    events.value = Array.isArray(data) ? data : []
  } catch (problem) {
    events.value = []
    error.value = problem?.code === 403 ? '仅管理员可查看审计日志' : '审计接口不可用'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.audit-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.audit-page__foot {
  margin: 0;
  font-size: 12px;
  color: var(--muted);
}
</style>
