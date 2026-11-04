import request from './request.js'

/**
 * GET /api/subjects/{subjectId}/follow-ups
 * 返回 FollowUpResult[]：{ id, subjectId, scheduledAt, purpose, status }
 * status ∈ PLANNED / COMPLETED / CANCELLED。
 */
export function listFollowUps(subjectId) {
  return request.get(`/subjects/${subjectId}/follow-ups`)
}

/**
 * POST /api/follow-ups
 * body：{ subjectId, scheduledAt(ISO Instant), purpose }；status 可选，默认 PLANNED。
 */
export function createFollowUp({ subjectId, scheduledAt, purpose }) {
  return request.post('/follow-ups', {
    subjectId: Number(subjectId),
    scheduledAt,
    purpose
  })
}

/**
 * PATCH /api/follow-ups/{followUpId}
 * 只传要改的字段：{ status } 用于标记完成/取消，{ scheduledAt, purpose } 用于改期。
 * 后端语义：CANCELLED 之后不可再变更；COMPLETED 不能退回 PLANNED。
 */
export function updateFollowUp(followUpId, patch) {
  return request.patch(`/follow-ups/${followUpId}`, patch)
}
