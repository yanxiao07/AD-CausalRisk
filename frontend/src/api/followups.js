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
 * GET /api/follow-ups —— 跨受试者的服务端分页列表（BusinessQueryController）。
 * 过滤：subjectId / status / due(overdue|today|next7) / from / to / page / pageSize。
 * 返回 { items, page, pageSize, total }，item 含 subjectCode / subjectAlias / ownerName。
 * 注意：PATIENT 调用会被拒绝（患者走 /api/patients/me/follow-up）。
 */
export function listFollowUpsPaged({
  page = 1,
  pageSize = 20,
  due = '',
  status = '',
  subjectId = '',
  keyword = '',
  from = '',
  to = '',
  sort = ''
} = {}) {
  const params = { page, pageSize }
  if (due) params.due = due
  if (status) params.status = status
  const sid = Number(subjectId)
  if (Number.isInteger(sid) && sid > 0) params.subjectId = sid
  if (keyword) params.keyword = keyword
  if (from) params.from = from
  if (to) params.to = to
  if (sort) params.sort = sort
  return request.get('/follow-ups', { params })
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
