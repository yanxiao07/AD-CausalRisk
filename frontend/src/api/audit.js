import request from './request.js'

/**
 * GET /api/audit-logs —— 仅 ADMIN。
 * 契约里 data 是数组（无分页），元素为 AuditEvent：
 * { id, actorId|null, eventType, traceId, summary, timestamp }
 */
export function listAuditLogs() {
  return request.get('/audit-logs')
}
