<template>
  <div class="settings-page">
    <header class="settings-head">
      <div>
        <h1 class="settings-title">系统设置</h1>
        <p class="settings-sub">只读的系统信息与契约版本。修改类配置尚无接口，本页不提供任何写入操作。</p>
      </div>
      <div class="settings-head__right">
        <StatusTag :type="probe.ok ? 'COMPLETED' : 'FAILED'" :label="probe.ok ? '后端在线' : '后端不可达'" />
        <el-button :icon="Refresh" :loading="probing" @click="probeBackend">重新探测</el-button>
      </div>
    </header>

    <div class="grid">
      <article class="panel">
        <h2 class="panel__title">前端构建</h2>
        <dl class="kv">
          <div><dt>版本</dt><dd>{{ appVersion }}</dd></div>
          <div><dt>构建时间</dt><dd>{{ buildTime }}</dd></div>
          <div><dt>运行模式</dt><dd>{{ mode }}</dd></div>
          <div><dt>框架</dt><dd>Vue 3 + Vite + Element Plus</dd></div>
        </dl>
      </article>

      <article class="panel">
        <h2 class="panel__title">Java 后端</h2>
        <dl class="kv">
          <div>
            <dt>连通性</dt>
            <dd>{{ probing ? '探测中…' : probe.ok ? '在线' : probe.message }}</dd>
          </div>
          <div>
            <dt>探测耗时</dt>
            <dd>{{ probe.latencyMs === null ? '—' : `${probe.latencyMs} ms` }}</dd>
          </div>
          <div class="kv__wide">
            <dt>版本</dt>
            <dd>接口未提供：契约里没有 /actuator/info 或版本端点，无法读取</dd>
          </div>
        </dl>
      </article>

      <article class="panel">
        <h2 class="panel__title">Python 算法版本</h2>
        <p class="panel__hint">以下三个常量取自 OpenAPI 契约的 <code>const</code> 声明，运行期由 Java 透传。</p>
        <dl class="kv">
          <div><dt>模型版本</dt><dd>{{ modelVersion }}</dd></div>
          <div><dt>Agent 契约</dt><dd>{{ agentContractVersion }}</dd></div>
          <div><dt>工作流版本</dt><dd>{{ workflowVersion }}</dd></div>
          <div><dt>实际运行的 runId</dt><dd>每份评估各自记录（见报告页）</dd></div>
        </dl>
      </article>

      <article class="panel">
        <h2 class="panel__title">OpenAPI 契约</h2>
        <dl class="kv">
          <div><dt>版本</dt><dd>{{ contractVersion }}</dd></div>
          <div><dt>操作数</dt><dd>{{ contractOps }}</dd></div>
          <div><dt>路径数</dt><dd>{{ contractPaths }}</dd></div>
          <div class="kv__wide"><dt>来源</dt><dd>contracts/java-business.target.openapi.json</dd></div>
        </dl>
      </article>

      <article class="panel panel--wide">
        <h2 class="panel__title">固定的业务规则</h2>
        <ul class="rules">
          <li><strong>预测窗口</strong>6–60 个月，默认 24 个月；超出范围直接 400。</li>
          <li><strong>BLOCKED</strong> 表示 <code>riskScore = null</code>、<code>riskLevel = UNAVAILABLE</code>，绝不用 0 或插值补分。</li>
          <li><strong>患者端</strong>只接收通俗摘要，不包含原始分数、图权重与内部轨迹。</li>
          <li><strong>审计日志</strong>仅 ADMIN 可读；写入走独立事务，失败不阻断业务。</li>
          <li><strong>评估复核</strong>同一评估只允许一次决策，重复提交返回 400。</li>
          <li><strong>幂等</strong>导入与评估任务都按 <code>Idempotency-Key</code> + 请求摘要去重。</li>
        </ul>
      </article>
    </div>

    <p class="settings-foot">
      配置项只读：契约的 22 个操作里没有系统设置/参数管理接口，因此本页不提供保存按钮。
      需要可配置项时，先在后端补 <code>GET/PATCH /api/system/settings</code> 再扩展本页。
    </p>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import StatusTag from '@/components/common/StatusTag.vue'
