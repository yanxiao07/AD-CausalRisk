import request from './request.js'

/**
 * GET /api/audit-logs —— 仅 ADMIN。
 * 契约里 data 是数组（无分页），元素为 AuditEvent：
 * { id, actorId|null, eventType, traceId, summary, timestamp }
 */
export function listAuditLogs() {
  return request.get('/audit-logs')
}

/**
 * GET /api/audit-logs/page —— 服务端分页与筛选（BusinessQueryController）。
 * 过滤参数：eventType / actorId / traceId / keyword / from / to；data 为 { items, page, pageSize, total }。
 * 注意这是新增的分页端点，老的 /audit-logs 仍返回数组。
 */
export function listAuditLogsPaged({
  page = 1,
  pageSize = 20,
  eventType = '',
  actorId = '',
  traceId = '',
  keyword = '',
  from = '',
  to = '',
  sort = ''
} = {}) {
  const params = { page, pageSize }
  if (eventType) params.eventType = eventType
  if (actorId) params.actorId = actorId
  if (traceId) params.traceId = traceId
  if (keyword) params.keyword = keyword
  if (from) params.from = from
  if (to) params.to = to
  if (sort) params.sort = sort
  return request.get('/audit-logs/page', { params })
}
