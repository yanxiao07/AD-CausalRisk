<template>
  <details v-if="entries.length" class="page-terms">
    <summary>本页术语</summary>
    <dl>
      <div v-for="[label, description] in entries" :key="label">
        <dt>{{ label }}</dt>
        <dd>{{ description }}</dd>
      </div>
    </dl>
  </details>
</template>

<script setup>
import { computed } from 'vue'
import { terms } from '@/utils/terms.js'

const props = defineProps({ names: { type: Array, default: () => [] } })
const entries = computed(() => props.names.map(name => terms[name]).filter(Boolean))
</script>

<style scoped>
.page-terms { margin-top: 5px; color: var(--ink); font-size: .875rem; }
.page-terms summary { width: fit-content; min-height: 32px; align-content: center; cursor: pointer; color: var(--brand-700); font-weight: 600; }
.page-terms summary:focus-visible { outline: 2px solid var(--brand-700); outline-offset: 3px; }
.page-terms dl { margin: 2px 0 4px; padding: 10px 14px; border-inline-start: 3px solid var(--brand-200); background: var(--el-fill-color-light); max-width: 56ch; }
.page-terms dl > div + div { margin-top: 8px; }
.page-terms dt { font-weight: 600; }
.page-terms dd { margin: 2px 0 0; line-height: 1.55; }
@media (pointer: coarse) {
  .page-terms summary { min-height: 44px; }
}
</style>
