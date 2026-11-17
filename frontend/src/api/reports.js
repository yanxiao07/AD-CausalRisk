import request from './request.js'

/**
 * GET /api/reports/{assessmentId}
 * 契约里的响应体就是 Assessment（与 GET /api/assessments/{id} 同构），
 * 权限也复用同一套 canReadRawScores 判定。
 */
export function getReport(assessmentId) {
  return request.get(`/reports/${assessmentId}`)
}

/**
 * GET /api/reports/{assessmentId}/download?format=pdf|html|json&audience=research|doctor
 *
 * 返回真实文件（PDF 由后端 PDFBox + ReportSans.ttf 生成），响应头带
 * Content-Disposition: attachment。这里用 fetch 拿二进制，避免 request.js 的
 * Envelope 解析把 PDF 当 JSON。
 */
export async function downloadReport(assessmentId, { format = 'pdf', audience = 'research' } = {}) {
  const response = await fetch(
    `/api/reports/${assessmentId}/download?format=${encodeURIComponent(format)}&audience=${encodeURIComponent(audience)}`,
    { credentials: 'include' }
  )

  if (!response.ok) {
    let message =
      response.status === 401
        ? '登录已过期，请重新登录后再导出'
        : response.status === 403
          ? '当前账号无权导出该报告'
          : '报告导出失败，请稍后重试'
    try {
      const body = await response.json()
      if (body?.message) message = body.message
    } catch {
      // 非 JSON 错误体：保留默认文案
    }
    const error = new Error(message)
    error.code = response.status
    throw error
  }

  const blob = await response.blob()
  const disposition = response.headers.get('Content-Disposition') || ''
  const match = /filename="?([^";]+)"?/i.exec(disposition)
  const filename = match?.[1] || `assessment-${assessmentId}.${format}`

  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  // 立即 revoke 会让部分浏览器（Safari）取消下载，延迟释放
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  return filename
}
