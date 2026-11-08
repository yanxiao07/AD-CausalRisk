/**
 * 纯前端导出 CSV。
 *
 * - 加 UTF-8 BOM，Excel 直接打开不乱码；
 * - 字段里的逗号/引号/换行按 RFC 4180 转义；
 * - 通过临时 <a download> 触发下载，不依赖后端。
 */
export function downloadCsv(filename, headers, rows) {
  const escapeCell = (value) => {
    const text = value == null ? '' : String(value)
    return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
  }

  const lines = [headers.map(escapeCell).join(',')]
  for (const row of rows) {
    lines.push(row.map(escapeCell).join(','))
  }

  const blob = new Blob(['\uFEFF' + lines.join('\r\n')], {
    type: 'text/csv;charset=utf-8;'
  })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
