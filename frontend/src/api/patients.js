import request from './request.js'

/**
 * GET /api/patients/me/research-summary —— 研究验证结果的定性、脱敏摘要。
 *
 * 只返回状态与通俗结论；不含 CIF、模型权重、因果图或原始 resultJson。
 */
export function getResearchSummary() {
  return request.get('/patients/me/research-summary')
}

/**
 * GET /api/patients/me/doctor —— 绑定的主治医生（name/department/phone/officeHours），
 * 未绑定或没有对应医生时返回 null。
 */
export function getMyDoctor() {
  return request.get('/patients/me/doctor')
}

/**
 * GET /api/patients/me/follow-up —— { bound, next: { scheduledAt, purpose } | null, overdueCount }
 * 比摘要里的 followUpSummary 更精确（能显示逾期条数）。
 */
export function getMyFollowUp() {
  return request.get('/patients/me/follow-up')
}

export function respondToFollowUp(id, appointmentRevision, action, note) {
  return request.post(`/patients/me/follow-up/${id}/response`, { appointmentRevision, action, note }, { silent: true })
}
