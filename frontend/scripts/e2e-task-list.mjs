/**
 * 「评估任务」页的渲染回归测试。
 *
 * 背景：TaskList 里 DataCard 曾经用 `v-else` 挂在 EmptyState 的 `v-if` 上，
 * 于是"查询失败（task 为 null 且 loadError 非空）"也会进 DataCard 分支，
 * 渲染期访问 `task.taskId` 直接抛错 —— 页面白屏。
 * 这个脚本用真实浏览器把三种状态都跑一遍：
 *
 *   1) 初始态：没有任何查询 → 空态，且不渲染 DataCard
 *   2) 失败态：后端 400（任务不存在）→ 只渲染错误提示，绝不渲染 DataCard
 *   3) 成功态：后端 200 → 渲染任务卡 + 阶段表
 *
 * 前置：dev server 已启动（默认 http://127.0.0.1:5199）。
 *   npm run dev -- --port 5199
 *   node scripts/e2e-task-list.mjs
 */
import puppeteer from 'puppeteer-core'

const BASE = process.env.E2E_BASE || 'http://127.0.0.1:5199'
const CHROME =
  process.env.E2E_CHROME || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'

const env = (data) =>
  JSON.stringify({
    code: 0,
    message: 'ok',
    data,
    errorCode: null,
    traceId: 't',
    timestamp: new Date().toISOString()
  })

const badRequest = (message) =>
  JSON.stringify({
    code: 400,
    message,
    data: null,
    errorCode: 'VALIDATION_ERROR',
    traceId: 't',
    timestamp: new Date().toISOString()
  })

const researcher = {
  id: 3,
  username: 'researcher',
  roles: ['RESEARCHER'],
  menus: ['graph', 'imports', 'quality', 'reports', 'subjects', 'tasks']
}

const results = []
const pageErrors = []

function check(name, ok, detail) {
  results.push({ name, ok: Boolean(ok), detail })
}

async function serverIsUp() {
  try {
    const response = await fetch(BASE, { method: 'GET' })
    return response.ok || response.status === 404
  } catch {
    return false
  }
}

if (!(await serverIsUp())) {
  console.error(
    `\n[e2e-task-list] 无法连接 ${BASE}\n` +
      '先启动 dev server：npm run dev -- --port 5199\n'
  )
  process.exit(1)
}

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
  defaultViewport: { width: 1440, height: 900 }
})

const page = await browser.newPage()
page.on('pageerror', (error) => pageErrors.push(String(error).slice(0, 200)))

await page.setRequestInterception(true)
page.on('request', (request) => {
  const path = new URL(request.url()).pathname
  const json = (body) => request.respond({ status: 200, contentType: 'application/json', body })

  if (request.url().includes('cdn.jsdelivr')) return request.abort()
  if (path === '/api/auth/login' || path === '/api/me') return json(env(researcher))

  const taskMatch = path.match(/^\/api\/assessment-tasks\/(\d+)$/)
  if (taskMatch) {
    const id = Number(taskMatch[1])
    if (id === 999) {
      return request.respond({
        status: 400,
        contentType: 'application/json',
        body: badRequest('任务不存在')
      })
    }
    return json(
      env({
        taskId: id,
        subjectId: 42,
        status: 'COMPLETED',
        runId: `run-${id}`,
        assessmentId: id,
        createdAt: '2026-10-30T10:00:00Z',
        updatedAt: '2026-10-30T10:00:03Z',
        stages: [
          { stage: 'DATA_FUSION', status: 'COMPLETED', durationMs: 420, summary: { rows: 3 } },
          { stage: 'CAUSAL_GRAPH', status: 'COMPLETED', durationMs: 610, summary: {} }
        ]
      })
    )
  }

  return request.continue()
})

async function login() {
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  await page.evaluate(() => localStorage.clear())
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('button[data-role="researcher"]', { timeout: 15000 })
  await page.click('button[data-role="researcher"]')
  await page.waitForSelector('#username', { visible: true, timeout: 15000 })
  await page.type('#username', 'researcher')
  await page.type('#password', 'DemoPass!2026')
  await Promise.all([
    page.waitForFunction(() => location.pathname !== '/login', { timeout: 20000 }),
    page.click('#submit-btn')
  ])
}

const snapshot = () =>
  page.evaluate(() => ({
    hasPage: Boolean(document.querySelector('.task-page')),
    cards: document.querySelectorAll('.task-page .data-card').length,
    cardTitle: document.querySelector('.task-page .data-card__title')?.textContent?.trim() || '',
    errorAlert: document.querySelector('.task-page .el-alert--error')?.textContent?.replace(/\s+/g, ' ').trim() || '',
    emptyState: document.querySelector('.task-page .empty-state')?.textContent?.replace(/\s+/g, ' ').trim() || '',
    stageRows: document.querySelectorAll('.task-page .el-table__body .el-table__row').length,
    bodyText: (document.querySelector('.task-page')?.textContent || '').replace(/\s+/g, ' ')
  }))

try {
  await login()

  // ---------- 1. 初始态 ----------
  await page.goto(`${BASE}/tasks`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.task-page', { timeout: 15000 })
  await new Promise((r) => setTimeout(r, 600))
  const initial = await snapshot()
  check('初始态渲染空态', initial.emptyState.includes('还没有查询任务'), initial.emptyState.slice(0, 40))
  check('初始态不渲染 DataCard', initial.cards === 0, `cards=${initial.cards}`)

  // ---------- 2. 失败态（核心回归） ----------
  await page.goto(`${BASE}/tasks?taskId=999`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.task-page', { timeout: 15000 })
  await page.waitForFunction(
    () => Boolean(document.querySelector('.task-page .el-alert--error')),
    { timeout: 15000 }
  )
  await new Promise((r) => setTimeout(r, 500))
  const failed = await snapshot()
  check(
    '失败态渲染错误提示',
    failed.errorAlert.includes('任务载入失败') && failed.errorAlert.includes('任务不存在'),
    failed.errorAlert.slice(0, 70)
  )
  check('失败态不渲染 DataCard（本次修复点）', failed.cards === 0, `cards=${failed.cards}`)
  check('失败态不出现任务卡标题', !/任务 #999/.test(failed.bodyText), failed.bodyText.slice(0, 60))
  check('失败态页面未白屏', failed.hasPage && failed.bodyText.length > 20, `len=${failed.bodyText.length}`)

  await page.screenshot({ path: new URL('../.shots/task-list-failed.png', import.meta.url).pathname.replace(/^\//, '') })

  // ---------- 3. 成功态 ----------
  await page.goto(`${BASE}/tasks?taskId=7`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.task-page .data-card', { timeout: 15000 })
  await new Promise((r) => setTimeout(r, 500))
  const ok = await snapshot()
  check('成功态渲染任务卡', ok.cards === 1 && ok.cardTitle === '任务 #7', `cards=${ok.cards} title=${ok.cardTitle}`)
  check('成功态渲染阶段表', ok.stageRows === 2, `rows=${ok.stageRows}`)
  check('成功态无错误提示', ok.errorAlert === '', ok.errorAlert.slice(0, 40))
} finally {
  await browser.close()
}

check('全程无页面异常', pageErrors.length === 0, pageErrors[0] || 'none')

const failedChecks = results.filter((item) => !item.ok)
console.log(JSON.stringify({ base: BASE, results, pageErrors }, null, 2))

if (failedChecks.length) {
  console.error(`\n[e2e-task-list] ${failedChecks.length} 项失败\n`)
  process.exit(1)
}
console.log('\n[e2e-task-list] 全部通过\n')
