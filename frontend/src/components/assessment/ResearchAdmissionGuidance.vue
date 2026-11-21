<template>
  <aside class="admission-guidance">
    <h4>如何补证</h4>
    <ul v-if="availability || missing">
      <li v-if="availability">核对原始来源中资料首次可用于研究的日期。访视日期和补录日期只能记录各自对应的时间。</li>
      <li v-if="missing">逐项查原始记录。有原值则连同来源、测量日期和质控记录登记。确实未采集、无法取得或质控未通过时，保留空值并登记可核验的缺失依据。</li>
    </ul>
    <p>由研究员或数据管理员核对原始来源，经研究宿主登记一份新快照后再提交核验。医生的常规建档和访视录入用于记录新资料，历史快照保留本次核验结果。</p>
  </aside>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ issues: { type: Array, required: true } })
const availability = computed(() => props.issues.some(issue => issue.code === 'AVAILABILITY_UNKNOWN'))
const missing = computed(() => props.issues.some(issue => issue.code === 'MISSING_EVIDENCE_UNKNOWN'))
</script>

<style scoped>
.admission-guidance { margin-top: 12px; font-size: 13px; line-height: 1.65; }
.admission-guidance h4 { margin: 0 0 4px; font-weight: 600; }
.admission-guidance ul { padding-left: 1.25rem; margin: 0 0 6px; }
.admission-guidance p { margin: 0; }
</style>
