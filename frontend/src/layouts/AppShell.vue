<template>
  <component :is="layout">
    <router-view v-slot="{ Component, route: current }">
      <transition name="page" mode="out-in">
        <component :is="Component" :key="current.path" />
      </transition>
    </router-view>
  </component>

  <!-- 批量评估进度面板：挂在 Shell 上，切页面也不丢 -->
  <BatchProgressPanel />
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user.js'
import BlankLayout from './BlankLayout.vue'
import PatientLayout from './PatientLayout.vue'
import ScreenLayout from './ScreenLayout.vue'
import SidebarLayout from './SidebarLayout.vue'
import BatchProgressPanel from '@/components/assessment/BatchProgressPanel.vue'

const LAYOUTS = {
  blank: BlankLayout,
  sidebar: SidebarLayout,
  patient: PatientLayout,
  screen: ScreenLayout
}

const route = useRoute()
const userStore = useUserStore()

/**
 * 布局选择：路由 meta.layout 优先；没声明时按角色兜底
 * （患者永远进适老布局，其余进侧边栏布局）。
 */
const layout = computed(() => {
  const declared = route.meta?.layout
  if (declared && LAYOUTS[declared]) return LAYOUTS[declared]
  return userStore.primaryRole === 'PATIENT' ? PatientLayout : SidebarLayout
})
</script>

<style>
/* 页面切换：淡入 + 轻微上移，240ms，符合 tokens 里的动效规范 */
.page-enter-active,
.page-leave-active {
  transition: opacity 240ms var(--ease-out), transform 240ms var(--ease-out);
}

.page-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.page-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (prefers-reduced-motion: reduce) {
  .page-enter-active,
  .page-leave-active {
    transition: none;
  }
}
</style>
