<template>
  <header class="page-header">
    <div class="page-header__main">
      <el-button
        v-if="back"
        class="page-header__back"
        text
        :icon="ArrowLeft"
        @click="goBack"
      >
        返回
      </el-button>
      <div class="page-header__text">
        <h1 class="page-header__title">{{ title }}</h1>
        <p v-if="subtitle" class="page-header__subtitle">{{ subtitle }}</p>
      </div>
    </div>

    <div v-if="$slots.extra" class="page-header__extra">
      <slot name="extra" />
    </div>
  </header>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user.js'

defineProps({
  /** 主标题 */
  title: { type: String, required: true },
  /** 副标题，一句话说明这个页面能做什么 */
  subtitle: { type: String, default: '' },
  /** 是否显示返回按钮 */
  back: { type: Boolean, default: false }
})

const router = useRouter()
const userStore = useUserStore()

/**
 * 优先回退到上一页；但如果这个页面是直接从链接/新标签打开的，浏览器没有可回退的历史，
 * 此时退回本角色工作台，避免按钮点了毫无反应。
 */
function goBack() {
  const hasHistory = window.history.state?.back != null && window.history.length > 1
  if (hasHistory) router.back()
  else router.push(userStore.homePath || '/')
}
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
}

.page-header__main {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  min-width: 0;
}

.page-header__back {
  flex: 0 0 auto;
  margin-top: 3px;
  padding: 6px 10px;
  height: auto;
  border-radius: var(--r-sm);
  font-size: 13px;
  font-weight: 600;
  color: var(--muted);
  transition: color 0.16s ease, background-color 0.16s ease;
}

.page-header__back:hover {
  color: var(--brand-700);
  background: rgba(10, 115, 173, 0.08);
}

.page-header__back:focus-visible {
  outline: 2px solid rgba(74, 155, 190, 0.8);
  outline-offset: 2px;
}

.page-header__text {
  min-width: 0;
}

.page-header__title {
  font-size: 28px;
  line-height: 1.28;
  font-weight: 700;
  letter-spacing: -0.3px;
  color: var(--ink);
}

.page-header__subtitle {
  margin: 6px 0 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--muted);
}

.page-header__extra {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
</style>
