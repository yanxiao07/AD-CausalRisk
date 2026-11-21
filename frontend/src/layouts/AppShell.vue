<template>
  <component :is="layout">
    <router-view v-slot="{ Component, route: current }">
      <transition name="page" mode="out-in">
        <component :is="Component" :key="current.path" />
      </transition>
    </router-view>
  </component>

  <!-- 路由切换的顶部进度条 -->
  <RouteProgress />

  <!-- 全局加载动画：切页 / 跳转时覆盖整页 -->
  <GlobalLoading />
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user.js'
import BlankLayout from './BlankLayout.vue'
import PatientLayout from './PatientLayout.vue'
import SidebarLayout from './SidebarLayout.vue'
import RouteProgress from '@/components/common/RouteProgress.vue'
import GlobalLoading from '@/components/common/GlobalLoading.vue'

/**
 * 三种外壳：空白（登录/异常页）、侧边栏（医生/研究员/管理员共用）、适老（患者）。
 * 管理员不再有独立的深色大屏外壳——四个角色统一浅色玻璃拟态。
 */
const LAYOUTS = {
  blank: BlankLayout,
  sidebar: SidebarLayout,
  patient: PatientLayout
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
