import request from './request.js'

/** 契约里 AssessmentCreate.modelVersion 是常量。 */
export const MODEL_VERSION = 'deepagent-causal-warning-v1'
export const WINDOW_MIN = 6
export const WINDOW_MAX = 60
export const WINDOW_DEFAULT = 24

/**
 * 生成一次幂等键。
 *
 * 幂等键的语义是"这一次业务意图"，不是"这一次网络请求"：
 *  - 用户点一次「发起风险预测」→ 生成一次；
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
 * POST /api/assessment-tasks
 *
 * @param {object}  options
 * @param {number}  options.subjectId
 * @param {number} [options.predictionWindowMonths]
 * @param {string} [options.idempotencyKey] 复用同一键 = 同一次意图；缺省时新建一个。
 */
export function createAssessmentTask({
  subjectId,
  predictionWindowMonths = WINDOW_DEFAULT,
  idempotencyKey
}) {
  return request.post(
    '/assessment-tasks',
    {
      subjectId,
      modelVersion: MODEL_VERSION,
      predictionWindowMonths
    },
    { headers: { 'Idempotency-Key': idempotencyKey || newIdempotencyKey() } }
  )
}

/** GET /api/assessment-tasks/{taskId} —— 轮询用；返回 AssessmentTask。 */
export function getAssessmentTask(taskId) {
  return request.get(`/assessment-tasks/${taskId}`)
}

/**
 * GET /api/assessment-tasks —— 任务历史（分页，按 id 倒序）。
 * 每行是摘要（不含 stages），详情仍需 getAssessmentTask(taskId)。
 */
export function listAssessmentTasks({ page = 1, pageSize = 20 } = {}) {
  return request.get('/assessment-tasks', { params: { page, pageSize } })
}

/**
 * GET /api/assessments —— 评估结果摘要（分页，按 id 倒序）。
 * 响应不含 agentResult：列表只需要 riskScore / riskLevel / reviewStatus 等标量。
 */
export function listAssessments({ page = 1, pageSize = 20 } = {}) {
  return request.get('/assessments', { params: { page, pageSize } })
}

/** GET /api/assessments/{assessmentId} —— 返回 Assessment（含 agentResult）。 */
export function getAssessment(assessmentId) {
  return request.get(`/assessments/${assessmentId}`)
}

/** POST /api/assessments/{assessmentId}/review —— comment 必填（≤2000 字）。 */
export function reviewAssessment(assessmentId, decision, comment) {
  return request.post(`/assessments/${assessmentId}/review`, { decision, comment })
}
