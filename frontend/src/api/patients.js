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
