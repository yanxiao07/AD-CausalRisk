/**
 * P1 冒烟测试：四种角色登录后的落地页、布局切换、动态菜单、路由守卫。
 *
 * 不依赖后端：用请求拦截把 /api/auth/login 与 /api/me 应答替换成指定角色，
 * 因此验证的是前端自己的路由 / 布局 / 菜单逻辑。
 *
 * 运行：
 *   npm i --no-save puppeteer-core
 *   npm run dev -- --port 5199        # 另开一个终端
 *   node scripts/e2e-roles.mjs
 */
import puppeteer from 'puppeteer-core'
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const BASE = process.env.E2E_BASE || 'http://127.0.0.1:5199'
const CHROME =
  process.env.E2E_CHROME || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'

const USERS = {
  doctor: {
    id: 4,
    username: 'doctor',
    roles: ['DOCTOR'],
    menus: ['followups', 'graph', 'reports', 'reviews', 'subjects']
  },
  researcher: {
    id: 3,
    username: 'researcher',
    roles: ['RESEARCHER'],
    menus: ['graph', 'imports', 'quality', 'reports', 'subjects', 'tasks']
  },
  admin: { id: 1, username: 'admin', roles: ['ADMIN'], menus: ['audit', 'system', 'users'] },
  patient: { id: 5, username: 'patient', roles: ['PATIENT'], menus: ['followups', 'summary'] }
}

const EXPECTED_PATH = {
  doctor: '/doctor',
  researcher: '/researcher',
  admin: '/admin',
  patient: '/patient'
}

const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..')
const shotDir = join(projectRoot, '.shots')
mkdirSync(shotDir, { recursive: true })

const envelope = (data) =>
  JSON.stringify({
    code: 0,
    message: 'success',
    data,
    errorCode: null,
    traceId: 'e2e-trace',
    timestamp: new Date().toISOString()
  })

const results = []
let failed = 0

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage', '--font-render-hinting=none']
})

try {
  for (const [role, user] of Object.entries(USERS)) {
    const page = await browser.newPage()
    await page.setViewport({ width: 1440, height: 900 })

    await page.setRequestInterception(true)
    page.on('request', (req) => {
      const url = req.url()
      if (url.includes('cdn.jsdelivr.net')) return req.abort() // 3D 场景有本地回退，避免等 CDN
      if (url.includes('/api/auth/login')) {
        return req.respond({
          status: 200,
          contentType: 'application/json',
          body: envelope(user)
        })
      }
      if (url.includes('/api/me')) {
        return req.respond({ status: 200, contentType: 'application/json', body: envelope(user) })
      }
      return req.continue()
    })

    const consoleErrors = []
    page.on('pageerror', (err) => consoleErrors.push(String(err).slice(0, 200)))

    await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    // 上一个角色的登录态会持久化在 localStorage，逐角色跑之前先清干净
    await page.evaluate(() => localStorage.clear())
    await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForSelector(`button[data-role="${role}"]`, { timeout: 15000 })
    await page.click(`button[data-role="${role}"]`)
    await page.waitForSelector('#username', { visible: true, timeout: 10000 })
    await page.type('#username', user.username)
    await page.type('#password', 'DemoPass!2026')

    await Promise.all([
      page.waitForFunction(() => location.pathname !== '/login', { timeout: 20000 }),
      page.click('#submit-btn')
    ])
    // 等布局与菜单渲染完
    await page.waitForFunction(() => !!document.querySelector('.shell__main, .screen__main, .patient__main'), {
      timeout: 15000
    })

    const state = await page.evaluate(() => {
      const patientRoot = document.querySelector('.patient')
      return {
        path: location.pathname,
        hasSider: !!document.querySelector('aside.shell__aside'),
        hasScreenBar: !!document.querySelector('.screen__bar'),
        hasPatientMain: !!document.querySelector('.patient__main'),
        menuLabels: [...document.querySelectorAll('.el-menu-item .menu__label')].map((n) =>
          n.textContent.trim()
        ),
        navLabels: [...document.querySelectorAll('.screen__nav-item')].map((n) => n.textContent.trim()),
        disabledMenuCount: [...document.querySelectorAll('.el-menu-item.is-disabled')].length,
        patientFontSize: patientRoot
          ? getComputedStyle(patientRoot).getPropertyValue('--el-font-size-base').trim()
          : ''
      }
    })

    // 先截落地页，再跑守卫测试（否则截到的是 401）
    await page.screenshot({ path: join(shotDir, `${role}.png`), fullPage: false })

    // 守卫：DOCTOR 直接访问 /admin 必须被挡到 401
    let guardPath = ''
    if (role === 'doctor') {
      await page.goto(`${BASE}/admin`, { waitUntil: 'domcontentloaded' })
      await page.waitForFunction(() => location.pathname !== '/admin', { timeout: 15000 })
      guardPath = await page.evaluate(() => location.pathname)
      await page.screenshot({ path: join(shotDir, 'doctor-guard-401.png'), fullPage: false })
    }

    const checks = []
    const check = (name, ok, detail) => {
      checks.push({ name, ok, detail })
      if (!ok) failed += 1
    }
    check('landing path', state.path === EXPECTED_PATH[role], `${state.path} (expected ${EXPECTED_PATH[role]})`)
    check(
      'layout',
      role === 'admin'
        ? state.hasScreenBar && !state.hasSider
        : role === 'patient'
          ? state.hasPatientMain && !state.hasSider
          : state.hasSider && !state.hasScreenBar,
      JSON.stringify({ sider: state.hasSider, screen: state.hasScreenBar, patient: state.hasPatientMain })
    )
    if (role === 'patient') {
      check('patient hides sidebar', !state.hasSider, `sider=${state.hasSider}`)
      check('patient font size', state.patientFontSize === '20px', state.patientFontSize)
    }
    if (role === 'admin') {
      check('screen nav from menus', state.navLabels.length === user.menus.length, state.navLabels.join(','))
    } else if (role !== 'patient') {
      check(
        'sidebar menu = home + menus',
        state.menuLabels.length === user.menus.length + 1,
        `${state.menuLabels.length} items: ${state.menuLabels.join(',')}`
      )
    }
    check('no page errors', consoleErrors.length === 0, consoleErrors[0] || 'none')
    if (role === 'doctor') {
      check('guard blocks cross-role route', guardPath === '/401', guardPath)
    }


    results.push({ role, path: state.path, menus: user.menus.length, checks, screenshots: `${role}.png` })
    await page.close()
  }
} finally {
  await browser.close()
}

console.log(JSON.stringify({ ok: failed === 0, failed, results }, null, 2))
process.exit(failed === 0 ? 0 : 1)
