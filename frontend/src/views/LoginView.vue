<template>
  <div
    class="screen"
    :data-active-role="currentRole"
    :class="{
      'is-expanded': isExpanded,
      'has-opened-scene': hasOpenedScene,
      'is-detail': detailOpen,
    }"
  >
    <div class="ambient" aria-hidden="true">
      <span class="orb orb-a"></span><span class="orb orb-b"></span><span class="orb orb-c"></span>
      <div class="wave"></div>
      <div class="wave w2"></div>
      <svg class="cross-mark" viewBox="0 0 128 128" fill="none">
        <path d="M64 18v92M18 64h92" stroke="#4BAEC9" stroke-width="1" />
        <circle cx="64" cy="64" r="31" stroke="#4BAEC9" stroke-width=".8" stroke-dasharray="2 5" />
        <circle cx="64" cy="18" r="2.5" fill="#67C7D7" />
        <circle cx="110" cy="64" r="2" fill="#67C7D7" />
      </svg>
    </div>

    <header class="topbar">
      <div class="brand">
        <span class="brand-mark" aria-hidden="true">
          <svg width="26" height="26" viewBox="0 0 28 28" fill="none">
            <path d="M14 2.8c6.2 0 11.2 5 11.2 11.2S20.2 25.2 14 25.2 2.8 20.2 2.8 14 7.8 2.8 14 2.8Z" stroke="#1B8DBE" stroke-width="1.35" opacity=".55" />
            <path d="M14 8.2v11.6M8.2 14h11.6" stroke="#18A0CA" stroke-width="3.1" stroke-linecap="round" />
            <circle cx="21.8" cy="7.1" r="3.2" fill="#56D5BD" />
            <path d="M20.8 7.1h2M21.8 6.1v2" stroke="#fff" stroke-width=".9" stroke-linecap="round" />
          </svg>
        </span>
        <span class="brand-word">
          <strong>AD-CausalRisk</strong>
          <small>阿尔茨海默病风险预测</small>
        </span>
      </div>
      <div class="top-tools">
        <button class="help" type="button" id="help-link" aria-label="需要帮助" title="需要帮助" @click="openHelp">
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <circle cx="8" cy="8" r="6.4" stroke="currentColor" stroke-width="1.1" />
            <path d="M6.3 6c.1-.9.8-1.55 1.75-1.55.98 0 1.72.63 1.72 1.5 0 .72-.4 1.13-1.02 1.52-.52.34-.7.62-.7 1.2" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" />
            <circle cx="8.05" cy="11.2" r=".7" fill="currentColor" />
          </svg>
          <span>需要帮助</span>
        </button>
      </div>
    </header>

    <main class="body-wrap">
      <LoginCard
        :current-role="currentRole"
        :expanded="isExpanded"
        @update:current-role="setRole"
        @collapse="closeScene"
        @help="openHelp"
        @logged-in="onLoggedIn"
      />
    </main>

    <footer class="foot">
      <span>© 2026 AD-CausalRisk · 阿尔茨海默病因果风险预测平台</span>
    </footer>
  </div>

  <button
    class="explore-pill"
    id="explore-btn"
    type="button"
    aria-controls="brain-scene"
    :aria-expanded="String(isExpanded)"
    :aria-label="isExpanded ? '收起 3D 探索视图' : '展开 3D 探索视图'"
    :class="{ 'is-close': isExpanded }"
    @click="toggleScene"
  >
    <span class="explore-orb" aria-hidden="true">
      <svg class="orb-open" width="17" height="17" viewBox="0 0 20 20" fill="none">
        <circle cx="10" cy="10" r="6.2" stroke="currentColor" stroke-width="1.15" opacity=".62" />
        <path d="M10 3.8c3 1.8 4.7 4 4.7 6.2S13 14.4 10 16.2 5.3 12.2 5.3 10 7 5.6 10 3.8Z" stroke="currentColor" stroke-width="1.05" opacity=".45" />
        <circle cx="13.4" cy="7.6" r="1.5" fill="currentColor" />
        <path d="M10 6.8v6.4M6.8 10h6.4" stroke="currentColor" stroke-width="1" opacity=".56" />
      </svg>
      <svg class="orb-close" width="17" height="17" viewBox="0 0 20 20" fill="none">
        <path d="M12.8 5.2 8 10l4.8 4.8M8.4 10h7.2" stroke="currentColor" stroke-width="1.35" stroke-linecap="round" stroke-linejoin="round" />
        <circle cx="10" cy="10" r="7.2" stroke="currentColor" stroke-width="1" opacity=".28" />
      </svg>
    </span>
    <span class="explore-copy">
      <small id="explore-eyebrow">{{ isExpanded ? 'BACK TO LOGIN' : 'CRYSTAL EXPLORER' }}</small>
      <strong id="explore-title">{{ isExpanded ? '返回登录' : '探索平台' }}</strong>
    </span>
  </button>

  <BrainCanvas :is-active="isExpanded" @close="closeScene" />

  <div class="global-ekg" aria-hidden="true"><div class="global-ekg-track"></div></div>

  <aside
    class="detail-drawer"
    id="detail-drawer"
    role="dialog"
    aria-modal="false"
    aria-labelledby="detail-title"
    :aria-hidden="String(!detailOpen)"
    :inert="!detailOpen"
    :class="{ 'is-mounted': detailMounted, 'is-open': detailOpen }"
  >
    <div class="detail-inner">
      <div class="detail-top">
        <span class="detail-kicker">AD-CausalRisk / Exploration</span>
        <button class="detail-back" id="detail-back" type="button" @click="backToScene">
          <svg width="13" height="13" viewBox="0 0 14 14" fill="none" aria-hidden="true">
            <path d="M11 7H3M6 3 2 7l4 4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          返回 3D 视图
        </button>
      </div>
      <div class="detail-index" id="detail-index">{{ detail.index }}</div>
      <h2 class="detail-title" id="detail-title">{{ detail.title }}</h2>
      <p class="detail-desc" id="detail-desc">{{ detail.desc }}</p>
      <div class="detail-tags" id="detail-tags">
        <span v-for="tag in detail.tags" :key="tag">{{ tag }}</span>
      </div>
      <div class="detail-metrics" id="detail-metrics">
        <div v-for="metric in detail.metrics" :key="metric.label" class="detail-metric">
          <span>{{ metric.label }}</span>
          <strong>{{ metric.value }}</strong>
          <small>{{ metric.note }}</small>
        </div>
      </div>
      <div class="detail-note" id="detail-note">{{ detail.note }}</div>
    </div>
  </aside>

  <dialog class="help-dialog" id="help-dialog" ref="helpDialog" aria-labelledby="help-title" aria-describedby="help-copy">
    <div class="help-dialog-inner">
      <div class="help-top">
        <span class="help-symbol" aria-hidden="true">
          <svg width="21" height="21" viewBox="0 0 22 22" fill="none">
            <path d="M11 2.5 19 6.6v5.5c0 4.5-3 7.8-8 9.4-5-1.6-8-4.9-8-9.4V6.6L11 2.5Z" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round" />
            <path d="M8.6 8.4c.2-1.25 1.1-2.05 2.45-2.05 1.4 0 2.45.87 2.45 2.08 0 .98-.56 1.55-1.4 2.08-.74.46-.95.82-.95 1.6" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" />
            <circle cx="11.1" cy="15.6" r=".9" fill="currentColor" />
          </svg>
        </span>
        <button class="help-close" type="button" id="help-close" aria-label="关闭帮助" @click="closeHelp">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
            <path d="m3 3 8 8M11 3l-8 8" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" />
          </svg>
        </button>
      </div>
      <h2 id="help-title">无法登录？</h2>
      <p class="help-copy" id="help-copy">{{ activeMeta.help }}</p>
      <div class="help-note">
        <svg width="12" height="12" viewBox="0 0 14 14" fill="none" aria-hidden="true">
          <path d="M7 1.7 12 3.6v3.5c0 2.8-1.7 5.2-5 6.2-3.3-1-5-3.4-5-6.2V3.6L7 1.7Z" stroke="currentColor" stroke-width="1.1" stroke-linejoin="round" />
          <path d="m4.8 7 1.5 1.5 3-2.9" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        平台不会通过外部链接或电话索要密码；勾选“记住我”只会在此设备按身份保留账号，不会保存密码。
      </div>
      <div class="help-foot">
        <span class="help-role" id="help-role">当前身份 · {{ activeMeta.label }}</span>
        <button class="help-confirm" type="button" id="help-confirm" @click="closeHelp">我知道了</button>
      </div>
    </div>
  </dialog>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

