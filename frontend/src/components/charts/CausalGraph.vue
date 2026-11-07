<template>
  <div class="causal-graph">
    <!--
      缩放/平移放在容器上做（CSS transform），而不是交给 ECharts 的 roam：
      ECharts 缩放时不会重算 edgeLabel 的坐标，线上文字会留在原处。
      整体变换能让节点、连线、线上文字作为一个整体缩放与移动，永远不会脱节。
    -->
    <div
      ref="viewportEl"
      class="causal-graph__viewport"
      :style="{ height: `${height}px` }"
      @wheel.prevent="onWheel"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
      @pointerleave="onPointerUp"
      @dblclick="resetView"
    >
      <div ref="chartEl" class="causal-graph__canvas" :style="canvasStyle" />
    </div>

    <div class="causal-graph__legend">
      <span class="legend__item"><i class="dot dot--prior" />先验证据（PRIOR）</span>
      <span class="legend__item"><i class="dot dot--model" />模型证据（MODEL）</span>
      <span class="legend__item"><i class="dot dot--missing" />缺失数据</span>
    </div>
    <p class="causal-graph__note">
      连线表示<strong>条件性关联</strong>（direction = CONDITIONAL），不是病因证明；
      箭头方向用于解释模型输入，不代表临床因果结论。滚轮缩放、拖动查看，悬停连线看类型，
      <strong>双击复位</strong>。
    </p>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([GraphChart, TooltipComponent, CanvasRenderer])

const props = defineProps({
  /**
   * AgentResult.graph：{ nodes: [{id,label,status,evidenceLevel,observedAt}], edges: [{from,to,note,status,evidenceLevel,direction}] }
   */
  graph: { type: Object, default: null },
  height: { type: Number, default: 380 }
})

const chartEl = ref(null)
const viewportEl = ref(null)
let chart = null
let observer = null

// 容器级缩放/平移：文字与图形始终同步，见模板里的说明
const scale = ref(1)
const offsetX = ref(0)
const offsetY = ref(0)
const MIN_SCALE = 0.6
const MAX_SCALE = 3
let dragging = false
let dragStartX = 0
let dragStartY = 0

const canvasStyle = computed(() => ({
  height: `${props.height}px`,
  transform: `translate3d(${offsetX.value}px, ${offsetY.value}px, 0) scale(${scale.value})`,
  transformOrigin: '0 0'
}))

const PRIOR = '#0a73ad'
const MODEL = '#0e806e'
const MUTED = '#9db4c2'

function nodeStyle(node) {
  const missing = node.status === 'MISSING'
  const color = node.evidenceLevel === 'MODEL' ? MODEL : PRIOR
  return {
    itemStyle: {
      color: missing ? '#ffffff' : color,
      borderColor: missing ? MUTED : color,
      borderWidth: missing ? 1.5 : 1,
      borderType: missing ? 'dashed' : 'solid',
      shadowBlur: missing ? 0 : 12,
      shadowColor: 'rgba(10,115,173,0.25)'
    },
    label: {
      color: missing ? MUTED : '#11344b',
      fontWeight: missing ? 400 : 600
    }
  }
}

/**
 * 后端的 note 是完整句子（"研究先验证据，不代表已识别的个体因果效应"），
 * 直接贴到线上一定会压字。这里压成 ≤6 字的短标签，完整文案留给 tooltip。
 */
function shortNote(note) {
  const text = String(note ?? '').trim()
  if (!text) return ''
  if (/缺失|missing/i.test(text)) return '数据缺失'
  if (/先验|prior/i.test(text)) return '先验关联'
  if (/模型|归因|model/i.test(text)) return '模型归因'
  if (/条件|conditional/i.test(text)) return '条件关联'
  return text.length <= 6 ? text : `${text.slice(0, 6)}…`
}

/** 画布高度相对 380px 基准的缩放系数（1 ~ 1.8）。 */
function layoutScaleOf(height) {
  const base = 380
  return Math.min(1.8, Math.max(1, height / base))
}

