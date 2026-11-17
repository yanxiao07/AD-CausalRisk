import request from './request.js'

/**
 * GET /api/quality-checks —— 输入质量检查记录（quality_check 表）。
 *
 * 参数沿用后端 BusinessQueries 的 Map 过滤：page / pageSize / status / keyword /
 * from / to / subjectId / sort。status 取值 PASS | WARN | BLOCKED。
 * 返回 PageResult：{ items, page, pageSize, total }，行键为 camelCase
 * （qualityStatus、issues(JSON 字符串)、checkedAt、ruleVersion、dataVersion、
 *  subjectCode、subjectAlias）。
 *
 * 注意：这是"系统对输入数据的自动检查结果"，与 /api/qc/list 的人工复核单
 * （PENDING/APPROVED/REJECTED）不是同一张表。
 */
export function listQualityChecks({
  status,
  page = 1,
  pageSize = 20,
  keyword,
  subjectId,
  from,
  to,
  sort
} = {}) {
  const params = { page, pageSize }
  if (status) params.status = status
  if (keyword) params.keyword = keyword
  const sid = Number(subjectId)
  if (Number.isInteger(sid) && sid > 0) params.subjectId = sid
  if (from) params.from = from
  if (to) params.to = to
  if (sort) params.sort = sort
  return request.get('/quality-checks', { params })
}

/**
 * 质控审核（非契约扩展端点，/api/qc/*）—— 人工复核单，保留给旧入口。
 *
 * GET  /api/qc/list?subjectCode&subjectAlias&status&page&pageSize
 *      -> PageResult<QcListView>
 * GET  /api/qc/stats -> { pendingCount, approvedCount, rejectedCount }
 * POST /api/qc/{id}/audit  body { status: APPROVED|REJECTED, abnormalItems?, auditComment? }
 *      仅 ADMIN / RESEARCHER；非 PENDING 的记录会返回 400。
 */
export function listQcRecords({ status, page = 1, pageSize = 20 } = {}) {
  const params = { page, pageSize }
  if (status) params.status = status
  return request.get('/qc/list', { params })
}

/** GET /api/qc/stats —— 旧质控计数（大屏已改用 /dashboard/summary）。 */
export function getQcStats() {
  return request.get('/qc/stats')
}

export function submitQcAudit(id, { status, auditComment, abnormalItems }) {
  const body = { status }
  if (auditComment) body.auditComment = auditComment
  if (abnormalItems) body.abnormalItems = abnormalItems
  return request.post(`/qc/${id}/audit`, body)
}
