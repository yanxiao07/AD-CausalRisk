<template>
  <section class="auth-card" id="auth-card" :data-active-role="currentRole" aria-label="登录面板">
    <div class="identity-pane">
      <div class="identity-head">
        <h1>今天，以什么<em>身份</em><br />进入<em>AD-CausalRisk</em>？</h1>
        <p>不同身份对应不同的数据与功能范围。</p>
      </div>

      <div class="identity-compact" aria-hidden="true">
        <span>选择登录身份</span>
        <small>身份决定数据范围</small>
      </div>

      <div
        class="role-grid is-enhanced"
        :class="{ 'is-positioned': outlineReady }"
        id="role-grid"
        role="group"
        aria-label="选择登录身份"
        ref="gridEl"
      >
        <span class="selection-outline" id="selection-outline" aria-hidden="true" ref="outlineEl"></span>
        <button
          v-for="role in roles"
          :key="role"
          class="role-card"
          type="button"
          :data-role="role"
          :aria-pressed="currentRole === role"
          :aria-label="`以${roleMeta[role].label}身份登录，${roleFacts[role].caption}`"
          :disabled="loading"
          :ref="(el) => setCardEl(role, el)"
          @click="selectRole(role)"
        >
          <span class="portrait" aria-hidden="true" v-html="rolePortraits[role]"></span>
          <strong>{{ roleMeta[role].label }}</strong>
          <small>{{ roleFacts[role].caption }}</small>
          <span class="role-check" aria-hidden="true">
            <svg width="10" height="10" viewBox="0 0 10 10" fill="none">
              <path d="m2 5.2 2 2 4-4.2" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </span>
        </button>
      </div>

      <div class="identity-note">
        <svg width="13" height="13" viewBox="0 0 14 14" fill="none" aria-hidden="true">
          <path d="M7 1.8 12 3.7v3.5c0 2.8-1.7 5.1-5 6.1-3.3-1-5-3.3-5-6.1V3.7L7 1.8Z" stroke="currentColor" stroke-width="1.1" stroke-linejoin="round" />
          <path d="m4.9 7 1.4 1.4 2.9-2.9" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <span id="identity-note-text">{{ roleMeta[currentRole].note }}</span>
      </div>
    </div>

    <div class="form-pane">
      <button class="scene-collapse" id="scene-collapse" type="button" aria-label="收起 3D 探索视图" @click="$emit('collapse')">
        <svg width="12" height="12" viewBox="0 0 14 14" fill="none" aria-hidden="true">
          <path d="M11 7H3M7 3 3 7l4 4" stroke="currentColor" stroke-width="1.35" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <span>收起 3D</span>
      </button>

      <div class="login-view" id="login-view" v-show="!signedIn">
        <div class="form-head">
          <h2 id="form-title">{{ roleMeta[currentRole].formTitle }}</h2>
          <p id="form-subtitle">{{ roleMeta[currentRole].title }}</p>
        </div>

        <form id="login-form" novalidate aria-labelledby="form-title" :aria-busy="String(loading)" @submit.prevent="submit">
          <div class="field" id="username-field">
            <div class="field-head"><label for="username">账号</label></div>
            <div class="input-wrap" :class="{ 'is-shaking': shake }">
              <svg width="16" height="16" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                <circle cx="10" cy="6.5" r="3.1" stroke="currentColor" stroke-width="1.3" />
                <path d="M4.4 16.8c.6-3.2 2.8-4.9 5.6-4.9s5 1.7 5.6 4.9" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" />
              </svg>
              <input
                id="username"
                v-model="username"
                name="username"
                type="text"
                autocomplete="username"
                required
                aria-required="true"
                :placeholder="roleMeta[currentRole].accountPlaceholder"
                autocapitalize="off"
                spellcheck="false"
                :aria-invalid="invalidField === 'username' ? 'true' : 'false'"
                :disabled="loading"
                @input="clearError"
              />
            </div>
          </div>

          <div class="field" id="password-field">
            <div class="field-head"><label for="password">密码</label></div>
            <div class="input-wrap password-wrap" :class="{ 'is-shaking': shake }">
              <svg width="16" height="16" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                <rect x="4.2" y="8.5" width="11.6" height="8.3" rx="1.7" stroke="currentColor" stroke-width="1.3" />
                <path d="M6.9 8.5V6.2a3.1 3.1 0 0 1 6.2 0v2.3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" />
                <circle cx="10" cy="12.5" r="1" fill="currentColor" />
              </svg>
              <input
                id="password"
                v-model="password"
                name="password"
                :type="showPassword ? 'text' : 'password'"
                autocomplete="current-password"
                required
                aria-required="true"
                placeholder="请输入密码"
                :aria-invalid="invalidField === 'password' ? 'true' : 'false'"
                :disabled="loading"
                @input="clearError"
              />
              <button
                class="eye-btn"
                type="button"
                id="toggle-password"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                :aria-pressed="String(showPassword)"
                aria-controls="password"
                @click="showPassword = !showPassword"
              >
                <svg v-if="!showPassword" class="icon-eye" width="17" height="17" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                  <path d="M2.3 10S5 5.6 10 5.6 17.7 10 17.7 10 15 14.4 10 14.4 2.3 10 2.3 10Z" stroke="currentColor" stroke-width="1.25" />
                  <circle cx="10" cy="10" r="2.1" stroke="currentColor" stroke-width="1.25" />
                </svg>
                <svg v-else class="icon-eye-off" width="17" height="17" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                  <path d="m3 3 14 14" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" />
                  <path d="M8.4 5.9c.5-.2 1-.3 1.6-.3 5 0 7.7 4.4 7.7 4.4a12.4 12.4 0 0 1-2.8 3M5.2 7.2A12.4 12.4 0 0 0 2.3 10s2.7 4.4 7.7 4.4c1 0 2-.2 2.8-.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" />
                  <path d="M8.3 8.4a2.1 2.1 0 0 0 3.2 3.2" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" />
                </svg>
              </button>
            </div>
          </div>

          <div class="field-error" id="form-error" role="alert" v-if="error">
            <svg width="13" height="13" viewBox="0 0 14 14" fill="none" aria-hidden="true">
              <circle cx="7" cy="7" r="5.8" stroke="currentColor" stroke-width="1.1" />
              <path d="M7 4.1v3.2" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" />
              <circle cx="7" cy="9.9" r=".7" fill="currentColor" />
            </svg>
            <span id="form-error-text">{{ error }}</span>
          </div>

          <button
            v-if="sessionIssue"
            type="button"
            class="session-retry"
            id="session-retry"
            :disabled="retryingLogout || loading"
            @click="retryLogout"
          >
            {{ retryingLogout ? '正在重试退出…' : '重试退出登录' }}
          </button>

          <div class="form-options">
            <label class="remember" title="仅在本设备浏览器保存账号与密码（非加密），公用电脑请勿勾选">
              <input type="checkbox" id="remember" v-model="remember" />
              <span class="checkbox-ui" aria-hidden="true">
                <svg width="9" height="9" viewBox="0 0 9 9" fill="none">
                  <path d="m1.6 4.6 1.8 1.8 4-4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </span>
              记住我
            </label>
            <button class="forgot" type="button" id="forgot-link" @click="$emit('help')">忘记密码？</button>
          </div>

          <button class="submit" type="submit" id="submit-btn" :class="{ 'is-loading': loading }" :disabled="loading || retryingLogout">
            <span class="spinner" aria-hidden="true"></span>
            <span id="submit-label">{{ loading ? '正在验证身份…' : roleMeta[currentRole].cta }}</span>
            <svg class="submit-arrow" width="17" height="17" viewBox="0 0 18 18" fill="none" aria-hidden="true">
              <path d="M4 9h10M10 5l4 4-4 4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </button>
          <p class="sr-only" id="form-status" role="status" aria-live="polite">{{ status }}</p>
        </form>
      </div>

      <div class="success-view" id="success-view" v-if="signedIn">
        <div class="success-mark" aria-hidden="true">
          <span class="success-halo"></span>
          <span class="success-circle">
            <svg width="25" height="25" viewBox="0 0 26 26" fill="none">
              <path d="m6.4 13.3 4.2 4.2 9-9.2" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </span>
        </div>
        <h2 class="success-title" id="success-title" tabindex="-1">身份验证通过</h2>
        <p class="success-copy">欢迎回来，你正在以<span id="success-role">{{ roleMeta[currentRole].label }}</span>身份登录。</p>
        <div class="success-identity">
          <span class="success-identity-dot" aria-hidden="true"></span>
          <span id="success-role-detail">{{ roleMeta[currentRole].detail }}</span>
        </div>
        <button class="success-back" type="button" id="success-back" @click="backToForm">返回登录</button>
      </div>

      <hr class="form-sep" />
      <div class="trust-row">
        <span>
          <svg width="12" height="12" viewBox="0 0 14 14" fill="none" aria-hidden="true">
            <path d="M7 1.8 12 3.7v3.5c0 2.8-1.7 5.1-5 6.1-3.3-1-5-3.3-5-6.1V3.7L7 1.8Z" stroke="currentColor" stroke-width="1.1" stroke-linejoin="round" />
          </svg>
          仅限授权人员使用
        </span>
      </div>
    </div>
  </section>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { closeSessionAndVerify, login as apiLogin } from '@/api/auth.js'
