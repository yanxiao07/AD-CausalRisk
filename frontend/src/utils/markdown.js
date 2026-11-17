/**
 * 轻量 Markdown 渲染器：只覆盖本项目研究报告用到的子集
 * （标题 / 段落 / 粗体 / 斜体 / 行内代码 / 代码块 / 列表 / 表格 / 引用 / 分隔线 / http(s) 链接）。
 *
 * 安全约定：所有原文先做 HTML 转义，再拼标签；链接只允许 http(s)，
 * 因此输出可以安全地交给 v-html。
 */

const ESCAPE = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }

export function escapeHtml(text) {
  return String(text ?? '').replace(/[&<>"']/g, (char) => ESCAPE[char])
}

function inline(text) {
  let html = escapeHtml(text)
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>')
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
  html = html.replace(
    /\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
    '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>'
  )
  return html
}

function splitRow(line) {
  return line
    .replace(/^\s*\|/, '')
    .replace(/\|\s*$/, '')
    .split('|')
    .map((cell) => cell.trim())
}

function isTableSeparator(line) {
  return /^\s*\|?[\s:|-]+\|[\s:|-]*$/.test(line) && /-/.test(line)
}

export function renderMarkdown(source) {
  const lines = String(source ?? '').replace(/\r\n?/g, '\n').split('\n')
  const blocks = []
  let i = 0

  while (i < lines.length) {
    const line = lines[i]

    if (/^\s*$/.test(line)) {
      i += 1
      continue
    }

    // 代码块
    if (/^\s*```/.test(line)) {
      const language = line.replace(/^\s*```/, '').trim()
      i += 1
      const buffer = []
      while (i < lines.length && !/^\s*```/.test(lines[i])) {
        buffer.push(lines[i])
        i += 1
      }
      i += 1
      blocks.push(
        `<pre><code${language ? ` class="language-${escapeHtml(language)}"` : ''}>${escapeHtml(
          buffer.join('\n')
        )}</code></pre>`
      )
      continue
    }

    // 标题
    const heading = line.match(/^(#{1,4})\s+(.*)$/)
    if (heading) {
      const level = heading[1].length
      blocks.push(`<h${level}>${inline(heading[2])}</h${level}>`)
      i += 1
      continue
    }

    // 分隔线
    if (/^\s*(---|\*\*\*|___)\s*$/.test(line)) {
      blocks.push('<hr>')
      i += 1
      continue
    }

    // 表格
    if (/\|/.test(line) && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
      const header = splitRow(line)
      i += 2
      const rows = []
      while (i < lines.length && /\|/.test(lines[i]) && !/^\s*$/.test(lines[i])) {
        rows.push(splitRow(lines[i]))
        i += 1
      }
      blocks.push(
        `<table><thead><tr>${header
          .map((cell) => `<th>${inline(cell)}</th>`)
          .join('')}</tr></thead><tbody>${rows
          .map((row) => `<tr>${row.map((cell) => `<td>${inline(cell)}</td>`).join('')}</tr>`)
          .join('')}</tbody></table>`
      )
      continue
    }

    // 引用
    if (/^\s*>\s?/.test(line)) {
      const buffer = []
      while (i < lines.length && /^\s*>\s?/.test(lines[i])) {
        buffer.push(lines[i].replace(/^\s*>\s?/, ''))
        i += 1
      }
      blocks.push(`<blockquote>${buffer.map(inline).join('<br>')}</blockquote>`)
      continue
    }

    // 无序列表
    if (/^\s*[-*+]\s+/.test(line)) {
      const buffer = []
      while (i < lines.length && /^\s*[-*+]\s+/.test(lines[i])) {
        buffer.push(lines[i].replace(/^\s*[-*+]\s+/, ''))
        i += 1
      }
      blocks.push(`<ul>${buffer.map((item) => `<li>${inline(item)}</li>`).join('')}</ul>`)
      continue
    }

    // 有序列表
    if (/^\s*\d+[.)]\s+/.test(line)) {
      const buffer = []
      while (i < lines.length && /^\s*\d+[.)]\s+/.test(lines[i])) {
        buffer.push(lines[i].replace(/^\s*\d+[.)]\s+/, ''))
        i += 1
      }
      blocks.push(`<ol>${buffer.map((item) => `<li>${inline(item)}</li>`).join('')}</ol>`)
      continue
    }

    // 段落（连续非空行合并）
    const buffer = [line]
    i += 1
    while (
      i < lines.length &&
      !/^\s*$/.test(lines[i]) &&
      !/^(#{1,4})\s+|^\s*```|^\s*>|^\s*[-*+]\s+|^\s*\d+[.)]\s+/.test(lines[i])
    ) {
      buffer.push(lines[i])
      i += 1
    }
    blocks.push(`<p>${inline(buffer.join(' '))}</p>`)
  }

  return blocks.join('\n')
}
