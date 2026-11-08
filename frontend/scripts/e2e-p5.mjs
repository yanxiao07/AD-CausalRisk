/**
 * P5 浏览器冒烟：随访创建/状态流转、批量评估进度、待办复核、账号管理骨架。
 * 接口用桩响应（不依赖 8080）。
 *
 *   node scripts/e2e-p5.mjs   （需先 npm run dev -- --port 5199）
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
    traceId: 'e2e',
    timestamp: new Date().toISOString()
  })

const USERS = {
  doctor: { id: 4, username: 'doctor', roles: ['DOCTOR'], menus: ['followups', 'reviews', 'subjects'] },
  researcher: { id: 3, username: 'researcher', roles: ['RESEARCHER'], menus: ['subjects', 'imports'] },
  admin: { id: 1, username: 'admin', roles: ['ADMIN'], menus: ['audit', 'users', 'system'] }
}

const SUBJECTS = {
  items: [
    { id: 1, subjectCode: 'DEMO-001', alias: '张样本', status: 'ACTIVE', dataVersion: 'demo', createdAt: '2026-10-01T00:00:00Z' },
    { id: 2, subjectCode: 'DEMO-002', alias: '李样本', status: 'ACTIVE', dataVersion: 'demo', createdAt: '2026-10-02T00:00:00Z' },
    { id: 3, subjectCode: 'DEMO-003', alias: '王样本', status: 'ACTIVE', dataVersion: 'demo', createdAt: '2026-10-03T00:00:00Z' }
  ],
  page: 1,
  pageSize: 20,
  total: 3
}

const ASSESSMENT = {
  assessmentId: 99,
  subjectId: 1,
  taskId: 7,
  reviewStatus: 'PENDING',
  createdAt: '2026-10-30T12:00:00Z',
  agentResult: {
    runId: 'run-99',
    dataVersion: 'demo',
    status: 'COMPLETED',
    contractVersion: 'deepagent-v1',
    workflowVersion: 'wf',
    modelVersion: 'deepagent-causal-warning-v1',
    qualityStatus: 'PASS',
    riskScore: 0.72,
    riskLevel: 'HIGH',
    warnings: [],
    explanations: [],
    graph: {
      nodes: [
        { id: 'a', label: 'APOE ε4', status: 'ACTIVE', evidenceLevel: 'MODEL', observedAt: null },
        { id: 'b', label: 'MMSE', status: 'ACTIVE', evidenceLevel: 'MODEL', observedAt: null }
      ],
      edges: [
        { from: 'a', to: 'b', note: '研究先验证据', status: 'ACTIVE', evidenceLevel: 'PRIOR', direction: 'CONDITIONAL' }
      ]
    }
  }
}

const checks = []
let failed = 0
const check = (name, ok, detail) => {
  checks.push({ name, ok, detail })
  if (!ok) failed += 1
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage']
})

async function loginAs(role, handler) {
  const page = await browser.newPage()
  await page.setViewport({ width: 1600, height: 1000 })
  const captured = []
  await page.setRequestInterception(true)
  page.on('request', (req) => {
    const path = pathOf(req.url())
    const method = req.method()
    if (req.url().includes('cdn.jsdelivr.net')) return req.abort()
    const handled = handler?.(path, method, req, captured)
    if (handled) return handled
    if (path === '/api/auth/login' || path === '/api/me') {
      return req.respond({ status: 200, contentType: 'application/json', body: envelope(USERS[role]) })
    }
    if (path === '/api/subjects') {
      return req.respond({ status: 200, contentType: 'application/json', body: envelope(SUBJECTS) })
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
  return { page, captured }
}

try {
  // ---------- 1) 医生：抽屉新建随访 ----------
  let followUpList = []
  const d = await loginAs('doctor', (path, method, req, captured) => {
    const respond = (data) => req.respond({ status: 200, contentType: 'application/json', body: envelope(data) })
    if (path === '/api/subjects/1') return respond({ subject: SUBJECTS.items[0], visits: [], features: [] })
    if (path === '/api/subjects/1/follow-ups' && method === 'GET') return respond(followUpList)
    if (path === '/api/subjects/1/follow-ups' && method === 'POST') return null
    if (path === '/api/follow-ups' && method === 'POST') {
      captured.push({ url: path, body: JSON.parse(req.postData() || '{}') })
      followUpList = [
        { id: 11, subjectId: 1, scheduledAt: '2026-12-15T02:00:00Z', purpose: '认知量表复查', status: 'PLANNED' }
      ]
      return respond(followUpList[0])
    }
    if (path === '/api/follow-ups/11' && method === 'PATCH') {
      captured.push({ url: path, body: JSON.parse(req.postData() || '{}') })
      return respond({ ...followUpList[0], status: 'COMPLETED' })
    }
    return null
  })

  await d.page.goto(`${BASE}/subjects`, { waitUntil: 'domcontentloaded' })
  await d.page.waitForSelector('.el-table__body .el-table__row', { timeout: 20000 })
  await d.page.click('.el-table__body .el-table__row')
  await d.page.waitForSelector('.followup', { timeout: 15000 })
  await d.page.evaluate(() => {
    const btn = [...document.querySelectorAll('.followup button')].find((b) => b.textContent.includes('新建随访'))
    btn?.click()
  })
  await d.page.waitForSelector('.el-dialog', { timeout: 10000 })
  const dateInput = await d.page.$('.el-dialog .el-date-editor input')
  await dateInput.click()
  await dateInput.type('2026-12-15 10:00')
  await d.page.keyboard.press('Enter')
  await d.page.type('.el-dialog textarea', '认知量表复查')
  await d.page.evaluate(() => {
    const btn = [...document.querySelectorAll('.el-dialog__footer button')].find((b) => b.textContent.includes('创建'))
    btn?.click()
  })
  await d.page.waitForFunction(() => document.body.textContent.includes('认知量表复查'), { timeout: 15000 })
  const posted = d.captured.find((c) => c.url === '/api/follow-ups')
  check(
    '随访创建请求体正确',
    !!posted && posted.body.subjectId === 1 && /^\d{4}-\d{2}-\d{2}T.*Z$/.test(posted.body.scheduledAt || ''),
    JSON.stringify(posted?.body || {})
  )
  check('随访创建后抽屉列表刷新', true, '包含"认知量表复查"')
  await d.page.screenshot({ path: join(shotDir, 'p5-followup-created.png') })

  // 随访页：标记完成
  await d.page.goto(`${BASE}/followups`, { waitUntil: 'domcontentloaded' })
  await d.page.waitForSelector('.picker__select', { timeout: 15000 })
  await d.page.click('.picker__select')
  await d.page.waitForSelector('.el-select-dropdown__item', { timeout: 10000 })
  await d.page.evaluate(() => {
    document.querySelectorAll('.el-select-dropdown__item')[0]?.click()
  })
  await d.page.waitForFunction(() => document.querySelectorAll('.el-table__body .el-table__row').length > 0, {
    timeout: 15000
  })
  await d.page.evaluate(() => {
    const btn = [...document.querySelectorAll('.el-table__body button')].find((b) => b.textContent.includes('标记完成'))
    btn?.click()
  })
  await d.page.waitForSelector('.el-message-box', { timeout: 10000 })
  await d.page.evaluate(() => {
    const btn = [...document.querySelectorAll('.el-message-box__btns button')].find((b) => b.textContent.includes('确定'))
    btn?.click()
  })
  await sleep(800)
  const patched = d.captured.find((c) => c.url === '/api/follow-ups/11')
  check('随访状态 PATCH 生效', patched?.body?.status === 'COMPLETED', JSON.stringify(patched?.body || {}))
  await d.page.close()

  // ---------- 2) 研究员：批量评估 ----------
  let taskSeq = 0
  const r = await loginAs('researcher', (path, method, req) => {
    const respond = (data) => req.respond({ status: 200, contentType: 'application/json', body: envelope(data) })
    if (path === '/api/assessment-tasks' && method === 'POST') {
      taskSeq += 1
      return respond({
        taskId: 100 + taskSeq,
        subjectId: 0,
        status: 'PENDING',
        runId: null,
        assessmentId: null,
        errorCode: null,
        message: null,
        createdAt: '2026-10-31T00:00:00Z',
        updatedAt: '2026-10-31T00:00:00Z',
        stages: []
      })
    }
    if (path.startsWith('/api/assessment-tasks/') && method === 'GET') {
      const id = Number(path.split('/').pop())
      return respond({
        taskId: id,
        subjectId: 0,
        status: 'COMPLETED',
        runId: 'run',
        assessmentId: 900 + id,
        errorCode: null,
        message: null,
        createdAt: '2026-10-31T00:00:00Z',
        updatedAt: '2026-10-31T00:00:02Z',
        stages: []
      })
    }
    return null
  })

  await r.page.goto(`${BASE}/subjects`, { waitUntil: 'domcontentloaded' })
  await r.page.waitForSelector('.el-table__body .el-table__row', { timeout: 20000 })
  // 勾选前两行
  await r.page.evaluate(() => {
    const boxes = document.querySelectorAll('.el-table__body .el-checkbox')
    boxes[0]?.click()
    boxes[1]?.click()
  })
  await sleep(300)
  const toolbarText = await r.page.evaluate(() => document.querySelector('.toolbar')?.textContent || '')
  check('研究员看到多选工具条', toolbarText.includes('已选 2'), toolbarText.trim().slice(0, 30))

  await r.page.evaluate(() => {
    const btn = [...document.querySelectorAll('.toolbar button')].find((b) => b.textContent.includes('批量发起评估'))
    btn?.click()
  })
  await r.page.waitForSelector('.batch-panel', { timeout: 10000 })
  check('进度面板出现', true, '')
  await r.page.waitForFunction(
    () => {
      const el = document.querySelector('.batch-panel')
      return !!el && (el.textContent.includes('2 / 2') || el.textContent.includes('2/2'))
    },
    { timeout: 20000 }
  )
  const panelText = await r.page.evaluate(() => document.querySelector('.batch-panel')?.textContent.replace(/\s+/g, ' '))
  check('批量任务全部完成', /完成 2/.test(panelText || ''), (panelText || '').slice(0, 60))
  check('创建了 2 个任务', taskSeq === 2, `taskSeq=${taskSeq}`)
  await r.page.screenshot({ path: join(shotDir, 'p5-batch-progress.png') })
  await r.page.close()

  // ---------- 3) 医生：待办复核 ----------
  let reviewed = false
  const rv = await loginAs('doctor', (path, method, req, captured) => {
    const respond = (data) => req.respond({ status: 200, contentType: 'application/json', body: envelope(data) })
    if (path === '/api/assessments/99' && method === 'GET') return respond(ASSESSMENT)
    if (path === '/api/assessments/99/review' && method === 'POST') {
      reviewed = true
      captured.push({ url: path, body: JSON.parse(req.postData() || '{}') })
      return respond({ id: 1, assessmentId: 99, decision: 'ACKNOWLEDGED', comment: '同意', reviewedAt: '2026-10-31T00:00:00Z' })
    }
    return null
  })

  await rv.page.goto(`${BASE}/reviews`, { waitUntil: 'domcontentloaded' })
  await rv.page.waitForSelector('.id-input input', { timeout: 15000 })
  await rv.page.type('.id-input input', '99')
  await rv.page.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === '载入')
    btn?.click()
  })
  await rv.page.waitForFunction(() => document.querySelectorAll('.el-table__body .el-table__row').length === 1, {
    timeout: 15000
  })
  check('按 ID 载入评估', true, 'row=1')
  await rv.page.evaluate(() => {
    const btn = [...document.querySelectorAll('.el-table__body button')].find((b) => b.textContent.includes('复核'))
    btn?.click()
  })
  await rv.page.waitForSelector('.el-drawer', { timeout: 10000 })
  await rv.page.waitForSelector('.el-drawer textarea', { timeout: 10000 })
  await rv.page.type('.el-drawer textarea', '已核对量表，同意该风险分层')
  await rv.page.evaluate(() => {
    const btn = [...document.querySelectorAll('.review-form__actions button')].find((b) => b.textContent.includes('确认复核'))
    btn?.click()
  })
  await sleep(900)
  check('复核提交成功', reviewed, '')
  await rv.page.screenshot({ path: join(shotDir, 'p5-review.png') })
  await rv.page.close()

  // ---------- 4) 管理员：账号管理骨架 ----------
  const a = await loginAs('admin', () => null)
  await a.page.goto(`${BASE}/admin/users`, { waitUntil: 'domcontentloaded' })
  await a.page.waitForSelector('.users-page .el-table', { timeout: 15000 })
  const usersText = await a.page.evaluate(() => document.querySelector('.users-page')?.textContent.replace(/\s+/g, ' '))
  check('账号管理页渲染骨架', /账号管理/.test(usersText || '') && /接口未接入/.test(usersText || ''), (usersText || '').slice(0, 50))
  await a.page.screenshot({ path: join(shotDir, 'p5-users.png') })
  await a.page.close()
} finally {
  await browser.close()
}

console.log(JSON.stringify({ ok: failed === 0, failed, checks }, null, 2))
process.exit(failed === 0 ? 0 : 1)
