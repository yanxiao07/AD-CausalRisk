<template>
  <!-- 管理员数据大屏：深色全屏，无侧边栏，顶部横向导航仍由后端 menus 驱动 -->
  <div class="screen">
    <header class="screen__bar">
      <div class="screen__brand">
        <span class="screen__mark" aria-hidden="true">AD</span>
        <div class="screen__brand-text">
          <strong>数据大屏</strong>
          <small>CausalRisk · 运营总览</small>
        </div>
      </div>

      <nav class="screen__nav" aria-label="大屏导航">
        <button
          v-for="item in navItems"
          :key="item.key"
          type="button"
          class="screen__nav-item"
          :class="{ 'is-active': active === item.key, 'is-disabled': !item.ready }"
          @click="onNav(item)"
        >
          {{ item.label }}
        </button>
      </nav>

      <div class="screen__meta">
        <span class="screen__clock">{{ clock }}</span>
        <span class="screen__user">{{ userStore.username }}</span>
        <el-button text class="screen__logout" @click="onLogout">退出</el-button>
      </div>
    </header>

    <main class="screen__main">
      <slot />
    </main>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user.js'
import { logout } from '@/api/auth.js'
import { activeKeyOf, buildMenuItems } from './menuRegistry.js'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const navItems = computed(() => [
  { key: 'home', label: '数据大屏', path: '/admin', ready: true },
  ...buildMenuItems(userStore.menus)
])
const active = computed(() => activeKeyOf(navItems.value, route.path))

const clock = ref('')
let timer = null

function tick() {
  const now = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  clock.value = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
}

onMounted(() => {
  tick()
  timer = window.setInterval(tick, 1000)
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
  timer = null
})

function onNav(item) {
  if (!item.ready || !item.path) return
  router.push(item.path)
}

async function onLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  await logout()
  router.replace('/login')
}
</script>

<style scoped>
.screen {
  --screen-bg: #06131f;
  --screen-panel: rgba(20, 48, 72, 0.55);
  --screen-line: rgba(120, 190, 235, 0.18);
  --screen-ink: #dcecfb;
  --screen-muted: #7fa6c4;
  --screen-accent: #4fc3f7;

  display: flex;
  flex-direction: column;
  min-height: 100vh;
  color: var(--screen-ink);
  background:
    radial-gradient(circle at 15% 0%, rgba(41, 121, 174, 0.35) 0%, transparent 45%),
    radial-gradient(circle at 85% 10%, rgba(26, 92, 140, 0.28) 0%, transparent 50%),
    var(--screen-bg);
}

.screen__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  height: 72px;
  padding: 0 clamp(18px, 3vw, 36px);
  border-bottom: 1px solid var(--screen-line);
  background: rgba(6, 19, 31, 0.72);
  -webkit-backdrop-filter: blur(10px);
  backdrop-filter: blur(10px);
}

.screen__brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.screen__mark {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: var(--r-sm);
  background: linear-gradient(135deg, #2f9ad4, #1a5c8c);
  color: #eaf6ff;
  font-family: var(--font-display);
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.5px;
}

.screen__brand-text strong {
  display: block;
  font-size: 15px;
  letter-spacing: 0.4px;
}

.screen__brand-text small {
  font-size: 11px;
  color: var(--screen-muted);
  letter-spacing: 0.6px;
}

.screen__nav {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  justify-content: center;
}

.screen__nav-item {
  padding: 8px 14px;
  border: 1px solid transparent;
  border-radius: var(--r-pill);
  background: transparent;
  color: var(--screen-muted);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease-out), background var(--dur-fast) var(--ease-out);
}

.screen__nav-item:hover:not(.is-disabled) {
  color: var(--screen-ink);
  background: rgba(79, 195, 247, 0.1);
}

.screen__nav-item.is-active {
  color: #06131f;
  background: var(--screen-accent);
}

.screen__nav-item.is-disabled {
  opacity: 0.42;
  cursor: not-allowed;
}

.screen__meta {
  display: flex;
  align-items: center;
  gap: 16px;
  font-size: 13px;
  color: var(--screen-muted);
}

.screen__clock {
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.4px;
}

.screen__user {
  color: var(--screen-ink);
  font-weight: 600;
}

.screen__logout {
  color: var(--screen-muted);
}

.screen__main {
  flex: 1;
  padding: clamp(18px, 2.4vw, 32px);
}

.screen__logout:hover {
  color: var(--screen-ink);
}
</style>
