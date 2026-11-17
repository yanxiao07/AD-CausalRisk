import request from './request.js'

/**
 * GET /api/dashboard/summary —— 管理员大屏的服务端汇总。
 *
 * 返回：{ subjects, pendingReview, highRisk, todayFollowups, overdueFollowups,
 *        followupPreviewLimit, subjectStates[{status,total}],
 *        trend[{date,total}], followups[] }
 * 权限：非 PATIENT；按当前账号的数据范围统计。
 */
export function getDashboardSummary() {
  return request.get('/dashboard/summary')
}
