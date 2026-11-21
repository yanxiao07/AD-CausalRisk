<template>
  <div class="scope-switch">
    <el-tag v-if="showingSamples" type="warning" effect="plain">测试样例</el-tag>
    <el-button link type="info"
      @click="$emit('update:modelValue', showingSamples ? CONTROLLED_RESEARCH : CONSTRUCTED_VALIDATION)">
      {{ showingSamples ? '返回受控研究' : '查看测试样例' }}
    </el-button>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { CONTROLLED_RESEARCH, CONSTRUCTED_VALIDATION } from '@/utils/researchResults.js'

const props = defineProps({ modelValue: { type: String, required: true } })
defineEmits(['update:modelValue'])
const showingSamples = computed(() => props.modelValue === CONSTRUCTED_VALIDATION)
</script>

<style scoped>
.scope-switch { display: flex; align-items: center; gap: 8px; }
</style>
