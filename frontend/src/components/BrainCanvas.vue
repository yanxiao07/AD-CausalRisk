<template>
  <!-- The 3D stage markup is the reviewed single-file design, verbatim. The animation
       only runs while `isActive`; the parent owns the layout transition. -->
<main class="brain-stage" id="brain-scene" aria-label="AD-CausalRisk 三维水晶大脑探索视图" :aria-hidden="!isActive" :inert="!isActive">

  <div class="backdrop" aria-hidden="true">
    <span class="wash wash-a"></span>
    <span class="wash wash-b"></span>
    <span class="wash wash-c"></span>
    <span class="orbit orbit-a"></span>
    <span class="orbit orbit-b"></span>
    <span class="floor-shadow"></span>
  </div>

  <button class="brain-back" id="brain-back" type="button" aria-label="返回登录页" @click="$emit('close')">
    <svg width="12" height="12" viewBox="0 0 14 14" fill="none" aria-hidden="true"><path d="M11 7H3M6 3 2 7l4 4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
    返回登录
  </button>

  <div class="canvas-shell" id="canvas-shell" tabindex="0" role="img"
       aria-label="可拖动旋转的三维水晶大脑模型，内部有蓝色神经电流光点流动，悬浮标注：数据融合、因果图谱、风险预测">
    <canvas id="brain-canvas"></canvas>
  </div>

  <svg class="leader-layer" id="leader-layer" xmlns="http://www.w3.org/2000/svg" aria-hidden="true"></svg>

  <div class="labels" id="labels">
    <div class="brain-label" data-anchor="fusion">
      <button class="label-chip" type="button" aria-pressed="false" aria-haspopup="dialog" aria-controls="detail-drawer"><span class="label-dot" aria-hidden="true"></span>数据融合</button>
    </div>
    <div class="brain-label" data-anchor="causal">
      <button class="label-chip" type="button" aria-pressed="false" aria-haspopup="dialog" aria-controls="detail-drawer"><span class="label-dot" aria-hidden="true"></span>因果图谱</button>
    </div>
    <div class="brain-label" data-anchor="risk">
      <button class="label-chip" type="button" aria-pressed="false" aria-haspopup="dialog" aria-controls="detail-drawer"><span class="label-dot" aria-hidden="true"></span>风险预测</button>
    </div>
  </div>

  <div class="scene-hint" id="scene-hint" aria-hidden="true">
    <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <path d="M5.3 7.2V3.9a1.15 1.15 0 0 1 2.3 0v2.8-4.1a1.15 1.15 0 0 1 2.3 0v4.1-2.9a1.15 1.15 0 0 1 2.3 0v5.3c0 3-1.8 4.7-4.6 4.7-2.2 0-3.5-1.1-4.7-3.1L1.7 8.8c-.4-.7-.2-1.4.4-1.8.6-.4 1.3-.2 1.8.4l1.4 1.8" stroke="currentColor" stroke-width="1.05" stroke-linecap="round" stroke-linejoin="round"/>
    </svg>
    <span>拖动旋转 · 悬停吸收 · 点击标签查看详情</span>
  </div>

  <div class="credit" id="credit" hidden>
    <a href="https://github.com/itayinbarr/brainproject" target="_blank" rel="noreferrer noopener">Z-Anatomy / BodyParts3D</a>
    · CC BY-SA 4.0
  </div>

  <div class="loading" id="loading" role="status" aria-live="polite">
    <span class="loader-mark" aria-hidden="true"></span>
    <div class="load-copy">
      <p>构建晶体大脑</p>
      <small id="load-status">加载解剖模型 · 0%</small>
      <div class="load-track" aria-hidden="true"><i id="load-progress"></i></div>
    </div>
  </div>

  <div class="scene-error" id="scene-error" hidden>
    <strong>当前浏览器无法创建 3D 场景</strong>
    请使用支持 WebGL 的现代浏览器打开，例如最新版 Chrome、Edge 或 Safari。
  </div>

  <p class="sr-only">这是一个医疗风的水晶大脑三维可视化。模型可以拖动旋转，周围悬浮三个可交互标签：数据融合、因果图谱、风险预测。蓝色电信号圆点沿大脑内部不可见的光滑神经电流持续流动。悬停标签时，所有圆点会离开电流、平滑转向并飞入标签，随后隐藏并被储存，标签随吸入数量逐步增强高光；全部吸入后脑内不再有游走圆点。光标离开标签时，被储存的圆点从标签位置重新出现，飞回脑内神经电流继续流转。点击任意标签可展开对应的功能详情。真实解剖几何加载失败时，会使用程序生成的水晶脑形态。</p>
  <noscript>
    <div class="noscript-note"><strong>需要启用 JavaScript</strong><br>启用后即可查看并拖动这颗水晶大脑。</div>
  </noscript>

</main>
</template>

<script setup>
// Three.js study ported from frontend/login-demo/index.html. The scene code is kept
// verbatim on purpose (it is tuned geometry/shader work), wrapped in onMounted so it
// runs after the template exists, and torn down in onUnmounted so SPA navigation does
// not leak a WebGL context, an animation loop or window listeners.
import * as THREE from 'three'
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js'
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'
import { DRACOLoader } from 'three/addons/loaders/DRACOLoader.js'
import { onMounted, onUnmounted, watch } from 'vue'

import brainModelUrl from '@/assets/brain.glb?url'

const props = defineProps({
  /** When true the render loop runs; when false the scene is frozen (login state). */
  isActive: { type: Boolean, default: false },
})

defineEmits(['close'])

// --- lifecycle scaffolding -------------------------------------------------
const listenerDisposers = []
const pendingTimeouts = new Set()
let resizeObserver = null
let rendererRef = null
let sceneRef = null
let api = null

function trackListener(target, type, handler, options) {
  target.addEventListener(type, handler, options)
  listenerDisposers.push(() => target.removeEventListener(type, handler, options))
}

function trackTimeout(fn, ms) {
  const id = window.setTimeout(() => {
    pendingTimeouts.delete(id)
    fn()
  }, ms)
  pendingTimeouts.add(id)
  return id
}

function trackClearTimeout(id) {
  window.clearTimeout(id)
  pendingTimeouts.delete(id)
}

