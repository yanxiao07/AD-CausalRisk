import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 全站唯一的 Axios 实例。
 *
 * 后端所有响应都是统一 Envelope：
 * { code, message, data, errorCode, traceId, timestamp }
 * 成功时 code === 0，业务数据在 data 里；失败时 HTTP 状态码与 code 一致。
 */
const request = axios.create({
  baseURL: '/api', // 走 Vite 代理：/api -> http://localhost:8080
  timeout: 60000, // 评估任务要等 Python 跑完，留足 60s
  withCredentials: true // 会话（JSESSIONID）必须随请求带过去
})

request.interceptors.request.use(
  config => config,
  error => Promise.reject(error)
)

/** 401 时清理登录态并回到登录页；用动态 import 避免与 router/store 形成循环依赖。 */
let redirecting = false
async function redirectToLogin() {
  if (redirecting) return
  redirecting = true
  try {
    const { useUserStore } = await import('../stores/user.js')
    useUserStore().clear()
    const { default: router } = await import('../router/index.js')
    const current = router.currentRoute.value
    if (current.path !== '/login') {
      await router.replace({ path: '/login', query: { redirect: current.fullPath } })
    }
  } catch {
    window.location.assign('/login')
  } finally {
    // 登录页重新挂载后允许下一次 401 再次跳转
    window.setTimeout(() => {
      redirecting = false
    }, 1000)
  }
}

/** 登录请求的判定：显式跳过标记优先，其次兼容 URL 判断。 */
function isLoginRequest(config) {
  if (config?.skipAuthHandling) return true
  return String(config?.url || '').includes('/auth/login')
}

/** 静默请求：错误由页面自行渲染，拦截器不再弹全局消息（避免同一错误弹两次）。 */
function isSilent(config) {
  return Boolean(config?.silent)
}

request.interceptors.response.use(
  response => {
    const payload = response.data
    if (payload && typeof payload === 'object' && 'code' in payload) {
      if (payload.code === 0) {
        // 成功：直接给业务层 data，调用方不需要再关心 Envelope
        return payload.data
      }
      const failure = {
        code: payload.code,
        errorCode: payload.errorCode,
        message: payload.message || '请求失败',
        traceId: payload.traceId,
        // HTTP 状态单独保留：业务 code 可能与真实状态不一致（如 503 正文里带 code:401），
        // 会话探测只认真正的 HTTP 401，不能看 body 里的 code/errorCode。
        httpStatus: response.status
      }
      // 登录接口失败（含后端以 200 + code!=0 返回的极端情况）：不弹全局消息、不跳路由，
      // 把错误抛回表单，由 LoginCard 渲染成红字。
      if (isLoginRequest(response.config)) {
        return Promise.reject({ ...failure, message: payload.message || '账号或密码错误' })
      }
      if (!isSilent(response.config)) ElMessage.error(failure.message)
      return Promise.reject(failure)
    }
    // 非 Envelope（例如静态文件）原样返回
    return payload
  },
  async error => {
    if (!error.response) {
      // 登录/登出/会话复核这类"探测型"请求自己会解释失败，不再弹全局提示
      if (!isLoginRequest(error.config) && !isSilent(error.config)) {
        ElMessage.error('无法连接服务器，请确认后端已启动（默认 http://localhost:8080）')
      }
      return Promise.reject({
        code: -1,
        errorCode: 'NETWORK_ERROR',
        message: '无法连接服务器'
      })
    }

    const { status, data: body = {} } = error.response

    // 探测型请求（登录/登出/会话复核）自己会把失败翻译成表单文案，不弹全局消息
    const quiet = isLoginRequest(error.config)

    if (status === 401) {
      // 登录接口自身的 401 = 账号或密码错误：交给登录表单内联提示，不跳转、不弹全局消息
      if (quiet) {
        return Promise.reject({
          code: 401,
          errorCode: body.errorCode || 'AUTH_INVALID_CREDENTIALS',
          message: body.message || '账号或密码错误',
          httpStatus: 401
        })
      }
      await redirectToLogin()
      if (!isSilent(error.config)) ElMessage.error(body.message || '登录已过期，请重新登录')
      return Promise.reject({
        code: 401,
        errorCode: body.errorCode || 'UNAUTHENTICATED',
        message: body.message,
        httpStatus: 401
      })
    }

    if (status === 403) {
      if (!quiet && !isSilent(error.config)) ElMessage.error(body.message || '无权限访问该资源')
      return Promise.reject({
        code: 403,
        errorCode: body.errorCode || 'ACCESS_DENIED',
        message: body.message,
        httpStatus: 403
      })
    }

    if (!quiet && !isSilent(error.config)) ElMessage.error(body.message || error.message || '请求失败')
    return Promise.reject({
      code: body.code || status,
      errorCode: body.errorCode,
      message: body.message || error.message || '请求失败',
      traceId: body.traceId,
      httpStatus: status
    })
  }
)

export default request
