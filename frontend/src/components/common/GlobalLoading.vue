<template>
  <transition name="global-loading">
    <div v-if="visible" class="global-loading" aria-live="polite" aria-busy="true">
      <NeuroLoader />
    </div>
  </transition>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import NeuroLoader from '@/components/common/NeuroLoader.vue'

/**
 * 全局加载动画：路由切换时覆盖整页。
 *
 * 只在"确实慢"时才出现：路由在 240ms 内完成就完全不显示，
 * 避免本地/缓存良好的切页每次都演一遍动画，显得笨重。
 *
 * 遮罩不拦截点击（pointer-events: none），这样自动化测试与用户在过渡期间的
 * 操作不会被挡住；它只负责"看得见"的加载反馈。
 */
const router = useRouter()
const visible = ref(false)
const SHOW_DELAY_MS = 240

let showTimer = null
let hideTimer = null

function show() {
  if (hideTimer) {
    clearTimeout(hideTimer)
    hideTimer = null
  }
  if (visible.value || showTimer) return
  showTimer = window.setTimeout(() => {
    showTimer = null
    visible.value = true
  }, SHOW_DELAY_MS)
}

function hide() {
  // 还没到显示阈值就结束了：直接取消，不播放。
  if (showTimer) {
    clearTimeout(showTimer)
    showTimer = null
  }
  if (!visible.value) return
  if (hideTimer) clearTimeout(hideTimer)
  hideTimer = window.setTimeout(() => {
    visible.value = false
    hideTimer = null
  }, 0)
}

onMounted(() => {
  router.beforeEach(show)
  router.afterEach(hide)
  router.onError(hide)
})

onBeforeUnmount(() => {
  if (showTimer) clearTimeout(showTimer)
  if (hideTimer) clearTimeout(hideTimer)
})
</script>

<style scoped>
.global-loading {
  position: fixed;
  inset: 0;
  z-index: 2900;
  display: grid;
  place-items: center;
  background: rgba(247, 252, 252, 0.9);
  -webkit-backdrop-filter: blur(4px);
  backdrop-filter: blur(4px);
  pointer-events: none;
}

.global-loading-enter-active,
.global-loading-leave-active {
  transition: opacity 180ms ease;
}

.global-loading-enter-from,
.global-loading-leave-to {
  opacity: 0;
}
</style>
