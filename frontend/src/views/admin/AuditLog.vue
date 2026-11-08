<template>
  <div class="audit-page">
    <PageHeader back title="审计日志" subtitle="谁在什么时候做了什么；仅管理员可见，按时间倒序。">
      <template #extra>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <DataCard padding="none">
      <div class="filter">
        <el-select
          v-model="eventType"
          class="filter__select"
          size="small"
          clearable
          placeholder="事件类型"
        >
          <el-option v-for="type in eventTypes" :key="type" :label="type" :value="type" />
        </el-select>
        <el-input
          v-model="actor"
          class="filter__input"
          size="small"
          clearable
          placeholder="操作人 ID"
        />
        <el-input
          v-model="keyword"
          class="filter__input"
          size="small"
          clearable
          placeholder="摘要关键词"
        />
        <span class="filter__count">
          共 {{ events.length }} 条 · 筛选后 {{ filtered.length }} 条
        </span>
      </div>

      <AuditStream :events="pageEvents" :loading="loading" :error="error" show-refresh @refresh="load" />

      <div v-if="filtered.length > pageSize" class="pager">
        <el-pagination
          v-model:current-page="page"
          :page-size="pageSize"
          :total="filtered.length"
          layout="total, prev, pager, next"
          background
        />
      </div>
    </DataCard>

    <p class="audit-page__foot">
      契约里的 data 是数组（没有分页参数），所以分页与筛选都在前端完成；全量请求仍会一次返回。
    </p>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import AuditStream from '@/components/admin/AuditStream.vue'
import { listAuditLogs } from '@/api/audit.js'

const events = ref([])
const loading = ref(true)
const error = ref('')

const eventType = ref('')
const actor = ref('')
const keyword = ref('')
const page = ref(1)
const pageSize = ref(20)

const eventTypes = computed(() =>
  [...new Set(events.value.map((item) => item.eventType).filter(Boolean))].sort()
)

const filtered = computed(() => {
  const type = eventType.value
  const actorText = actor.value.trim()
  const kw = keyword.value.trim().toLowerCase()
  return events.value.filter((item) => {
    if (type && item.eventType !== type) return false
    if (actorText && String(item.actorId ?? '') !== actorText) return false
    if (kw && !String(item.summary ?? '').toLowerCase().includes(kw)) return false
    return true
  })
})

const pageEvents = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filtered.value.slice(start, start + pageSize.value)
})

watch([eventType, actor, keyword], () => {
  page.value = 1
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await listAuditLogs()
    events.value = Array.isArray(data) ? data : []
    page.value = 1
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

.filter {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.filter__select {
  width: 200px;
}

.filter__input {
  width: 160px;
}

.filter__count {
  margin-left: auto;
  font-size: 12px;
  color: var(--muted);
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 14px 18px;
  border-top: 1px solid var(--line);
}

.audit-page__foot {
  margin: 0;
  font-size: 12px;
  color: var(--muted);
}
</style>
