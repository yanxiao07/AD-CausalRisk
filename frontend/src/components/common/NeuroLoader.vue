<template>
  <div class="neuro-loader" :style="{ '--loader-size': `${size}px` }">
    <canvas ref="canvas" class="neuro-loader__art" role="img" :aria-label="label" />
    <p class="neuro-loader__label">
      {{ label }}
      <span class="neuro-loader__dots" aria-hidden="true"><i>.</i><i>.</i><i>.</i></span>
    </p>
  </div>
</template>

<script setup>
/**
 * 加载动画（Canvas 版，移植自团队提供的 loading-animation 资源）。
 *
 * 图案：手绘侧视脑 + 外圈二进制环 + 沿脑回逐个点亮的信号节点。
 * 动画：2.24s 一个周期，phase 从 0→1 线性推进；环上数字的亮度随 phase
 * 绕圈流动（渐变加载），信号节点按各自的 start 时间依次淡入。
 * 无第三方依赖：用 requestAnimationFrame 驱动，卸载时停止。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'

defineProps({
  label: { type: String, default: '正在载入工作台' },
  size: { type: Number, default: 200 }
})

const canvas = ref(null)

const SIZE = 328
const CENTER = SIZE / 2
const TURN = Math.PI * 2
const PERIOD_MS = 2240
const DIGITS = '10100100101000100010000010100100101001001000100000'
const TEAL = '#426b63'

const OUTLINE = new Path2D(`M 459 466
  C 477 447 421 397 400 354
  C 439 376 480 382 504 366
  C 532 346 549 315 540 280
  C 563 267 571 248 565 220
  C 562 186 546 162 523 160
  C 521 128 491 91 448 90
  C 433 67 406 48 367 41
  C 326 31 306 40 286 62
  C 255 32 205 45 191 88
  C 148 72 110 85 91 117
  C 79 136 82 157 87 171
  C 53 185 36 212 37 252
  C 38 296 62 329 124 321
  C 133 345 168 356 212 351
  C 229 368 259 380 289 369
  C 333 381 365 433 406 449`)

const FOLDS = new Path2D(`M 282 76 Q 284 66 286 62
  M 448 90 C 455 132 418 143 365 118
  M 523 160 C 485 158 454 179 457 206
  M 540 280 Q 525 290 509 290
  M 289 369 Q 317 357 328 327
  M 212 351 Q 198 338 198 318
  M 124 321 L 123 310
  M 87 171 Q 117 163 143 182
  M 109 240 C 151 258 172 208 205 233 Q 217 244 231 237
  M 193 134 C 209 169 256 166 276 203 Q 291 236 280 275
  M 303 156 Q 272 182 278 210
  M 381 177 C 316 182 321 261 400 267`)

const FIXED_NODES = [
  [143, 182],
  [231, 237],
  [365, 118],
  [400, 267]
]

const SIGNALS = [
  { x: 193, y: 134, start: 0.02 },
  { x: 303, y: 156, start: 0.08 },
  { x: 280, y: 99, start: 0.12, path: 'M 282 76 L 280 99' },
  { x: 89, y: 222, start: 0.16, path: 'M 109 240 Q 95 232 89 222' },
  { x: 134, y: 303, start: 0.20, path: 'M 123 310 L 134 303' },
  { x: 201, y: 305, start: 0.26, path: 'M 198 318 L 201 305' },
  { x: 277, y: 290, start: 0.29, path: 'M 280 275 L 277 290' },
  { x: 348, y: 339, start: 0.33, path: 'M 378 408 Q 353 365 348 339' },
  { x: 498, y: 302, start: 0.37, path: 'M 509 290 L 498 302' },
  { x: 458, y: 223, start: 0.40, path: 'M 457 206 L 458 223' }
].map((signal) => ({ ...signal, path: signal.path ? new Path2D(signal.path) : null }))

const digitAngles = Array.from(DIGITS, (_, index) => (index / DIGITS.length) * TURN)

let ctx = null
let raf = 0
let observer = null
let startTime = 0

function clamp(value) {
  return Math.max(0, Math.min(1, value))
}

function dot(x, y) {
  ctx.beginPath()
  ctx.arc(x, y, 17, 0, TURN)
  ctx.fill()
}

function draw(phase) {
  if (!ctx) return
  ctx.clearRect(0, 0, SIZE, SIZE)
  ctx.strokeStyle = '#d3ddd6'
  ctx.lineWidth = 1.4
  ctx.beginPath()
  ctx.arc(CENTER, CENTER, 161.5, 0, TURN)
  ctx.stroke()

  ctx.font = '600 21px "IBM Plex Mono", Consolas, "Courier New", monospace'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillStyle = TEAL
  const head = (phase * 2 + 0.134) % 1

  digitAngles.forEach((angle, index) => {
    const distance = (head - index / DIGITS.length + 1) % 1
    const strength = clamp(distance / 0.055) * clamp((0.61 - distance) / 0.22)
    ctx.save()
    ctx.translate(CENTER, CENTER)
    ctx.rotate(angle)
    // 数字环随 phase 整体缓慢转动，同时亮度波沿环流动。
    ctx.globalAlpha = 0.14 + 0.81 * strength
    ctx.fillText(DIGITS[index], 0, -139)
    ctx.restore()
  })

  ctx.save()
  ctx.translate(64, 82)
  ctx.scale(1 / 3, 1 / 3)
  ctx.lineJoin = 'round'
  ctx.lineCap = 'round'
  ctx.lineWidth = 10.2
  ctx.fillStyle = '#eaf0e8'
  ctx.fill(OUTLINE)
  ctx.strokeStyle = TEAL
  ctx.stroke(OUTLINE)
  ctx.stroke(FOLDS)

  ctx.fillStyle = TEAL
  FIXED_NODES.forEach(([x, y]) => dot(x, y))
  for (const signal of SIGNALS) {
    const fadeIn = clamp((phase - signal.start) / 0.085)
    const fadeOut = clamp((1 - phase) / 0.2)
    ctx.globalAlpha = 0.12 + 0.88 * fadeIn * fadeOut
    if (signal.path) ctx.stroke(signal.path)
    dot(signal.x, signal.y)
  }
  ctx.restore()
}

function fit() {
  if (!canvas.value || !ctx) return
  const pixels = Math.max(1, Math.round(canvas.value.clientWidth * Math.min(window.devicePixelRatio || 1, 3)))
  canvas.value.width = pixels
  canvas.value.height = pixels
  ctx.setTransform(pixels / SIZE, 0, 0, pixels / SIZE, 0, 0)
  draw(framePhase)
}

let framePhase = 0.12

onMounted(() => {
  const el = canvas.value
  if (!el) return
  ctx = el.getContext('2d')
  if (!ctx) return
  fit()
  observer = new ResizeObserver(fit)
  observer.observe(el)

  const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  if (reduce) {
    framePhase = 0.55
    draw(framePhase)
    return
  }

  startTime = performance.now()
  const tick = (now) => {
    framePhase = ((now - startTime) % PERIOD_MS) / PERIOD_MS
    draw(framePhase)
    raf = requestAnimationFrame(tick)
  }
  raf = requestAnimationFrame(tick)
})

onBeforeUnmount(() => {
  if (raf) cancelAnimationFrame(raf)
  raf = 0
  if (observer) observer.disconnect()
  observer = null
  ctx = null
})
</script>

<style scoped>
.neuro-loader {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  width: var(--loader-size);
}

.neuro-loader__art {
  display: block;
  width: 100%;
  height: auto;
  aspect-ratio: 1;
}

.neuro-loader__label {
  margin: 0;
  font-family: var(--font-sans, 'Microsoft YaHei', sans-serif);
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.3px;
  color: #4a746f;
  text-align: center;
}

.neuro-loader__dots i {
  font-style: normal;
  animation: neuro-dot 1.25s ease-in-out infinite;
}

.neuro-loader__dots i:nth-child(2) {
  animation-delay: 0.18s;
}

.neuro-loader__dots i:nth-child(3) {
  animation-delay: 0.36s;
}

@keyframes neuro-dot {
  0%,
  100% {
    opacity: 0.15;
  }
  40% {
    opacity: 1;
  }
}
</style>
