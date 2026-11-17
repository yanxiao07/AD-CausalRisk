const PREF_KEY = 'adc-causalrisk:preferences'

/** 把偏好应用到根元素（字号变量 + 密度/通知的 data 属性），并缓存到本机。 */
export function applyPreferences(prefs = {}) {
  const root = document.documentElement
  const fontSize = Number(prefs.fontSize) || 16
  root.style.setProperty('--user-font-size', `${fontSize}px`)
  root.dataset.density = prefs.density === 'compact' ? 'compact' : 'comfortable'
  root.dataset.notifications = prefs.notifications === false ? 'off' : 'on'
  try {
    window.localStorage.setItem(PREF_KEY, JSON.stringify(prefs))
  } catch {
    // 隐私模式忽略
  }
}

/** 读取本机缓存的偏好（用于首屏立即生效，避免闪烁）。 */
export function readLocalPreferences() {
  try {
    const raw = window.localStorage.getItem(PREF_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

export function defaultPreferences() {
  return { fontSize: 16, density: 'comfortable', notifications: true }
}
