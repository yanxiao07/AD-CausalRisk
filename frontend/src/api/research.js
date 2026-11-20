import request from './request.js'

export const getPatientPublication = id => request.get(`/research/table-assessment-tasks/${id}/patient-publication`)
export const publishPatientMessage = (id, message) => request.put(`/research/table-assessment-tasks/${id}/patient-publication`, { message })
export const withdrawPatientMessage = id => request.delete(`/research/table-assessment-tasks/${id}/patient-publication`)
export function importModelCsv(file, preview) {
  const body = new FormData()
  body.append('file', file)
  return request.post('/research/table-assessment-tasks/snapshots/batch-csv', body, { params: { preview } })
}
import { newIdempotencyKey } from './assessments.js'

/**
 * 研究证据目录（ResearchEvidenceController）。
 *
 * GET /api/research/evidence → { evidenceVersion, lookupConfigured, models[], reports[],
 *   predictionAdmission, monitoringAdmission, limitations[] }
 * GET /api/research/evidence/{key} → 单份报告的元信息 + markdown 原文
 */
export function getResearchEvidence() {
  return request.get('/research/evidence')
}

export function getResearchReport(key) {
  return request.get(`/research/evidence/${key}`)
}

/**
 * 研究表格评估任务（ResearchTableAssessmentController，V13 新增）。
 *
 * GET /api/research/table-assessment-tasks/snapshots?subjectId={id}
 *   → [{ snapshotId, taskId }]
 *   只返回当前用户获准评估、已登记为 CONSTRUCTED_VALIDATION + trained-seven-logistic
 *   的冻结快照；taskId 非空说明该快照已有任务，可直接回读，避免换幂等键重复提交。
 *
 * POST /api/research/table-assessment-tasks
 *   Header: Idempotency-Key（必填，1–100 字符）
 *   Body:   { snapshotId }（正整数）
 *   返回 ResearchTableTask：{ taskId, snapshotId, subjectId, runId, status, errorCode,
 *   result, createdAt, updatedAt }；status ∈ COMPLETED / BLOCKED / FAILED / OUTCOME_UNKNOWN。
 *
 * GET /api/research/table-assessment-tasks/{id} —— 读取已持久化的研究任务与结果。
 *
 * 旧规则通道 POST /api/assessment-tasks 与 /submit 已退役（默认 503），页面不再调用。
 */
export function listResearchSnapshots(subjectId) {
  return request.get('/research/table-assessment-tasks/snapshots', {
    params: { subjectId: Number(subjectId) },
    // 快照接口的 403/404/503 由页面翻译成具体提示，不再弹全局 toast
    silent: true
  })
}

export function registerResearchSnapshot({ subjectId, indexDate, features, model }) {
  const body = {
    subjectId: Number(subjectId),
    indexDate,
    features
  }
  if (model) body.model = model
  return request.post('/research/table-assessment-tasks/snapshots/register', body, { silent: true })
}

export function retryResearchSnapshotSync(snapshotId) {
  return request.post(
    `/research/table-assessment-tasks/snapshots/${Number(snapshotId)}/sync`,
    null,
    { silent: true }
  )
}

export function reRegisterResearchSnapshot(snapshotId) {
  return request.post(
    `/research/table-assessment-tasks/snapshots/${Number(snapshotId)}/re-register`,
    null,
    { silent: true }
  )
}

export function getResearchIntakeState(subjectId) {
  return request.get('/research/table-assessment-tasks/intake-state', {
    params: { subjectId: Number(subjectId) }, silent: true
  })
}

export function submitResearchTableTask({ subjectId, snapshotId, idempotencyKey }) {
  return request.post(
    '/research/table-assessment-tasks',
    { snapshotId: Number(snapshotId), ...(subjectId == null ? {} : { subjectId: Number(subjectId) }) },
    {
      headers: { 'Idempotency-Key': idempotencyKey || newIdempotencyKey() },
      silent: true
    }
  )
}

export function getResearchTableTask(taskId) {
  return request.get(`/research/table-assessment-tasks/${taskId}`, { silent: true })
}

