/**
 * P3 浏览器冒烟：发起评估 → 轮询 → 结果（分数/等级/因果图）→ 人工复核 → 历史访视。
 *
 * 全部接口用桩响应（不依赖 8080 / Python），验证前端状态机与渲染。
 *
 * 运行：
 *   npm i --no-save puppeteer-core
 *   npm run dev -- --port 5199
 *   node scripts/e2e-p3.mjs
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

const DOCTOR = {
  id: 4,
  username: 'doctor',
  roles: ['DOCTOR'],
  menus: ['followups', 'graph', 'reports', 'reviews', 'subjects']
}

const SUBJECT = {
  id: 1,
  subjectCode: 'DEMO-001',
  alias: '张样本',
  status: 'ACTIVE',
  dataVersion: 'demo-2026-01',
  createdAt: '2026-10-01T09:00:00Z'
}

const DETAIL = {
  subject: SUBJECT,
  features: [],
  visits: [
    { id: 1, subjectId: 1, visitCode: 'V1', visitDate: '2026-03-01', mmse: 26, moca: 22, cdrSb: 1.5 },
    { id: 2, subjectId: 1, visitCode: 'V2', visitDate: '2026-09-01', mmse: 23, moca: null, cdrSb: 3 }
  ]
}

const AGENT_RESULT = {
  runId: 'run-e2e',
  currentStage: 'EXPLANATION',
  dataVersion: 'demo-2026-01',
  status: 'COMPLETED',
  contractVersion: 'deepagent-v1',
  workflowVersion: 'causalrisk-workflow-v1',
  modelVersion: 'deepagent-causal-warning-v1',
  qualityStatus: 'PASS',
  riskScore: 0.8304,
  riskLevel: 'HIGH',
  warnings: ['影像指标为半年前的测量值'],
  graph: {
    nodes: [
      { id: 'apoe', label: 'APOE ε4', status: 'ACTIVE', evidenceLevel: 'MODEL', observedAt: '2026-01-05' },
      { id: 'mmse', label: 'MMSE', status: 'ACTIVE', evidenceLevel: 'MODEL', observedAt: '2026-09-01' },
      { id: 'mri', label: '海马体积', status: 'MISSING', evidenceLevel: 'MODEL', observedAt: null },
      { id: 'risk', label: 'AD 风险', status: 'ACTIVE', evidenceLevel: 'PRIOR', observedAt: null }
    ],
    edges: [
      { from: 'apoe', to: 'risk', note: '先验关联', status: 'ACTIVE', evidenceLevel: 'PRIOR', direction: 'CONDITIONAL' },
      { from: 'mmse', to: 'risk', note: '模型归因', status: 'ACTIVE', evidenceLevel: 'PRIOR', direction: 'CONDITIONAL' },
      { from: 'mri', to: 'risk', note: '数据缺失', status: 'MISSING', evidenceLevel: 'PRIOR', direction: 'CONDITIONAL' }
    ]
  },
  explanations: [
    { feature: 'apoe', label: 'APOE ε4 携带', message: '风险升高', source: 'model', interpretation: '携带 ε4 与 24 个月内风险升高相关。', direction: 'UP' }
  ],
  limitations: ['本结果仅用于科研辅助，不构成诊断。']
}

const ASSESSMENT = {
  assessmentId: 99,
  subjectId: 1,
  taskId: 7,
  agentResult: AGENT_RESULT,
  reviewStatus: 'PENDING',
  createdAt: '2026-10-30T12:00:00Z'
}

const checks = []
let failed = 0
const check = (name, ok, detail) => {
  checks.push({ name, ok, detail })
  if (!ok) failed += 1
}

let pollCount = 0

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage']
})

try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1600, height: 1000 })
  const pageErrors = []
  page.on('pageerror', (err) => pageErrors.push(String(err).slice(0, 160)))

  await page.setRequestInterception(true)
  page.on('request', (req) => {
    const path = pathOf(req.url())
    const method = req.method()
    const respond = (data) =>
      req.respond({ status: 200, contentType: 'application/json', body: envelope(data) })

    if (req.url().includes('cdn.jsdelivr.net')) return req.abort()
    if (path === '/api/auth/login' || path === '/api/me') return respond(DOCTOR)
    if (path === '/api/subjects' && method === 'GET') {
      return respond({ items: [SUBJECT], page: 1, pageSize: 20, total: 1 })
    }
    if (path === '/api/subjects/1') return respond(DETAIL)
    if (path === '/api/assessment-tasks' && method === 'POST') {
      return respond({
        taskId: 7,
        subjectId: 1,
        status: 'PENDING',
        runId: null,
        assessmentId: null,
        errorCode: null,
        message: null,
        createdAt: '2026-10-30T12:00:00Z',
        updatedAt: '2026-10-30T12:00:00Z',
        stages: []
      })
    }
    if (path === '/api/assessment-tasks/7') {
      pollCount += 1
      const done = pollCount >= 2
      return respond({
        taskId: 7,
        subjectId: 1,
        status: done ? 'COMPLETED' : 'RUNNING',
        runId: 'run-e2e',
        assessmentId: done ? 99 : null,
        errorCode: null,
        message: null,
        createdAt: '2026-10-30T12:00:00Z',
        updatedAt: '2026-10-30T12:00:02Z',
        stages: done
          ? [
              { stage: 'DATA_FUSION', status: 'COMPLETED', durationMs: 420, summary: {} },
              { stage: 'CAUSAL_GRAPH', status: 'COMPLETED', durationMs: 610, summary: {} },
              { stage: 'WARNING_MODEL', status: 'COMPLETED', durationMs: 300, summary: {} },
              { stage: 'EXPLANATION', status: 'COMPLETED', durationMs: 180, summary: {} }
            ]
          : [{ stage: 'DATA_FUSION', status: 'COMPLETED', durationMs: 420, summary: {} }]
      })
    }
    if (path === '/api/assessments/99') return respond(ASSESSMENT)
    if (path === '/api/assessments/99/review' && method === 'POST') {
      return respond({
        id: 5,
        assessmentId: 99,
        decision: 'ACKNOWLEDGED',
        comment: '同意风险分层',
        reviewedAt: '2026-10-30T12:05:00Z'
      })
    }
    return req.continue()
  })

  // 登录
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

  // 进入档案列表并打开抽屉
  await page.goto(`${BASE}/subjects`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.el-table__row', { timeout: 15000 })
  await page.click('.el-table__row')
  await page.waitForSelector('.assessment', { timeout: 15000 })
  check('抽屉含评估面板', true, '')

  // 访视表格
  await page.waitForFunction(() => document.querySelectorAll('.visits .el-table__row').length === 2, {
    timeout: 15000
  })
  check('访视表格 2 行', true, '')

  // 发起评估
  await page.evaluate(() => {
    const btn = [...document.querySelectorAll('.assessment button')].find((b) =>
      b.textContent.includes('发起风险预测')
    )
    btn?.click()
  })

  // 轮询中：应看到"正在计算"
  await page.waitForFunction(() => document.body.textContent.includes('正在计算'), { timeout: 15000 })
  check('进入轮询态', true, '')
  await page.screenshot({ path: join(shotDir, 'p3-running.png') })

  // 完成：分数 + 等级 + 因果图 canvas
  await page.waitForFunction(() => document.body.textContent.includes('83.0%'), { timeout: 30000 })
  check('风险概率渲染 83.0%', true, '')

  const levelText = await page.evaluate(
    () => document.querySelector('.score__side')?.textContent.replace(/\s+/g, ' ') || ''
  )
  check('风险等级标签', /高风险|HIGH/.test(levelText), levelText.slice(0, 40))

  const canvasCount = await page.evaluate(
    () => document.querySelectorAll('.causal-graph__canvas canvas').length
  )
  check('因果图 canvas 已渲染', canvasCount > 0, `canvas=${canvasCount}`)
  await page.screenshot({ path: join(shotDir, 'p3-result.png'), fullPage: true })

  // 复核
  await page.evaluate(() => {
    const btn = [...document.querySelectorAll('.review button')].find((b) =>
      b.textContent.includes('确认复核')
    )
    btn?.click()
  })
  await page.waitForSelector('.el-message-box__input textarea', { timeout: 10000 })
  await page.type('.el-message-box__input textarea', '已核对量表与影像，同意该风险分层。')
  await page.evaluate(() => {
    const btn = [...document.querySelectorAll('.el-message-box__btns button')].find((b) =>
      b.textContent.includes('提交')
    )
    btn?.click()
  })
  await page.waitForFunction(
    () => document.body.textContent.includes('该评估已复核') || document.body.textContent.includes('已确认'),
    { timeout: 15000 }
  )
  const reviewText = await page.evaluate(
    () => document.querySelector('.review')?.textContent.replace(/\s+/g, ' ') || ''
  )
  check('复核后状态更新', /已确认|已复核/.test(reviewText), reviewText.slice(0, 40))
  await page.screenshot({ path: join(shotDir, 'p3-reviewed.png') })

  check('无页面异常', pageErrors.length === 0, pageErrors[0] || 'none')
} finally {
  await browser.close()
}

console.log(JSON.stringify({ ok: failed === 0, failed, checks }, null, 2))
process.exit(failed === 0 ? 0 : 1)
