import request from './request.js'

/**
 * 质控审核（非契约扩展端点，/api/qc/*）
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

/** GET /api/qc/stats —— 管理员大屏的质控计数。 */
export function getQcStats() {
  return request.get('/qc/stats')
}

export function submitQcAudit(id, { status, auditComment, abnormalItems }) {
  const body = { status }
  if (auditComment) body.auditComment = auditComment
  if (abnormalItems) body.abnormalItems = abnormalItems
  return request.post(`/qc/${id}/audit`, body)
}
