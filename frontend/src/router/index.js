import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user.js'

/**
 * 路由表约定：
 * - meta.public：未登录也可访问（登录页、异常页）
 * - meta.roles：允许访问的角色，未声明表示"登录即可"
 * - meta.layout：P1 接入布局后由 AppShell 读取（blank / sidebar / patient）
 */
const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginView.vue'),
    meta: { title: '登录', layout: 'blank', public: true }
  },
  {
    path: '/401',
    name: 'Exception401',
    component: () => import('../views/exception/401.vue'),
    meta: { title: '无权限', layout: 'blank', public: true }
  },
  {
    path: '/500',
    name: 'Exception500',
    component: () => import('../views/exception/500.vue'),
    meta: { title: '服务器错误', layout: 'blank', public: true }
  },
  {
    // 登录后按角色落到各自工作台
    path: '/',
    redirect: () => useUserStore().homePath
  },
  {
    path: '/doctor',
    name: 'DoctorHome',
    component: () => import('../views/doctor/DoctorHome.vue'),
    meta: { title: '医生工作台', layout: 'sidebar', roles: ['DOCTOR'] }
  },
  {
    path: '/researcher',
    name: 'ResearcherHome',
    component: () => import('../views/researcher/ResearchHome.vue'),
    meta: { title: '研究员工作台', layout: 'sidebar', roles: ['RESEARCHER'] }
  },
  {
    path: '/admin',
    name: 'AdminHome',
    component: () => import('../views/admin/AdminHome.vue'),
    meta: { title: '数据大屏', layout: 'screen', roles: ['ADMIN'] }
  },
  {
    path: '/admin/audit',
    name: 'AuditLog',
    component: () => import('../views/admin/AuditLog.vue'),
    meta: { title: '审计日志', layout: 'screen', roles: ['ADMIN'] }
  },
  {
    path: '/patient',
    name: 'PatientHome',
    component: () => import('../views/patient/PatientHome.vue'),
    meta: { title: '我的健康档案', layout: 'patient', roles: ['PATIENT'] }
  },
  {
    path: '/subjects',
    name: 'PatientList',
    component: () => import('../views/doctor/PatientList.vue'),
    meta: { title: '受试者档案', layout: 'sidebar', roles: ['DOCTOR', 'RESEARCHER'] }
  },
  {
    path: '/imports',
    name: 'ImportCenter',
    component: () => import('../views/researcher/ImportCenter.vue'),
    meta: { title: '数据导入', layout: 'sidebar', roles: ['RESEARCHER'] }
  },
  {
    path: '/followups',
    name: 'FollowUpList',
    component: () => import('../views/doctor/FollowUpList.vue'),
    meta: { title: '随访计划', layout: 'sidebar', roles: ['DOCTOR', 'RESEARCHER'] }
  },
  {
    path: '/reviews',
    name: 'ReviewBoard',
    component: () => import('../views/doctor/ReviewBoard.vue'),
    meta: { title: '待办复核', layout: 'sidebar', roles: ['DOCTOR', 'RESEARCHER'] }
  },
  {
    path: '/admin/users',
    name: 'UserAdmin',
    component: () => import('../views/admin/UserAdmin.vue'),
    meta: { title: '账号管理', layout: 'screen', roles: ['ADMIN'] }
  },
  {
    path: '/reports',
    name: 'ReportList',
    component: () => import('../views/doctor/ReportList.vue'),
    meta: { title: '评估报告', layout: 'sidebar', roles: ['DOCTOR', 'RESEARCHER'] }
  },
  {
    path: '/graph',
    name: 'GraphView',
    component: () => import('../views/doctor/GraphView.vue'),
    meta: { title: '因果图谱', layout: 'sidebar', roles: ['DOCTOR', 'RESEARCHER'] }
  },
  {
    path: '/admin/system',
    name: 'SystemSettings',
    component: () => import('../views/admin/SystemSettings.vue'),
    meta: { title: '系统设置', layout: 'screen', roles: ['ADMIN'] }
  },
  {
    path: '/quality',
    name: 'QcList',
    component: () => import('../views/researcher/QcList.vue'),
    meta: { title: '数据质控', layout: 'sidebar', roles: ['RESEARCHER', 'ADMIN'] }
  },
  {
    path: '/tasks',
    name: 'TaskList',
    component: () => import('../views/researcher/TaskList.vue'),
    meta: { title: '评估任务', layout: 'sidebar', roles: ['RESEARCHER', 'DOCTOR', 'ADMIN'] }
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'Exception404',
    component: () => import('../views/exception/404.vue'),
    meta: { title: '页面不存在', layout: 'blank', public: true }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach((to) => {
  document.title = to.meta?.title
    ? `${to.meta.title} · AD-CausalRisk`
    : 'AD-CausalRisk · 因果风险评估平台'

  const userStore = useUserStore()

  if (to.meta?.public) {
    // 已登录还停留在登录页 -> 回各自工作台
    if (to.name === 'Login' && userStore.isLoggedIn) return userStore.homePath
    return true
  }

  if (!userStore.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  if (Array.isArray(to.meta?.roles) && !userStore.hasRole(to.meta.roles)) {
    return '/401'
  }

  return true
})

export default router
