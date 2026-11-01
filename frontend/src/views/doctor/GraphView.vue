<template>
  <div class="graph-page">
    <PageHeader title="因果图谱" subtitle="在更大的画布上查看个体因果网络；支持拖拽节点与滚轮缩放。">
      <template #extra>
        <el-input
          v-model="inputId"
          class="id-input"
          placeholder="输入评估 ID"
          clearable
          @keyup.enter="load"
        />
        <el-button type="primary" :loading="loading" @click="load">载入图谱</el-button>
        <el-button :icon="FullScreen" :disabled="!graph" @click="toggleImmersive">
          {{ immersive ? '退出沉浸' : '沉浸查看' }}
        </el-button>
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

    <EmptyState
      v-else-if="!graph"
      :icon="Share"
      title="还没有载入图谱"
      description="输入评估 ID 后载入该受试者的个体因果网络；也可以从「待办复核」或「评估报告」跳转过来。"
    />

    <section v-else class="stage" :class="{ 'stage--immersive': immersive }">
      <header class="stage__head">
        <div class="stage__meta">
          <strong>评估 #{{ assessment?.assessmentId }}</strong>
          <span>受试者 #{{ assessment?.subjectId }}</span>
          <span>{{ nodes.length }} 个节点 · {{ edges.length }} 条关联</span>
        </div>
        <div class="stage__actions">
          <StatusTag :type="assessment?.agentResult?.riskLevel || 'UNAVAILABLE'" />
          <el-button text :icon="RefreshRight" @click="relayout">重新排布</el-button>
        </div>
      </header>

      <CausalGraph :key="graphKey" :graph="graph" :height="canvasHeight" />

      <footer class="stage__foot">
        <span>
          缺失节点用虚线圆表示；实线箭头为已观测条件性关联，虚线为数据缺失导致的待补关联。
        </span>
        <span>runId {{ assessment?.agentResult?.runId || '—' }}</span>
      </footer>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { FullScreen, RefreshRight, Share, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import CausalGraph from '@/components/charts/CausalGraph.vue'
import { getAssessment } from '@/api/assessments.js'

const route = useRoute()

const inputId = ref('')
const loading = ref(false)
const assessment = ref(null)
const immersive = ref(false)
const graphKey = ref(0)
const viewportHeight = ref(window.innerHeight)
/** 载入失败的原文（null = 没有错误） */
const loadError = ref('')

/** 请求序号守卫：只让最新的载入请求写状态 */
let requestSeq = 0

const graph = computed(() => assessment.value?.agentResult?.graph || null)
const nodes = computed(() => graph.value?.nodes || [])
const edges = computed(() => graph.value?.edges || [])

const canvasHeight = computed(() =>
  immersive.value ? Math.max(360, viewportHeight.value - 200) : 560
)

function onResize() {
  viewportHeight.value = window.innerHeight
}

function toggleImmersive() {
  immersive.value = !immersive.value
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
  // 请求序号：连续载入两个 ID 时，只有最后一次的响应能写入页面
  const seq = ++requestSeq
  loading.value = true
  loadError.value = ''
  try {
    const data = await getAssessment(id)
    if (seq !== requestSeq) return
    assessment.value = data
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

/** 把后端的 400/403/404/网络错误翻译成页面上的可读提示。 */
function describeLoadError(problem, id) {
  if (!problem) return `评估 #${id} 载入失败，请稍后重试。`
  if (problem.errorCode === 'NETWORK_ERROR') return '无法连接服务器，请确认后端已启动。'
  if (problem.code === 403) return '当前账号无权读取该评估结果。'
  if (problem.message) return problem.message
  return `评估 #${id} 载入失败，请稍后重试。`
}

onMounted(() => {
  window.addEventListener('resize', onResize)
  const fromQuery = route.query.assessmentId
  if (fromQuery) {
    inputId.value = String(fromQuery)
    load()
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

.stage {
  padding: 16px 18px 14px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-card);
}

.stage--immersive {
  position: fixed;
  inset: 0;
  z-index: 3000;
  border-radius: 0;
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  overflow: auto;
}

.stage__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.stage__meta {
  display: flex;
  align-items: baseline;
  gap: 14px;
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
  margin-top: 10px;
  font-size: 12px;
  color: var(--soft);
}
</style>
