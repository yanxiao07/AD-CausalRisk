/**
 * P2 浏览器冒烟：医生档案列表（空态/表格/抽屉）+ 研究员导入中心（上传/预检/入库）。
 *
 * 不依赖后端：/api/auth/login、/api/me、/api/subjects、/api/import-jobs 全部用桩响应，
 * 验证的是页面自身的渲染、空态、加载态与交互链路。
 *
 * 运行：
 *   npm i --no-save puppeteer-core
 *   npm run dev -- --port 5199
 *   node scripts/e2e-p2.mjs
 */
import puppeteer from 'puppeteer-core'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const BASE = process.env.E2E_BASE || 'http://127.0.0.1:5199'
const CHROME =
  process.env.E2E_CHROME || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'

const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..')
const shotDir = join(projectRoot, '.shots')
mkdirSync(shotDir, { recursive: true })
mkdirSync(join(projectRoot, '.tmp'), { recursive: true })

const csvPath = join(projectRoot, '.tmp', 'e2e-sample.csv')
writeFileSync(csvPath, 'subjectCode,alias\nDEMO-E2E,冒烟样本\n', 'utf8')

const USERS = {
  doctor: { id: 4, username: 'doctor', roles: ['DOCTOR'], menus: ['followups', 'graph', 'reports', 'reviews', 'subjects'] },
  researcher: { id: 3, username: 'researcher', roles: ['RESEARCHER'], menus: ['graph', 'imports', 'quality', 'reports', 'subjects', 'tasks'] }
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

const SUBJECT = (id) => ({
  id,
  subjectCode: `DEMO-${String(id).padStart(3, '0')}`,
  alias: `样本 ${id}`,
  status: 'ACTIVE',
  dataVersion: 'demo-e2e-2026',
  createdAt: '2026-10-01T09:00:00Z'
})

const JOB = {
  jobId: 42,
  status: 'PREVIEW_READY',
  dataVersion: 'demo-e2e-2026',
  totalRows: 5,
  validRows: 4,
  invalidRows: 1,
  errors: [{ rowNumber: 3, field: 'mmse', message: '超出 0-30 范围' }]
}

const checks = []
let failed = 0
const check = (name, ok, detail) => {
  checks.push({ name, ok, detail })
  if (!ok) failed += 1
}

/** 只按 pathname 判断，避免 /src/api/subjects.js 被误当成 /api/subjects 拦截。 */
const pathOf = (url) => {
  try {
    return new URL(url).pathname
  } catch {
    return url
  }
}

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage']
})

async function openAs(role, handlers) {
  const page = await browser.newPage()
  await page.setViewport({ width: 1440, height: 900 })
  await page.setRequestInterception(true)
  page.on('request', (req) => {
    const url = req.url()
    const method = req.method()
    if (url.includes('cdn.jsdelivr.net')) return req.abort()
    const custom = handlers.find((h) => h.match(pathOf(url), method))
    if (custom) {
      return req.respond({
        status: custom.status || 200,
        contentType: 'application/json',
        body: envelope(typeof custom.body === 'function' ? custom.body() : custom.body)
      })
    }
    if (pathOf(url) === '/api/auth/login' || pathOf(url) === '/api/me') {
      return req.respond({ status: 200, contentType: 'application/json', body: envelope(USERS[role]) })
    }
    return req.continue()
  })

  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  await page.evaluate(() => localStorage.clear())
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector(`button[data-role="${role}"]`, { timeout: 15000 })
  await page.click(`button[data-role="${role}"]`)
  await page.waitForSelector('#username', { visible: true })
  await page.type('#username', role)
  await page.type('#password', 'DemoPass!2026')
  await Promise.all([
    page.waitForFunction(() => location.pathname !== '/login', { timeout: 20000 }),
    page.click('#submit-btn')
  ])
  return page
}

async function clickMenu(page, label) {
  await page.waitForSelector('.el-menu-item', { timeout: 15000 })
  const items = await page.$$('.el-menu-item:not(.is-disabled)')
  for (const item of items) {
    const text = await item.evaluate((node) => node.textContent || '')
    if (text.includes(label)) {
      await item.click()
      return true
    }
  }
  const labels = await page.evaluate(() =>
    [...document.querySelectorAll('.el-menu-item')].map((n) => n.textContent.trim())
  )
  throw new Error(`menu item "${label}" not found; items=${JSON.stringify(labels)}`)
}

