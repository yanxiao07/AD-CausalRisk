import request from './request.js'

export const listValidationCases = page =>
  request.get('/research/validation-cohort', { params: { page }, silent: true })

export const listValidationScopes = () =>
  request.get('/research/validation-cohort/scopes', { silent: true })

export const getValidationReport = params =>
  request.get('/research/validation-cohort/report', { params, silent: true })

export const runValidationReport = data =>
  request.post('/research/validation-cohort/report', data, { silent: true })

export const grantValidationCase = data =>
  request.put('/research/validation-cohort/grant', data, { silent: true })

export const revokeValidationCase = params =>
  request.delete('/research/validation-cohort/grant', { params, silent: true })

export const getValidationFollowUp = id =>
  request.get(`/subjects/${id}/validation-follow-up`, { silent: true })

export const saveValidationFollowUp = (id, data) =>
  request.put(`/subjects/${id}/validation-follow-up`, data, { silent: true })
