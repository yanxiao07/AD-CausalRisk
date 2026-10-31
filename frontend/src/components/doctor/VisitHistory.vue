<template>
  <section class="visits">
    <header class="visits__head">
      <h3 class="visits__title">历史访视</h3>
      <span class="visits__count">{{ visits.length }} 次</span>
    </header>

    <el-skeleton v-if="loading" :rows="3" animated />

    <EmptyState
      v-else-if="visits.length === 0"
      :icon="Calendar"
      title="还没有访视记录"
      description="访视由医生在建档后录入；这里只显示后端真实返回的数据。"
    />

    <el-table v-else :data="visits" class="visits__table" max-height="300">
      <el-table-column prop="visitCode" label="访视编号" min-width="120" />
      <el-table-column prop="visitDate" label="日期" width="120" />
      <el-table-column label="MMSE" width="90">
        <template #default="{ row }">{{ formatScore(row.mmse) }}</template>
      </el-table-column>
      <el-table-column label="MoCA" width="90">
        <template #default="{ row }">{{ formatScore(row.moca) }}</template>
      </el-table-column>
      <el-table-column label="CDR-SB" width="100">
        <template #default="{ row }">{{ formatScore(row.cdrSb) }}</template>
      </el-table-column>
    </el-table>
    <p class="visits__note">
      分数为缺失时显示「—」；系统不会用 0 或插值补全缺失的测量值。
    </p>
  </section>
</template>

<script setup>
import { Calendar } from '@element-plus/icons-vue'
import EmptyState from '@/components/common/EmptyState.vue'

defineProps({
  /** VisitResult[]：{ id, subjectId, visitCode, visitDate, mmse, moca, cdrSb } */
  visits: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})

function formatScore(value) {
  return typeof value === 'number' ? String(value) : '—'
}
</script>

<style scoped>
.visits {
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: #fff;
}

.visits__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}

.visits__title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
}

.visits__count {
  font-size: 12px;
  color: var(--muted);
}

.visits__table {
  width: 100%;
}

.visits__note {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--soft);
}
</style>