import { rolePortraits } from '@/components/rolePortraits'
import { roleMeta } from '@/data/roleMeta'

const props = defineProps({
  /** 当前身份：doctor | researcher | admin | patient。 */
  currentRole: { type: String, default: 'doctor' },
  /** 是否处于双屏（左移）状态，用于重新测量选中框位置。 */
  expanded: { type: Boolean, default: false },
})

const emit = defineEmits(['update:currentRole', 'collapse', 'help', 'logged-in'])

const roles = ['doctor', 'researcher', 'admin', 'patient']

// 与原型一致：每张卡片第二行的小字（取 roleMeta.detail 中"·"后的部分）。
const roleFacts = {
  doctor: { caption: '临床风险与随访' },
  researcher: { caption: '队列与因果分析' },
  admin: { caption: '权限与数据质控' },
  patient: { caption: '个人风险档案' },
}

// 账号按身份分别记忆，避免一个身份的账号串到另一个身份的表单里。
const REMEMBER_PREFIX = 'ad-causalrisk:remembered-account:'

const router = useRouter()

const username = ref('')
const password = ref('')
const remember = ref(false)
const showPassword = ref(false)
const loading = ref(false)
const error = ref('')
/** 角色不符后会话未确认退出（有效或无法确认）时，显示「重试退出登录」按钮 */
const sessionIssue = ref('')
const retryingLogout = ref(false)
const status = ref('')
const invalidField = ref('')
const shake = ref(false)
const signedIn = ref(false)

