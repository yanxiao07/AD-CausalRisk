export const visitModelFields = [
  { key: 'ageYears', label: '访视时年龄（岁）', max: 120 },
  { key: 'educationYears', label: '受教育年限（年）', max: 40 },
  { key: 'cdrGlobal', label: 'CDR 全局评分', max: 3 },
  { key: 'adasCog13', label: 'ADAS-Cog 13', max: 85 },
  { key: 'faqTotal', label: 'FAQ 总分', max: 30 }
]

export function newVisitModelDraft(saved) {
  return Object.fromEntries(visitModelFields.map(({ key }) => [key, saved?.[key] == null ? '' : String(saved[key])]))
}

export function visitModelErrors(draft) {
  return Object.fromEntries(visitModelFields.flatMap(({ key, label, max }) => {
    const value = draft[key]
    return value !== '' && (!/^\d{1,3}(?:\.\d{1,6})?$/.test(value) || Number(value) > max)
      ? [[key, `${label}须在 0–${max} 之间，最多六位小数。`]] : []
  }))
}

export function visitModelPayload(draft) {
  return Object.fromEntries(visitModelFields.map(({ key }) => [key, draft[key] === '' ? null : Number(draft[key])]))
}