import BrainCanvas from '@/components/BrainCanvas.vue'
import LoginCard from '@/components/LoginCard.vue'
import { exploreDetails } from '@/data/exploreDetails'
import { roleMeta } from '@/data/roleMeta'

/** 双屏状态：false = 居中单屏（登录），true = 左移 + 右侧 3D 淡入。 */
const isExpanded = ref(false)
/** 当前登录身份：doctor | researcher | admin | patient。 */
const currentRole = ref('doctor')

const hasOpenedScene = ref(false)
const detailMounted = ref(false)
const detailOpen = ref(false)
const helpDialog = ref(null)

const EMPTY_DETAIL = { index: '', title: '', desc: '', tags: [], metrics: [], note: '' }
const detail = ref(EMPTY_DETAIL)

const activeMeta = computed(() => roleMeta[currentRole.value] ?? roleMeta.doctor)

let detailCloseTimer = null

function setRole(role) {
  if (role in roleMeta) currentRole.value = role
}

/**
 * The demo toggles `body.scene-open` because the stylesheet drives the two-screen
 * layout from `body.*` selectors; the same class is applied here for that reason.
 */
function setBodyClass(name, on) {
  document.body.classList.toggle(name, on)
}

function openScene() {
  if (isExpanded.value) return
  isExpanded.value = true
  hasOpenedScene.value = true
  window.__ADBrainRequested = true
  setBodyClass('scene-open', true)
}

