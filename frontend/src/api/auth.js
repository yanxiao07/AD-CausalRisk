import request from './request.js'
import { useUserStore } from '../stores/user.js'

/** 登录页身份卡的 key → 后端角色码。 */
const ROLE_CODES = {
  doctor: 'DOCTOR',
  researcher: 'RESEARCHER',
  admin: 'ADMIN',
  patient: 'PATIENT'
}

/**
 * POST /api/auth/login
 * 成功时 Envelope 已被 request 拦截器脱壳，这里拿到的是 data：
 * { id, username, roles, menus }
 *
 * `skipAuthHandling` 让拦截器在 401 时**不要**跳转 `/login`、也不要弹全局消息：
 * 登录接口的 401 只代表"账号或密码错误"，必须由表单自己渲染成红字提示，
 * 否则用户会看到页面莫名跳走或提示一闪而过。
 *
 * `expectedRoleKey` 是登录页当前选中的身份。后端只校验用户名/密码，不校验"你想以哪个身份进"，
 * 所以 admin 账号选「医生」身份也能拿到 200。这里在写入登录态**之前**拦下来，
 * 避免跳到一个自己没有权限的角色首页（表现为 /401 或白屏）。
 */
export async function login(username, password, expectedRoleKey) {
  const data = await request.post('/auth/login', { username, password }, {
    skipAuthHandling: true
  })

  const expected = ROLE_CODES[expectedRoleKey]
  const roles = Array.isArray(data?.roles) ? data.roles : []
  if (expected && !roles.includes(expected)) {
    // 后端已经为这个账号建立了会话。只在前端报错是不够的：浏览器仍持有有效的
    // JSESSIONID，/api/me 会继续返回 ADMIN，管理员接口也照样 200。必须显式登出。
    try {
      await request.post('/auth/logout', null, { skipAuthHandling: true })
    } catch {
      // 登出请求失败也不能放行：本地会话同样清掉，并让用户重试
    }
    useUserStore().clear()
    throw {
      code: 403,
      errorCode: 'ROLE_MISMATCH',
      message: '该账号没有当前身份权限，请切换身份后重试'
    }
  }

  useUserStore().setUser(data)
  return data
}

/** GET /api/me —— 刷新页面后用它确认会话仍然有效。 */
export async function fetchCurrentUser() {
  const data = await request.get('/me')
  useUserStore().setUser(data)
  return data
}

/** POST /api/auth/logout —— 无论后端是否成功，本地登录态都要清掉。 */
export async function logout() {
  try {
    await request.post('/auth/logout')
  } finally {
    useUserStore().clear()
  }
}