const gridEl = ref(null)
const outlineEl = ref(null)
const outlineReady = ref(false)
const cardEls = reactive({})

function setCardEl(role, el) {
  if (el) cardEls[role] = el
}

/** 移动选中高亮框：原型用 getBoundingClientRect 定位，这里保持一致。 */
function syncOutline(animate = true, attempt = 0) {
  const grid = gridEl.value
  const outline = outlineEl.value
  const card = cardEls[props.currentRole]
  if (!grid || !outline || !card) return
  const gridRect = grid.getBoundingClientRect()
  const cardRect = card.getBoundingClientRect()
  // 首帧（字体/布局未稳定）可能量到 0，重试一次再放弃。
  if ((!cardRect.width || !cardRect.height) && attempt < 3) {
    window.requestAnimationFrame(() => syncOutline(animate, attempt + 1))
    return
  }
  if (!cardRect.width || !cardRect.height) return
  outline.style.transition = animate ? '' : 'none'
  outline.style.width = cardRect.width + 'px'
  outline.style.height = cardRect.height + 'px'
  outline.style.transform =
    'translate3d(' + (cardRect.left - gridRect.left) + 'px,' + (cardRect.top - gridRect.top) + 'px,0)'
  if (!animate) {
    void outline.getBoundingClientRect()
    outline.style.transition = ''
  }
  outlineReady.value = true
}

function selectRole(role) {
  if (loading.value || role === props.currentRole) return
  emit('update:currentRole', role)
}

function loadRememberedAccount() {
  try {
    const raw = window.localStorage.getItem(REMEMBER_PREFIX + props.currentRole)
    if (!raw) {
      remember.value = false
      return
    }
    // 兼容旧版本：以前只存了用户名字符串
    let stored = null
    try {
      stored = JSON.parse(raw)
    } catch {
      stored = raw
    }
    if (typeof stored === 'string') stored = { username: stored, password: '' }
    username.value = stored?.username || ''
    password.value = stored?.password ? decodePassword(stored.password) : ''
    remember.value = Boolean(username.value)
  } catch {
    // 隐私模式/禁用存储时忽略：记住我只是便利功能。
    remember.value = false
  }
}

function storeRememberedAccount() {
  try {
    if (remember.value) {
      window.localStorage.setItem(
        REMEMBER_PREFIX + props.currentRole,
        JSON.stringify({
          username: username.value.trim(),
          password: encodePassword(password.value)
        })
      )
    } else {
      window.localStorage.removeItem(REMEMBER_PREFIX + props.currentRole)
    }
  } catch {
    // 忽略存储失败。
  }
}

/** 非加密混淆：只为避免明文直接出现在 DevTools 里，不能当作安全措施。 */
function encodePassword(value) {
  try {
    return window.btoa(unescape(encodeURIComponent(value)))
  } catch {
    return ''
  }
}

