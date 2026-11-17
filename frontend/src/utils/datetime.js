const NAIVE_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?$/
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/

/**
 * Parse timestamps returned by the Java API.
 *
 * Paged business queries historically serialized MySQL DATETIME as a naive
 * "2026-11-15T12:20:25" string. Those values are UTC (the JDBC URL sets
 * connectionTimeZone=UTC), so attach Z before handing them to Date. Otherwise
 * the browser interprets them as local time and China users see times eight
 * hours early.
 */
export function parseInstant(value) {
  if (!value) return null
  let text = String(value).trim()
  if (!text) return null
  if (NAIVE_INSTANT.test(text)) text += 'Z'
  const date = new Date(text)
  return Number.isNaN(date.getTime()) ? null : date
}

export function formatDateTime(value, options = {}) {
  const date = parseInstant(value)
  if (!date) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    ...options
  }).format(date)
}

export function formatDate(value) {
  if (!value) return '—'
  if (DATE_ONLY.test(String(value))) {
    const [year, month, day] = String(value).split('-')
    return `${year}/${month}/${day}`
  }
  return formatDateTime(value, { hour: undefined, minute: undefined })
}

export function formatRelativeDay(value) {
  const date = parseInstant(value)
  if (!date) return ''
  const now = new Date()
  const dayMs = 24 * 60 * 60 * 1000
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const target = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
  const diff = Math.round((target - start) / dayMs)
  if (diff === 0) return '今天'
  if (diff === 1) return '明天'
  if (diff === -1) return '昨天'
  return ''
}
