<template>
  <div class="attribution">
    <p>连线展示各因素对本次预测的贡献。模型贡献无法证明个体病因。拖动节点、滚轮缩放，双击复位。</p>
    <CausalGraph :graph="graph" attribution :height="Math.max(height, 64 + contributions.length * 38)" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import CausalGraph from '@/components/charts/CausalGraph.vue'
import { researchFeatureLabel, signedContribution } from '@/utils/researchResults.js'

const props = defineProps({
  contributions: { type: Array, required: true },
  height: { type: Number, default: 360 }
})
const targets = [
  { id: 'ad-cif', label: '阿尔茨海默病', key: 'ad' },
  { id: 'other-cif', label: '其他痴呆', key: 'other' }
]
const graph = computed(() => ({
  nodes: [
    ...props.contributions.map(row => ({
      id: row.id, label: researchFeatureLabel(row.group), evidenceLevel: 'MODEL'
    })),
    ...targets.map(({ id, label }) => ({ id, label, outcome: true, evidenceLevel: 'MODEL' }))
  ],
  edges: props.contributions.flatMap(row => targets.map(target => ({
    from: row.id, to: target.id, evidenceLevel: 'MODEL', contribution: row[target.key],
    note: `模型贡献 ${signedContribution(row[target.key])}`
  })))
}))
</script>

<style scoped>
.attribution > p { margin: 0 0 12px; color: var(--muted); font-size: 14px; line-height: 1.6; }
</style>
