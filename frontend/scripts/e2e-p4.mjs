/**
 * P4 浏览器冒烟：管理员大屏（真实接口形状）+ 患者端三种降级路径。
 *
 * 接口用桩响应（不依赖 8080），验证渲染、统计口径与降级文案。
 *   node scripts/e2e-p4.mjs   （需先 npm run dev -- --port 5199）
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

const USERS = {
  admin: { id: 1, username: 'admin', roles: ['ADMIN'], menus: ['audit', 'system', 'users'] },
  patient: { id: 5, username: 'patient', roles: ['PATIENT'], menus: ['followups', 'summary'] }
}

const daysAgo = (n) => {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d.toISOString()
}

const ADMIN_SUBJECTS = {
  items: [
    { id: 1, subjectCode: 'DEMO-001', alias: '张样本', status: 'ACTIVE', dataVersion: 'demo-2026', createdAt: daysAgo(0) },
    { id: 2, subjectCode: 'DEMO-002', alias: '李样本', status: 'ACTIVE', dataVersion: 'demo-2026', createdAt: daysAgo(1) },
    { id: 3, subjectCode: 'DEMO-003', alias: '王样本', status: 'ACTIVE', dataVersion: 'demo-2026', createdAt: daysAgo(1) },
    { id: 4, subjectCode: 'DEMO-004', alias: '赵样本', status: 'ARCHIVED', dataVersion: 'demo-2026', createdAt: daysAgo(3) },
    { id: 5, subjectCode: 'DEMO-005', alias: '陈样本', status: 'ACTIVE', dataVersion: 'demo-2026', createdAt: daysAgo(5) }
  ],
  page: 1,
  pageSize: 100,
  total: 42
}

const QC_STATS = { pendingCount: 3, approvedCount: 18, rejectedCount: 2 }

const AUDIT = [
  { id: 9, actorId: 4, eventType: 'ASSESSMENT_REVIEW', traceId: 'trace-9', summary: '记录人工业务复核', timestamp: daysAgo(0) },
  { id: 8, actorId: 1, eventType: 'ASSESSMENT_REVIEW', traceId: 'trace-8', summary: '记录人工业务复核', timestamp: daysAgo(0) },
  { id: 7, actorId: 3, eventType: 'IMPORT_COMMIT', traceId: 'trace-7', summary: 'CSV 入库 12 行', timestamp: daysAgo(1) },
  { id: 6, actorId: 2, eventType: 'VISIT_CREATE', traceId: 'trace-6', summary: '新增访视 V3', timestamp: daysAgo(2) },
  { id: 5, actorId: 1, eventType: 'SUBJECT_CREATE', traceId: 'trace-5', summary: '建档 DEMO-001', timestamp: daysAgo(3) }
]

const checks = []
let failed = 0
const check = (name, ok, detail) => {
  checks.push({ name, ok, detail })
  if (!ok) failed += 1
}

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage']
})

async function loginAs(role, handler) {
  const page = await browser.newPage()
  await page.setViewport({ width: 1600, height: 1000 })
  const errors = []
  page.on('pageerror', (err) => errors.push(String(err).slice(0, 160)))
  await page.setRequestInterception(true)
  page.on('request', (req) => {
    const path = pathOf(req.url())
    if (req.url().includes('cdn.jsdelivr.net')) return req.abort()
    const custom = handler?.(path, req)
    if (custom) {
      return req.respond({
        status: custom.status || 200,
        contentType: 'application/json',
        body: envelope(custom.body)
      })
    }
    if (path === '/api/auth/login' || path === '/api/me') {
      return req.respond({ status: 200, contentType: 'application/json', body: envelope(USERS[role]) })
    }
    return req.continue()
  })

  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  await page.evaluate(() => localStorage.clear())
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector(`button[data-role="${role}"]`)
  await page.click(`button[data-role="${role}"]`)
  await page.waitForSelector('#username', { visible: true })
  await page.type('#username', role)
  await page.type('#password', 'DemoPass!2026')
  await Promise.all([
    page.waitForFunction(() => location.pathname !== '/login', { timeout: 20000 }),
    page.click('#submit-btn')
  ])
  return { page, errors }
}

try {
  // ---------------- 管理员大屏 ----------------
  const { page, errors } = await loginAs('admin', (path) => {
    if (path === '/api/subjects') return { body: ADMIN_SUBJECTS }
    if (path === '/api/qc/stats') return { body: QC_STATS }
    if (path === '/api/audit-logs') return { body: AUDIT }
    return null
  })

  await page.waitForSelector('.metric__value', { timeout: 20000 })
  await page.waitForFunction(
    () => document.querySelectorAll('.echart canvas').length === 2,
    { timeout: 20000 }
  )
  const metricValues = await page.evaluate(() =>
    [...document.querySelectorAll('.metric__value')].map((n) => n.textContent.trim())
  )
  check('四个指标来自真实接口', metricValues.join(',') === '42,3,2,5', metricValues.join(','))
  check('两个 ECharts 图表渲染', true, 'canvas=2')
  const auditRows = await page.evaluate(() => document.querySelectorAll('.audit .el-table__row').length)
  check('审计流渲染（上限 8 条）', auditRows === 5, `rows=${auditRows}`)
  await page.screenshot({ path: join(shotDir, 'p4-admin.png'), fullPage: true })

  // 大屏导航：审计日志
  await page.evaluate(() => {
    const item = [...document.querySelectorAll('.screen__nav-item')].find((n) =>
      n.textContent.includes('审计日志')
    )
    item?.click()
  })
  await page.waitForFunction(() => location.pathname === '/admin/audit', { timeout: 15000 })
  await page.waitForSelector('.audit__table .el-table__row', { timeout: 15000 })
  const auditPageRows = await page.evaluate(
    () => document.querySelectorAll('.audit__table .el-table__row').length
  )
  check('审计页导航成功且行数正确', auditPageRows === 5, `rows=${auditPageRows}`)
  await page.screenshot({ path: join(shotDir, 'p4-audit-page.png') })

  check('管理员无页面异常', errors.length === 0, errors[0] || 'none')
  await page.close()

  // ---------------- 患者：A 未绑定档案 ----------------
  const a = await loginAs('patient', (path) => {
    if (path === '/api/subjects') return { body: { items: [], page: 1, pageSize: 1, total: 0 } }
    return null
  })
  await a.page.waitForSelector('.hcard__status', { timeout: 20000 })
  await a.page.waitForFunction(
    () => document.body.textContent.includes('还没有绑定健康档案'),
    { timeout: 15000 }
  )
  const aText = await a.page.evaluate(() => document.querySelector('.patient-home')?.textContent.replace(/\s+/g, ' '))
  check('患者未绑定 → 等待复核文案', aText.includes('您的报告正在等待医生复核'), aText.slice(0, 40))
  check('患者未绑定 → 无侧边栏', !(await a.page.evaluate(() => !!document.querySelector('aside.shell__aside'))), '')
  await a.page.screenshot({ path: join(shotDir, 'p4-patient-unbound.png'), fullPage: true })
  await a.page.close()

  // ---------------- 患者：B 已绑定 + 有复诊 ----------------
  const b = await loginAs('patient', (path) => {
    if (path === '/api/subjects') {
      return {
        body: {
          items: [{ id: 1, subjectCode: 'DEMO-001', alias: '张样本', status: 'ACTIVE', dataVersion: 'demo', createdAt: daysAgo(3) }],
          page: 1,
          pageSize: 1,
          total: 1
        }
      }
    }
    if (path === '/api/subjects/1/follow-ups') {
      return {
        body: [
          { id: 1, subjectId: 1, scheduledAt: '2026-12-15T01:00:00Z', purpose: '认知量表复查 + 影像', status: 'PLANNED' }
        ]
      }
    }
    return null
  })
  await b.page.waitForFunction(() => document.body.textContent.includes('张样本'), { timeout: 20000 })
  const bVisit = await b.page.evaluate(() => document.querySelector('.hcard__value')?.textContent.trim())
  check('患者已绑定 → 显示复诊日期', /2026/.test(bVisit || ''), bVisit || '(empty)')
  await b.page.screenshot({ path: join(shotDir, 'p4-patient-bound.png'), fullPage: true })
  await b.page.close()

  // ---------------- 患者：C 403 降级 ----------------
  const c = await loginAs('patient', (path) => {
    if (path === '/api/subjects') return { status: 403, body: null }
    return null
  })
  await c.page.waitForFunction(
    () => document.body.textContent.includes('暂时无法查看您的档案'),
    { timeout: 20000 }
  )
  const cText = await c.page.evaluate(() => document.querySelector('.hcard__note')?.textContent.trim())
  check('患者 403 → 优雅降级', /暂时无法查看您的档案/.test(cText || ''), (cText || '').slice(0, 40))
  const cStatus = await c.page.evaluate(() => document.querySelector('.hcard__status')?.textContent.trim())
  check('患者 403 → 仍显示等待复核', /等待医生复核/.test(cStatus || ''), cStatus || '')
  await c.page.screenshot({ path: join(shotDir, 'p4-patient-403.png'), fullPage: true })
  await c.page.close()
} finally {
  await browser.close()
}

console.log(JSON.stringify({ ok: failed === 0, failed, checks }, null, 2))
process.exit(failed === 0 ? 0 : 1)
