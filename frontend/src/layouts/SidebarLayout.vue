<template>
  <div class="shell" :class="{ 'is-collapsed': collapsed }">
    <aside class="shell__aside">
      <div class="brand">
        <span class="brand__mark" aria-hidden="true">AD</span>
        <transition name="fade">
          <span v-if="!collapsed" class="brand__text">
            <strong>CausalRisk</strong>
            <small>因果风险评估</small>
          </span>
        </transition>
      </div>

      <el-menu
        :default-active="activeKey"
        :collapse="collapsed"
        :collapse-transition="false"
        class="shell__menu"
        @select="onSelect"
      >
        <el-menu-item v-for="item in items" :key="item.key" :index="item.key" :disabled="!item.ready">
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>
            <span class="menu__label">{{ item.label }}</span>
            <span v-if="!item.ready" class="menu__badge">待建</span>
          </template>
        </el-menu-item>
      </el-menu>

      <div class="shell__aside-foot">
        <el-tooltip :content="collapsed ? '展开菜单' : '收起菜单'" placement="right">
          <el-button text :icon="collapsed ? Expand : Fold" @click="collapsed = !collapsed" />
        </el-tooltip>
        <span v-if="!collapsed" class="shell__version">v0.1 · P7</span>
      </div>
    </aside>

    <div class="shell__body">
      <header class="topbar">
        <div class="topbar__left">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>{{ roleLabel }}</el-breadcrumb-item>
            <el-breadcrumb-item>{{ route.meta?.title || '工作台' }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div class="topbar__right">
          <span class="role-chip">{{ roleLabel }}</span>

          <el-dropdown trigger="click" @command="onCommand">
            <button class="user" type="button">
              <el-avatar :size="30" class="user__avatar">{{ initial }}</el-avatar>
              <span class="user__name">{{ userStore.username }}</span>
              <el-icon><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout" :icon="SwitchButton">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="shell__main">
        <slot />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ArrowDown, Expand, Fold, HomeFilled, SwitchButton } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user.js'
import { logout } from '@/api/auth.js'
import { activeKeyOf, buildMenuItems } from './menuRegistry.js'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const collapsed = ref(false)

const ROLE_LABELS = { ADMIN: '管理员', RESEARCHER: '研究员', DOCTOR: '医生', PATIENT: '患者' }
const roleLabel = computed(() => ROLE_LABELS[userStore.primaryRole] ?? '访客')
const initial = computed(() => (userStore.username || '?').slice(0, 1).toUpperCase())

/** 第一项永远是本角色工作台；其余由后端 menus 决定。 */
const items = computed(() => [
  { key: 'home', label: '工作台', path: userStore.homePath, icon: HomeFilled, ready: true },
  ...buildMenuItems(userStore.menus)
])

const activeKey = computed(() => {
  if (route.path === userStore.homePath) return 'home'
  return activeKeyOf(items.value, route.path) || 'home'
})

function onSelect(key) {
  const item = items.value.find((entry) => entry.key === key)
  if (item?.ready && item.path) router.push(item.path)
}

async function onCommand(command) {
  if (command !== 'logout') return
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
.shell {
  display: flex;
  min-height: 100vh;
  background: var(--page-glow), var(--bg);
}

.shell__aside {
  display: flex;
  flex-direction: column;
  width: 232px;
  padding: 18px 12px;
  background: rgba(255, 255, 255, 0.78);
  border-right: 1px solid var(--line);
  -webkit-backdrop-filter: var(--glass-blur);
  backdrop-filter: var(--glass-blur);
  transition: width var(--dur-base) var(--ease-out);
  flex-shrink: 0;
}

.shell.is-collapsed .shell__aside {
  width: 76px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px 8px 18px;
}

.brand__mark {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: var(--r-sm);
  background: linear-gradient(135deg, var(--brand-600), var(--brand-700));
  color: #fff;
  font-family: var(--font-display);
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.5px;
  flex-shrink: 0;
}

.brand__text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
  white-space: nowrap;
}

.brand__text strong {
  font-size: 15px;
  color: var(--ink);
}

.brand__text small {
  font-size: 11px;
  color: var(--muted);
}

.shell__menu {
  flex: 1;
  border-right: none;
  background: transparent;
  overflow-x: hidden;
}

.shell__menu :deep(.el-menu-item) {
  height: 44px;
  margin-bottom: 4px;
  border-radius: var(--r-md);
  color: var(--ink-700);
  font-weight: 600;
}

.shell__menu :deep(.el-menu-item.is-active) {
  background: var(--brand-50);
  color: var(--brand-700);
}

.shell__menu :deep(.el-menu-item.is-disabled) {
  opacity: 0.55;
}

.menu__label {
  margin-right: 6px;
}

.menu__badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: var(--r-pill);
  background: #eef3f7;
  color: var(--muted);
}

.shell__aside-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 6px 0;
  border-top: 1px solid var(--line);
}

.shell__version {
  font-size: 11px;
  color: var(--soft);
}

.shell__body {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 64px;
  padding: 0 clamp(16px, 3vw, 32px);
  background: rgba(255, 255, 255, 0.7);
  border-bottom: 1px solid var(--line);
  -webkit-backdrop-filter: var(--glass-blur);
  backdrop-filter: var(--glass-blur);
  position: sticky;
  top: 0;
  z-index: 10;
}

.topbar__right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.role-chip {
  display: inline-flex;
  align-items: center;
  height: 26px;
  padding: 0 12px;
  border-radius: var(--r-pill);
  background: var(--brand-50);
  border: 1px solid rgba(10, 115, 173, 0.18);
  color: var(--brand-700);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.3px;
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border: none;
  background: transparent;
  border-radius: var(--r-pill);
  cursor: pointer;
  color: var(--ink);
  transition: background var(--dur-fast) var(--ease-out);
}

.user:hover {
  background: var(--brand-50);
}

.user__avatar {
  background: linear-gradient(135deg, var(--brand-600), var(--brand-700));
  color: #fff;
  font-weight: 700;
}

.user__name {
  font-size: 14px;
  font-weight: 600;
}

.shell__main {
  flex: 1;
  min-width: 0;
  padding: clamp(20px, 3vw, 36px) clamp(16px, 3vw, 36px) 48px;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity var(--dur-base) var(--ease-out);
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
