<template>
  <section class="history-wall">
    <header class="history-wall__head">
      <h3 class="history-wall__title">最近打开</h3>
      <span class="history-wall__meta">共 {{ history.length }} 张 · 点击卡片直接载入</span>
      <el-button text size="small" @click="emit('clear')">清空</el-button>
    </header>

    <div class="history-grid">
      <article
        v-for="item in history"
        :key="item.assessmentId"
        class="history-card"
        :class="{ 'is-active': item.assessmentId === activeId }"
        role="button"
        tabindex="0"
        @click="emit('open', item)"
        @keydown.enter="emit('open', item)"
      >
        <div class="history-card__body">
          <p class="history-card__title">评估 #{{ item.assessmentId }}</p>
          <p class="history-card__meta">
            <span v-if="item.subjectId">受试者 #{{ item.subjectId }} · </span>打开时重新载入完整结果
          </p>
          <div class="history-card__foot">
            <span>点击载入</span>
            <span>{{ formatTime(item.createdAt || item.savedAt) }}</span>
          </div>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup>
defineProps({
  history: { type: Array, default: () => [] },
  activeId: { type: Number, default: null }
})

const emit = defineEmits(['open', 'clear'])

const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit'
})

function formatTime(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : timeFormatter.format(date)
}
</script>

<style scoped>
.history-wall {
  margin-top: 4px;
}

.history-wall__head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.history-wall__title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
}

.history-wall__meta {
  font-size: 12px;
  color: var(--muted);
}

.history-wall__head .el-button {
  margin-left: auto;
}

.history-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
}

.history-card {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: #fff;
  cursor: pointer;
  transition:
    border-color 0.16s ease,
    box-shadow 0.16s ease,
    transform 0.16s ease;
}

.history-card:hover {
  border-color: var(--line-strong);
  box-shadow: var(--shadow-card);
  transform: translateY(-2px);
}

.history-card.is-active {
  border-color: var(--brand-600);
  box-shadow: 0 0 0 2px var(--brand-50);
}

.history-card:focus-visible {
  outline: 2px solid var(--brand-600);
  outline-offset: 2px;
}

.history-card__body {
  padding: 10px 12px 12px;
}

.history-card__title {
  margin: 0;
  font-size: 13px;
  font-weight: 700;
  color: var(--ink);
}

.history-card__meta {
  margin: 4px 0 8px;
  font-size: 11px;
  color: var(--muted);
}

.history-card__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 11px;
  color: var(--soft);
}

@media (max-width: 640px) {
  .history-grid {
    grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  }
}
</style>
