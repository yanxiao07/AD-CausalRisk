<template>
  <section class="data-card" :class="[`data-card--pad-${padding}`, { 'data-card--hover': hover }]">
    <header v-if="title || $slots['header-extra']" class="data-card__header">
      <div class="data-card__heading">
        <h2 v-if="title" class="data-card__title">{{ title }}</h2>
        <p v-if="subtitle" class="data-card__subtitle">{{ subtitle }}</p>
      </div>
      <div v-if="$slots['header-extra']" class="data-card__extra">
        <slot name="header-extra" />
      </div>
    </header>

    <div class="data-card__body">
      <slot />
    </div>

    <footer v-if="$slots.footer" class="data-card__footer">
      <slot name="footer" />
    </footer>
  </section>
</template>

<script setup>
defineProps({
  title: { type: String, default: '' },
  subtitle: { type: String, default: '' },
  /** 内边距档位：none 用于自带 padding 的表格/图表 */
  padding: {
    type: String,
    default: 'md',
    validator: (v) => ['none', 'sm', 'md', 'lg'].includes(v)
  },
  /** 悬停上浮（可点击的卡片才开） */
  hover: { type: Boolean, default: false }
})
</script>

<style scoped>
.data-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-card);
  transition: box-shadow var(--dur-base) var(--ease-out),
    transform var(--dur-base) var(--ease-out);
  overflow: hidden;
}

.data-card--hover:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-hover);
}

.data-card--pad-none .data-card__body { padding: 0; }
.data-card--pad-sm .data-card__body { padding: 16px; }
.data-card--pad-md .data-card__body { padding: 24px; }
.data-card--pad-lg .data-card__body { padding: 32px; }

.data-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 20px 24px 0;
}

.data-card__title {
  font-size: 17px;
  line-height: 1.4;
  font-weight: 700;
  color: var(--ink);
}

.data-card__subtitle {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--muted);
}

.data-card__extra {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.data-card__footer {
  padding: 14px 24px;
  border-top: 1px solid var(--line);
  background: var(--bg-soft);
}
</style>
