<template>
  <fieldset class="visit-model-fields">
    <legend>补充访视资料 <small>选填</small></legend>
    <p>按本次访视的实际记录填写，暂缺的项目可留空。</p>
    <div class="visit-model-fields__grid">
      <label v-for="field in visitModelFields" :key="field.key">
        <span>{{ field.label }}</span>
        <input v-model.trim="draft[field.key]" inputmode="decimal" :placeholder="`0–${field.max}`"
          :aria-invalid="!!errors[field.key]" :aria-describedby="errors[field.key] ? `${prefix}-${field.key}-error` : undefined"
          @input="emit('clear', field.key)" />
        <span v-if="errors[field.key]" :id="`${prefix}-${field.key}-error`" class="visit-model-fields__error">{{ errors[field.key] }}</span>
      </label>
    </div>
  </fieldset>
</template>

<script setup>
import { useId } from 'vue'
import { visitModelFields } from './visitModelInputs.js'

const prefix = useId()
const draft = defineModel({ type: Object, required: true })
defineProps({ errors: { type: Object, default: () => ({}) } })
const emit = defineEmits(['clear'])
</script>

<style scoped>
.visit-model-fields { border: 1px solid var(--el-border-color-light); border-radius: 8px; margin: 12px 0; padding: 10px; }
.visit-model-fields legend { padding: 0 4px; font-weight: 600; }
.visit-model-fields legend small { margin-left: 4px; font-weight: 400; color: var(--muted); }
.visit-model-fields p { margin: 2px 0 10px; color: var(--muted); }
.visit-model-fields__grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(145px, 1fr)); gap: 10px; }
.visit-model-fields__grid label { display: grid; align-content: start; gap: 5px; min-width: 0; }
.visit-model-fields__grid input { box-sizing: border-box; width: 100%; min-height: 44px; padding: 5px 8px; font: inherit; color: inherit; background: var(--el-fill-color-blank); border: 1px solid var(--el-border-color); border-radius: 6px; }
.visit-model-fields__grid input:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 1px; }
.visit-model-fields__error { color: var(--el-color-danger); font-size: 13px; }
</style>

<style>
.el-dialog.visit-model-dialog { display: flex; flex-direction: column; max-height: calc(100dvh - 32px); margin: 16px auto; }
.el-dialog.visit-model-dialog > .el-dialog__body { min-height: 0; overflow-y: auto; }
.el-dialog.visit-model-dialog > .el-dialog__header, .el-dialog.visit-model-dialog > .el-dialog__footer { flex: none; }
</style>
