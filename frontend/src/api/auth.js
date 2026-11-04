import request from './request.js'
import { useUserStore } from '../stores/user.js'

/**
 * POST /api/auth/login
 * 成功时 Envelope 已被 request 拦截器脱壳，这里拿到的是 data：
 * { id, username, roles, menus }
 */
export async function login(username, password) {
  const data = await request.post('/auth/login', { username, password })
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
