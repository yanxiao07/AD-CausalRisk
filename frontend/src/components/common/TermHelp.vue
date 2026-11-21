<template>
  <el-tooltip v-if="entry" :visible="visible" placement="top" :offset="4" effect="light"
    popper-class="term-help-popup" :teleported="true">
    <template #content>
      <span ref="popup" class="term-help-text" @pointerenter="showOnHover($event, 0)" @pointerleave="leave">
        {{ entry[1] }}
      </span>
    </template>
    <button ref="button" type="button" class="term-help-button" :aria-label="`${label || entry[0]}，词语说明`"
      :aria-expanded="visible" @pointerenter="showOnHover($event, 220)" @pointerleave="leave"
      @focus="focus" @blur="blur" @click="toggle">
      {{ label || entry[0] }}
    </button>
  </el-tooltip>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { terms } from '@/utils/terms.js'

const props = defineProps({ name: { type: String, required: true }, label: { type: String, default: '' } })
const entry = computed(() => terms[props.name])
const button = ref(null)
const popup = ref(null)
const visible = ref(false)
let pinned = false
let keyboardFocus = false
let timer

function showOnHover(event, delay) {
  if (event.pointerType !== 'mouse') return
  clearTimeout(timer)
  timer = setTimeout(() => { visible.value = true }, delay)
}
function leave(event) {
  if (event.pointerType !== 'mouse') return
  clearTimeout(timer)
  if (!pinned && !keyboardFocus) timer = setTimeout(hide, 220)
}
function focus(event) {
  if (!event.currentTarget.matches(':focus-visible')) return
  keyboardFocus = true
  clearTimeout(timer)
  visible.value = true
}
function blur() {
  keyboardFocus = false
  if (!pinned && !popup.value?.matches(':hover') && !button.value?.matches(':hover')) hide()
}
function toggle() {
  if (pinned) hide()
  else {
    clearTimeout(timer)
    pinned = true
    visible.value = true
  }
}
function hide() {
  clearTimeout(timer)
  visible.value = false
  pinned = false
  keyboardFocus = false
}

watch(visible, (open, _, cleanup) => {
  if (!open) return
  const dismissOutside = event => {
    if (!button.value?.contains(event.target) && !popup.value?.contains(event.target)) hide()
  }
  const dismissOnEscape = event => {
    if (event.key !== 'Escape') return
    event.stopPropagation()
    hide()
  }
  document.addEventListener('pointerdown', dismissOutside)
  document.addEventListener('keydown', dismissOnEscape, true)
  cleanup(() => {
    document.removeEventListener('pointerdown', dismissOutside)
    document.removeEventListener('keydown', dismissOnEscape, true)
  })
})

onBeforeUnmount(() => clearTimeout(timer))
</script>

<style scoped>
.term-help-button { display: inline-block; min-height: 24px; padding: 0 2px; border: 0; background: none; color: var(--brand-700); font: inherit; text-decoration: underline dotted; text-underline-offset: 3px; cursor: help; }
.term-help-button:focus-visible { outline: 2px solid var(--brand-700); outline-offset: 2px; border-radius: 4px; }
@media (pointer: coarse) {
  .term-help-button { min-height: 44px; padding-inline: 4px; }
}
</style>

<style>
.el-popper.term-help-popup { padding: 0; width: max-content; max-width: min(290px, calc(100vw - 32px)); }
.term-help-text { display: block; padding: 10px 12px; font-size: .875rem; line-height: 1.55; }
</style>