import request from '@/api/request.js'

const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit'
})

function formatTime(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : timeFormatter.format(date)
}
/**
 * 构建期注入的常量（见 vite.config.js 的 define）。
 * 用 typeof 保护，避免在未注入的环境（例如单测）里报 ReferenceError。
 */
const appVersion = typeof __APP_VERSION__ === 'string' ? __APP_VERSION__ : 'dev'
const buildTime = typeof __BUILD_TIME__ === 'string' ? formatTime(__BUILD_TIME__) : '—'
const contractVersion = typeof __CONTRACT_VERSION__ === 'string' ? __CONTRACT_VERSION__ : '—'
const contractOps = typeof __CONTRACT_OPS__ === 'number' ? __CONTRACT_OPS__ : '—'
const contractPaths = typeof __CONTRACT_PATHS__ === 'number' ? __CONTRACT_PATHS__ : '—'
const modelVersion = typeof __MODEL_VERSION__ === 'string' ? __MODEL_VERSION__ : '—'
const agentContractVersion =
  typeof __AGENT_CONTRACT_VERSION__ === 'string' ? __AGENT_CONTRACT_VERSION__ : '—'
const workflowVersion = typeof __WORKFLOW_VERSION__ === 'string' ? __WORKFLOW_VERSION__ : '—'
const mode = import.meta.env.MODE

const probing = ref(false)
const probe = ref({ ok: false, latencyMs: null, message: '未探测' })



/** 真实探测：/api/me 需要登录态，能返回就说明后端与安全链都活着。 */
async function probeBackend() {
  probing.value = true
  const startedAt = performance.now()
  try {
    await request.get('/me')
    probe.value = {
      ok: true,
      latencyMs: Math.round(performance.now() - startedAt),
      message: '在线'
    }
  } catch (problem) {
    probe.value = {
      ok: false,
      latencyMs: Math.round(performance.now() - startedAt),
      message: problem?.code === 403 ? '无权限（403）' : '不可达'
    }
  } finally {
    probing.value = false
  }
}

onMounted(probeBackend)
</script>

<style scoped>
.settings-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.settings-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.settings-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: #eaf6ff;
}

.settings-sub {
  margin: 6px 0 0;
  font-size: 13px;
  color: #7fa6c4;
}

.settings-head__right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
  gap: 16px;
}

.panel {
  padding: 18px 20px;
  border: 1px solid rgba(120, 190, 235, 0.18);
  border-radius: var(--r-lg);
  background: linear-gradient(160deg, rgba(22, 54, 80, 0.72), rgba(12, 32, 50, 0.72));
}

.panel--wide {
  grid-column: 1 / -1;
}

.panel__title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.3px;
  color: #dcecfb;
}

.panel__hint {
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.7;
  color: #7fa6c4;
}

.kv {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 10px 18px;
  margin: 0;
}

.kv > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.kv__wide {
  grid-column: 1 / -1;
}

.kv dt {
  font-size: 11px;
  letter-spacing: 0.4px;
  text-transform: uppercase;
  color: #5d86a4;
}

.kv dd {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #dcecfb;
  word-break: break-word;
}

.rules {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rules li {
  font-size: 13px;
  line-height: 1.7;
  color: #a8c4d6;
}

.rules strong {
  color: #dcecfb;
  margin-right: 4px;
}

code {
  padding: 1px 5px;
  border-radius: 4px;
  background: rgba(79, 195, 247, 0.12);
  color: #9fd4f0;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}

.settings-foot {
  margin: 0;
  font-size: 12px;
  line-height: 1.8;
  color: #5d86a4;
}
</style>
