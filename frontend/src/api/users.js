import request from './request.js'

/**
 * 管理员账号管理（UserManagementController）。
 *
 * GET /api/users?... 返回 { items, page, pageSize, total }，每个 item：
 * { id, username, status, dataScope, createdAt, displayName, department, phone, officeHours, roles[] }
 * 注意：后端没有 last_login_at，页面上不能编造"最近登录"。
 */
export function listUsers({
  page = 1,
  pageSize = 20,
  status = '',
  keyword = '',
  from = '',
  to = '',
  sort = ''
} = {}) {
  const params = { page, pageSize }
  if (status) params.status = status
  if (keyword) params.keyword = keyword
  if (from) params.from = from
  if (to) params.to = to
  if (sort) params.sort = sort
  return request.get('/users', { params })
}

/** POST /api/users —— 必填 username / password / roles；其余为可选资料字段。 */
export function createUser(body) {
  return request.post('/users', body)
}

/** PATCH /api/users/{id} —— username 不可修改；password 留空表示不改。 */
export function updateUser(userId, body) {
  return request.patch(`/users/${userId}`, body)
}

/** GET /api/subjects/{id}/permissions —— 某档案下的授权行（含 username）。 */
export function listSubjectPermissions(subjectId) {
  return request.get(`/subjects/${subjectId}/permissions`)
}

/** POST /api/subjects/{id}/permissions —— permissionType ∈ VIEW / MANAGE / SUMMARY_READ。 */
export function grantSubjectPermission(subjectId, { userId, permissionType }) {
  return request.post(`/subjects/${subjectId}/permissions`, { userId, permissionType })
}

/** DELETE /api/subjects/{id}/permissions/{permissionId} —— 路径变量是授权行 id，不是 userId。 */
export function revokeSubjectPermission(subjectId, permissionId) {
  return request.delete(`/subjects/${subjectId}/permissions/${permissionId}`)
}
