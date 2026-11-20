import request from './request.js'

export const requestPatientAccount = subjectId => request.post('/patient-account-requests', { subjectId })
export const getPatientOnboardingStatus = subjectId => request.get('/patient-account-requests/status', {
  params: { subjectId }, silent: true
})
export const listPatientAccountRequests = () => request.get('/patient-account-requests')
export const issuePatientAccount = (id, credentials) => request.post(`/patient-account-requests/${id}/issue`, credentials)
export const handoffPatientDoctor = (id, doctorId) => request.post(`/patient-account-requests/${id}/responsible-doctor`, {
  doctorId, identityVerified: true
})

export function onboardingQualityLabel(status) {
  return ({
    NO_CHECK: '待首次质控',
    ARCHIVED: '档案已归档',
    STALE: '资料已变化，需重新质控',
    INCOMPLETE: '最近访视缺少 MMSE 或 CDR-SB',
    BLOCKED: '质控阻断，需修正资料',
    WARN: '质控无阻断，仍有提示',
    PASS: '质控通过',
    ISSUED: '已绑定患者账号'
  })[status] || '质控状态暂不可用'
}
