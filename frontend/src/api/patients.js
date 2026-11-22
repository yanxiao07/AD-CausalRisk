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

export function generateCards(memoId) {
  return request.post(`/patients/me/memos/${memoId}/generate-cards`)
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