onMounted(() => {


/* --------------------------------------------------------------------------
   AD-CausalRisk · Crystal Brain study
   Real anatomy: Z-Anatomy / BodyParts3D, via the open Brain Project dataset:
   https://github.com/itayinbarr/brainproject (brain.glb, CC BY-SA 4.0).
   The lightweight credit is kept visible only when the real atlas is shown.
   If both model mirrors fail, a procedural crystal brain is built locally so
   the medical stage never depends on a single CDN request.
   -------------------------------------------------------------------------- */

// The real atlas (cortex + cerebellum + brainstem) is embedded as a compact
// Draco GLB so phones never have to pull the 4.6MB source over mobile data.
// Source: Z-Anatomy / BodyParts3D via Brain Project, CC BY-SA 4.0.
const BRAIN_MODEL_DATA_URL = brainModelUrl;
const MODEL_URLS = [
  BRAIN_MODEL_DATA_URL,
  'https://cdn.jsdelivr.net/gh/itayinbarr/brainproject@2929e94f521a8ddceab26bc100a98dc06b0da060/brain-atlas/models/brain.glb',
  'https://raw.githubusercontent.com/itayinbarr/brainproject/2929e94f521a8ddceab26bc100a98dc06b0da060/brain-atlas/models/brain.glb'
];
const DRACO_PATH = 'https://cdn.jsdelivr.net/npm/three@0.161.0/examples/jsm/libs/draco/';
const ANATOMY_CATEGORIES = new Set(['cortex', 'cerebellum', 'brainstem']);
const PREFERS_REDUCED = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
const MOBILE_QUERY = window.matchMedia('(max-width: 767px)');
// Mobile checks still drive renderer/particle budgets, but the real atlas now loads everywhere.
const MODEL_LITE_QUERY = window.matchMedia('(max-width: 1024px), (pointer: coarse)');
// Only the visual renderer/particles degrade on phones/coarse tablets. A PC at
// 1024px with a fine pointer must keep the full 12-point Lissajous + trails.
const PARTICLE_LITE_QUERY = window.matchMedia('(max-width: 767px), (max-width: 1024px) and (pointer: coarse)');
// A slightly lateral three-quarter view reads as a brain, not a symmetrical oval.
const VIEW_AZIMUTH = THREE.MathUtils.degToRad(64);
const VIEW_ELEVATION = THREE.MathUtils.degToRad(8);

const canvas = document.getElementById('brain-canvas');
const shell = document.getElementById('canvas-shell');
const labelsRoot = document.getElementById('labels');
const loading = document.getElementById('loading');
const loadStatus = document.getElementById('load-status');
const loadProgress = document.getElementById('load-progress');
const credit = document.getElementById('credit');
const hint = document.getElementById('scene-hint');
const errorPanel = document.getElementById('scene-error');
const leaderLayer = document.getElementById('leader-layer');
const stageRoot = document.getElementById('brain-scene');
if (window.matchMedia('(pointer: coarse)').matches) {
  const hintCopy = hint.querySelector('span');
  if (hintCopy) hintCopy.textContent = '单指拖动 · 轻触标签查看详情';
}

const clock = new THREE.Clock();
const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(35, 1, 0.1, 100);
const root = new THREE.Group();
const anatomyPivot = new THREE.Group();
const signalGroup = new THREE.Group();
const anchors = [];
const anchorObjects = {
  fusion: new THREE.Vector3(-1.02, 0.5, 0.32),
  causal: new THREE.Vector3(1.18, 0.17, -0.04),
  risk: new THREE.Vector3(-0.4, -0.67, 0.54)
};
const labelEls = Array.from(document.querySelectorAll('.brain-label'));
const leaderParts = new Map();
const state = {
  ready: false,
  fallback: false,
  dragging: false,
  pointerX: 0,
  pointerY: 0,
  manualYaw: 0,
  manualPitch: 0,
  lastX: 0,
  lastY: 0,
  lastMoveAt: 0,
  loadProgress: 0,
  sceneWidth: 1,
  sceneHeight: 1
};
const look = { x: 0, y: 0, targetX: 0, targetY: 0 };
const SIGNAL_SPEED = 4.2;
const SIGNAL_RETURN_SPEED = 2.8;
const SIGNAL_TRAIL_COUNT = 3;
const SIGNAL_TRAIL_STEP = 0.035;
const SIGNAL_STORE_RADIUS = 0.1;
const SIGNAL_HIDDEN = 100000;
const SIGNAL_SPACE = { rx: 1.0, ry: 0.68, rz: 0.78, cy: -0.02 };
const _signalDirection = new THREE.Vector3();
const _trackPoint = new THREE.Vector3();
const _trackAhead = new THREE.Vector3();
const _trackTrailPoint = new THREE.Vector3();
let activeLabelKey = null;
let signalResetPending = false;
let signalHold = false;
let lastPointerType = 'mouse';
const activeLabelAnchor = new THREE.Vector3();
const AUTO_BASE_Y = -0.25;
const AUTO_BASE_X = 0.015;
// Effective orbit/drag rotate speed for this custom orbit camera.
const DRAG_ROTATE_SPEED = 0.0105;
const DRAG_PITCH_SPEED = 0.0072;
let keyLight;
let fillLight;
let renderer;
let modelRootReady = false;
let signalSystem = null;
let running = false;
let anatomyStarted = false;
let realModelStarted = false;
let detailThrottle = false;
let lastDetailFrame = 0;

root.add(anatomyPivot, signalGroup);
scene.add(root);

setLoading(0, '加载解剖模型');

if (!canvas || !canvas.getContext) {
  showError();
} else {
  try {
    renderer = new THREE.WebGLRenderer({
      canvas,
      antialias: !PARTICLE_LITE_QUERY.matches,
      alpha: true,
      powerPreference: 'high-performance',
      premultipliedAlpha: true
    });
  } catch (error) {
    console.error('[Crystal Brain] WebGL unavailable', error);
    showError();
  }
}

if (renderer) {
  init();
}

function init(){
  renderer.setPixelRatio(MODEL_LITE_QUERY.matches ? Math.min(window.devicePixelRatio || 1, 1.5) : Math.min(window.devicePixelRatio || 1, 1.9));
  renderer.setClearColor(0x000000, 0);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.02;
  renderer.setSize(1, 1, false);

  try{
    scene.environment = createCoolEnvironment(renderer);
  }catch(error){
    console.warn('[Crystal Brain] environment unavailable', error);
  }

  setupLights();
  buildLeaderElements();
  initLabelInteraction();
  camera.position.set(0, 0, 8);
  camera.lookAt(0, 0, 0);

  resize();
  bindPointerInteraction();

  trackListener(window, 'resize', resize, { passive: true });
  if (window.ResizeObserver) {
    resizeObserver = new ResizeObserver(resize); resizeObserver.observe(stageRoot);
  }
  trackListener(document, 'visibilitychange', () => {
    if (!document.hidden) clock.getDelta();
  });

}

function setupLights(){
  scene.add(new THREE.HemisphereLight(0xffffff, 0xd7e9f2, 1.5));
  keyLight = new THREE.DirectionalLight(0xffffff, 1.9);
  keyLight.position.set(4.5, 5.5, 6.2);
  scene.add(keyLight);
  fillLight = new THREE.DirectionalLight(0xb7e8ff, 1.25);
  fillLight.position.set(-5.5, 2.5, 4.0);
  scene.add(fillLight);
  const rim = new THREE.DirectionalLight(0xc6f2ff, 1.7);
  rim.position.set(-2.5, 4.0, -5.5);
  scene.add(rim);
  const bottom = new THREE.DirectionalLight(0xddefff, 0.45);
  bottom.position.set(2.0, -4.0, 2.5);
  scene.add(bottom);
  const glint = new THREE.PointLight(0xffffff, 38, 22, 2);
  glint.position.set(3.4, 4.3, 5.6);
  scene.add(glint);
}

/* A studio made only of white / pale medical blue. The atlas looks wrong
   when reflections borrow the orange or pink of a neutral photo studio. */
function createCoolEnvironment(rendererInstance){
  const width = 1024;
  const height = 512;
  const surface = document.createElement('canvas');
  surface.width = width;
  surface.height = height;
  const ctx = surface.getContext('2d');
  const sky = ctx.createLinearGradient(0, 0, 0, height);
  sky.addColorStop(0, '#ffffff');
  sky.addColorStop(0.38, '#f9feff');
  sky.addColorStop(0.66, '#e8f8fe');
  sky.addColorStop(1, '#bce7f6');
  ctx.fillStyle = sky;
  ctx.fillRect(0, 0, width, height);

  const bloom = (x, y, radius, color) => {
    const glow = ctx.createRadialGradient(x, y, 0, x, y, radius);
    glow.addColorStop(0, color);
    glow.addColorStop(0.42, color.replace(/[\d.]+\)$/, '0.32)'));
    glow.addColorStop(1, 'rgba(255,255,255,0)');
    ctx.fillStyle = glow;
    ctx.beginPath();
    ctx.arc(x, y, radius, 0, Math.PI * 2);
    ctx.fill();
  };
  bloom(245, 112, 245, 'rgba(255,255,255,0.96)');
  bloom(712, 92, 205, 'rgba(255,255,255,0.92)');
  bloom(872, 310, 320, 'rgba(159,222,245,0.48)');
  bloom(326, 410, 290, 'rgba(203,243,251,0.5)');

  const texture = new THREE.CanvasTexture(surface);
  texture.mapping = THREE.EquirectangularReflectionMapping;
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.needsUpdate = true;
  const pmrem = new THREE.PMREMGenerator(rendererInstance);
  const environment = pmrem.fromEquirectangular(texture).texture;
  texture.dispose();
  pmrem.dispose();
  return environment;
}

function buildLeaderElements(){
  if (!leaderLayer) return;
  const NS = 'http://www.w3.org/2000/svg';
  leaderLayer.replaceChildren();
  leaderParts.clear();
  labelEls.forEach((el) => {
    const key = el.dataset.anchor;
    if (!key) return;
    const path = document.createElementNS(NS, 'path');
    path.setAttribute('class', 'leader-line');
    const halo = document.createElementNS(NS, 'circle');
    halo.setAttribute('class', 'leader-halo');
    halo.setAttribute('r', '7');
    const dot = document.createElementNS(NS, 'circle');
    dot.setAttribute('class', 'leader-dot');
    dot.setAttribute('r', '2.4');
    leaderLayer.append(path, halo, dot);
    leaderParts.set(key, { path, halo, dot });
  });
}

function setActiveLabel(key){
  if (key && !anchorObjects[key]) key = null;
  if (key === activeLabelKey) return;
  activeLabelKey = key;
  signalResetPending = true;
  if (key) activeLabelAnchor.copy(anchorObjects[key]);

  labelEls.forEach((el) => {
    const isActive = el.dataset.anchor === key;
    el.classList.toggle('is-active', isActive);
    el.classList.remove('is-full');
    el.style.setProperty('--charge', '0');
    const button = el.querySelector('.label-chip');
    if (button) button.setAttribute('aria-pressed', isActive ? 'true' : 'false');
  });

  leaderParts.forEach((parts, anchorKey) => {
    const isActive = anchorKey === key;
    parts.path.classList.toggle('is-active', isActive);
    parts.halo.classList.toggle('is-active', isActive);
    parts.dot.classList.toggle('is-active', isActive);
  });
}

function updateStoredLabelUI(){
  if (!activeLabelKey || !signalSystem) return;
  const activeLabel = labelEls.find((el) => el.dataset.anchor === activeLabelKey);
  if (!activeLabel) return;
  const ratio = signalSystem.total ? signalSystem.storedCount / signalSystem.total : 0;
  activeLabel.style.setProperty('--charge', ratio.toFixed(3));
  activeLabel.classList.toggle('is-full', signalSystem.storedCount >= signalSystem.total);
}

function initLabelInteraction(){
  if (!labelEls.length) return;

  labelEls.forEach((el) => {
    const key = el.dataset.anchor;
    const button = el.querySelector('.label-chip');
    if (!key || !button) return;

    button.addEventListener('pointerenter', (event) => {
      if (event.pointerType === 'touch') return;
      setActiveLabel(key);
    });

    button.addEventListener('pointerleave', (event) => {
      if (event.pointerType === 'touch' || signalHold) return;
      if (activeLabelKey === key) setActiveLabel(null);
    });

    // Keyboard / touch still get a stable activation; a mouse user's active
    // state is owned by hover and is released on pointerleave.
    button.addEventListener('focus', () => setActiveLabel(key));
    button.addEventListener('blur', () => {
      if (signalHold) return;
      if (activeLabelKey === key) setActiveLabel(null);
    });

    button.addEventListener('click', (event) => {
      event.stopPropagation();
      if (lastPointerType === 'touch') setActiveLabel(key);
      if (typeof window.ADShowLabelDetail === 'function') {
        window.ADShowLabelDetail(key, button);
      }
    });

    button.addEventListener('pointerdown', (event) => {
      event.stopPropagation();
    });
  });

  trackListener(window, 'pointerdown', (event) => {
    lastPointerType = event.pointerType || 'mouse';
    const target = event.target;
    if (target && target.closest && target.closest('.brain-label')) return;
    if (signalHold) return;
    if (activeLabelKey) setActiveLabel(null);
    const activeElement = document.activeElement;
    if (activeElement && activeElement.closest && activeElement.closest('.brain-label')) {
      activeElement.blur();
    }
  }, true);

  trackListener(window, 'keydown', (event) => {
    if (event.key !== 'Escape') return;
    if (activeLabelKey) setActiveLabel(null);
    const activeElement = document.activeElement;
    if (activeElement && activeElement.closest && activeElement.closest('.brain-label')) {
      activeElement.blur();
    }
  });
}

function preloadBrain(){
  if (anatomyStarted) return;
  anatomyStarted = true;

  // 100% local procedural brain. No network / no GLB on phones or tablets.
  buildFallbackBrain();
  finishLoading(false);
  loading.classList.add('is-done');
  loading.setAttribute('aria-hidden', 'true');

  // Warm the hidden canvas and advance the current tracks a few frames so the
  // first visible PC frame already contains the 3-layer trails.
  for (let i = 0; i < 12; i++) updateSignalParticles(1 / 60);
  resize();
  try {
    renderer.render(scene, camera);
  } catch (error) {
    console.warn('[Crystal Brain] preload render failed', error);
  }

  // Every device gets the real atlas as a progressive upgrade over the local
  // fallback. Phones/tablets wait a beat so the login paints first.
  trackTimeout(function(){
    if (realModelStarted) return;
    realModelStarted = true;
    loadAnatomy();
  }, MODEL_LITE_QUERY.matches ? 360 : 140);
}

function startBrain(){
  if (!renderer) {
    showError();
    return;
  }
  // Renderer, canvas size, camera and geometry already exist. Starting the
  // loop must not resize or rebuild anything during the drawer animation.
  if (!running) {
    detailThrottle = false;
    clock.start();
    renderer.render(scene, camera);
    renderer.setAnimationLoop(render);
    running = true;
  }
}

function stopBrain(){
  if (!renderer) return;
  renderer.setAnimationLoop(null);
  running = false;
  detailThrottle = false;
  clock.stop();
}

function setDetailMode(active){
  detailThrottle = !!active;
  if (detailThrottle) lastDetailFrame = performance.now();
}

function setSignalHold(active){
  signalHold = !!active;
}

function deactivateBrain(){
  signalHold = false;
  setActiveLabel(null);
}

function setLoading(percent, message){
  const safe = Math.max(0, Math.min(100, Math.round(percent || 0)));
  loadProgress.style.transform = 'scaleX(' + (safe / 100) + ')';
  loadStatus.textContent = message || ('加载解剖模型 · ' + safe + '%');
}

function finishLoading(hasRealModel){
  state.ready = true;
  setLoading(100, hasRealModel ? '晶体结构已就绪' : '已生成晶体结构');
  credit.hidden = !hasRealModel;
  labelsRoot.classList.add('is-live');
  leaderLayer.classList.add('is-live');
  trackTimeout(() => {
    loading.classList.add('is-done');
    loading.setAttribute('aria-hidden', 'true');
  }, 260);
}

function showError(){
  loading.classList.add('is-done');
  errorPanel.hidden = false;
  labelsRoot.classList.remove('is-live');
  leaderLayer.classList.remove('is-live');
}

/* ============================ model loading ============================ */
async function loadAnatomy(){
  const draco = new DRACOLoader();
  draco.setDecoderPath(DRACO_PATH);
  const loader = new GLTFLoader();
  loader.setDRACOLoader(draco);

  let urlIndex = 0;
  let settled = false;
  let watchdog = null;

  // Fall back only when the transfer genuinely stalls or both mirrors fail.
  // A slow but working download is allowed to finish; the progress bar stays honest.
  const armWatchdog = () => {
    if (watchdog) trackClearTimeout(watchdog);
    watchdog = trackTimeout(() => {
      if (settled || state.ready) return;
      settled = true;
      console.warn('[Crystal Brain] anatomy transfer stalled; using procedural fallback');
      buildFallbackBrain();
      finishLoading(false);
    }, MODEL_LITE_QUERY.matches ? 45000 : 30000);
  };

  const failover = (error) => {
    console.warn('[Crystal Brain] anatomy request failed', MODEL_URLS[urlIndex - 1], error && error.message || error);
    if (settled) return;
    if (urlIndex < MODEL_URLS.length) {
      attempt(urlIndex++);
    } else {
      settled = true;
      if (watchdog) trackClearTimeout(watchdog);
      buildFallbackBrain();
      finishLoading(false);
    }
  };

  const attempt = async (index) => {
    if (settled) return;
    armWatchdog();
    setLoading(6 + index * 2, index === 0 ? '加载解剖模型 · 连接中' : '切换镜像 · 连接中');
    try{
      const gltf = await loader.loadAsync(MODEL_URLS[index], (event) => {
        armWatchdog();
        if (event.lengthComputable && event.total > 0) {
          const pct = Math.max(4, Math.min(96, Math.round(event.loaded / event.total * 92)));
          setLoading(pct, '加载解剖模型 · ' + pct + '%');
        }
      });
      if (settled) return;
      settled = true;
      if (watchdog) trackClearTimeout(watchdog);
      const isReal = buildRealBrain(gltf);
      finishLoading(isReal);
    }catch(error){
      failover(error);
    }
  };

  attempt(urlIndex++);
}

function anatomyCategory(object){
  if (object.userData && object.userData.bx_cat) return object.userData.bx_cat;
  if (object.parent && object.parent.userData && object.parent.userData.bx_cat) return object.parent.userData.bx_cat;
  return null;
}

function buildRealBrain(gltf){
  const pieces = [];
  const identity = new THREE.Matrix4();
  gltf.scene.updateMatrixWorld(true);
  gltf.scene.traverse((object) => {
    if (!object.isMesh || !object.geometry) return;
    const category = anatomyCategory(object);
    if (!ANATOMY_CATEGORIES.has(category)) return;
    // The atlas nodes are baked; avoid cloning and exploding 320k triangles
    // into non-indexed buffers on phones. Only transform when a node is
    // actually offset, and merge indexed data directly when possible.
    let geometry = object.geometry;
    if (!geometry.getAttribute('normal')) geometry.computeVertexNormals();
    for (const key of Object.keys(geometry.attributes)) {
      if (key !== 'position' && key !== 'normal') geometry.deleteAttribute(key);
    }
    if (!object.matrixWorld.equals(identity)) {
      geometry = geometry.clone();
      geometry.applyMatrix4(object.matrixWorld);
    }
    pieces.push(geometry);
  });

  if (!pieces.length) {
    console.warn('[Crystal Brain] no cortex geometry found in atlas; using procedural shape');
    buildFallbackBrain();
    return false;
  }

  let merged = mergeGeometries(pieces, false);
  if (!merged) {
    const nonIndexed = pieces.map((piece) => piece.index ? piece.toNonIndexed() : piece);
    merged = mergeGeometries(nonIndexed, false);
    nonIndexed.forEach((piece) => {
      if (!pieces.includes(piece)) piece.dispose();
    });
  }
  pieces.forEach((piece) => piece.dispose());
  if (!merged) {
    buildFallbackBrain();
    return false;
  }
  merged.computeBoundingBox();
  merged.computeBoundingSphere();
  const sphere = merged.boundingSphere;
  const center = new THREE.Vector3();
  merged.boundingBox.getCenter(center);
  merged.translate(-center.x, -center.y, -center.z);
  const scale = 1.62 / Math.max(0.001, sphere.radius);
  merged.scale(scale, scale, scale);
  merged.computeBoundingBox();
  merged.computeBoundingSphere();

  buildAnatomyFromGeometry(merged, true);
  modelRootReady = true;
  return true;
}

/* A purposely quiet fallback: two lobed crystal hemispheres, a tiny
   cerebellum, and the same crystal shell and labels as the real specimen. */
function buildFallbackBrain(){
  if (state.fallback && modelRootReady) return;
  state.fallback = true;
  const pieces = [];
  const left = makeLobe(-1);
  const right = makeLobe(1);
  pieces.push(left, right);

  const cerebellum = new THREE.SphereGeometry(1, 44, 32);
  const cp = cerebellum.attributes.position;
  const cv = new THREE.Vector3();
  for (let i = 0; i < cp.count; i++) {
    cv.fromBufferAttribute(cp, i);
    const folds = 1 + 0.055 * Math.sin(cv.y * 22 + cv.x * 3) + 0.022 * Math.cos(cv.z * 28);
    cp.setXYZ(i, cv.x * 0.49 * folds, cv.y * 0.24 * folds, cv.z * 0.38 * folds);
  }
  cerebellum.computeVertexNormals();
  cerebellum.translate(-0.42, -0.6, -0.32);
  const cerebellumRight = cerebellum.clone();
  cerebellumRight.translate(0.84, 0, 0);
  pieces.push(cerebellum, cerebellumRight);

  const merged = mergeGeometries(pieces, false);
  pieces.forEach((piece) => piece.dispose());
  if (!merged) {
    showError();
    return;
  }
  merged.computeBoundingSphere();
  const center = new THREE.Vector3();
  merged.computeBoundingBox();
  merged.boundingBox.getCenter(center);
  merged.translate(-center.x, -center.y, -center.z);
  const scale = 1.58 / Math.max(0.001, merged.boundingSphere.radius);
  merged.scale(scale, scale, scale);
  merged.computeBoundingSphere();
  buildAnatomyFromGeometry(merged, false);
  modelRootReady = true;
}

function makeLobe(side){
  const geometry = new THREE.SphereGeometry(1, 86, 64);
  const pos = geometry.attributes.position;
  const v = new THREE.Vector3();
  for (let i = 0; i < pos.count; i++) {
    v.fromBufferAttribute(pos, i);
    const theta = Math.atan2(v.z, v.x);
    const phi = Math.asin(THREE.MathUtils.clamp(v.y, -1, 1));
    const folds =
      1 +
      0.045 * Math.sin(theta * 9 + phi * 4 + side * 1.3) * Math.cos(phi * 7 - theta * 2.1) +
      0.021 * Math.sin(theta * 19 - phi * 10 + side * 0.7) +
      0.011 * Math.cos(theta * 30 + phi * 13);
    const x = v.x * 0.54 * folds + side * 0.53;
    const y = v.y * 0.88 * folds;
    const z = v.z * 1.13 * folds;
    // A slight forehead and occipital taper helps it read as a brain rather than a bean.
    const front = THREE.MathUtils.smoothstep(z, -0.2, 1.0);
    const back = -THREE.MathUtils.smoothstep(z, -1.0, 0.1);
    pos.setXYZ(i, x, y + front * 0.05 + back * 0.01, z - Math.abs(x) * 0.06);
  }
  geometry.computeVertexNormals();
  return geometry;
}

function buildAnatomyFromGeometry(geometry, isReal){
  anatomyPivot.clear();
  const realLite = isReal && MODEL_LITE_QUERY.matches;
  const glassMaterial = new THREE.MeshPhysicalMaterial({
    color: new THREE.Color('#eefbff'),
    roughness: realLite ? 0.16 : 0.13,
    metalness: 0.0,
    // Transmission needs a second full-scene pass. Mobile keeps a clearcoat
    // glass with opacity instead, which preserves the look without that cost.
    transmission: realLite ? 0.0 : (MOBILE_QUERY.matches ? 0.36 : 0.44),
    thickness: 1.0,
    ior: 1.36,
    clearcoat: realLite ? 0.9 : 1.0,
    clearcoatRoughness: 0.06,
    attenuationColor: new THREE.Color('#c4ebf8'),
    attenuationDistance: 3.8,
    transparent: true,
    opacity: realLite ? 0.4 : (isReal ? 0.3 : 0.34),
    depthWrite: false,
    side: THREE.FrontSide,
    envMapIntensity: realLite ? 1.15 : 1.05
  });
  const shell = new THREE.Mesh(geometry, glassMaterial);
  shell.renderOrder = 2;
  shell.frustumCulled = false;
  anatomyPivot.add(shell);

  const wire = new THREE.Mesh(geometry, new THREE.MeshBasicMaterial({
    color: new THREE.Color('#8fc4dc'),
    transparent: true,
    // The real atlas is dense; a lighter wire keeps the anatomical line work
    // without adding moire on a phone display.
    opacity: realLite ? 0.035 : (isReal ? 0.05 : 0.065),
    wireframe: true,
    depthWrite: false,
    side: THREE.FrontSide,
    blending: THREE.NormalBlending
  }));
  wire.renderOrder = 3;
  wire.frustumCulled = false;
  anatomyPivot.add(wire);

  const rim = new THREE.Mesh(geometry, makeRimMaterial());
  rim.renderOrder = 4;
  rim.frustumCulled = false;
  anatomyPivot.add(rim);

  anatomyPivot.rotation.y = Math.PI;
  buildSignalAnchors();
  buildSignalParticles();
}

function makeRimMaterial(){
  return new THREE.ShaderMaterial({
    uniforms: {
      uRim: { value: new THREE.Color('#8ac6e1') },
      uRimStrength: { value: 0.035 }
    },
    vertexShader: `
      varying vec3 vNormal;
      varying vec3 vViewDirection;
      void main(){
        vec4 viewPosition = modelViewMatrix * vec4(position, 1.0);
        vNormal = normalize(normalMatrix * normal);
        vViewDirection = normalize(-viewPosition.xyz);
        gl_Position = projectionMatrix * viewPosition;
      }
    `,
    fragmentShader: `
      uniform vec3 uRim;
      uniform float uRimStrength;
      varying vec3 vNormal;
      varying vec3 vViewDirection;
      void main(){
        float fresnel = pow(1.0 - abs(dot(normalize(vNormal), normalize(vViewDirection))), 2.35);
        gl_FragColor = vec4(uRim, fresnel * uRimStrength);
      }
    `,
    transparent: true,
    depthWrite: false,
    side: THREE.DoubleSide,
    blending: THREE.NormalBlending
  });
}

/* ============================ neural layer ============================ */
/* Labels are the only 3D anchors; there is no ambient node cloud competing
   with the pure signal particles. */
function buildSignalAnchors(){
  anchors.length = 0;
  labelEls.forEach((el) => {
    const key = el.dataset.anchor;
    const position = anchorObjects[key];
    if (position) anchors.push({ el, key, position: position.clone() });
  });
}

/* A tighter sprite than the ambient node glow: white-hot centre, a readable
   medical-blue ring and a soft falloff. This keeps the travelling heads crisp
   against the extremely pale crystal instead of dissolving into it. */
function makeSignalTexture(){
  const size = 128;
  const c = document.createElement('canvas');
  c.width = c.height = size;
  const ctx = c.getContext('2d');
  const gradient = ctx.createRadialGradient(size / 2, size / 2, size * 0.018, size / 2, size / 2, size * 0.5);
  gradient.addColorStop(0, 'rgba(255,255,255,1)');
  gradient.addColorStop(0.14, 'rgba(255,255,255,1)');
  gradient.addColorStop(0.26, 'rgba(178,241,253,.98)');
  gradient.addColorStop(0.42, 'rgba(92,197,226,.86)');
  gradient.addColorStop(0.67, 'rgba(73,169,201,.28)');
  gradient.addColorStop(1, 'rgba(73,169,201,0)');
  ctx.fillStyle = gradient;
  ctx.fillRect(0, 0, size, size);
  const texture = new THREE.CanvasTexture(c);
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.needsUpdate = true;
  return texture;
}

/* ==================== neural currents ==================== */
/* The signals ride six invisible, closed 3D currents inside the brain. Each
   current is an arc-length-resampled Lissajous loop, so particles glide at a
   constant speed with no boundaries, no vertex hops and no surface crawling.
   Short curved trails reveal just enough of each current to make it legible. */

const SIGNAL_CURRENT_DEFS = [
  { fx: 1, fy: 2, fz: 1, ax: 0.92, ay: 0.70, az: 0.84, px: 0.2, py: 1.1, pz: 2.0, speed: 0.72 },
  { fx: 2, fy: 1, fz: 1, ax: 0.82, ay: 0.80, az: 0.78, px: 1.7, py: 0.4, pz: 0.9, speed: 0.86 },
  { fx: 1, fy: 1, fz: 2, ax: 0.74, ay: 0.76, az: 0.70, px: 2.4, py: 2.0, pz: 0.3, speed: 0.62 },
  { fx: 2, fy: 3, fz: 1, ax: 0.76, ay: 0.66, az: 0.88, px: 0.8, py: 2.3, pz: 1.4, speed: 0.92 },
  { fx: 3, fy: 2, fz: 2, ax: 0.70, ay: 0.62, az: 0.70, px: 2.1, py: 0.9, pz: 2.7, speed: 0.76 },
  { fx: 1, fy: 3, fz: 2, ax: 0.86, ay: 0.60, az: 0.76, px: 1.2, py: 0.2, pz: 1.9, speed: 0.82 }
];

const SIGNAL_RAW_STEPS = 720;
const SIGNAL_TRACK_POINTS = 420;

function buildSignalTracks(){
  const tracks = [];
  for (let d = 0; d < SIGNAL_CURRENT_DEFS.length; d++) {
    const def = SIGNAL_CURRENT_DEFS[d];
    const raw = [];
    const cumulative = [];
    let total = 0;
    for (let k = 0; k <= SIGNAL_RAW_STEPS; k++) {
      const t = k / SIGNAL_RAW_STEPS * Math.PI * 2;
      const point = new THREE.Vector3(
        SIGNAL_SPACE.rx * def.ax * Math.sin(def.fx * t + def.px),
        SIGNAL_SPACE.cy + SIGNAL_SPACE.ry * def.ay * Math.sin(def.fy * t + def.py),
        SIGNAL_SPACE.rz * def.az * Math.cos(def.fz * t + def.pz)
      );
      if (k > 0) total += point.distanceTo(raw[k - 1]);
      raw.push(point);
      cumulative.push(total);
    }

    const points = [];
    let cursor = 0;
    for (let j = 0; j < SIGNAL_TRACK_POINTS; j++) {
      const target = total * j / SIGNAL_TRACK_POINTS;
      while (cursor < raw.length - 2 && cumulative[cursor + 1] < target) cursor++;
      const span = cumulative[cursor + 1] - cumulative[cursor] || 1;
      const f = (target - cumulative[cursor]) / span;
      const a = raw[cursor];
      const b = raw[cursor + 1];
      points.push(new THREE.Vector3(
        a.x + (b.x - a.x) * f,
        a.y + (b.y - a.y) * f,
        a.z + (b.z - a.z) * f
      ));
    }
    tracks.push({ points, length: total, speed: def.speed });
  }
  return tracks;
}

function sampleSignalTrack(track, distance, out){
  const length = track.length || 1;
  let d = distance % length;
  if (d < 0) d += length;
  const points = track.points;
  const n = points.length;
  const u = d / length * n;
  const index = Math.floor(u);
  const f = u - index;
  const a = points[index % n];
  const b = points[(index + 1) % n];
  out.set(
    a.x + (b.x - a.x) * f,
    a.y + (b.y - a.y) * f,
    a.z + (b.z - a.z) * f
  );
  return out;
}

function prepareTrackLanding(particle, rng, tracks){
  particle.state = 'return';
  particle.seekElapsed = 0;
  const trackIndex = Math.floor(rng() * tracks.length);
  const track = tracks[trackIndex];
  const s = rng() * track.length;
  sampleSignalTrack(track, s, _trackPoint);
  particle.landingTrackIndex = trackIndex;
  particle.landingS = s;
  particle.landingX = _trackPoint.x;
  particle.landingY = _trackPoint.y;
  particle.landingZ = _trackPoint.z;
}

function attachToTrack(particle, tracks){
  particle.trackIndex = particle.landingTrackIndex;
  particle.s = particle.landingS;
  particle.x = particle.landingX;
  particle.y = particle.landingY;
  particle.z = particle.landingZ;
  particle.dir.set(0, 0, 0);
  particle.state = 'flow';
  particle.seekElapsed = 0;
}

function storeSignalParticle(particle, index){
  particle.state = 'stored';
  particle.x = SIGNAL_HIDDEN;
  particle.y = SIGNAL_HIDDEN;
  particle.z = SIGNAL_HIDDEN;
  signalSystem.storedCount++;
  signalSystem.positions[index] = SIGNAL_HIDDEN;
  signalSystem.positions[index + 1] = SIGNAL_HIDDEN;
  signalSystem.positions[index + 2] = SIGNAL_HIDDEN;
  for (let t = 0; t < signalSystem.trailLayers.length; t++) {
    const layer = signalSystem.trailLayers[t];
    layer.positions[index] = SIGNAL_HIDDEN;
    layer.positions[index + 1] = SIGNAL_HIDDEN;
    layer.positions[index + 2] = SIGNAL_HIDDEN;
  }
  updateStoredLabelUI();
}

function buildSignalParticles(){
  signalGroup.clear();
  signalSystem = null;

  const mobile = MOBILE_QUERY.matches;
  const lite = PARTICLE_LITE_QUERY.matches;
  // The restored lobed fallback keeps the full current system everywhere:
  // 12 heads and 3 trailing layers bound to the same Lissajous tracks.
  const count = 12;
  const trailCount = SIGNAL_TRAIL_COUNT;
  const rng = mulberry32(68123);
  const tracks = buildSignalTracks();
  const positions = new Float32Array(count * 3);
  const particles = new Array(count);
  const groups = tracks.map(() => []);
  for (let i = 0; i < count; i++) groups[i % tracks.length].push(i);

  for (let trackIndex = 0; trackIndex < tracks.length; trackIndex++) {
    const track = tracks[trackIndex];
    const group = groups[trackIndex];
    for (let k = 0; k < group.length; k++) {
      const i = group[k];
      const s = track.length * ((k + 0.5) / group.length + (rng() - 0.5) * 0.04);
      const particle = {
        x: 0, y: 0, z: 0,
        dir: new THREE.Vector3(),
        state: 'flow',
        seekElapsed: 0,
        trackIndex,
        s: ((s % track.length) + track.length) % track.length,
        landingTrackIndex: -1,
        landingS: 0,
        landingX: 0,
        landingY: 0,
        landingZ: 0
      };
      sampleSignalTrack(track, particle.s, _trackPoint);
      particle.x = _trackPoint.x;
      particle.y = _trackPoint.y;
      particle.z = _trackPoint.z;
      particles[i] = particle;
      positions[i * 3] = particle.x;
      positions[i * 3 + 1] = particle.y;
      positions[i * 3 + 2] = particle.z;
    }
  }

  const pointGeometry = new THREE.BufferGeometry();
  const attribute = new THREE.BufferAttribute(positions, 3).setUsage(THREE.DynamicDrawUsage);
  pointGeometry.setAttribute('position', attribute);

  const signalTexture = makeSignalTexture();
  const material = new THREE.PointsMaterial({
    color: new THREE.Color('#effcff'),
    size: lite ? 0.23 : 0.2,
    sizeAttenuation: true,
    map: signalTexture,
    transparent: true,
    opacity: 1,
    alphaTest: 0.015,
    depthWrite: false,
    depthTest: false,
    toneMapped: false
  });
  const points = new THREE.Points(pointGeometry, material);
  points.renderOrder = 5;
  points.frustumCulled = false;
  signalGroup.add(points);

  // Soft additive halo behind the crisp original sprite: this raises the
  // luminance of each current without turning the head into a solid dot.
  const haloMaterial = new THREE.PointsMaterial({
    color: new THREE.Color('#45e5ff'),
    size: lite ? 0.5 : 0.42,
    sizeAttenuation: true,
    map: signalTexture,
    transparent: true,
    opacity: lite ? 0.28 : 0.34,
    alphaTest: 0,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
    depthTest: false,
    toneMapped: false
  });
  const haloPoints = new THREE.Points(pointGeometry, haloMaterial);
  haloPoints.renderOrder = 4.9;
  haloPoints.frustumCulled = false;
  signalGroup.add(haloPoints);

  const sparkMaterial = new THREE.PointsMaterial({
    color: new THREE.Color('#ffffff'),
    size: lite ? 0.11 : 0.09,
    sizeAttenuation: true,
    map: signalTexture,
    transparent: true,
    opacity: 0.82,
    alphaTest: 0,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
    depthTest: false,
    toneMapped: false
  });
  const sparkPoints = new THREE.Points(pointGeometry, sparkMaterial);
  sparkPoints.renderOrder = 5.05;
  sparkPoints.frustumCulled = false;
  signalGroup.add(sparkPoints);

  const trailLayers = [];
  const trailSizes = mobile ? [0.14, 0.11, 0.085] : [0.125, 0.098, 0.073];
  const trailOpacities = [0.62, 0.38, 0.2];
  for (let layer = 0; layer < trailCount; layer++) {
    const trailPositions = new Float32Array(count * 3);
    trailPositions.fill(SIGNAL_HIDDEN);
    const trailGeometry = new THREE.BufferGeometry();
    const trailAttribute = new THREE.BufferAttribute(trailPositions, 3).setUsage(THREE.DynamicDrawUsage);
    trailGeometry.setAttribute('position', trailAttribute);
    const trailMaterial = new THREE.PointsMaterial({
      color: new THREE.Color('#8bd7f0'),
      size: trailSizes[layer],
      sizeAttenuation: true,
      map: signalTexture,
      transparent: true,
      opacity: trailOpacities[layer],
      alphaTest: 0.01,
      depthWrite: false,
      depthTest: false,
      toneMapped: false
    });
    const trailPoints = new THREE.Points(trailGeometry, trailMaterial);
    trailPoints.renderOrder = 4.96 - layer * 0.02;
    trailPoints.frustumCulled = false;
    signalGroup.add(trailPoints);
    trailLayers.push({ positions: trailPositions, attribute: trailAttribute });
  }

  signalSystem = {
    particles,
    positions,
    attribute,
    trailLayers,
    points,
    tracks,
    rng,
    total: count,
    speed: SIGNAL_SPEED,
    returnSpeed: SIGNAL_RETURN_SPEED,
    activeKey: null,
    activePoint: new THREE.Vector3(),
    storedCount: 0
  };
}

function updateSignalParticles(dt){
  if (!signalSystem) return;
  const {
    particles, positions, trailLayers, attribute, rng,
    speed, returnSpeed, tracks, activePoint
  } = signalSystem;
  const step = Math.min(Math.max(dt, 0), 0.05);
  const nextKey = activeLabelKey;

  if (signalResetPending || nextKey !== signalSystem.activeKey) {
    signalResetPending = false;
    const previousKey = signalSystem.activeKey;

    if (nextKey) {
      if (previousKey) {
        for (let i = 0; i < particles.length; i++) {
          const p = particles[i];
          if (p.state !== 'stored') continue;
          p.x = activePoint.x;
          p.y = activePoint.y;
          p.z = activePoint.z;
          p.state = 'seek';
        }
      }
      signalSystem.activePoint.copy(activeLabelAnchor);
      for (let i = 0; i < particles.length; i++) {
        const p = particles[i];
        p.state = 'seek';
        p.seekElapsed = 0;
      }
      signalSystem.storedCount = 0;
    } else if (previousKey) {
      for (let i = 0; i < particles.length; i++) {
        const p = particles[i];
        if (p.state === 'stored') {
          p.x = activePoint.x;
          p.y = activePoint.y;
          p.z = activePoint.z;
        }
        if (p.state === 'stored' || p.state === 'seek') {
          prepareTrackLanding(p, rng, tracks);
        }
      }
      signalSystem.storedCount = 0;
    }

    signalSystem.activeKey = nextKey;
    updateStoredLabelUI();
  }

  const seeking = signalSystem.activeKey !== null;

  for (let i = 0; i < particles.length; i++) {
    const p = particles[i];
    const hi = i * 3;

    if (p.state === 'stored') {
      positions[hi] = SIGNAL_HIDDEN;
      positions[hi + 1] = SIGNAL_HIDDEN;
      positions[hi + 2] = SIGNAL_HIDDEN;
      for (let t = 0; t < trailLayers.length; t++) {
        const layer = trailLayers[t];
        layer.positions[hi] = SIGNAL_HIDDEN;
        layer.positions[hi + 1] = SIGNAL_HIDDEN;
        layer.positions[hi + 2] = SIGNAL_HIDDEN;
      }
      continue;
    }

    if (seeking) {
      const dx = activePoint.x - p.x;
      const dy = activePoint.y - p.y;
      const dz = activePoint.z - p.z;
      const distance = Math.hypot(dx, dy, dz);

      if (distance <= SIGNAL_STORE_RADIUS) {
        storeSignalParticle(p, hi);
        continue;
      }

      p.seekElapsed += step;
      if (PARTICLE_LITE_QUERY.matches) {
        // Lite phones/tablets: straight Vector3.lerp, no curved steering.
        const alpha = Math.min(1, step * 10);
        _signalDirection.set(p.x, p.y, p.z).lerp(activePoint, alpha);
        p.x = _signalDirection.x;
        p.y = _signalDirection.y;
        p.z = _signalDirection.z;
        if (Math.hypot(activePoint.x - p.x, activePoint.y - p.y, activePoint.z - p.z) <= SIGNAL_STORE_RADIUS || p.seekElapsed > 0.8) {
          storeSignalParticle(p, hi);
          continue;
        }
      } else {
        _signalDirection.set(dx / distance, dy / distance, dz / distance);
        const ease = p.seekElapsed > 0.3 ? 1 : 1 - Math.exp(-step * 10);
        p.dir.lerp(_signalDirection, ease);
        if (p.dir.lengthSq() < 0.000001) p.dir.copy(_signalDirection);
        else p.dir.normalize();

        const travel = speed * step;
        if (p.seekElapsed > 0.3 && distance <= travel) {
          storeSignalParticle(p, hi);
          continue;
        }

        p.x += p.dir.x * travel;
        p.y += p.dir.y * travel;
        p.z += p.dir.z * travel;

        if (Math.hypot(activePoint.x - p.x, activePoint.y - p.y, activePoint.z - p.z) <= SIGNAL_STORE_RADIUS) {
          storeSignalParticle(p, hi);
          continue;
        }
      }
    } else if (p.state === 'return') {
      const dx = p.landingX - p.x;
      const dy = p.landingY - p.y;
      const dz = p.landingZ - p.z;
      const distance = Math.hypot(dx, dy, dz);
      const travel = returnSpeed * step;
      if (distance <= travel || distance < 0.02) {
        attachToTrack(p, tracks);
      } else {
        p.x += dx / distance * travel;
        p.y += dy / distance * travel;
        p.z += dz / distance * travel;
        p.dir.set(dx / distance, dy / distance, dz / distance);
      }
    } else {
      const track = tracks[p.trackIndex];
      p.s += track.speed * step;
      if (p.s >= track.length) p.s %= track.length;
      sampleSignalTrack(track, p.s, _trackPoint);
      p.x = _trackPoint.x;
      p.y = _trackPoint.y;
      p.z = _trackPoint.z;
      sampleSignalTrack(track, p.s + 0.02, _trackAhead);
      const dx = _trackAhead.x - p.x;
      const dy = _trackAhead.y - p.y;
      const dz = _trackAhead.z - p.z;
      const length = Math.hypot(dx, dy, dz);
      if (length > 0.000001) p.dir.set(dx / length, dy / length, dz / length);
    }

    positions[hi] = p.x;
    positions[hi + 1] = p.y;
    positions[hi + 2] = p.z;

    for (let t = 0; t < trailLayers.length; t++) {
      const layer = trailLayers[t];
      if (p.state === 'flow') {
        const track = tracks[p.trackIndex];
        sampleSignalTrack(track, p.s - SIGNAL_TRAIL_STEP * (t + 1), _trackTrailPoint);
        layer.positions[hi] = _trackTrailPoint.x;
        layer.positions[hi + 1] = _trackTrailPoint.y;
        layer.positions[hi + 2] = _trackTrailPoint.z;
      } else {
        layer.positions[hi] = SIGNAL_HIDDEN;
        layer.positions[hi + 1] = SIGNAL_HIDDEN;
        layer.positions[hi + 2] = SIGNAL_HIDDEN;
      }
    }
  }

  attribute.needsUpdate = true;
  for (let t = 0; t < trailLayers.length; t++) {
    trailLayers[t].attribute.needsUpdate = true;
  }
}

function mulberry32(seed){
  return function(){
    let t = seed += 0x6D2B79F5;
    t = Math.imul(t ^ t >>> 15, t | 1);
    t ^= t + Math.imul(t ^ t >>> 7, t | 61);
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
  };
}

/* ============================ camera & feel ============================ */
function resize(){
  if (!renderer) return;
  const stageRect = stageRoot.getBoundingClientRect();
  const width = Math.max(1, stageRect.width);
  const height = Math.max(1, stageRect.height);
  const aspect = width / height;
  state.sceneWidth = width;
  state.sceneHeight = height;
  renderer.setPixelRatio(MODEL_LITE_QUERY.matches ? Math.min(window.devicePixelRatio || 1, 1.5) : Math.min(window.devicePixelRatio || 1, 1.9));
  renderer.setSize(width, height, false);
  camera.aspect = aspect;
  camera.fov = aspect < 0.82 ? 36 : 35;
  camera.updateProjectionMatrix();

  // Frame the whole crystal on either axis so the floating labels still have air.
  const verticalFov = THREE.MathUtils.degToRad(camera.fov);
  const horizontalFov = 2 * Math.atan(Math.tan(verticalFov / 2) * aspect);
  const limitingFov = aspect < 0.9 ? horizontalFov : verticalFov;
  const lite = MODEL_LITE_QUERY.matches;
  // The restored fallback is the full lobed brain, so phones/tablets get a
  // slightly wider framing than the desktop version.
  const frameFraction = lite
    ? (aspect < 0.9 ? 0.74 : 0.78)
    : (aspect < 0.9 ? 0.81 : 0.8);
  const radius = lite ? 1.88 : 1.72;
  const distance = THREE.MathUtils.clamp(
    radius / Math.tan(limitingFov * frameFraction / 2),
    5.8,
    18
  );
  const horizontal = Math.cos(VIEW_ELEVATION);
  camera.position.set(
    Math.sin(VIEW_AZIMUTH) * horizontal * distance,
    Math.sin(VIEW_ELEVATION) * distance,
    Math.cos(VIEW_AZIMUTH) * horizontal * distance
  );
  camera.lookAt(0, 0, 0);
  updateLabels();
}

function bindPointerInteraction(){
  shell.addEventListener('pointerdown', (event) => {
    if (event.button !== undefined && event.button !== 0 && event.pointerType === 'mouse') return;
    state.dragging = true;
    state.lastX = event.clientX;
    state.lastY = event.clientY;
    shell.classList.add('is-dragging');
    shell.setPointerCapture?.(event.pointerId);
    hint.classList.add('is-soft');
  });

  shell.addEventListener('pointermove', (event) => {
    const shellRect = shell.getBoundingClientRect();
    const nx = ((event.clientX - shellRect.left) / Math.max(1, shellRect.width)) * 2 - 1;
    const ny = ((event.clientY - shellRect.top) / Math.max(1, shellRect.height)) * 2 - 1;
    state.pointerX = THREE.MathUtils.clamp(nx, -1, 1);
    state.pointerY = THREE.MathUtils.clamp(ny, -1, 1);
    look.targetX = state.pointerX * 0.13;
    look.targetY = state.pointerY * 0.08;

    if (!PREFERS_REDUCED) {
      keyLight.position.x = 4.5 + state.pointerX * 1.12;
      keyLight.position.y = 5.5 - state.pointerY * 0.8;
    }

    if (state.dragging) {
      const dx = event.clientX - state.lastX;
      const dy = event.clientY - state.lastY;
      state.manualYaw = THREE.MathUtils.clamp(state.manualYaw + dx * DRAG_ROTATE_SPEED, -1.0, 1.0);
      state.manualPitch = THREE.MathUtils.clamp(state.manualPitch + dy * DRAG_PITCH_SPEED, -0.36, 0.36);
      state.lastX = event.clientX;
      state.lastY = event.clientY;
      state.lastMoveAt = performance.now();
    }
  });

  const endDrag = (event) => {
    if (!state.dragging) return;
    state.dragging = false;
    shell.classList.remove('is-dragging');
    try { shell.releasePointerCapture?.(event.pointerId); } catch (error) {}
    trackTimeout(() => hint.classList.add('is-soft'), 500);
  };

  shell.addEventListener('pointerup', endDrag);
  shell.addEventListener('pointercancel', endDrag);
  shell.addEventListener('pointerleave', () => {
    look.targetX = 0;
    look.targetY = 0;
    if (!state.dragging) shell.classList.remove('is-dragging');
  });

  // The actual model angles respond to the pointer position even without dragging.
  stageRoot.addEventListener('pointerleave', () => {
    look.targetX = 0;
    look.targetY = 0;
  });
}

function updateLabels(){
  if (!state.ready || !anchors.length) return;
  const width = state.sceneWidth;
  const height = state.sceneHeight;
  const padX = width < 760 ? 58 : 92;
  const padTop = width < 760 ? 58 : 72;
  const padBottom = width < 760 ? 92 : 88;
  const spread = width < 760 ? 44 : 72;
  if (leaderLayer) leaderLayer.setAttribute('viewBox', '0 0 ' + width + ' ' + height);

  const origin = new THREE.Vector3(0, 0, 0).applyMatrix4(root.matrixWorld).project(camera);
  const centerX = (origin.x * 0.5 + 0.5) * width;
  const centerY = (-origin.y * 0.5 + 0.5) * height;

  anchors.forEach(({ el, key, position }) => {
    const projected = position.clone().applyMatrix4(root.matrixWorld).project(camera);
    const anchorX = (projected.x * 0.5 + 0.5) * width;
    const anchorY = (-projected.y * 0.5 + 0.5) * height;
    let dx = anchorX - centerX;
    let dy = anchorY - centerY;
    let length = Math.hypot(dx, dy);
    if (length < 1) {
      dx = 0;
      dy = 1;
      length = 1;
    }
    let x = anchorX + (dx / length) * spread;
    let y = anchorY + (dy / length) * spread;
    x = THREE.MathUtils.clamp(x, padX, width - padX);
    y = THREE.MathUtils.clamp(y, padTop, height - padBottom);
    el.style.transform = 'translate3d(' + x.toFixed(1) + 'px,' + y.toFixed(1) + 'px,0)';

    const leader = leaderParts.get(key);
    if (!leader) return;
    const visible = projected.z > -1 && projected.z < 1 &&
      anchorX > -24 && anchorX < width + 24 &&
      anchorY > -24 && anchorY < height + 24;
    leader.path.style.opacity = visible ? '' : '0';
    leader.halo.style.opacity = visible ? '' : '0';
    leader.dot.style.opacity = visible ? '' : '0';
    if (!visible) return;

    const normalX = dx / length;
    const normalY = dy / length;
    const bend = width < 760 ? 6 : 11;
    const controlX = anchorX + (x - anchorX) * 0.56 - normalY * bend;
    const controlY = anchorY + (y - anchorY) * 0.56 + normalX * bend;
    leader.path.setAttribute('d',
      'M ' + anchorX.toFixed(1) + ' ' + anchorY.toFixed(1) +
      ' Q ' + controlX.toFixed(1) + ' ' + controlY.toFixed(1) +
      ' ' + x.toFixed(1) + ' ' + y.toFixed(1)
    );
    leader.halo.setAttribute('cx', anchorX.toFixed(1));
    leader.halo.setAttribute('cy', anchorY.toFixed(1));
    leader.dot.setAttribute('cx', anchorX.toFixed(1));
    leader.dot.setAttribute('cy', anchorY.toFixed(1));
  });
}

/* ============================ animate ============================ */
function render(){
  if (!renderer) return;
  // In the fully covered detail state we keep the simulation alive at a low
  // frame rate instead of resetting the clock or rebuilding the scene.
  if (detailThrottle) {
    const now = performance.now();
    if (now - lastDetailFrame < 95) return;
    lastDetailFrame = now;
  }
  const dt = Math.min(clock.getDelta(), detailThrottle ? 0.12 : 0.05);
  const elapsed = clock.elapsedTime;

  if (!PREFERS_REDUCED) {
    look.x += (look.targetX - look.x) * 0.075;
    look.y += (look.targetY - look.y) * 0.075;
    const sway = Math.sin(elapsed * 0.23) * 0.045;
    const lift = Math.cos(elapsed * 0.33) * 0.012;
    const desiredY = AUTO_BASE_Y + state.manualYaw + look.x + sway;
    const desiredX = AUTO_BASE_X + state.manualPitch + look.y + lift;
    // Lighter damping makes dragging feel direct rather than weighted.
    root.rotation.y += (desiredY - root.rotation.y) * 0.18;
    root.rotation.x += (desiredX - root.rotation.x) * 0.18;
    root.position.y = Math.sin(elapsed * 0.48) * 0.012;
  } else {
    look.x = 0;
    look.y = 0;
    const desiredY = AUTO_BASE_Y + state.manualYaw;
    const desiredX = AUTO_BASE_X + state.manualPitch;
    root.rotation.y += (desiredY - root.rotation.y) * 0.22;
    root.rotation.x += (desiredX - root.rotation.x) * 0.22;
    root.position.y = 0;
  }

  if (!PREFERS_REDUCED) {
    updateSignalParticles(dt);
  }

  root.updateMatrixWorld(true);
  camera.updateMatrixWorld(true);
  updateLabels();
  renderer.render(scene, camera);
}

/* Small keyboard affordance for a 3D annotation: arrow keys nudge the crystal.
   It is not required for the drag-first concept, but avoids a mouse-only trap. */
shell.addEventListener('keydown', (event) => {
  if (!state.ready || !running) return;
  const step = 0.11;
  if (event.key === 'ArrowLeft') state.manualYaw = THREE.MathUtils.clamp(state.manualYaw - step, -1.0, 1.0);
  else if (event.key === 'ArrowRight') state.manualYaw = THREE.MathUtils.clamp(state.manualYaw + step, -1.0, 1.0);
  else if (event.key === 'ArrowUp') state.manualPitch = THREE.MathUtils.clamp(state.manualPitch - step * 0.6, -0.36, 0.36);
  else if (event.key === 'ArrowDown') state.manualPitch = THREE.MathUtils.clamp(state.manualPitch + step * 0.6, -0.36, 0.36);
  else return;
  event.preventDefault();
});

/* Silent preload after the whole module (and all const initializers) has run.
   This avoids TDZ issues with signal geometry declarations. */
preloadBrain();

/* Public bridge used by the integrated login shell. */
window.ADBrain = {
  start: startBrain,
  stop: stopBrain,
  resize,
  deactivate: deactivateBrain,
  setDetailMode,
  holdLabel: setSignalHold,
  isRunning: function(){ return running; },
  isThrottled: function(){ return detailThrottle; }
};
window.dispatchEvent(new CustomEvent('ad-brain-ready'));
if (window.__ADBrainRequested) startBrain();

/* A tiny boot guard for CDN module failure. The main module logs and replaces
   the loader; this only catches a scenario where the module never executes. */
trackTimeout(() => {
  if (!anatomyStarted || state.ready) return;
  if (!state.ready && loading && !loading.classList.contains('is-done')) {
    const status = document.getElementById('load-status');
    if (status && /连接中|0%/.test(status.textContent || '')) {
      status.textContent = '资源加载较慢，请检查网络或尝试刷新';
    }
  }
}, 18000);
  // Expose the module's own public bridge (window.ADBrain) to the component.
  rendererRef = renderer
  sceneRef = scene
  api = window.ADBrain || null

  // The stage is laid out from mount (hidden via opacity/visibility, never display:none),
  // but the first frame still needs an explicit size so the canvas cannot start at 0×0.
  // Measure now, then again next frame in case fonts or safe-area insets settle.
  if (api) {
    api.resize()
    if (props.isActive) api.start()
    window.requestAnimationFrame(() => api && api.resize())
  }
})