try {
  // ---------------- 医生：档案列表 ----------------
  let subjectItems = []
  const page = await openAs('doctor', [
    { match: (path, m) => path === '/api/subjects' && m === 'GET', body: () => ({ items: subjectItems, page: 1, pageSize: 20, total: subjectItems.length }) }
  ])

  check('doctor: 菜单可点击进入档案', await clickMenu(page, '受试者档案'), 'sidebar item')
  await page.waitForFunction(() => location.pathname === '/subjects', { timeout: 15000 })
  check('doctor: 路由落点', await page.evaluate(() => location.pathname) === '/subjects', '')
  await page.waitForSelector('.patient-list .empty-state', { timeout: 15000 })
  const emptyText = await page.evaluate(
    () => document.querySelector('.patient-list .empty-state')?.textContent || ''
  )
  check('doctor: 空态文案', emptyText.includes('授权范围内还没有档案'), emptyText.replace(/\s+/g, ' ').slice(0, 60))
  await page.screenshot({ path: join(shotDir, 'p2-subjects-empty.png') })

  // 有数据后：表格 + 抽屉
  subjectItems = [SUBJECT(1), SUBJECT(2)]
  await page.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.includes('刷新'))
    btn?.click()
  })
  await page.waitForFunction(() => document.querySelectorAll('.el-table__row').length === 2, { timeout: 15000 })
  check('doctor: 表格渲染 2 行', await page.evaluate(() => document.querySelectorAll('.el-table__row').length) === 2, '')
  await page.screenshot({ path: join(shotDir, 'p2-subjects-table.png') })

  await page.click('.el-table__row')
  await page.waitForSelector('.el-drawer', { timeout: 10000 })
  const drawerTitle = await page.evaluate(() => document.querySelector('.el-drawer__title, .el-drawer__header')?.textContent || '')
  check('doctor: 抽屉打开且带档案信息', drawerTitle.includes('样本 1'), drawerTitle.trim().slice(0, 40))
  await page.screenshot({ path: join(shotDir, 'p2-subject-drawer.png') })
  await page.close()

  // ---------------- 研究员：导入中心 ----------------
  const rPage = await openAs('researcher', [
    {
      match: (path, m) => path === '/api/import-jobs' && m === 'POST',
      body: JOB
    },
    {
      match: (path, m) => path === '/api/import-jobs/42/commit' && m === 'POST',
      body: { ...JOB, status: 'COMMITTED' }
    },
    { match: (path, m) => path === '/api/subjects' && m === 'GET', body: { items: [], page: 1, pageSize: 20, total: 0 } }
  ])

  check('researcher: 菜单可点击进入导入', await clickMenu(rPage, '数据导入'), 'sidebar item')
  await rPage.waitForFunction(() => location.pathname === '/imports', { timeout: 15000 })
  await rPage.waitForSelector('.el-upload-dragger', { timeout: 15000 })
  await rPage.waitForSelector('.import-center .empty-state', { timeout: 15000 })
  const beforeText = await rPage.evaluate(
    () => document.querySelector('.import-center .empty-state')?.textContent || ''
  )
  check('researcher: 上传前有空态', beforeText.includes('还没有上传文件'), beforeText.replace(/\s+/g, ' ').slice(0, 40))
  await rPage.screenshot({ path: join(shotDir, 'p2-import-empty.png') })

  const input = await rPage.$('.dropzone input[type=file]')
  check('researcher: 存在文件输入', !!input, '')
  await input.uploadFile(csvPath)

  await rPage.waitForSelector('.stats', { timeout: 15000 })
  const stats = await rPage.evaluate(() =>
    [...document.querySelectorAll('.stat')].map((n) => n.textContent.replace(/\s+/g, ' ').trim())
  )
  check('researcher: 预检统计 5/4/1', stats.join('|').includes('5') && stats.join('|').includes('4') && stats.join('|').includes('1'), stats.join(' / '))
  const errRows = await rPage.evaluate(() => document.querySelectorAll('.errors .el-table__row').length)
  check('researcher: 错误明细 1 行', errRows === 1, String(errRows))
  const commitDisabled = await rPage.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.includes('确认入库'))
    return btn?.disabled ?? true
  })
  check('researcher: 确认入库可点击', commitDisabled === false, `disabled=${commitDisabled}`)
  await rPage.screenshot({ path: join(shotDir, 'p2-import-preview.png') })

  await rPage.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.includes('确认入库'))
    btn?.click()
  })
  await rPage.waitForFunction(
    () => [...document.querySelectorAll('.status-tag')].some((n) => n.textContent.includes('已入库')),
    { timeout: 15000 }
  )
  const committedDisabled = await rPage.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.includes('确认入库'))
    return btn?.disabled ?? false
  })
  check('researcher: 入库后按钮禁用', committedDisabled === true, `disabled=${committedDisabled}`)
  await rPage.screenshot({ path: join(shotDir, 'p2-import-committed.png') })
  await rPage.close()
} finally {
  await browser.close()
}

console.log(JSON.stringify({ ok: failed === 0, failed, checks }, null, 2))
process.exit(failed === 0 ? 0 : 1)
