import request from './request.js'

/** 契约里 AssessmentCreate.modelVersion 是常量。 */
export const MODEL_VERSION = 'deepagent-causal-warning-v1'
export const WINDOW_MIN = 6
export const WINDOW_MAX = 60
export const WINDOW_DEFAULT = 24

/**
 * POST /api/assessment-tasks
 * 每次点击都生成新的 Idempotency-Key：同一秒内重复点击会被后端按"同 key"重放，
 * 但用户明确重新发起时应该是一次新任务。
 */
export function createAssessmentTask({ subjectId, predictionWindowMonths = WINDOW_DEFAULT }) {
  return request.post(
    '/assessment-tasks',
    {
      subjectId,
      modelVersion: MODEL_VERSION,
      predictionWindowMonths
    },
    { headers: { 'Idempotency-Key': crypto.randomUUID() } }
  )
}

/** GET /api/assessment-tasks/{taskId} —— 轮询用；返回 AssessmentTask。 */
export function getAssessmentTask(taskId) {
  return request.get(`/assessment-tasks/${taskId}`)
}

/** GET /api/assessments/{assessmentId} —— 返回 Assessment（含 agentResult）。 */
export function getAssessment(assessmentId) {
  return request.get(`/assessments/${assessmentId}`)
}

/** POST /api/assessments/{assessmentId}/review —— comment 必填（≤2000 字）。 */
export function reviewAssessment(assessmentId, decision, comment) {
  return request.post(`/assessments/${assessmentId}/review`, { decision, comment })
}
