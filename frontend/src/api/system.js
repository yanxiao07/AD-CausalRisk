import request from './request.js'

/**
 * GET /api/system/info —— 运行时版本 / Python 健康 / 模型开关（仅 ADMIN）。
 */
export function getSystemInfo() {
  return request.get('/system/info')
}

/**
 * GET /api/me/preferences —— 当前账号的界面偏好。
 * 默认：{ density: 'comfortable', fontSize: 16, notifications: true }
 */
export function getMyPreferences() {
  return request.get('/me/preferences')
}

/**
 * PATCH /api/me/preferences —— 只传要改的键，后端按现有值合并后保存。
 */
export function saveMyPreferences(patch) {
  return request.patch('/me/preferences', patch)
}

/**
 * PATCH /api/system/models/{version} —— 启用 / 停用某个模型版本（仅 ADMIN）。
 * body：{ enabled: boolean }；返回 { saved: true }。
 */
export function updateModelConfig(version, enabled) {
  return request.patch(`/system/models/${encodeURIComponent(version)}`, { enabled })
}

/**
 * GET /api/system/research-host —— 研究宿主地址、超时、启用状态（Token 不回传）。
 */
export function getResearchHost() {
  return request.get('/system/research-host')
}

/**
 * PUT /api/system/research-host —— 更新研究宿主；bearerToken 留空表示保留原 Token。
 */
export function saveResearchHost(payload) {
  return request.put('/system/research-host', payload)
}

/**
 * GET /api/system/assessment-policy —— 旧规则评估通道（test-rule-enabled）当前状态。
 */
export function getAssessmentPolicy() {
  return request.get('/system/assessment-policy')
}

/**
 * PUT /api/system/assessment-policy —— 开启 / 关闭旧规则评估通道。
 */
export function saveAssessmentPolicy(testRuleEnabled) {
  return request.put('/system/assessment-policy', { testRuleEnabled })
}

/**
 * GET /api/system/retention —— 审计日志保留天数。
 */
export function getRetentionPolicy() {
  return request.get('/system/retention')
}

/**
 * PUT /api/system/retention —— 修改审计日志保留天数（30–3650）。
 */
export function saveRetentionPolicy(auditLogDays) {
  return request.put('/system/retention', { auditLogDays })
}
