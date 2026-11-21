<template>
  <div class="shell" :class="{ 'is-collapsed': collapsed, 'is-mobile-open': mobileOpen }">
    <button
      v-if="mobileOpen"
      class="shell__scrim"
      type="button"
      aria-label="关闭导航菜单"
      @click="closeMobile"
    />
    <aside class="shell__aside">
      <div class="brand">
        <span class="brand__mark" aria-hidden="true">AD</span>
        <transition name="fade">
          <span v-if="!isCollapsed" class="brand__text">
            <strong>CausalRisk</strong>
            <small>因果风险评估</small>
          </span>
        </transition>
      </div>

      <el-menu
        :default-active="activeKey"
        :collapse="collapsed && !isNarrow"
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
          <el-button
            class="topbar__menu"
            text
            aria-label="打开导航菜单"
            @click="openMobile"
          >
            <span class="hamburger" aria-hidden="true">
              <span></span><span></span><span></span>
            </span>
          </el-button>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>{{ roleLabel }}</el-breadcrumb-item>
            <el-breadcrumb-item>{{ pageTitle }}</el-breadcrumb-item>
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
                <el-dropdown-item command="profile" :icon="User">账号信息</el-dropdown-item>
                <el-dropdown-item command="account" :icon="EditPen">个人设置</el-dropdown-item>
                <el-dropdown-item command="preferences" :icon="Setting">用户设置</el-dropdown-item>
                <el-dropdown-item divided command="logout" :icon="SwitchButton">
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="shell__main">
        <slot />
      </main>
    </div>

    <!-- 账号信息 -->
    <el-dialog v-model="profileOpen" title="账号信息" width="420px" destroy-on-close>
      <div class="profile">
        <el-avatar :size="54" class="profile__avatar">{{ initial }}</el-avatar>
        <div class="profile__meta">
          <p class="profile__name">{{ userStore.username }}</p>
          <div class="profile__tags">
            <el-tag
              v-for="role in userStore.roles"
              :key="role"
              size="small"
              type="info"
            >
              {{ ROLE_LABELS[role] || role }}
            </el-tag>
          </div>
        </div>
      </div>
      <el-descriptions :column="1" border size="small" class="profile__desc">
        <el-descriptions-item label="账号 ID">{{ userStore.id ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="当前身份">{{ roleLabel }}</el-descriptions-item>
        <el-descriptions-item label="主导航">
          {{ items.length }} 项
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="profileOpen = false">关闭</el-button>
        <el-button type="primary" @click="profileOpen = false; settingsOpen = true">
          打开用户设置
        </el-button>
      </template>
    </el-dialog>

    <!-- 用户设置（字号 / 密度 / 通知） -->
    <UserSettingsDialog v-model="settingsOpen" />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  EditPen,
  Expand,
  Fold,
  HomeFilled,
  Setting,
  SwitchButton,
  User
} from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user.js'
import { logout } from '@/api/auth.js'
import UserSettingsDialog from '@/components/user/UserSettingsDialog.vue'
import { applyPreferences, readLocalPreferences } from '@/utils/preferences.js'
import { activeKeyOf, buildMenuItems } from './menuRegistry.js'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const collapsed = ref(false)
const mobileOpen = ref(false)
const isNarrow = ref(typeof window !== 'undefined' && window.innerWidth < 1024)
const profileOpen = ref(false)
const settingsOpen = ref(false)

/** 桌面可收起；窄屏始终显示完整菜单（抽屉内的图标列表难用）。 */
const isCollapsed = computed(() => collapsed.value && !isNarrow.value)

function openMobile() {
  mobileOpen.value = true
}

function closeMobile() {
  mobileOpen.value = false
}

function onResize() {
  isNarrow.value = window.innerWidth < 1024
  if (!isNarrow.value) mobileOpen.value = false
}

watch(
  () => route.fullPath,
  () => closeMobile()
)

const ROLE_LABELS = { ADMIN: '管理员', RESEARCHER: '研究员', DOCTOR: '医生', PATIENT: '患者' }
const roleLabel = computed(() => ROLE_LABELS[userStore.primaryRole] ?? '访客')
const pageTitle = computed(() => {
  if (route.name === 'PatientList') return userStore.primaryRole === 'DOCTOR'
    ? (route.query.tab === 'legacy' ? '全部授权档案' : '我的患者') : '研究档案'
  return route.meta?.title || '工作台'
})
const initial = computed(() => (userStore.username || '?').slice(0, 1).toUpperCase())

/** 第一项永远是本角色工作台；其余由后端 menus 决定。 */
const items = computed(() => [
  { key: 'home', label: '工作台', path: userStore.homePath, icon: HomeFilled, ready: true },
  ...buildMenuItems(userStore.menus, userStore.primaryRole)
])
const activeKey = computed(() => {
  if (route.path === userStore.homePath) return 'home'
  return activeKeyOf(items.value, route.path) || 'home'
})

function onSelect(key) {
  const item = items.value.find((entry) => entry.key === key)
  if (item?.ready && item.path) router.push(item.path)
  closeMobile()
}

async function onCommand(command) {
  if (command === 'profile') {
    profileOpen.value = true
    return
  }
  if (command === 'account') {
    router.push('/settings/profile')
    return
  }
  if (command === 'preferences') {
    settingsOpen.value = true
    return
  }
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
  // 清空本地会话并回到登录页（旧批量评估轮询已退役）
  userStore.logout()
  router.replace('/login')
}

onMounted(() => {
  // 首屏应用本机缓存的偏好，避免设置生效延迟
  const local = readLocalPreferences()
  if (local) applyPreferences(local)
  window.addEventListener('resize', onResize, { passive: true })
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
})
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

.shell__scrim {
  display: none;
}

.topbar__menu {
  display: none;
}

.hamburger {
  display: inline-flex;
  flex-direction: column;
  gap: 4px;
  width: 18px;
}

.hamburger span {
  display: block;
  height: 2px;
  width: 100%;
  border-radius: 2px;
  background: currentColor;
}

.topbar__left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.topbar__left :deep(.el-breadcrumb) {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
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

@media (max-width: 1023px) {
  .shell__aside,
  .shell.is-collapsed .shell__aside {
    position: fixed;
    inset: 0 auto 0 0;
    z-index: 40;
    width: 264px;
    max-width: 86vw;
    transform: translateX(-105%);
    transition: transform var(--dur-base) var(--ease-out);
    box-shadow: 18px 0 40px rgba(8, 31, 48, 0.18);
  }

  .shell.is-mobile-open .shell__aside {
    transform: translateX(0);
  }

  .shell__scrim {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 30;
    border: 0;
    background: rgba(8, 31, 48, 0.38);
    -webkit-backdrop-filter: blur(2px);
    backdrop-filter: blur(2px);
  }

  .topbar__menu {
    display: inline-flex;
    margin-left: -6px;
  }

  .topbar {
    height: 56px;
    padding: 0 12px;
  }

  .topbar__right {
    gap: 4px;
  }

  .role-chip,
  .user__name,
  .shell__aside-foot {
    display: none;
  }

  .shell__main {
    padding: 16px 12px 40px;
  }

  .brand {
    padding-bottom: 10px;
  }
}

@media (max-width: 480px) {
  .topbar {
    padding: 0 10px;
  }

  .shell__main {
    padding-left: 10px;
    padding-right: 10px;
  }
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity var(--dur-base) var(--ease-out);
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* ---- 账号信息弹窗 ---- */
.profile {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 14px;
}

.profile__avatar {
  background: var(--brand-600);
  color: #fff;
  font-weight: 700;
}

.profile__meta {
  min-width: 0;
}

.profile__name {
  margin: 0 0 6px;
  font-size: 17px;
  font-weight: 700;
  color: var(--ink);
}

.profile__tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.profile__desc :deep(.el-descriptions__label) {
  width: 96px;
  color: var(--muted);
}
</style>
