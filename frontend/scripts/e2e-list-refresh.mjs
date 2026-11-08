/**
 * 回归用例：受试者档案列表的刷新行为。
 *
 * 覆盖三件事：
 *  1. 刷新时不卸载表格（避免 repeated remount 造成的错位/重影）；
 *  2. 结果整表覆盖、按 id 去重（不追加、不重复）；
 *  3. 竞态保护：先发出的慢请求后返回时，不能覆盖后发出的快请求结果。
 *
 *   node scripts/e2e-list-refresh.mjs   （需先 npm run dev -- --port 5199）
 */
import puppeteer from 'puppeteer-core'
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const BASE = process.env.E2E_BASE || 'http://127.0.0.1:5199'
const CHROME =
  process.env.E2E_CHROME || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'

const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..')
const shotDir = join(projectRoot, '.shots')
mkdirSync(shotDir, { recursive: true })

const pathOf = (url) => {
  try {
    return new URL(url).pathname
  } catch {
    return url
  }
}

const envelope = (data) =>
  JSON.stringify({
    code: 0,
    message: 'success',
    data,
    errorCode: null,
    traceId: 'e2e-trace',
    timestamp: new Date().toISOString()
  })

const DOCTOR = { id: 4, username: 'doctor', roles: ['DOCTOR'], menus: ['subjects'] }

const makeRows = (count, prefix = 'DEMO') =>
  Array.from({ length: count }, (_, i) => ({
    id: i + 1,
    subjectCode: `${prefix}-${String(i + 1).padStart(3, '0')}`,
    alias: `样本 ${i + 1}`,
    status: 'ACTIVE',
    dataVersion: 'demo-2026',
    createdAt: '2026-10-01T09:00:00Z'
  }))

const checks = []
let failed = 0
const check = (name, ok, detail) => {
  checks.push({ name, ok, detail })
  if (!ok) failed += 1
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage']
})

try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1500, height: 950 })
  const pageErrors = []
  page.on('pageerror', (err) => pageErrors.push(String(err).slice(0, 160)))

  // 第 1 次列表请求：慢（600ms），返回 20 行；
  // 第 2 次及以后：快（50ms），返回 5 行 —— 用来验证"旧响应不覆盖新响应"。
  let listCalls = 0
  await page.setRequestInterception(true)
  page.on('request', async (req) => {
    const path = pathOf(req.url())
    if (req.url().includes('cdn.jsdelivr.net')) return req.abort()
    if (path === '/api/auth/login' || path === '/api/me') {
      return req.respond({ status: 200, contentType: 'application/json', body: envelope(DOCTOR) })
    }
    if (path === '/api/subjects') {
      listCalls += 1
      const first = listCalls === 1
      await sleep(first ? 600 : 50)
      const rows = first ? makeRows(20) : makeRows(5)
      return req.respond({
        status: 200,
        contentType: 'application/json',
        body: envelope({ items: rows, page: 1, pageSize: 20, total: rows.length })
      })
    }
    if (path === '/api/subjects/1') {
      return req.respond({
        status: 200,
        contentType: 'application/json',
        body: envelope({ subject: makeRows(1)[0], visits: [], features: [] })
      })
    }
    return req.continue()
  })

  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  await page.evaluate(() => localStorage.clear())
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('button[data-role="doctor"]')
  await page.click('button[data-role="doctor"]')
  await page.waitForSelector('#username', { visible: true })
  await page.type('#username', 'doctor')
  await page.type('#password', 'DemoPass!2026')
  await Promise.all([
    page.waitForFunction(() => location.pathname !== '/login', { timeout: 20000 }),
    page.click('#submit-btn')
  ])

  await page.goto(`${BASE}/subjects`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.el-table__body .el-table__row', { timeout: 20000 })
  await sleep(200)

  const initial = await page.evaluate(() => ({
    rows: document.querySelectorAll('.el-table__body .el-table__row').length,
    tables: document.querySelectorAll('.el-table').length
  }))
  check('首次加载 20 行', initial.rows === 20, `rows=${initial.rows}`)

  // 连点刷新 3 次，期间采样表格是否被卸载
  const clickRefresh = () =>
    page.evaluate(() => {
      const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.includes('刷新'))
      btn?.click()
    })

  const sampled = []
  await clickRefresh()
  for (let i = 0; i < 10; i += 1) {
    sampled.push(await page.evaluate(() => document.querySelectorAll('.el-table').length))
    await sleep(80)
  }
  await clickRefresh()
  await sleep(1200)

  check(
    '刷新期间表格未被卸载',
    sampled.every((n) => n === 1),
    `samples=${sampled.join('')}`
  )

  const after = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('.el-table__body .el-table__row')]
    const codes = rows.map((r) => r.querySelector('td')?.textContent.trim())
    return {
      rows: rows.length,
      tables: document.querySelectorAll('.el-table').length,
      unique: new Set(codes).size,
      dups: codes.filter((c, i) => codes.indexOf(c) !== i)
    }
  })

  // 第 3 次请求（最后点的那次）返回 5 行；慢的第 1 次（20 行）绝不能覆盖它
  check('竞态：最新响应生效（5 行）', after.rows === 5, `rows=${after.rows}`)
  check('无重复行', after.dups.length === 0 && after.unique === after.rows, `unique=${after.unique}`)
  check('表格只有一个实例', after.tables === 1, `tables=${after.tables}`)
  check('列表请求次数符合预期', listCalls >= 3, `calls=${listCalls}`)

  await page.screenshot({ path: join(shotDir, 'fix-list-refresh.png') })

  // 打开抽屉后再刷新，行数与唯一性保持不变
  await page.click('.el-table__body .el-table__row')
  await page.waitForSelector('.assessment', { timeout: 15000 })
  await clickRefresh()
  await sleep(1000)
  const withDrawer = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('.el-table__body .el-table__row')]
    const codes = rows.map((r) => r.querySelector('td')?.textContent.trim())
    return { rows: rows.length, unique: new Set(codes).size, tables: document.querySelectorAll('.el-table').length }
  })
  check(
    '抽屉打开时刷新仍然正常',
    withDrawer.rows === 5 && withDrawer.unique === 5 && withDrawer.tables === 1,
    JSON.stringify(withDrawer)
  )
  check('无页面异常', pageErrors.length === 0, pageErrors[0] || 'none')
} finally {
  await browser.close()
}

console.log(JSON.stringify({ ok: failed === 0, failed, checks }, null, 2))
process.exit(failed === 0 ? 0 : 1)
