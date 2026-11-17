import request from './request.js'

/**
 * GET /api/patients/me/summary —— 患者本人的脱敏摘要。
 *
 * 只返回通俗结论、复诊安排与局限声明；不含 riskScore、因果图或解释贡献。
 * 任何非 PATIENT 角色调用都会得到 403。
 */
export function getPatientSummary() {
  return request.get('/patients/me/summary')
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
