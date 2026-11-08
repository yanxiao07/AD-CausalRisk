<template>
  <div class="exception">
    <div class="exception__card">
      <span class="exception__code">{{ code }}</span>
      <h1 class="exception__title">{{ title }}</h1>
      <p class="exception__desc">{{ description }}</p>
      <div class="exception__actions">
        <el-button type="primary" @click="goHome">回到工作台</el-button>
        <el-button :icon="Back" @click="router.back()">返回上一页</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { Back } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user.js'

defineProps({
  code: { type: String, required: true },
  title: { type: String, required: true },
  description: { type: String, required: true }
})

const router = useRouter()
const userStore = useUserStore()
const goHome = () => router.replace(userStore.isLoggedIn ? userStore.homePath : '/login')
</script>

<style scoped>
.exception {
  display: grid;
  place-items: center;
  min-height: 100vh;
  padding: 24px;
  background: var(--page-glow), var(--bg);
}

.exception__card {
  width: min(520px, 100%);
  padding: 44px 40px;
  text-align: center;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-panel);
}

.exception__code {
  display: block;
  font-family: var(--font-display);
  font-size: 56px;
  font-weight: 700;
  letter-spacing: -2px;
  color: var(--brand-50);
  -webkit-text-stroke: 1px var(--brand-600);
  margin-bottom: 8px;
}

.exception__title {
  font-size: 22px;
  font-weight: 700;
  color: var(--ink);
}

.exception__desc {
  margin: 10px auto 24px;
  max-width: 380px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--muted);
}

.exception__actions {
  display: flex;
  justify-content: center;
  gap: 12px;
}
</style>
