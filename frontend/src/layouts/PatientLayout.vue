<template>
  <!-- 适老布局：不挂载侧边栏，单列、大字号、大触控目标 -->
  <div class="patient">
    <header class="patient__bar">
      <div class="patient__brand">
        <span class="patient__mark" aria-hidden="true">AD</span>
        <div>
          <strong>我的健康档案</strong>
          <small>{{ userStore.username }}</small>
        </div>
      </div>

      <el-button class="patient__logout" size="large" text @click="onLogout">
        退出
      </el-button>
    </header>

    <main class="patient__main">
      <slot />
    </main>

    <footer class="patient__foot">
      如有不适请及时就医；页面信息不能替代医生面诊。
    </footer>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user.js'
import { logout } from '@/api/auth.js'

const router = useRouter()
const userStore = useUserStore()

async function onLogout() {
  try {
    await ElMessageBox.confirm('确定要退出吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '再想想',
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
.patient {
  /* 适老化：基础字号 20px，控件高度同步放大（Element Plus 变量在本容器内生效） */
  --el-font-size-base: 20px;
  --el-font-size-medium: 20px;
  --el-component-size: 56px;
  --el-component-size-large: 64px;

  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: var(--page-glow), var(--bg-soft);
  color: var(--ink);
}

.patient__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 20px clamp(20px, 5vw, 48px);
  background: rgba(255, 255, 255, 0.82);
  border-bottom: 1px solid var(--line);
  -webkit-backdrop-filter: var(--glass-blur);
  backdrop-filter: var(--glass-blur);
}

.patient__brand {
  display: flex;
  align-items: center;
  gap: 14px;
}

.patient__mark {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  border-radius: 14px;
  background: linear-gradient(135deg, var(--brand-600), var(--brand-700));
  color: #fff;
  font-family: var(--font-display);
  font-size: 15px;
  font-weight: 700;
}

.patient__brand strong {
  display: block;
  font-size: 22px;
  font-weight: 700;
}

.patient__brand small {
  font-size: 15px;
  color: var(--muted);
}

.patient__logout {
  font-size: 18px;
  color: var(--muted);
}

.patient__main {
  flex: 1;
  width: 100%;
  max-width: 720px;
  margin: 0 auto;
  padding: 28px clamp(20px, 5vw, 32px) 40px;
}

.patient__foot {
  padding: 0 24px 28px;
  text-align: center;
  font-size: 14px;
  line-height: 1.8;
  color: var(--soft);
}
</style>
