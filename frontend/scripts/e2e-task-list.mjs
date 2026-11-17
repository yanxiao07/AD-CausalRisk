/**
 * 「评估任务」页的渲染回归测试。
 *
 * 页面已经改成"列表 + 详情抽屉"，这个脚本用真实浏览器覆盖四件事：
 *
 *   1) 初始态：列表分页渲染，行数/计数正确
 *   2) 乱序响应：先点任务 11（慢），再点任务 22（快），迟到的 11 不得覆盖 22
 *   3) 详情动作：抽屉里的「查看报告」指向上一次点击的那条任务
 *   4) 失败态：列表接口失败 → EmptyState 提示，不渲染表格行
 *
 * 前置：dev server 已启动（默认 http://127.0.0.1:5199）。
 *   npm run dev -- --port 5199
 *   node scripts/e2e-task-list.mjs
 */
import puppeteer from 'puppeteer-core'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const BASE = process.env.E2E_BASE || 'http://127.0.0.1:5199'
const CHROME =
  process.env.E2E_CHROME || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'

/**
 * 截图目录：用 fileURLToPath 而不是 URL.pathname。
 * pathname 在 Windows 上会给出 "/C:/Users/..."，直接传给 fs 会拼出错误路径；而且 .shots/
 * 是 gitignore 的，干净检出时并不存在，不先建目录就会在保存截图时 ENOENT。
 */
const shotDir = fileURLToPath(new URL('../.shots/', import.meta.url))
mkdirSync(shotDir, { recursive: true })
const shotPath = join(shotDir, 'task-list.png')

const env = (data) =>
  JSON.stringify({
    code: 0,
    message: 'ok',
    data,
    errorCode: null,
    traceId: 't',
    timestamp: new Date().toISOString()
  })

const failure = (status, message, errorCode) =>
  JSON.stringify({
    code: status,
    message,
    data: null,
    errorCode,
    traceId: 't',
    timestamp: new Date().toISOString()
  })

const researcher = {
  id: 3,
  username: 'researcher',
  roles: ['RESEARCHER'],
  menus: ['graph', 'imports', 'quality', 'reports', 'subjects', 'tasks']
}

const taskRows = [
  {
    taskId: 11,
    subjectId: 41,
    status: 'COMPLETED',
    runId: 'run-11',
    assessmentId: 11,
    errorCode: null,
    message: null,
    createdAt: '2026-11-06T02:00:00Z',
    updatedAt: '2026-11-06T02:00:05Z'
  },
  {
    taskId: 22,
    subjectId: 42,
    status: 'COMPLETED',
    runId: 'run-22',
    assessmentId: 22,
    errorCode: null,
    message: null,
    createdAt: '2026-11-06T01:00:00Z',
    updatedAt: '2026-11-06T01:00:05Z'
  }
]

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

/** 列表接口是否返回失败；乱序用例需要它保持成功。 */
let listMode = 'ok'
/** 任务 11 的详情响应延迟（ms），用来制造乱序。 */
const SLOW_TASK_ID = 11
const SLOW_DELAY_MS = 1200

await page.setRequestInterception(true)
page.on('request', async (request) => {
  const url = new URL(request.url())
  const path = url.pathname
  const json = (body) => request.respond({ status: 200, contentType: 'application/json', body })

  if (request.url().includes('cdn.jsdelivr')) return request.abort()
  if (path === '/api/auth/login' || path === '/api/me') return json(env(researcher))

  if (path === '/api/assessment-tasks' && url.search) {
    if (listMode === 'fail') {
      return request.respond({
        status: 500,
        contentType: 'application/json',
        body: failure(500, '服务暂不可用，请稍后重试', 'INTERNAL_ERROR')
      })
    }
    return json(
      env({ items: taskRows, page: 1, pageSize: 20, total: taskRows.length })
    )
  }

  const taskMatch = path.match(/^\/api\/assessment-tasks\/(\d+)$/)
  if (taskMatch) {
    const id = Number(taskMatch[1])
    if (id === SLOW_TASK_ID) await new Promise((r) => setTimeout(r, SLOW_DELAY_MS))
    return json(
      env({
        taskId: id,
        subjectId: 40 + id,
        status: 'COMPLETED',
        runId: `run-${id}`,
        assessmentId: id,
        errorCode: null,
        message: null,
        createdAt: '2026-11-06T02:00:00Z',
        updatedAt: '2026-11-06T02:00:05Z',
        stages: [
          { stage: 'DATA_FUSION', status: 'COMPLETED', durationMs: 420, summary: { rows: 3 } },
          { stage: 'EXPLANATION', status: 'COMPLETED', durationMs: 610, summary: {} }
        ]
      })
    )
  }

  if (path === '/api/subjects') {
    return json(env({ items: [], page: 1, pageSize: 20, total: 0 }))
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
    rows: document.querySelectorAll('.task-page .el-table__body .el-table__row').length,
    count: document.querySelector('.task-page .filter__count')?.textContent?.trim() || '',
    empty: document.querySelector('.task-page .empty-state')?.textContent?.replace(/\s+/g, ' ').trim() || '',
    drawerText: document.querySelector('.el-drawer')?.textContent?.replace(/\s+/g, ' ').trim() || '',
    drawerOpen: Boolean(document.querySelector('.el-drawer')),
    bodyText: (document.querySelector('.task-page')?.textContent || '').replace(/\s+/g, ' ')
  }))