watch(
  () => props.isActive,
  (active) => {
    if (!api) return
    if (active) {
      // Entering the two-screen state changes the stage box (it slides in from the right).
      // Size the renderer to the settled box and update the projection matrix before the
      // loop starts; otherwise the first visible frames are stretched or empty.
      api.resize()
      api.start()
      window.requestAnimationFrame(() => {
        if (!api) return
        api.resize()
        api.start()
      })
    } else {
      api.stop()
    }
  },
)

onUnmounted(() => {
  // 1) stop the render loop and drop the label/hold state
  if (api) {
    api.stop()
    api.deactivate()
  }

  // 2) undo every global side effect the module registered
  resizeObserver?.disconnect()
  resizeObserver = null
  pendingTimeouts.forEach((id) => window.clearTimeout(id))
  pendingTimeouts.clear()
  listenerDisposers.forEach((dispose) => dispose())
  listenerDisposers.length = 0

  // 3) release GPU resources (WebGL context, geometries, materials, textures)
  if (rendererRef) {
    rendererRef.setAnimationLoop(null)
    rendererRef.dispose()
    rendererRef.forceContextLoss?.()
  }
  sceneRef?.traverse((object) => {
    object.geometry?.dispose?.()
    const material = object.material
    if (Array.isArray(material)) material.forEach((entry) => entry?.dispose?.())
    else material?.dispose?.()
  })
  rendererRef = null
  sceneRef = null

  delete window.ADBrain
  api = null
})
</script>
