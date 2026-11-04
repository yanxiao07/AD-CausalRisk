/**
 * 真实联调自测 v2（不打桩）：V9 迁移之后，医生端为主链路。
 *
 * 覆盖：
 *  医生   档案(100) → 发起评估 → 轮询出结果 → 确认复核 → 待办复核页 → 随访新建/完成
 *  研究员 CSV 预检 → 确认入库 → 质控页通过一条
 *  管理员 大屏指标 + 审计含复核
 *  患者   真实绑定档案 + 大字号 + 联系医生
 *  全局   HTTP>=400 / pageerror / console.error 全量收集（favicon 必须消失）
 *
 *   node scripts/e2e-live.mjs
 */
import puppeteer from 'puppeteer-core'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const BASE = process.env.E2E_BASE || 'http://127.0.0.1:5173'
const CHROME =
  process.env.E2E_CHROME || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const shotDir = join(root, '.shots')
mkdirSync(shotDir, { recursive: true })
mkdirSync(join(root, '.tmp'), { recursive: true })

const RUN_TAG = String(Date.now()).slice(-6)
const CSV = join(root, '.tmp', 'e2e-visits-v2.csv')
writeFileSync(
  CSV,
  ['subjectCode,visitCode,visitDate,mmse,moca,cdrSb', `DEMO-004,E2E${RUN_TAG},2026-10-03,27,24,1.0`, ''].join('\n'),
  'utf8'
)

const steps = []
const httpErrors = []
const consoleErrors = []
const pageErrors = []
let assessmentId = null

const record = (step, ok, detail) => steps.push({ step, ok, detail: String(detail ?? '') })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function phase(name, fn) {
  try {
    await fn()
  } catch (err) {
    record(name, false, `中断：${String(err?.message || err).slice(0, 180)}`)
  }
}

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
  defaultViewport: { width: 1600, height: 1000 }
})

async function open(role) {
  const page = await browser.newPage()
  page.on('pageerror', (e) => pageErrors.push({ role, message: String(e).slice(0, 240) }))
  page.on('console', (m) => {
    if (m.type() !== 'error') return
    consoleErrors.push({ role, text: m.text().slice(0, 240) })
  })
  page.on('response', async (res) => {
    const match = res.url().match(/\/api\/assessments\/(\d+)$/)
    if (match && res.status() === 200) assessmentId = Number(match[1])
    if (res.status() < 400) return
    let body = ''
    try {
      body = (await res.text()).slice(0, 200)
    } catch {
      body = '(unreadable)'
    }
    httpErrors.push({ role, status: res.status(), url: res.url().replace(BASE, ''), body })
  })
  return page
}

async function login(page, role) {
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  await page.evaluate(() => localStorage.clear())
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector(`button[data-role="${role}"]`, { timeout: 20000 })
  await page.click(`button[data-role="${role}"]`)
  await page.waitForSelector('#username', { visible: true, timeout: 15000 })
  await page.type('#username', role)
  await page.type('#password', 'DemoPass!2026')
  await Promise.all([
    page.waitForFunction(() => location.pathname !== '/login', { timeout: 25000 }),
    page.click('#submit-btn')
  ])
}

async function clickByText(page, selector, text) {
  return page.evaluate(
    (sel, label) => {
      const el = [...document.querySelectorAll(sel)].find((n) => n.textContent.includes(label))
      if (!el) return false
      el.click()
      return true
    },
    selector,
    text
  )
}

