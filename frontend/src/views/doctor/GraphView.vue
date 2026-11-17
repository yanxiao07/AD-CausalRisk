<template>
  <div class="graph-page">
    <PageHeader
      back
      title="因果图谱"
      subtitle="历史图谱集中在这里；点击卡片以抽屉查看，支持拖拽节点与滚轮缩放。"
    >
      <template #extra>
        <el-input
          v-model="inputId"
          class="id-input"
          placeholder="输入评估 ID"
          clearable
          @keyup.enter="load"
        />
        <el-button type="primary" :loading="loading" @click="load">载入图谱</el-button>
      </template>
    </PageHeader>

    <EmptyState
      v-if="loadError"
      :icon="WarningFilled"
      title="图谱载入失败"
      :description="loadError"
      action-text="重试"
      @action="load"
    />

    <template v-else>
      <section class="graph-intro">
        <div class="graph-intro__text">
          <p class="graph-intro__title">图谱档案</p>
          <p class="graph-intro__desc">
            输入评估 ID 或点击下方历史卡片；图谱从右侧抽屉打开，关闭后回到这里，刷新页面也会保留记录。
          </p>
        </div>
        <el-button :icon="Share" @click="goReviews">去待办复核页找报告</el-button>
      </section>

      <GraphHistoryWall
        v-if="history.length"
        :history="history"
        :active-id="assessment?.assessmentId ?? null"
        @open="openHistory"
        @clear="clearHistory"
      />

      <EmptyState
        v-else
        :icon="Share"
        title="还没有历史图谱"
        description="在上方输入评估 ID 载入一张图谱，之后会自动汇总到这里，方便随时回看。"
      />
    </template>

    <!-- 图谱抽屉：无论从输入框载入还是点历史卡片，都用同一套滑入/抽回动画 -->
    <el-drawer
      v-model="graphDrawerOpen"
      :title="drawerTitle"
      direction="rtl"
      :size="drawerWidth"
      class="graph-drawer"
      destroy-on-close
      @keydown.esc="graphDrawerOpen = false"
    >
      <section v-if="assessment" class="stage">
        <header class="stage__head">
          <div class="stage__meta">
            <strong>评估 #{{ assessment.assessmentId }}</strong>
            <span>受试者 #{{ assessment.subjectId }}</span>
            <span>{{ nodes.length }} 个节点 · {{ edges.length }} 条关联</span>
          </div>
          <div class="stage__actions">
            <StatusTag :type="assessment.agentResult?.riskLevel || 'UNAVAILABLE'" />
            <el-button text :icon="RefreshRight" @click="relayout">重新排布</el-button>
          </div>
        </header>

        <CausalGraph :key="graphKey" :graph="graph" :height="canvasHeight" />

        <footer class="stage__foot">
          <span>
            缺失节点用虚线圆表示；实线箭头为已观测条件性关联，虚线为数据缺失导致的待补关联。
          </span>
          <span>runId {{ assessment.agentResult?.runId || '—' }}</span>
        </footer>
      </section>

      <el-skeleton v-else :rows="8" animated />
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { RefreshRight, Share, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import CausalGraph from '@/components/charts/CausalGraph.vue'
import GraphHistoryWall from '@/components/charts/GraphHistoryWall.vue'
import { getAssessment } from '@/api/assessments.js'
import { useUserStore } from '@/stores/user.js'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const HISTORY_PREFIX = 'adc-causalrisk:graph-history'
const HISTORY_LIMIT = 12

/**
 * 历史图谱按当前账号隔离：同一浏览器切换账号/角色后，
 * 只能看到自己打开过的图谱，不会串到别人的数据。
 */
const historyKey = computed(() => {
  const identity = userStore.id ?? userStore.username ?? 'anonymous'
  return `${HISTORY_PREFIX}:${identity}`
})

const inputId = ref('')
const loading = ref(false)
const assessment = ref(null)
const graphKey = ref(0)
const viewportHeight = ref(window.innerHeight)
/** 载入失败的原文（null = 没有错误） */
const loadError = ref('')
/** 本地历史图谱：只保存引用，打开时重新向后端请求完整结果。 */
const history = ref([])

// ---- 抽屉 ----
const graphDrawerOpen = ref(false)
const isNarrow = ref(false)
const drawerWidth = computed(() => (isNarrow.value ? '100%' : '1040px'))
const drawerTitle = computed(() =>
  assessment.value
    ? `个体因果网络 · 评估 #${assessment.value.assessmentId}`
    : '个体因果网络'
)

/** 请求序号守卫：只让最新的载入请求写状态 */
let requestSeq = 0

const graph = computed(() => assessment.value?.agentResult?.graph || null)
const nodes = computed(() => graph.value?.nodes || [])
const edges = computed(() => graph.value?.edges || [])

const canvasHeight = computed(() => Math.max(420, viewportHeight.value - 330))

function onResize() {
  viewportHeight.value = window.innerHeight
  isNarrow.value = window.innerWidth < 1200
}

/** 空态里的引导：没有评估 ID 的人，最可能从待办复核页开始。 */
function goReviews() {
  router.push('/reviews')
}

/** 重新排布：换 key 让图重新跑一次力导向布局 */
function relayout() {
  graphKey.value += 1
}

async function load() {
  const id = Number(inputId.value)
  if (!Number.isInteger(id) || id <= 0) {
    ElMessage.warning('请输入有效的评估 ID')
    return
  }
  await loadAssessment(id)
}

/** 载入一张图谱：成功则打开抽屉、写入历史并把 URL 同步为 ?assessmentId=。 */
async function loadAssessment(id) {
  // 请求序号：连续载入两个 ID 时，只有最后一次的响应能写入页面
  const seq = ++requestSeq
  loading.value = true
  loadError.value = ''
  try {
    const data = await getAssessment(id)
    if (seq !== requestSeq) return
    assessment.value = data
    inputId.value = String(id)
    rememberHistory(data)
    router.replace({ query: { ...route.query, assessmentId: String(id) } })
    graphDrawerOpen.value = true
    if (!data?.agentResult?.graph?.nodes?.length) {
      ElMessage.warning('这次评估没有返回因果图（可能被 BLOCKED）')
    }
  } catch (problem) {
    if (seq !== requestSeq) return
    // 失败时清掉上一份图，避免"看起来是新 ID、其实还是旧数据"
    assessment.value = null
    loadError.value = describeLoadError(problem, id)
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

/** 只保留引用字段；旧版本缓存里的图谱、风险等级和统计一并丢弃。 */
function historyRef(item) {
  const assessmentId = Number(item?.assessmentId)
  if (!Number.isInteger(assessmentId) || assessmentId <= 0) return null
  const subjectId = Number(item?.subjectId)
  return {
    assessmentId,
    subjectId: Number.isInteger(subjectId) && subjectId > 0 ? subjectId : null,
    createdAt: item?.createdAt || item?.savedAt || null,
    savedAt: item?.savedAt || new Date().toISOString()
  }
}

/** 读取本机历史引用（仅当前账号），顺手清理旧版敏感缓存。 */
function readHistory() {
  try {
    const raw = window.localStorage.getItem(historyKey.value)
    const list = raw ? JSON.parse(raw) : []
    const refs = Array.isArray(list) ? list.map(historyRef).filter(Boolean).slice(0, HISTORY_LIMIT) : []
    if (raw) {
      window.localStorage.setItem(historyKey.value, JSON.stringify(refs))
    }
    return refs
  } catch {
    return []
  }
}

/** 记入历史（按评估 ID 去重、最新在前、最多 12 条引用）。 */
function rememberHistory(data) {
  const graphValue = data?.agentResult?.graph
  if (!graphValue?.nodes?.length) return
  const entry = historyRef({
    assessmentId: data.assessmentId,
    subjectId: data.subjectId,
    createdAt: data.createdAt,
    savedAt: new Date().toISOString()
  })
  if (!entry) return
  const next = [
    entry,
    ...history.value.filter((item) => item.assessmentId !== entry.assessmentId)
  ].slice(0, HISTORY_LIMIT)
  history.value = next
  try {
    window.localStorage.setItem(historyKey.value, JSON.stringify(next))
  } catch {
    // 配额不足：只保留最近几张，避免整页不可用
    try {
      window.localStorage.setItem(historyKey.value, JSON.stringify(next.slice(0, 6)))
    } catch {
      // 忽略
    }
  }
}

/** 从历史卡片打开：与输入框载入完全同一条路径（同一抽屉动画）。 */
function openHistory(item) {
  if (!item?.assessmentId) return
  loadAssessment(item.assessmentId)
}

async function clearHistory() {
  try {
    await ElMessageBox.confirm(
      '清空后只影响本机浏览历史，不影响评估数据。确定清空吗？',
      '清空历史图谱',
      {
        confirmButtonText: '清空',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
  } catch {
    return
  }
  history.value = []
  try {
    window.localStorage.removeItem(historyKey.value)
  } catch {
    // 忽略
  }
  ElMessage.success('已清空历史图谱')
}

/** 把后端的 400/403/404/网络错误翻译成页面上的可读提示。 */
function describeLoadError(problem, id) {
  if (!problem) return `评估 #${id} 载入失败，请稍后重试。`
  if (problem.errorCode === 'NETWORK_ERROR') return '无法连接服务器，请确认后端已启动。'
  if (problem.code === 403) return '当前账号无权读取该评估结果。'
  if (problem.message) return problem.message
  return `评估 #${id} 载入失败，请稍后重试。`
}

onMounted(() => {
  isNarrow.value = window.innerWidth < 1200
  window.addEventListener('resize', onResize)
  // 清理旧版本的共享 key：它没有账号隔离，不能再读取/保留
  try {
    window.localStorage.removeItem(HISTORY_PREFIX)
  } catch {
    // 忽略
  }
  history.value = readHistory()
  const fromQuery = route.query.assessmentId
  if (fromQuery) {
    inputId.value = String(fromQuery)
    loadAssessment(Number(fromQuery))
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
})
</script>

<style scoped>
.graph-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.id-input {
  width: 180px;
}

/* ---- 历史图谱引导条 ---- */
.graph-intro {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
  padding: 14px 18px;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: rgba(255, 255, 255, 0.72);
}

.graph-intro__text {
  min-width: 0;
}

.graph-intro__title {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: var(--ink);
}

.graph-intro__desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
}

/* ---- 抽屉里的图谱卡片 ---- */
.stage {
  padding: 4px 2px 0;
}

.stage__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.stage__meta {
  display: flex;
  align-items: baseline;
  gap: 14px;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--muted);
}

.stage__meta strong {
  font-size: 15px;
  color: var(--ink);
}

.stage__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.stage__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-top: 10px;
  font-size: 12px;
  color: var(--soft);
}
</style>
