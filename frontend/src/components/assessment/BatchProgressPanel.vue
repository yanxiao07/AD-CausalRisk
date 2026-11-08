<template>
  <transition name="batch">
    <aside v-if="batch.visible" class="batch-panel" aria-live="polite">
      <header class="batch-panel__head">
        <div class="batch-panel__title">
          <strong>批量评估</strong>
          <span class="batch-panel__count">{{ batch.finished }} / {{ batch.total }}</span>
        </div>
        <el-button text :icon="Close" aria-label="关闭进度面板" @click="batch.dismiss()" />
      </header>

      <el-progress
        :percentage="batch.percent"
        :status="progressStatus"
        :stroke-width="8"
        :show-text="false"
      />

      <p class="batch-panel__meta">
        完成 {{ batch.completed }} · 阻断 {{ batch.blocked }} · 失败 {{ batch.failed }}
      </p>

      <ul v-if="batch.failures.length" class="batch-panel__list">
        <li v-for="item in batch.failures" :key="item.subjectId">
          受试者 #{{ item.subjectId }}：{{ item.errorCode || item.status }}
          <span v-if="item.message">（{{ item.message }}）</span>
        </li>
      </ul>

      <p v-if="batch.error" class="batch-panel__error">{{ batch.error }}</p>
      <p v-else-if="!batch.running && batch.total" class="batch-panel__done">
        本批已结束，可到「受试者档案 → 抽屉」查看每个受试者的结果。
      </p>
    </aside>
  </transition>
</template>

<script setup>
import { computed } from 'vue'
import { Close } from '@element-plus/icons-vue'
import { useBatchAssessmentStore } from '@/stores/batch.js'

const batch = useBatchAssessmentStore()

const progressStatus = computed(() => {
  if (batch.running) return ''
  return batch.failed > 0 ? 'warning' : 'success'
})
</script>

<style scoped>
.batch-panel {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 2000;
  width: 320px;
  padding: 16px 18px;
  border-radius: var(--r-lg);
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid var(--line);
  box-shadow: var(--shadow-panel);
  -webkit-backdrop-filter: var(--glass-blur);
  backdrop-filter: var(--glass-blur);
}

.batch-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.batch-panel__title {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.batch-panel__title strong {
  font-size: 14px;
  color: var(--ink);
}

.batch-panel__count {
  font-size: 12px;
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

.batch-panel__meta {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--muted);
}

.batch-panel__list {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
  max-height: 120px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.6;
  color: var(--danger);
}

.batch-panel__error {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--danger);
}

.batch-panel__done {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--soft);
}

.batch-enter-active,
.batch-leave-active {
  transition: opacity var(--dur-base) var(--ease-out), transform var(--dur-base) var(--ease-out);
}

.batch-enter-from,
.batch-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