try {
  await login()

  // ---------- 1. 列表初始态 ----------
  await page.goto(`${BASE}/tasks`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.task-page .el-table__body .el-table__row', { timeout: 20000 })
  const initial = await snapshot()
  check('列表渲染两行任务', initial.rows === 2, `rows=${initial.rows}`)
  check('分页计数正确', initial.count.includes('共 2 条'), initial.count)
  check('列表含状态标签', initial.bodyText.includes('已完成'), initial.bodyText.slice(0, 80))

  // ---------- 2. 乱序响应：慢的 11 不能覆盖快的 22 ----------
  await page.evaluate(() =>
    document.querySelectorAll('.task-page .el-table__body .el-table__row')[0].click()
  )
  await new Promise((r) => setTimeout(r, 150))
  await page.evaluate(() =>
    document.querySelectorAll('.task-page .el-table__body .el-table__row')[1].click()
  )
  await page.waitForFunction(
    () => document.querySelector('.el-drawer')?.textContent?.includes('任务 #22'),
    { timeout: 15000 }
  )
  // 等慢响应（1200ms）回来，确认它没有把 22 覆盖成 11
  await new Promise((r) => setTimeout(r, SLOW_DELAY_MS + 700))
  const afterLate = await snapshot()
  check(
    '迟到的任务 11 不覆盖任务 22',
    afterLate.drawerText.includes('任务 #22') && afterLate.drawerText.includes('run-22'),
    afterLate.drawerText.slice(0, 80)
  )
  check(
    '抽屉内容仍指向 22',
    !afterLate.drawerText.includes('run-11') && !afterLate.drawerText.includes('任务 #11'),
    afterLate.drawerText.slice(0, 80)
  )

  // ---------- 3. 详情动作指向当前任务 ----------
  await page.evaluate(() => {
    const button = [...document.querySelectorAll('.el-drawer button')].find((b) =>
      b.textContent.includes('查看报告')
    )
    button?.click()
  })
  // 等路由真正切到 /reports/22 再断言，否则紧随其后的 goto('/tasks') 会和这次导航打架
  await page
    .waitForFunction(() => location.pathname === '/reports/22', { timeout: 10000 })
    .catch(() => {})
  const url = new URL(page.url())
  check(
    '查看报告跳转到 /reports/22',
    url.pathname === '/reports/22',
    `${url.pathname}${url.search}`
  )

  // ---------- 4. 列表失败态 ----------
  listMode = 'fail'
  await page.goto(`${BASE}/tasks`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.task-page .empty-state', { timeout: 20000 })
  const failed = await snapshot()
  check('失败态显示错误空态', failed.empty.includes('任务列表加载失败'), failed.empty.slice(0, 60))
  check('失败态不渲染表格行', failed.rows === 0, `rows=${failed.rows}`)
  check('失败态提供重试入口', failed.bodyText.includes('重试'), failed.bodyText.slice(0, 60))

  try {
    await page.screenshot({ path: shotPath })
    check('截图已保存', true, shotPath)
  } catch (error) {
    check('截图已保存', false, String(error.message).slice(0, 80))
  }
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
