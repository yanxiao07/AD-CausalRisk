import request from './request.js'

/**
 * POST /api/import-jobs（multipart/form-data）
 * 请求头必须带 Idempotency-Key（1..100 字符，UUID 即可）。
 * 返回 ImportJob：{ jobId, status, dataVersion, totalRows, validRows, invalidRows, errors[] }
 *
 * 注意：不要手动设置 Content-Type，让浏览器带上 multipart boundary。
 */
export function createImportJob(file, idempotencyKey) {
  const form = new FormData()
  form.append('file', file)
  return request.post('/import-jobs', form, {
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/** POST /api/import-jobs/{jobId}/commit —— 只有 PREVIEW_READY 能入库，重复提交后端返回 400。 */
export function commitImportJob(jobId) {
  return request.post(`/import-jobs/${jobId}/commit`)
}

/**
 * GET /api/import-jobs —— 历史导入任务（分页）。
 * 返回 { items, page, pageSize, total }；非 ADMIN 只看到自己创建的任务。
 * 每行是列表摘要：{ jobId, status, dataVersion, totalRows, validRows, invalidRows,
 *                  createdAt, createdBy }
 */
export function listImportJobs({
  page = 1,
  pageSize = 10,
  status = '',
  keyword = '',
  from = '',
  to = '',
  sort = ''
} = {}) {
  const params = { page, pageSize }
  if (status) params.status = status
  if (keyword) params.keyword = keyword
  if (from) params.from = from
  if (to) params.to = to
  if (sort) params.sort = sort
  return request.get('/import-jobs', { params })
}

/** GET /api/import-jobs/{jobId} —— 单个任务详情（含 errors[]）。 */
export function getImportJob(jobId) {
  return request.get(`/import-jobs/${jobId}`)
}

/**
 * GET /api/import-jobs/{jobId}/errors.csv —— 下载错误明细 CSV。
 *
 * 和报告下载一样走 fetch：响应体是文件而不是 Envelope，
 * 交给 request.js 会被当成 JSON 解析失败。
 */
export async function downloadImportErrors(jobId) {
  const response = await fetch(`/api/import-jobs/${jobId}/errors.csv`, {
    credentials: 'include'
  })

  if (!response.ok) {
    let message =
      response.status === 401
        ? '登录已过期，请重新登录后再导出'
        : response.status === 403
          ? '当前账号无权导出该任务的错误明细'
          : '错误明细导出失败，请稍后重试'
    try {
      const body = await response.json()
      if (body?.message) message = body.message
    } catch {
      // 非 JSON 错误体：保留默认文案
    }
    const problem = new Error(message)
    problem.code = response.status
    throw problem
  }

  const blob = await response.blob()
  const disposition = response.headers.get('Content-Disposition') || ''
  const match = /filename="?([^";]+)"?/i.exec(disposition)
  const filename = match?.[1] || `import-${jobId}-errors.csv`

  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  return filename
}
