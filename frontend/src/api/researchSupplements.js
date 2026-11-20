import request from './request.js'

export function listResearchSupplements(params) {
  return request.get('/research/supplements', { params, silent: true })
}

export function createResearchSupplement(originalTaskId, note) {
  return request.post('/research/supplements', { originalTaskId, note }, { silent: true })
}

export function updateResearchSupplement(id, action, replacementTaskId) {
  return request.patch(`/research/supplements/${id}`, { action, replacementTaskId }, { silent: true })
}
