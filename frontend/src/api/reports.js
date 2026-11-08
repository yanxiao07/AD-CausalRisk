import request from './request.js'

/**
 * GET /api/reports/{assessmentId}
 * 契约里的响应体就是 Assessment（与 GET /api/assessments/{id} 同构），
 * 权限也复用同一套 canReadRawScores 判定。
 */
export function getReport(assessmentId) {
  return request.get(`/reports/${assessmentId}`)
}
