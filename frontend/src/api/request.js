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

request.interceptors.response.use(
  response => {
    const payload = response.data
    if (payload && typeof payload === 'object' && 'code' in payload) {
      if (payload.code === 0) {
        // 成功：直接给业务层 data，调用方不需要再关心 Envelope
        return payload.data
      }
      ElMessage.error(payload.message || '请求失败')
      return Promise.reject({
        code: payload.code,
        errorCode: payload.errorCode,
        message: payload.message || '请求失败',
        traceId: payload.traceId
      })
    }
    // 非 Envelope（例如静态文件）原样返回
    return payload
  },
  async error => {
    if (!error.response) {
      ElMessage.error('无法连接服务器，请确认后端已启动（默认 http://localhost:8080）')
      return Promise.reject({
        code: -1,
        errorCode: 'NETWORK_ERROR',
        message: '无法连接服务器'
      })
    }

    const { status, data: body = {} } = error.response

    if (status === 401) {
      // 登录接口自身的 401 = 账号或密码错误，交给登录卡片内联提示，不跳转、不弹全局消息
      const isLoginAttempt = String(error.config?.url || '').includes('/auth/login')
      if (isLoginAttempt) {
        return Promise.reject({
          code: 401,
          errorCode: body.errorCode || 'AUTH_INVALID_CREDENTIALS',
          message: body.message || '账号或密码错误'
        })
      }
      await redirectToLogin()
      ElMessage.error(body.message || '登录已过期，请重新登录')
      return Promise.reject({ code: 401, errorCode: body.errorCode || 'UNAUTHENTICATED', message: body.message })
    }

    if (status === 403) {
      ElMessage.error(body.message || '无权限访问该资源')
      return Promise.reject({ code: 403, errorCode: body.errorCode || 'ACCESS_DENIED', message: body.message })
    }

    ElMessage.error(body.message || error.message || '请求失败')
    return Promise.reject({
      code: body.code || status,
      errorCode: body.errorCode,
      message: body.message || error.message || '请求失败',
      traceId: body.traceId
    })
  }
)

export default request
