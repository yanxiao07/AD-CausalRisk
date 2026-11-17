<template>
  <div
    class="route-progress"
    :class="{ 'is-active': active, 'is-done': done }"
    aria-hidden="true"
  >
    <span class="route-progress__bar"></span>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

/**
 * 顶部路由进度条：路由开始加载时出现，结束后走完并淡出。
 * 与 GlobalLoading 一样按需显示：260ms 内完成的切页不出现进度条。
 */
const router = useRouter()
const active = ref(false)
const done = ref(false)
const SHOW_DELAY_MS = 260
let showTimer = null
let hideTimer = null

function start() {
  if (hideTimer) {
    clearTimeout(hideTimer)
    hideTimer = null
  }
  done.value = false
  if (active.value || showTimer) return
  showTimer = window.setTimeout(() => {
    showTimer = null
    active.value = true
  }, SHOW_DELAY_MS)
}

function finish() {
  if (showTimer) {
    clearTimeout(showTimer)
    showTimer = null
    done.value = false
    return
  }
  if (!active.value) return
  active.value = false
  done.value = true
  hideTimer = window.setTimeout(() => {
    done.value = false
  }, 360)
}

onMounted(() => {
  router.beforeEach(start)
  router.afterEach(finish)
  router.onError(finish)
})

onBeforeUnmount(() => {
  if (showTimer) clearTimeout(showTimer)
  if (hideTimer) clearTimeout(hideTimer)
})
</script>

<style scoped>
.route-progress {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 3000;
  height: 2px;
  pointer-events: none;
  opacity: 0;
  transition: opacity 160ms ease;
}

.route-progress.is-active,
.route-progress.is-done {
  opacity: 1;
}

.route-progress__bar {
  display: block;
  width: 0;
  height: 100%;
  background: linear-gradient(90deg, #0a73ad 0%, #38bdf8 60%, #7dd3fc 100%);
  box-shadow: 0 0 8px rgba(56, 189, 248, 0.55);
  transition: width 520ms cubic-bezier(0.22, 0.8, 0.25, 1);
}

.route-progress.is-active .route-progress__bar {
  width: 82%;
}

.route-progress.is-done .route-progress__bar {
  width: 100%;
  transition: width 180ms ease, opacity 240ms ease 180ms;
  opacity: 0;
}
</style>
