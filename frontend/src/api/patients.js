import request from './request.js'

export function getResearchSummary() {
  return request.get('/patients/me/research-summary')
}

export function getMyDoctor() {
  return request.get('/patients/me/doctor')
}

export function getMyFollowUp() {
  return request.get('/patients/me/follow-up')
}

export function respondToFollowUp(id, appointmentRevision, action, note) {
  return request.post(`/patients/me/follow-up/${id}/response`, { appointmentRevision, action, note }, { silent: true })
}

/**
 * POST /api/patients/me/follow-up/{id}/cancel —— 患者自己取消本次复诊。
 *
 * 必须带上 appointmentRevision：医生改过安排后旧版本会被拒绝，避免取消掉一个已经作废的时间。
 */
export function cancelFollowUp(id, appointmentRevision) {
  return request.post(`/patients/me/follow-up/${id}/cancel`, { appointmentRevision }, { silent: true })
}

/**
 * GET /api/patients/me/consent —— 当前档案的资料授权（研究使用、复诊联系）。
 */
export function getMyConsent() {
  return request.get('/patients/me/consent')
}

/**
 * PUT /api/patients/me/consent —— 保存授权；每次保存都会追加一条记录，保留改动历史。
 */
export function saveMyConsent(researchUse, followUpContact) {
  return request.put('/patients/me/consent', { researchUse, followUpContact }, { silent: true })
}

/**
 * GET /api/patients/me/timeline —— 按时间倒序的评估与复诊记录，措辞与首页摘要一致。
 */
export function getMyTimeline() {
  return request.get('/patients/me/timeline')
}

export function getMyMemos() {
  return request.get('/patients/me/memos')
}

export function createMemo(content) {
  return request.post('/patients/me/memos', { content })
}

export function updateMemo(memoId, content) {
  return request.patch(`/patients/me/memos/${memoId}`, { content })
}

export function deleteMemo(memoId) {
  return request.delete(`/patients/me/memos/${memoId}`)
}

export function draftCards(memoId) {
  return request.post(`/patients/me/memos/${memoId}/card-drafts`)
}

/**
 * 确认生成卡片。drafts 传预览页展示给患者的那批草稿，后端按原样发布，
 * 不会在确认时重新抽取——否则语义服务在确认时不可用，保存下来的卡片会和患者看过的不一样。
 */
export function generateCards(memoId, memoVersion, drafts) {
  const body = {}
  if (memoVersion) body.memoVersion = memoVersion
  if (drafts && drafts.length) body.drafts = drafts
  return request.post(
    `/patients/me/memos/${memoId}/generate-cards`,
    Object.keys(body).length ? body : undefined,
    { silent: true }
  )
}

export function getCardsForMemo(memoId) {
  return request.get(`/patients/me/memos/${memoId}/cards`)
}

export function getDueCards() {
  return request.get('/patients/me/flash-cards/due')
}

export function reviewCard(memoId, cardId, action) {
  return request.post(`/patients/me/memos/${memoId}/cards/${cardId}/review`, { action })
}

export function editCard(memoId, cardId, frontText, backText) {
  return request.patch(`/patients/me/memos/${memoId}/cards/${cardId}`, { frontText, backText })
}

export function deleteCard(memoId, cardId) {
  return request.delete(`/patients/me/memos/${memoId}/cards/${cardId}`)
}