try {
  // ================= 医生端 =================
  let doctorPage = null
  await phase('医生-评估链路', async () => {
    const page = await open('doctor')
    doctorPage = page
    await login(page, 'doctor')

    await page.goto(`${BASE}/subjects`, { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('.patient-list .el-table__body .el-table__row', { timeout: 30000 })
    await sleep(1500)
    const list = await page.evaluate(() => ({
      rows: document.querySelectorAll('.patient-list .el-table__body .el-table__row').length,
      pager: document.querySelector('.el-pagination')?.textContent?.replace(/\s+/g, ' ') || ''
    }))
    record('医生-档案列表', list.rows > 0, `rows=${list.rows} pager="${list.pager}"`)
    await page.screenshot({ path: join(shotDir, 'live2-doctor-subjects.png') })

    await page.click('.patient-list .el-table__body .el-table__row')
    await page.waitForSelector('.assessment', { timeout: 20000 })
    await sleep(1500)

    await clickByText(page, '.assessment button', '发起风险预测')

    await page.waitForFunction(
      () => !!document.querySelector('.score__value') || document.body.textContent.includes('评估未产生结果'),
      { timeout: 200000 }
    )
    await sleep(2000)
    const result = await page.evaluate(() => ({
      score: document.querySelector('.score__value')?.textContent?.trim() || '',
      side: document.querySelector('.score__side')?.textContent?.replace(/\s+/g, ' ').trim() || '',
      failed: document.body.textContent.includes('评估未产生结果'),
      canvas: document.querySelectorAll('.causal-graph canvas').length
    }))
    record(
      '医生-发起评估出结果',
      Boolean(result.score) && !result.failed,
      `score=${result.score} side=${result.side.slice(0, 40)} canvas=${result.canvas}`
    )
    record('医生-取得 assessmentId', Boolean(assessmentId), String(assessmentId))
    await page.screenshot({ path: join(shotDir, 'live2-doctor-assess.png') })

    await clickByText(page, '.review button', '确认复核')
    await page.waitForSelector('.el-message-box__input textarea', { timeout: 15000 })
    await page.type('.el-message-box__input textarea', '真实联调 v2：已核对量表与影像，同意该风险分层。')
    await clickByText(page, '.el-message-box__btns button', '提交')
    await sleep(2500)
    const review = await page.evaluate(
      () => document.querySelector('.review')?.textContent?.replace(/\s+/g, ' ') || ''
    )
    record('医生-确认复核', /已复核|已确认/.test(review), review.slice(0, 60))
  })

  await phase('医生-待办复核页', async () => {
    const page = doctorPage
    await page.goto(`${BASE}/reviews?assessmentId=${assessmentId ?? ''}`, { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('.review-page', { timeout: 20000 })
    await sleep(2500)
    const state = await page.evaluate(() => ({
      path: location.pathname,
      rows: document.querySelectorAll('.review-page .el-table__body .el-table__row').length,
      body: document.querySelector('.review-page .el-table__body')?.textContent?.replace(/\s+/g, ' ').trim() || ''
    }))
    record('医生-待办复核页载入', state.rows > 0, `path=${state.path} rows=${state.rows} :: ${state.body.slice(0, 70)}`)
    await page.screenshot({ path: join(shotDir, 'live2-doctor-reviews.png') })
  })

  await phase('医生-报告与图谱', async () => {
    const page = doctorPage
    await page.goto(`${BASE}/reports?assessmentId=${assessmentId ?? ''}`, { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('.sheet', { timeout: 25000 })
    await sleep(1500)
    const report = await page.evaluate(() => ({
      score: document.querySelector('.verdict__value')?.textContent?.trim() || '',
      canvas: document.querySelectorAll('.sheet canvas').length,
      hasDisclaimer: document.body.textContent.includes('不构成诊断')
    }))
    record(
      '医生-评估报告页',
      Boolean(report.score) && report.canvas === 1 && report.hasDisclaimer,
      `score=${report.score} canvas=${report.canvas} disclaimer=${report.hasDisclaimer}`
    )
    await page.screenshot({ path: join(shotDir, 'live2-doctor-report.png'), fullPage: true })

    await page.goto(`${BASE}/graph?assessmentId=${assessmentId ?? ''}`, { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('.stage', { timeout: 25000 })
    await page.waitForFunction(() => document.querySelectorAll('.stage canvas').length === 1, { timeout: 20000 })
    const graphState = await page.evaluate(() => ({
      meta: document.querySelector('.stage__meta')?.textContent?.replace(/\s+/g, ' ').trim() || '',
      canvas: document.querySelectorAll('.stage canvas').length
    }))
    record('医生-因果图谱页', graphState.canvas === 1, `canvas=${graphState.canvas} meta="${graphState.meta.slice(0, 50)}"`)
    await page.screenshot({ path: join(shotDir, 'live2-doctor-graph.png') })
  })
  await phase('医生-随访', async () => {
    const page = doctorPage
    await page.goto(`${BASE}/followups`, { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('.followup-page .picker__select', { timeout: 20000 })
    await page.click('.followup-page .picker__select')
    await page.waitForSelector('.el-select-dropdown__item', { timeout: 15000 })
    const options = await page.evaluate(
      () => document.querySelectorAll('.el-select-dropdown__item').length
    )
    record('医生-随访页可选受试者', options > 0, `下拉选项=${options}`)
    await page.evaluate(() => document.querySelectorAll('.el-select-dropdown__item')[0]?.click())
    await sleep(1800)

    await clickByText(page, 'button', '新建随访')
    await page.waitForSelector('.el-dialog', { timeout: 15000 })
    const dateInput = await page.$('.el-dialog .el-date-editor input')
    await dateInput.click()
    await dateInput.type('2026-12-01 09:30')
    await page.keyboard.press('Enter')
    await page.type('.el-dialog textarea', `真实联调随访 ${RUN_TAG}`)
    await clickByText(page, '.el-dialog__footer button', '创建')
    await sleep(2500)
    const created = await page.evaluate((tag) => document.body.textContent.includes(tag), RUN_TAG)
    record('医生-新建随访', created, created ? '列表出现随访' : '列表未出现')

    await clickByText(page, '.followup-page .el-table__body button', '标记完成')
    await sleep(900)
    await clickByText(page, '.el-message-box__btns button', '确定')
    await sleep(2200)
    const done = await page.evaluate(
      () => document.querySelector('.followup-page .el-table__body')?.textContent?.replace(/\s+/g, ' ') || ''
    )
    record('医生-标记随访完成', /已完成/.test(done), done.slice(0, 60))
    await page.screenshot({ path: join(shotDir, 'live2-doctor-followups.png') })
    await page.close()
  })

  // ================= 研究员端 =================
  await phase('研究员-导入与质控', async () => {
    const page = await open('researcher')
    await login(page, 'researcher')

    await page.goto(`${BASE}/imports`, { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('.dropzone input[type=file]', { timeout: 20000 })
    const input = await page.$('.dropzone input[type=file]')
    await input.uploadFile(CSV)
    await page.waitForSelector('.stats', { timeout: 90000 })
    const stats = await page.evaluate(
      () => document.querySelector('.stats')?.textContent?.replace(/\s+/g, ' ') || ''
    )
    record('研究员-CSV 预检', /总计/.test(stats), stats.slice(0, 80))
    await clickByText(page, 'button', '确认入库')
    await sleep(3500)
    const committed = await page.evaluate(() => document.body.textContent.includes('已入库'))
    record('研究员-确认入库', committed, committed ? '状态：已入库' : '未看到已入库')

    await page.goto(`${BASE}/quality`, { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('.qc-page', { timeout: 20000 })
    await sleep(2000)
    const qc = await page.evaluate(() => ({
      rows: document.querySelectorAll('.qc-page .el-table__body .el-table__row').length,
      text: document.querySelector('.qc-page .el-table__body')?.textContent?.replace(/\s+/g, ' ').trim() || ''
    }))
    record('研究员-质控列表', qc.rows >= 1, `rows=${qc.rows} :: ${qc.text.slice(0, 70)}`)
    await page.screenshot({ path: join(shotDir, 'live2-qc.png') })

    if (qc.rows > 0) {
      await clickByText(page, '.qc-page .el-table__body button', '通过')
      await page.waitForSelector('.el-message-box__input textarea', { timeout: 10000 })
      await page.type('.el-message-box__input textarea', '真实联调：已核对源数据，异常项确认无误。')
      await sleep(900)
      await clickByText(page, '.el-message-box__btns button', '确定')
      await sleep(2500)
      const after = await page.evaluate(() => ({
        rows: document.querySelectorAll('.qc-page .el-table__body .el-table__row').length,
        body: document.body.textContent
      }))
      record('研究员-质控通过', after.rows === qc.rows - 1, `剩余=${after.rows}（原 ${qc.rows}）`)
      await page.screenshot({ path: join(shotDir, 'live2-qc-after.png') })
    }
    await page.close()
  })

  // ================= 管理员端 =================
  await phase('管理员端', async () => {
    const page = await open('admin')
    await login(page, 'admin')
    await page.waitForSelector('.admin-home', { timeout: 25000 })
    await sleep(3000)
    const dash = await page.evaluate(() => ({
      metrics: [...document.querySelectorAll('.metric__value')].map((n) => n.textContent.trim()),
      charts: document.querySelectorAll('.echart canvas').length,
      auditRows: document.querySelectorAll('.audit .el-table__row').length
    }))
    record(
      '管理员-数据大屏',
      dash.metrics.length === 4 && dash.charts === 2,
      `metrics=${dash.metrics.join('/')} charts=${dash.charts} auditRows=${dash.auditRows}`
    )
    await page.screenshot({ path: join(shotDir, 'live2-admin.png'), fullPage: true })

    await page.goto(`${BASE}/admin/audit`, { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('.audit__table', { timeout: 20000 })
    await sleep(1800)
    const audit = await page.evaluate(() => {
      const rows = [...document.querySelectorAll('.audit__table .el-table__row')]
      return {
        count: rows.length,
        hasReview: rows.some((r) => r.textContent.includes('ASSESSMENT_REVIEW')),
        first: rows[0]?.textContent?.replace(/\s+/g, ' ').slice(0, 70) || ''
      }
    })
    record('管理员-审计含复核', audit.hasReview, `rows=${audit.count} first="${audit.first}"`)
    await page.screenshot({ path: join(shotDir, 'live2-audit.png') })
    await page.close()
  })

  // ================= 患者端 =================
  await phase('患者端', async () => {
    const page = await open('patient')
    await login(page, 'patient')
    await page.waitForSelector('.patient-home', { timeout: 25000 })
    await sleep(2500)
    const state = await page.evaluate(() => ({
      sider: Boolean(document.querySelector('aside.shell__aside')),
      cards: document.querySelectorAll('.hcard').length,
      status: document.querySelector('.hcard__status')?.textContent?.trim() || '',
      note: document.querySelector('.hcard__note')?.textContent?.replace(/\s+/g, ' ').trim() || '',
      fontSize: getComputedStyle(document.querySelector('.patient') || document.body)
        .getPropertyValue('--el-font-size-base')
        .trim()
    }))
    record(
      '患者-布局与字号',
      !state.sider && state.cards === 3,
      `sider=${state.sider} cards=${state.cards} 字号=${state.fontSize}`
    )
    record(
      '患者-显示绑定档案',
      /已绑定档案/.test(state.note) && /DEMO-001/.test(state.note),
      state.note.slice(0, 80)
    )
    await clickByText(page, 'button', '联系医生')
    await sleep(900)
    const dialog = await page.evaluate(
      () => document.querySelector('.el-message-box')?.textContent?.replace(/\s+/g, ' ') || ''
    )
    record('患者-一键联系医生', dialog.length > 0, dialog.slice(0, 70))
    await page.screenshot({ path: join(shotDir, 'live2-patient.png'), fullPage: true })
    await page.close()
  })
} finally {
  await browser.close()
}

// favicon 专项：若有 /favicon 相关 4xx 就记一笔
const faviconErrors = httpErrors.filter((e) => e.url.includes('favicon'))
record('全局-favicon 无 404', faviconErrors.length === 0, `favicon 错误数=${faviconErrors.length}`)
record(
  '全局-无 5xx',
  httpErrors.every((e) => e.status < 500),
  httpErrors.filter((e) => e.status >= 500).map((e) => `${e.status} ${e.url}`).join(', ') || 'none'
)
record('全局-无页面异常', pageErrors.length === 0, pageErrors[0]?.message || 'none')

const report = { base: BASE, assessmentId, steps, httpErrors, consoleErrors, pageErrors }
writeFileSync(join(shotDir, 'live-report-v2.json'), JSON.stringify(report, null, 2), 'utf8')
console.log(JSON.stringify({ steps, httpErrors, consoleErrors, pageErrors }, null, 2))
