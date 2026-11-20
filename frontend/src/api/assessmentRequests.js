import request from './request.js'

const archivePath = subjectId => `/subjects/${subjectId}/assessment-request`
const queuePath = '/research/assessment-requests'

export const getAssessmentRequest = subjectId => request.get(archivePath(subjectId), { silent: true })
export const requestAssessment = subjectId => request.post(archivePath(subjectId), undefined, { silent: true })
export const getAssessmentRequests = page => request.get(queuePath, { params: { page }, silent: true })
export const executeAssessmentRequest = id => request.post(`${queuePath}/${id}/execute`, undefined, { silent: true })
