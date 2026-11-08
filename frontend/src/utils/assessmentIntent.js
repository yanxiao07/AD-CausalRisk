/**
 * 评估幂等键的持久化（按受试者）。
 *
 * 为什么需要持久化：
 *   抽屉关闭会销毁组件，内存里的 key 随之丢失。如果上一次 POST 其实已经在后端
 *   建好了任务、只是响应没回来（超时/断网/用户直接关了抽屉），重新打开后再提交
 *   就会生成新键 → 后端认为是一次全新请求 → 重复建任务。
 *
 * 存 sessionStorage 而不是 localStorage：
 *   - 关抽屉、刷新页面都还在（这正是要扛的场景）；
 *   - 关掉标签页就消失，不会让"昨天那次评估"的键干扰今天。
 *
 * 条目结构：{ key, windowMonths, savedAt }
 *   windowMonths 与 key 绑定：换窗口 = 换意图，调用方据此决定是复用还是换新键。
 */
const PREFIX = 'adc:assessment-key:'
const MAX_AGE_MS = 24 * 60 * 60 * 1000

function safeStorage() {
  try {
    return window.sessionStorage
  } catch {
    return null
  }
}

/** 读取某个受试者尚未完成的评估意图；过期或不合法返回 null。 */
export function readAssessmentIntent(subjectId) {
  if (subjectId === null || subjectId === undefined) return null
  const store = safeStorage()
  if (!store) return null
  try {
    const raw = store.getItem(PREFIX + subjectId)
    if (!raw) return null
    const entry = JSON.parse(raw)
    if (!entry?.key) return null
    if (Date.now() - Number(entry.savedAt || 0) > MAX_AGE_MS) {
      store.removeItem(PREFIX + subjectId)
      return null
    }
    return {
      key: entry.key,
      windowMonths: Number.isFinite(entry.windowMonths) ? entry.windowMonths : null
    }
  } catch {
    return null
  }
}

/** 记下"这一次意图"：必须在发请求之前调用，否则响应丢失就再也找不回键。 */
export function saveAssessmentIntent(subjectId, { key, windowMonths }) {
  const store = safeStorage()
  if (!store || !key) return
  try {
    store.setItem(
      PREFIX + subjectId,
      JSON.stringify({ key, windowMonths, savedAt: Date.now() })
    )
  } catch {
    // 隐私模式/配额满时静默降级：退化成"内存内幂等"，不阻断主流程
  }
}

/** 意图已闭环（出结果 / 明确失败后换新键 / 用户主动新建）时清除。 */
export function clearAssessmentIntent(subjectId) {
  const store = safeStorage()
  if (!store) return
  try {
    store.removeItem(PREFIX + subjectId)
  } catch {
    // ignore
  }
}

/** 退出登录时清空所有档案的残留键，避免下一个人继承上一个人的意图。 */
export function clearAllAssessmentIntents() {
  const store = safeStorage()
  if (!store) return
  try {
    const keys = []
    for (let i = 0; i < store.length; i += 1) {
      const key = store.key(i)
      if (key && key.startsWith(PREFIX)) keys.push(key)
    }
    keys.forEach((key) => store.removeItem(key))
  } catch {
    // ignore
  }
}
