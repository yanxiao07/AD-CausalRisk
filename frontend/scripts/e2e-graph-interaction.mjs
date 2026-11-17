import assert from 'node:assert/strict'
import { mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'

const base = process.env.E2E_BASE || 'http://127.0.0.1:5204'
const output = fileURLToPath(new URL('../.shots/', import.meta.url))
mkdirSync(output, { recursive: true })

const graph = {
  nodes: [
    { id: 'age', label: '年龄', status: 'ACTIVE', evidenceLevel: 'PRIOR' },
    { id: 'cognition', label: '认知评分', status: 'ACTIVE', evidenceLevel: 'PRIOR' },
    { id: 'mri', label: '影像缺失', status: 'MISSING', evidenceLevel: 'PRIOR' },
    { id: 'risk', label: '合成预警', status: 'ACTIVE', evidenceLevel: 'MODEL' }
  ],
  edges: ['age', 'cognition', 'mri'].map(from => ({
    from, to: 'risk', note: from === 'mri' ? '数据缺失' : '先验关联',
    status: from === 'mri' ? 'MISSING' : 'ACTIVE',
    evidenceLevel: 'PRIOR', direction: 'CONDITIONAL'
  }))
}
const user = { id: 4, username: 'doctor', roles: ['DOCTOR'], menus: ['graph', 'reports', 'subjects', 'reviews'] }
const assessment = {
  assessmentId: 99, subjectId: 1, taskId: 7, reviewStatus: 'PENDING',
  agentResult: { graph, runId: 'demo-graph-test', dataSource: 'SYNTHETIC_DEMO', riskLevel: 'HIGH' }
}
const browser = await puppeteer.launch({
  executablePath: process.env.E2E_CHROME || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: true,
  defaultViewport: { width: 1440, height: 1000 }
})
const result = { checks: [], errors: [] }
const check = (name, passed, details) => {
  result.checks.push({ name, passed, details })
}
try {
  const page = await browser.newPage()
  page.on('pageerror', error => result.errors.push(error.message))
  await page.setRequestInterception(true)
  page.on('request', request => {
    const url = new URL(request.url())
    const respond = data => request.respond({
      status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data })
    })
    if (url.pathname === '/api/auth/login' || url.pathname === '/api/me') return respond(user)
    if (url.pathname === '/api/assessments/99' || url.pathname === '/api/reports/99') return respond(assessment)
    if (url.pathname.startsWith('/api/') || !request.url().startsWith(base)) return request.abort()
    return request.continue()
  })
  await page.goto(base + '/login', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('#username', { visible: true })
  await page.type('#username', 'doctor')
  await page.type('#password', 'synthetic-test-password')
  await page.click('#submit-btn')
  await page.waitForFunction(() => location.pathname !== '/login')
  await page.goto(base + '/graph?assessmentId=99', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.el-drawer .stage .causal-graph__canvas canvas')
  // 等抽屉滑入动画与 ECharts 首次 resize 完成，避免按动画中的尺寸测坐标
  await new Promise(resolve => setTimeout(resolve, 800))

  await page.evaluate(async () => {
    const source = await (await fetch('/src/components/charts/CausalGraph.vue')).text()
    const match = source.match(/import \* as echarts from ["']([^"']+)["']/)
    if (!match) throw new Error('Cannot resolve the chart module used by the component')
    const echarts = await import(match[1])
    const el = document.querySelector('.el-drawer .causal-graph__canvas')
    window.graphTestChart = echarts.getInstanceByDom(el)
    if (!window.graphTestChart) throw new Error('Chart instance is missing')
  })

  const snapshot = () => page.evaluate(() => {
    const chart = window.graphTestChart
    const model = chart.getModel().getSeriesByIndex(0)
    const nodeData = model.getData()
    const edgeData = model.getEdgeData()
    const rect = document.querySelector('.el-drawer .causal-graph__canvas').getBoundingClientRect()
    const nodes = []
    nodeData.eachItemGraphicEl((el, index) => {
      const point = el.transformCoordToGlobal(0, 0)
      nodes.push({ id: nodeData.getId(index), x: point[0], y: point[1] })
    })
    const edges = []
    edgeData.eachItemGraphicEl((el, index) => {
      const line = el.childOfName('line')
      const label = el.getTextContent()
      const point = line.transformCoordToGlobal(...line.pointAt(0.5))
      const bounds = label.getBoundingRect().clone()
      bounds.applyTransform(label.getComputedTransform())
      const centre = [bounds.x + bounds.width / 2, bounds.y + bounds.height / 2]
      edges.push({
        index, point, centre, gap: Math.hypot(point[0] - centre[0], point[1] - centre[1]),
        text: label.style.text, visible: !label.ignore, local: el.textConfig?.local
      })
    })
    return {
      nodes, edges, rect: { x: rect.x, y: rect.y, width: rect.width, height: rect.height },
      roam: model.get('roam'), draggable: model.get('draggable'),
      zoom: model.get('zoom'),
      transform: getComputedStyle(document.querySelector('.el-drawer .causal-graph__canvas')).transform
    }
  })

  const settle = () => page.waitForFunction(() => !window.graphTestChart.getViewOfSeriesModel(
    window.graphTestChart.getModel().getSeriesByIndex(0)
  )._layouting, { timeout: 15000 })
  const frame = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(
    () => requestAnimationFrame(resolve)
  )))
  const attached = (name, state) => check(name, state.edges.every(edge =>
    !edge.visible || edge.gap < 12
  ), state.edges.map(({ gap, local }) => ({ gap, local })))
  // Let the real force layout converge before measuring pointer motion.
  await settle()
  const initial = await snapshot()
  check('node dragging is enabled', initial.draggable === true, initial.draggable)
  check('native canvas pan and zoom are enabled', initial.roam === true, initial.roam)
  check('canvas has no CSS scaling', initial.transform === 'none', initial.transform)
  attached('edge labels start on their lines', initial)
  const node = initial.nodes[0]
  await page.mouse.move(initial.rect.x + node.x, initial.rect.y + node.y)
  await page.mouse.down()
  await page.mouse.move(initial.rect.x + node.x + 70, initial.rect.y + node.y + 40, { steps: 12 })
  await frame()
  const moved = await snapshot()
  const change = Math.hypot(moved.nodes[0].x - node.x, moved.nodes[0].y - node.y)
  check('pointer drag moves a node in chart coordinates', change > 30, { change })
  attached('edge labels follow the dragged node', moved)
  await page.mouse.up()
  await settle()

  const beforePan = await snapshot()
  await page.mouse.move(beforePan.rect.x + 50, beforePan.rect.y + 50)
  await page.mouse.down()
  await page.mouse.move(beforePan.rect.x + 90, beforePan.rect.y + 80, { steps: 8 })
  await page.mouse.up()
  await frame()
  const panned = await snapshot()
  check('empty canvas drag pans every node', panned.nodes.every((item, index) =>
    Math.abs(item.x - beforePan.nodes[index].x - 40) < 3 &&
    Math.abs(item.y - beforePan.nodes[index].y - 30) < 3
  ), { before: beforePan.nodes, after: panned.nodes })
  attached('edge labels follow canvas pan', panned)

  await page.mouse.move(panned.rect.x + panned.rect.width / 2, panned.rect.y + panned.rect.height / 2)
  await page.mouse.wheel({ deltaY: -240 })
  await frame()
  const zoomed = await snapshot()
  check('mouse wheel zooms the graph', zoomed.zoom > panned.zoom, zoomed.zoom)
  attached('edge labels follow mouse wheel zoom', zoomed)
  const target = zoomed.edges.find(edge => edge.point[0] > 30 &&
    edge.point[0] < zoomed.rect.width - 30 && edge.point[1] > 30 &&
    edge.point[1] < zoomed.rect.height - 30)
  assert.ok(target, 'Expected an edge visible inside the chart')
  await page.mouse.move(zoomed.rect.x + target.point[0] - 25, zoomed.rect.y + target.point[1] - 25)
  await page.mouse.move(zoomed.rect.x + target.point[0], zoomed.rect.y + target.point[1], { steps: 5 })
  try {
    await page.waitForFunction(() => [...document.querySelectorAll('div')].some(el =>
      el.style.position === 'absolute' && getComputedStyle(el).visibility === 'visible' &&
      el.textContent.includes('条件性关联') && el.querySelector('b')
    ), { timeout: 3000 })
  } catch (error) {
    result.probe = { initial, moved, panned, zoomed, target }
    writeFileSync(output + 'graph-tooltip-debug.json', JSON.stringify(await page.evaluate(() =>
      [...document.querySelectorAll('div')].filter(el =>
        el.style.position === 'absolute' && el.textContent.includes('条件性关联')
      ).map(el => ({ html: el.innerHTML, css: el.style.cssText }))
    ), null, 2))
    await page.screenshot({ path: output + 'graph-tooltip-debug.png', fullPage: true })
    writeFileSync(output + 'graph-interaction.json', JSON.stringify(result, null, 2))
    throw error
  }
  check('edge tooltip remains available after zoom', true)
  await page.screenshot({ path: output + 'graph-fixed-zoom.png', fullPage: true })

  await page.mouse.click(zoomed.rect.x + 15, zoomed.rect.y + 15, { count: 2 })
  await settle()
  const reset = await snapshot()
  check('double click resets zoom', Math.abs(reset.zoom - 1) < 0.001, reset.zoom)
  attached('edge labels stay attached after reset', reset)
  // 图谱现在在右侧抽屉里：关闭抽屉应回到历史图谱页
  await page.evaluate(() => document.querySelector('.el-drawer__close-btn')?.click())
  await new Promise(resolve => setTimeout(resolve, 900))
  const drawerClosed = await page.evaluate(() => {
    const overlay = document.querySelector('.el-overlay')
    return !overlay || getComputedStyle(overlay).display === 'none'
  })
  check('关闭抽屉后回到历史图谱页', drawerClosed, '')
  result.probe = { initial, moved, panned, zoomed, reset }
  await page.screenshot({ path: output + 'graph-fixed.png', fullPage: true })
  check('no browser errors', result.errors.length === 0, result.errors)
  writeFileSync(output + 'graph-interaction.json', JSON.stringify(result, null, 2) + '\n')
  console.log(JSON.stringify(result, null, 2))
  assert.ok(result.checks.every(item => item.passed), 'Graph interaction regression failed')
} finally {
  await browser.close()
}
