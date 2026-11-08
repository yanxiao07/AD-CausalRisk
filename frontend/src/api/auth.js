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
 * 会话状态判定，**只认 /api/me 的 HTTP 状态**：
 *   200  → 'active'   会话仍然有效
 *   401  → 'cleared'  会话已失效
 *   其它（503 / 超时 / 断网）→ 'unknown'  无法确认
 *
 * 之前把"复核请求失败"当成"已登出"，于是 503 时会提示角色不符，但同一会话其实还能读
 * 管理员接口。这里必须把"确认失效"和"无法确认"分开。
 */
export const SESSION_ACTIVE = 'active'
export const SESSION_CLEARED = 'cleared'
export const SESSION_UNKNOWN = 'unknown'

async function probeSession() {
  try {
    await request.get('/me', { skipAuthHandling: true })
    return SESSION_ACTIVE
  } catch (problem) {
    // 只认**真正的 HTTP 401**。故障注入里 503 的响应正文可能带 code:401 或
    // UNAUTHENTICATED，那是服务端故障，不代表会话已失效。
    return problem?.httpStatus === 401 ? SESSION_CLEARED : SESSION_UNKNOWN
  }
}

/**
 * 正在进行的"登出 + 复核"。登录与退出必须互斥：
 * 否则退出请求还在路上时用户能提交新登录，登录成功、进入工作台后，迟到的登出
 * 会把刚建立的新会话一起注销。并发调用（自动登出 + 用户点重试）合并成同一次。
 */
let closingSession = null

export function isClosingSession() {
  return Boolean(closingSession)
}

/** 尝试登出并复核会话状态；即使登出请求本身失败，也以 /api/me 的结果为准。 */
export function closeSessionAndVerify() {
  if (closingSession) return closingSession
  closingSession = (async () => {
    try {
      await request.post('/auth/logout', null, { skipAuthHandling: true })
    } catch {
      // 登出失败不影响结论：下面的 probe 会给出 200 / 401 / unknown
    }
    return probeSession()
  })().finally(() => {
    closingSession = null
  })
  return closingSession
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
  // 互斥：退出还没结束时不允许登录，否则迟到的登出会把新会话一起注销
  if (closingSession) {
    throw {
      code: 409,
      errorCode: 'SESSION_CLOSING',
      message: '上一个会话正在退出，请稍候再试。'
    }
  }

  const data = await request.post('/auth/login', { username, password }, {
    skipAuthHandling: true
  })

  const expected = ROLE_CODES[expectedRoleKey]
  const roles = Array.isArray(data?.roles) ? data.roles : []
  if (expected && !roles.includes(expected)) {
    // 后端已经为这个账号建立了会话，只在前端报错不够：浏览器仍持有有效 JSESSIONID。
    const session = await closeSessionAndVerify()
    useUserStore().clear()

    if (session === SESSION_ACTIVE) {
      throw {
        code: 403,
        errorCode: 'ROLE_MISMATCH_SESSION_ACTIVE',
        message:
          '该账号没有当前身份权限，且会话未能安全退出；请关闭浏览器后重试，或联系管理员处理。'
      }
    }
    if (session === SESSION_UNKNOWN) {
      throw {
        code: 403,
        errorCode: 'ROLE_MISMATCH_SESSION_UNKNOWN',
        message:
          '该账号没有当前身份权限，且暂时无法确认会话是否已退出；请点击「重试退出登录」再试一次。'
      }
    }
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
