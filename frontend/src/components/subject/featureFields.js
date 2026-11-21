export const featureFields = {
  APOE_E4_COUNT: { label: 'APOE ε4 等位基因数', unit: 'count' },
  MRI_HIPPOCAMPUS_L: { label: '左侧海马影像指标', unit: 'normalized' },
  MRI_HIPPOCAMPUS_R: { label: '右侧海马影像指标', unit: 'normalized' }
}

export function isValidFeatureValue(unit, value) {
  if (unit === 'count') return [0, 1, 2, '0', '1', '2'].includes(value)
  return /^(?:0(?:\.\d{1,6})?|1(?:\.0{1,6})?)$/.test(value)
}
