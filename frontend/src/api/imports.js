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