function decodePassword(value) {
  try {
    return decodeURIComponent(escape(window.atob(value)))
  } catch {
    return ''
  }
}

function clearError() {
  if (error.value) error.value = ''
  // 注意：不清 sessionIssue —— 退出未确认时，用户改账号/密码不应把「重试退出登录」入口藏掉
  invalidField.value = ''
}

function fail(message, field = '') {
  error.value = message
  invalidField.value = field
  shake.value = false
  requestAnimationFrame(() => {
    shake.value = true
    window.setTimeout(() => {
      shake.value = false
    }, 420)
  })
}

function roleHomePath(role) {
  return { doctor: '/doctor', researcher: '/researcher', admin: '/admin', patient: '/patient' }[role] ?? '/'
}

/**
 * POST /api/auth/login 返回项目统一 Envelope：
 * { code, message, data: { id, username, roles, menus }, errorCode, traceId, timestamp }
 */
async function submit() {
  if (loading.value) return
  // 退出请求仍在路上时不允许登录：否则迟到的登出会注销刚建立的新会话
  if (retryingLogout.value) {
    fail('上一个会话正在退出，请稍候再试。')
    return
  }
  clearError()
  status.value = ''

  const account = username.value.trim()
  if (!account) return fail('请输入账号。', 'username')
  if (!password.value) return fail('请输入密码。', 'password')

  loading.value = true
  status.value = '正在验证身份…'
  try {
    // 统一走 api/auth.js -> api/request.js 的唯一 axios 实例（Envelope 脱壳 + 会话 Cookie）
    // 第三个参数是当前选中的身份：账号没有该角色时，login() 会在写登录态之前直接拒绝
    const data = await apiLogin(account, password.value, props.currentRole)

    storeRememberedAccount()
    signedIn.value = true
    sessionIssue.value = ''
    status.value = '身份验证通过'
    password.value = ''
    emit('logged-in', data ?? null)

    const target = roleHomePath(props.currentRole)
    if (router.resolve(target).matched.length > 0) await router.push(target)
  } catch (problem) {
    if (problem?.message) fail(problem.message)
    else if (problem?.code === -1) fail('无法连接服务器，请确认后端已启动（默认 http://localhost:8080）。')
    else fail('登录失败，请稍后重试。')
    // 角色不符且会话状态未确认：允许用户重试退出，而不是让他关掉浏览器。
    // 其它错误（密码错、上一个会话正在退出…）**保留**已有入口，不清空。
    if (
      problem?.errorCode === 'ROLE_MISMATCH_SESSION_ACTIVE' ||
      problem?.errorCode === 'ROLE_MISMATCH_SESSION_UNKNOWN'
    ) {
      sessionIssue.value = problem.errorCode
    }
    status.value = error.value
  } finally {
    loading.value = false
  }
}

/** 重试退出登录：再次登出并用 /api/me 复核，成功就允许重新选择身份。 */
async function retryLogout() {
  if (retryingLogout.value) return
  retryingLogout.value = true
  try {
    const session = await closeSessionAndVerify()
    if (session === 'cleared') {
      sessionIssue.value = ''
      fail('已安全退出该会话，请重新选择身份登录。')
    } else if (session === 'active') {
      fail('会话仍未退出，请关闭浏览器后重试，或联系管理员处理。')
    } else {
      fail('仍无法确认会话状态（服务暂不可用），请稍后再试，或关闭浏览器。')
    }
    status.value = error.value
  } finally {
    retryingLogout.value = false
  }
}

function backToForm() {
  signedIn.value = false
  error.value = ''
  status.value = ''
  loadRememberedAccount()
}

function handleResize() {
  if (outlineReady.value) syncOutline(false)
}

watch(
  () => props.currentRole,
  () => {
    clearError()
    signedIn.value = false
    loadRememberedAccount()
    nextTick(() => syncOutline(true))
  },
)

// 双屏切换会改变卡片宽度与位置，需要重新测量高亮框。
watch(
  () => props.expanded,
  () => nextTick(() => syncOutline(false)),
)

// 取消勾选"记住我"时立即清掉本设备已保存的凭据，而不是等下一次登录成功
watch(remember, (next) => {
  if (next) return
  try {
    window.localStorage.removeItem(REMEMBER_PREFIX + props.currentRole)
  } catch {
    // 忽略存储失败
  }
})

onMounted(() => {
  loadRememberedAccount()
  nextTick(() => syncOutline(false))
  window.addEventListener('resize', handleResize, { passive: true })
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
})
</script>