function buildOption(graph) {
  const layoutScale = layoutScaleOf(props.height)
  const rawEdges = graph?.edges ?? []
  // 边多的时候先不铺边标签，避免整张图被文字淹没；悬停仍能看到完整说明
  const showEdgeLabels = rawEdges.length <= 12

  const nodes = (graph?.nodes ?? []).map((node) => ({
    id: node.id,
    name: node.label || node.id,
    symbolSize: node.status === 'MISSING' ? 34 : 46,
    ...nodeStyle(node),
    // tooltip 用
    value: node
  }))

  const edges = rawEdges.map((edge) => {
    const missing = edge.status === 'MISSING'
    return {
      source: edge.from,
      target: edge.to,
      // 短标签走 edgeLabel 的 {c}；完整 note 留在 data 上给 tooltip
      value: shortNote(edge.note),
      note: edge.note || '',
      lineStyle: {
        color: missing ? MUTED : 'rgba(10,115,173,0.55)',
        width: missing ? 1 : 1.5,
        type: missing ? 'dashed' : 'solid',
        curveness: 0.1,
        opacity: missing ? 0.5 : 0.8
      }
    }
  })

  return {
    backgroundColor: 'transparent',
    tooltip: {
      confine: true,
      backgroundColor: 'rgba(255,255,255,0.96)',
      borderColor: '#dbe9f1',
      textStyle: { color: '#11344b', fontSize: 12 },
      formatter: (params) => {
        if (params.dataType === 'edge') {
          const note = params.data.note || '无备注'
          return `<b>${params.data.source} → ${params.data.target}</b><br/>${note}<br/><span style="color:#637f91">条件性关联 · 非病因证明</span>`
        }
        const node = params.data.value || {}
        const status = node.status === 'MISSING' ? '缺失' : '已观测'
        const level = node.evidenceLevel === 'MODEL' ? '模型证据' : '先验证据'
        return `<b>${node.label || node.id}</b><br/>状态：${status} · ${level}${
          node.observedAt ? `<br/>观测日期：${node.observedAt}` : ''
        }`
      }
    },
    series: [
      {
        // 画布越高，力参数同步放大，否则节点会缩在中间一小团
        type: 'graph',
        layout: 'force',
        // 缩放/平移交给外层容器（CSS transform）：ECharts 内部的 roam 缩放不会重算
        // edgeLabel 坐标，线上文字会留在原处。容器整体变换能保证文字、线、节点同步。
        roam: false,
        draggable: false,
        // 留足边距：节点标签放在下方，边距太小会被裁在卡片边缘
        top: 36,
        bottom: 36,
        left: 36,
        right: 36,
        symbol: 'circle',
        edgeSymbol: ['none', 'arrow'],
        edgeSymbolSize: 7,
        // 节点名放节点下方，并让 ECharts 自动隐藏互相重叠的标签
        label: {
          show: true,
          position: 'bottom',
          distance: 8,
          fontSize: 11,
          fontWeight: 600,
          color: '#11344b'
        },
        labelLayout: { hideOverlap: true },
        // 边标签常驻显示（边不多时），悬停/高亮时加重
        edgeLabel: {
          show: showEdgeLabels,
          formatter: '{c}',
          fontSize: 10,
          color: '#5f7887',
          backgroundColor: 'rgba(255,255,255,0.9)',
          padding: [2, 4],
          borderRadius: 4,
          align: 'middle',
          verticalAlign: 'middle'
        },
        // 缩放由外层容器负责，这里不再需要 ECharts 的 scaleLimit
        // 边标签常驻显示（边不多时），悬停/高亮时加重
        emphasis: {
          focus: 'adjacency',
          lineStyle: { width: 2.4, opacity: 1 },
          edgeLabel: { show: true, fontSize: 10, color: '#11344b' }
        },
        force: {
          // 斥力要够大才不挤在一起，但太大会把边缘节点挤出画布。
          // 这里按画布高度缩放：380px 时是 900 / 110~170，560px 约 1.47 倍，最大 1.8 倍。
          repulsion: 900 * layoutScale,
          edgeLength: [110 * layoutScale, 170 * layoutScale],
          gravity: 0.09,
          friction: 0.2,
          // 力布局动画保留：这是"丝滑"的来源，不要为了稳定把它关掉
          layoutAnimation: true
        },
        data: nodes,
        links: edges,
        lineStyle: { opacity: 0.8 }
      }
    ]
  }
}

function render() {
  if (!chart) return
  const graph = props.graph
  if (!graph || !(graph.nodes?.length)) {
    chart.clear()
    return
  }
  chart.setOption(buildOption(graph), { notMerge: true })
}

