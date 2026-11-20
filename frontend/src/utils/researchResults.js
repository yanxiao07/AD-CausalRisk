export const CONTROLLED_RESEARCH = 'CONTROLLED_RESEARCH'
export const CONSTRUCTED_VALIDATION = 'CONSTRUCTED_VALIDATION'
export const CARE_WORKFLOW = 'CARE_WORKFLOW'
export const completedSampleReports = {
  name: 'ReportList',
  query: { dataScope: CONSTRUCTED_VALIDATION, status: 'COMPLETED' }
}

export function researchScope(value) {
  return value === CONSTRUCTED_VALIDATION ? CONSTRUCTED_VALIDATION : CONTROLLED_RESEARCH
}

export function researchScopeLabel(value) {
  if (value === CONTROLLED_RESEARCH) return '受控研究'
  if (value === CONSTRUCTED_VALIDATION) return '测试样例'
  return '—'
}

export function subjectResearchScope(subject) {
  return subject?.dataVersion?.startsWith('demo-') ? CONSTRUCTED_VALIDATION : CONTROLLED_RESEARCH
}

const featureNames = {
  age_years: '年龄', education_years: '受教育年限', mmse: 'MMSE 评分',
  cdr_sb: 'CDR-SB 评分', cdr_global: 'CDR 总体分级',
  adas__total13: 'ADAS-Cog 13 评分', faq__faqtotal: '日常功能（FAQ）',
  apoe4_count: 'APOE ε4 数量', bilateral_hippocampus: '双侧海马体积'
}

export const researchFeatureLabel = key => featureNames[key] || key || '整份资料'

const issueNames = {
  QC_UNKNOWN: '缺少质控记录', COMPLETION_UNKNOWN: '缺少量表完成记录',
  SOURCE_METADATA_UNKNOWN: '缺少量表版本、语言或来源说明',
  MEASUREMENT_DATE_UNKNOWN: '测量日期未核实', AVAILABILITY_UNKNOWN: '资料可用时间未核实',
  MISSING_EVIDENCE_UNKNOWN: '缺少缺失原因的记录', RECORD_EVIDENCE_UNKNOWN: '缺少原始记录依据',
  SOURCE_MAPPING_MISMATCH: '登记来源与原始记录不一致', REGISTRY_DATE_PROXY: '日期来自访视登记',
  NO_CORE_INPUT: '缺少评估所需的基础资料'
}

export const researchIssueLabel = code => issueNames[code] || '请核对该项资料'

export function modelContributions(task) {
  const scope = task?.dataScope
  if (task?.status !== 'COMPLETED' || (scope !== CONTROLLED_RESEARCH && scope !== CONSTRUCTED_VALIDATION)
    || scope !== task.result?.dataScope) return []
  const { groups, contributions } = task.result?.explanation || {}
  if (!Array.isArray(groups) || !Array.isArray(contributions)) return []
  // The outer array is the evaluated row for single-subject Python results.
  const values = Array.isArray(contributions[0]?.[0]) ? contributions[0] : contributions
  if (groups.length !== values.length || !values.every(
    pair => Array.isArray(pair) && pair.length === 2 && pair.every(Number.isFinite)
  )) return []
  return groups.map((group, index) => ({
    id: `factor-${index}`,
    group: typeof group === 'string' ? group : group?.label || group?.name || `分组 ${index + 1}`,
    ad: values[index][0],
    other: values[index][1]
  }))
}

export function signedContribution(value) {
  return `${value >= 0 ? '+' : ''}${value.toFixed(6)}`
}
