import request from './request.js'

/**
 * 生成一次幂等键。
 *
 * 幂等键的语义是"这一次业务意图"，不是"这一次网络请求"：
 *  - 用户点一次「提交研究评估」→ 生成一次；
 *  - 超时重试、点「重新发起」→ 必须复用同一个键，后端才不会重复建任务；
 *  - 成功出结果（或换受试者）后才换新键，表示下一次是全新的评估。
 * 所以调用方应当显式传入并自己持有这个值，而不是让这里每次随机生成。
 */
export function newIdempotencyKey() {
  return crypto?.randomUUID
    ? crypto.randomUUID()
    : `idem-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

/**
 * GET /api/assessment-tasks/{taskId} —— 轮询用；返回 AssessmentTask。
 * 旧规则通道（POST /api/assessment-tasks 与 /submit）已退役，前端不再提供创建入口。
 */
export function getAssessmentTask(taskId) {
  return request.get(`/assessment-tasks/${taskId}`)
}

/**
 * GET /api/assessment-tasks/{id}/input —— 任务输入快照。
 * 返回 { available, inputDigest, dataVersion, capturedAt, request, qualityCheckId, provenance }；
 * 历史任务没有快照时 available=false。request 里包含创建评估时的 predictionWindowMonths。
 */
export function getAssessmentTaskInput(taskId) {
  return request.get(`/assessment-tasks/${taskId}/input`)
}

/** POST /api/assessment-tasks/{taskId}/cancel —— 仅 PENDING 任务可取消。 */
export function cancelAssessmentTask(taskId) {
  return request.post(`/assessment-tasks/${taskId}/cancel`)
}

/**
 * GET /api/assessment-tasks —— 任务历史（分页，按 id 倒序）。
 * 每行是摘要（不含 stages），详情仍需 getAssessmentTask(taskId)。
 */
export function listAssessmentTasks({
  page = 1,
  pageSize = 20,
  status = '',
  subjectId = '',
  keyword = '',
  from = '',
  to = '',
  sort = ''
} = {}) {
  const params = { page, pageSize }
  if (status) params.status = status
  const sid = Number(subjectId)
  if (Number.isInteger(sid) && sid > 0) params.subjectId = sid
  if (keyword) params.keyword = keyword
  if (from) params.from = from
  if (to) params.to = to
  if (sort) params.sort = sort
  return request.get('/assessment-tasks', { params })
}

/**
 * GET /api/assessments —— 评估结果摘要（分页，按 id 倒序）。
 * 响应不含 agentResult：列表只需要 riskScore / riskLevel / reviewStatus 等标量。
 */
export function listAssessments({
  page = 1,
  pageSize = 20,
  reviewStatus = '',
  riskLevel = '',
  keyword = '',
  subjectId = '',
  from = '',
  to = '',
  sort = ''
} = {}) {
  const params = { page, pageSize }
  if (reviewStatus) params.reviewStatus = reviewStatus
  if (riskLevel) params.riskLevel = riskLevel
  if (keyword) params.keyword = keyword
  const sid = Number(subjectId)
  if (Number.isInteger(sid) && sid > 0) params.subjectId = sid
  if (from) params.from = from
  if (to) params.to = to
  if (sort) params.sort = sort
  return request.get('/assessments', { params })
}

/** GET /api/assessments/{assessmentId} —— 返回 Assessment（含 agentResult）。 */
export function getAssessment(assessmentId) {
  return request.get(`/assessments/${assessmentId}`)
}

/**
 * GET /api/assessments/{assessmentId}/reviews —— 复核历史（最新在前）。
 * 每行：{ id, assessmentId, reviewerId, decision, comment, reviewedAt, reviewerName }
 */
export function getAssessmentReviews(assessmentId) {
  return request.get(`/assessments/${assessmentId}/reviews`)
}

/** POST /api/assessments/{assessmentId}/review —— comment 必填（≤2000 字）。 */
export function reviewAssessment(assessmentId, decision, comment) {
  return request.post(`/assessments/${assessmentId}/review`, { decision, comment })
}