/**
 * 只有容器拿到正尺寸才初始化。
 *
 * el-drawer 的滑入动画约 300ms，动画期间容器宽度是 0；此时 init 会让 ECharts 按 0 尺寸
 * 计算缩放，表现就是字号被无限放大、线条飞出画布。这里先等动画结束，再用 ResizeObserver
 * 兜底（动画超过 300ms 或之后才挂载时也能补上）。
 */
const INIT_DELAY_MS = 300

let initTimer = null
let lastWidth = 0

function hasUsableSize(el) {
  if (!el) return false
  const { width, height } = el.getBoundingClientRect()
  return width >= 40 && height >= 40
}

function ensureChart() {
  if (chart) return chart
  if (!hasUsableSize(chartEl.value)) return null
  chart = echarts.init(chartEl.value, null, { renderer: 'canvas' })
  lastWidth = chartEl.value.getBoundingClientRect().width
  return chart
}

onMounted(() => {
  // 不立刻 init：等抽屉/弹窗的 slide 动画结束、容器尺寸稳定
  initTimer = window.setTimeout(() => {
    initTimer = null
    if (!ensureChart()) return
    render()
  }, INIT_DELAY_MS)

  observer = new ResizeObserver(() => {
    const el = chartEl.value
    if (!el) return
    const { width, height } = el.getBoundingClientRect()
    if (width < 1 || height < 1) return

    if (!chart) {
      // 动画比 300ms 更久：尺寸一稳定就补上初始化
      if (ensureChart()) render()
      return
    }
    chart.resize()
    // 从 0 宽恢复成正常宽度时，重新布局一次，修掉按错误尺寸算出的力导向布局
    if (lastWidth === 0) render()
    lastWidth = width
  })
  observer.observe(chartEl.value)
})

watch(
  () => props.graph,
  () => render(),
  { deep: true }
)

/** 滚轮缩放：以鼠标位置为锚点，保证指针下的内容不动。 */
function onWheel(event) {
  const viewport = viewportEl.value
  if (!viewport) return
  const rect = viewport.getBoundingClientRect()
  const pointerX = event.clientX - rect.left
  const pointerY = event.clientY - rect.top
  const factor = event.deltaY < 0 ? 1.12 : 1 / 1.12
  const next = Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale.value * factor))
  const ratio = next / scale.value
  offsetX.value = pointerX - (pointerX - offsetX.value) * ratio
  offsetY.value = pointerY - (pointerY - offsetY.value) * ratio
  scale.value = next
}

function onPointerDown(event) {
  if (event.button !== 0) return
  dragging = true
  dragStartX = event.clientX - offsetX.value
  dragStartY = event.clientY - offsetY.value
}

function onPointerMove(event) {
  if (!dragging) return
  offsetX.value = event.clientX - dragStartX
  offsetY.value = event.clientY - dragStartY
}

function onPointerUp() {
  dragging = false
}

/** 双击复位：缩放与平移都回到初始，避免图被拖出可视区。 */
function resetView() {
  scale.value = 1
  offsetX.value = 0
  offsetY.value = 0
}

onBeforeUnmount(() => {
  if (initTimer) window.clearTimeout(initTimer)
  initTimer = null
  dragging = false
  observer?.disconnect()
  observer = null
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.causal-graph {
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: linear-gradient(180deg, #ffffff 0%, #f6fbfe 100%);
  overflow: hidden;
}

.causal-graph__viewport {
  position: relative;
  overflow: hidden;
  cursor: grab;
  touch-action: none;
  user-select: none;
}

.causal-graph__viewport:active {
  cursor: grabbing;
}

.causal-graph__canvas {
  width: 100%;
  /* 高度由内联样式给定；缩放/平移由外层 transform 驱动，这里只负责绘制 */
  will-change: transform;
}

.causal-graph__legend {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  padding: 10px 14px;
  border-top: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.7);
}

.legend__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--muted);
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
}

.dot--prior {
  background: #0a73ad;
}

.dot--model {
  background: #0e806e;
}

.dot--missing {
  background: #fff;
  border: 1.5px dashed var(--soft);
}

.causal-graph__note {
  margin: 0;
  padding: 10px 14px 14px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--muted);
}

.causal-graph__note strong {
  color: var(--ink-700);
}
</style>
