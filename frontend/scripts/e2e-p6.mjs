/**
 * P6 浏览器冒烟：评估报告（含打印）+ 因果图谱大屏。
 * 接口用桩响应（不依赖 8080）。
 *
 *   node scripts/e2e-p6.mjs   （需先 npm run dev -- --port 5199）
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

const DOCTOR = { id: 4, username: 'doctor', roles: ['DOCTOR'], menus: ['graph', 'reports', 'subjects', 'reviews'] }

const SUBJECT = {
  id: 1,
  subjectCode: 'DEMO-001',
  alias: '张样本',
  status: 'ACTIVE',
  dataVersion: 'demo-2026',
  createdAt: '2026-10-01T00:00:00Z'
}

const ASSESSMENT = {
  assessmentId: 99,
  subjectId: 1,
  taskId: 7,
  reviewStatus: 'ACKNOWLEDGED',
  createdAt: '2026-10-30T12:00:00Z',
  agentResult: {
    runId: 'run-99',
    dataVersion: 'demo-2026',
    status: 'COMPLETED',
    contractVersion: 'deepagent-v1',
    workflowVersion: 'causalrisk-workflow-v1',
    modelVersion: 'deepagent-causal-warning-v1',
    qualityStatus: 'PASS',
    riskScore: 0.72,
    riskLevel: 'HIGH',
    predictionWindowMonths: 24,
    warnings: ['影像为半年前测量'],
    limitations: ['本结果仅用于科研辅助'],
    explanations: [
      { feature: 'apoe', label: 'APOE ε4 携带', message: '风险升高', source: 'model', interpretation: '携带 ε4 与 24 个月风险升高相关。', direction: 'UP' }
    ],
    graph: {
      nodes: [
        { id: 'a', label: 'APOE ε4', status: 'ACTIVE', evidenceLevel: 'MODEL', observedAt: '2026-01-01' },
        { id: 'b', label: 'MMSE', status: 'ACTIVE', evidenceLevel: 'MODEL', observedAt: '2026-09-01' },
        { id: 'c', label: '海马体积', status: 'MISSING', evidenceLevel: 'MODEL', observedAt: null },
        { id: 'd', label: 'AD 风险', status: 'ACTIVE', evidenceLevel: 'PRIOR', observedAt: null }
      ],
      edges: [
        { from: 'a', to: 'd', note: '研究先验证据', status: 'ACTIVE', evidenceLevel: 'PRIOR', direction: 'CONDITIONAL' },
        { from: 'b', to: 'd', note: '模型归因', status: 'ACTIVE', evidenceLevel: 'PRIOR', direction: 'CONDITIONAL' },
        { from: 'c', to: 'd', note: '数据缺失', status: 'MISSING', evidenceLevel: 'PRIOR', direction: 'CONDITIONAL' }
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

try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1600, height: 1100 })
  const errors = []
  page.on('pageerror', (e) => errors.push(String(e).slice(0, 160)))

  await page.setRequestInterception(true)
  page.on('request', (req) => {
    const path = pathOf(req.url())
    const respond = (data) =>
      req.respond({ status: 200, contentType: 'application/json', body: envelope(data) })
    if (req.url().includes('cdn.jsdelivr.net')) return req.abort()
    if (path === '/api/auth/login' || path === '/api/me') return respond(DOCTOR)
    if (path === '/api/subjects') return respond({ items: [SUBJECT], page: 1, pageSize: 20, total: 1 })
    if (path === '/api/subjects/1') return respond({ subject: SUBJECT, visits: [], features: [] })
    if (path === '/api/reports/99') return respond(ASSESSMENT)
    if (path === '/api/assessments/99') return respond(ASSESSMENT)
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

  // ---------- 评估报告 ----------
  await page.goto(`${BASE}/reports?assessmentId=99`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.sheet', { timeout: 20000 })

  const reportText = await page.evaluate(() => document.querySelector('.sheet').textContent.replace(/\s+/g, ' '))
  check('报告标题渲染', reportText.includes('个体化风险评估报告'), '')
  check('风险分渲染 72.0%', reportText.includes('72.0%'), '')
  check('受试者信息来自档案接口', reportText.includes('张样本') && reportText.includes('DEMO-001'), '')
  check('关键解释渲染', reportText.includes('APOE ε4 携带'), '')
  check('免责声明存在', reportText.includes('不构成诊断'), '')

  const canvas = await page.evaluate(() => document.querySelectorAll('.sheet .causal-graph canvas').length)
  check('报告内嵌因果图', canvas === 1, `canvas=${canvas}`)

  const printCall = await page.evaluate(() => {
    let called = false
    const original = window.print
    window.print = () => {
      called = true
    }
    const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.includes('打印报告'))
    btn?.click()
    window.print = original
    return called
  })
  check('打印按钮调用 window.print()', printCall === true, '')
  await page.screenshot({ path: join(shotDir, 'p6-report.png'), fullPage: true })

  // ---------- 因果图谱 ----------
  await page.goto(`${BASE}/graph`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.id-input input', { timeout: 15000 })
  await page.type('.id-input input', '99')
  await page.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === '载入图谱')
    btn?.click()
  })
  await page.waitForSelector('.stage', { timeout: 20000 })
  await page.waitForFunction(() => document.querySelectorAll('.stage .causal-graph canvas').length === 1, {
    timeout: 20000
  })
  const stageText = await page.evaluate(() => document.querySelector('.stage').textContent.replace(/\s+/g, ' '))
  check('图谱页显示节点/边统计', stageText.includes('4 个节点') && stageText.includes('3 条关联'), stageText.slice(0, 60))
  check('图谱 canvas 渲染', true, 'canvas=1')

  await page.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.includes('沉浸查看'))
    btn?.click()
  })
  await sleep(600)
  const immersive = await page.evaluate(() =>
    document.querySelector('.stage')?.classList.contains('stage--immersive')
  )
  check('沉浸模式切换', immersive === true, '')
  await page.screenshot({ path: join(shotDir, 'p6-graph.png') })

  check('无页面异常', errors.length === 0, errors[0] || 'none')
} finally {
  await browser.close()
}

console.log(JSON.stringify({ ok: failed === 0, failed, checks }, null, 2))
process.exit(failed === 0 ? 0 : 1)
