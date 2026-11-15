import request from './request.js'
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

export function submitResearchTableTask({ snapshotId, idempotencyKey }) {
  return request.post(
    '/research/table-assessment-tasks',
    { snapshotId: Number(snapshotId) },
    {
      headers: { 'Idempotency-Key': idempotencyKey || newIdempotencyKey() },
      silent: true
    }
  )
}

export function getResearchTableTask(taskId) {
  return request.get(`/research/table-assessment-tasks/${taskId}`, { silent: true })
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