/** GET /api/research/table-assessment-tasks/reports/{id} —— 报告详情，复用报告的档案授权规则。 */
export function getResearchReportTask(taskId) {
  return request.get(`/research/table-assessment-tasks/reports/${Number(taskId)}`, { silent: true })
}

/**
 * Fetches a report through the authenticated Java session instead of letting the browser
 * open the protected URL directly. The server re-authorizes every export and audits it.
 */
export async function downloadResearchReport(taskId, format) {
  const response = await fetch(
    `/api/research/table-assessment-tasks/${Number(taskId)}/export?format=${encodeURIComponent(format)}`,
    { credentials: 'include', headers: { Accept: '*/*' } }
  )
  if (!response.ok) {
    let message = '报告导出失败，请稍后重试'
    try {
      const payload = await response.json()
      message = payload?.message || message
    } catch {
      /* non-JSON error body */
    }
    const problem = new Error(message)
    problem.httpStatus = response.status
    throw problem
  }
  const disposition = response.headers.get('Content-Disposition') || ''
  const match = /filename="?([^";]+)"?/i.exec(disposition)
  return {
    blob: await response.blob(),
    filename: match?.[1] || `research-task-${taskId}.${format}`
  }
}

/**
 * GET /api/research/table-assessment-tasks/history?subjectId={id}
 * 最近 50 条研究任务（持久化）；刷新页面后仍可重建历史。
 */
export function listResearchTaskHistory(subjectId) {
  return request.get('/research/table-assessment-tasks/history', {
    params: { subjectId: Number(subjectId) },
    silent: true
  })
}

/** POST /api/research/table-assessment-tasks/{id}/retry —— 仅 FAILED / OUTCOME_UNKNOWN。 */
export function retryResearchTableTask(taskId) {
  return request.post(`/research/table-assessment-tasks/${Number(taskId)}/retry`, null, {
    silent: true
  })
}

/**
 * GET /api/research/table-assessment-tasks/reviews?status&page&pageSize
 * 医生研究模型复核队列；仅 COMPLETED 任务，按档案权限过滤。
 */
export function listResearchReviews({ status = 'PENDING', page = 1, pageSize = 20, dataScope } = {}) {
  return request.get('/research/table-assessment-tasks/reviews', {
    params: { status, page, pageSize, dataScope },
    silent: true
  })
}

/** POST /api/research/table-assessment-tasks/{id}/review —— 医生确认研究模型结果。 */
export function reviewResearchTask(taskId, { decision, comment }) {
  return request.post(
    `/research/table-assessment-tasks/${Number(taskId)}/review`,
    { decision, comment },
    { silent: true }
  )
}

/**
 * GET /api/research/table-assessment-tasks/reports?status&page&pageSize
 * 统一研究验证报告列表：医生按档案授权、研究员按本人任务。
 */
export function listResearchReports({ status = 'ALL', page = 1, pageSize = 20, dataScope, subjectId } = {}) {
  return request.get('/research/table-assessment-tasks/reports', {
    params: { status, page, pageSize, dataScope, subjectId },
    silent: true
  })
}

/** GET /api/research/table-assessment-tasks/summary —— 当前研究员今日任务吞吐。 */
export function getResearchTaskSummary(dataScope) {
  return request.get('/research/table-assessment-tasks/summary', { params: { dataScope }, silent: true })
}

/** GET /api/research/table-assessment-tasks/metrics — 研究验证指标，与旧规则评估分开统计。 */
export function getResearchMetrics(dataScope) {
  return request.get('/research/table-assessment-tasks/metrics', { params: { dataScope }, silent: true })
}

/**
 * POST /api/research/table-assessment-tasks/snapshots/batch
 * 按受试者最新一次访视批量生成七变量快照；缺失变量由后端标记 NOT_COLLECTED。
 * Body: { subjectIds: number[], evaluate?: boolean }
 */
export function batchResearchSnapshots({ subjectIds, evaluate = false }) {
  return request.post(
    '/research/table-assessment-tasks/snapshots/batch',
    { subjectIds: subjectIds.map(Number), evaluate: Boolean(evaluate) },
    { silent: true }
  )
}