function closeScene() {
  if (!isExpanded.value) return
  closeDetail(true)
  isExpanded.value = false
  setBodyClass('scene-open', false)
}

function toggleScene() {
  if (isExpanded.value) closeScene()
  else openScene()
}

function openDetail(key) {
  const entry = exploreDetails[key]
  if (!entry || !isExpanded.value) return
  if (detailCloseTimer) {
    window.clearTimeout(detailCloseTimer)
    detailCloseTimer = null
  }
  detail.value = entry
  detailMounted.value = true
  detailOpen.value = true
  setBodyClass('scene-detail', true)
  setBodyClass('scene-detail-covered', false)
}

function closeDetail(immediate = false) {
  if (!detailOpen.value && !detailMounted.value) return
  detailOpen.value = false
  setBodyClass('scene-detail', false)
  setBodyClass('scene-detail-covered', false)
  const unmount = () => {
    detailMounted.value = false
    detail.value = EMPTY_DETAIL
    detailCloseTimer = null
  }
  if (immediate) unmount()
  else detailCloseTimer = window.setTimeout(unmount, 320)
}

function backToScene() {
  closeDetail()
}

function openHelp() {
  const dialog = helpDialog.value
  if (!dialog) return
  if (typeof dialog.showModal === 'function') dialog.showModal()
  else dialog.setAttribute('open', '')
}

function closeHelp() {
  const dialog = helpDialog.value
  if (!dialog) return
  if (typeof dialog.close === 'function') dialog.close()
  else dialog.removeAttribute('open')
}

/** Bridge consumed by the 3D stage when a floating label is activated. */
function handleLabelDetail(key) {
  openDetail(key)
}

function onLoggedIn(payload) {
  if (payload) window.__ADAuthUser = payload
}

onMounted(() => {
  window.ADShowLabelDetail = handleLabelDetail
  // Deep link for review/screenshots: /?scene=1 opens the 3D view on load.
  const params = new URLSearchParams(window.location.search)
  if (params.get('scene') === '1' || params.get('scene') === 'true') {
    nextTick(() => openScene())
  }
})

onBeforeUnmount(() => {
  if (detailCloseTimer) window.clearTimeout(detailCloseTimer)
  delete window.ADShowLabelDetail
  delete window.__ADBrainRequested
  delete window.__ADAuthUser
  setBodyClass('scene-open', false)
  setBodyClass('scene-detail', false)
  setBodyClass('scene-detail-covered', false)
})
</script>
