<template>
  <div class="console-page">
    <PageHeader
      back
      title="系统控制台"
      subtitle="运行时健康、评估通道、研究宿主与审计保留策略的统一运维入口。所有保存立即生效并写入审计。"
    >
      <template #extra>
        <span class="health-pill" :class="probe.ok ? 'health-pill--ok' : 'health-pill--bad'">
          <span class="health-pill__dot" />
          {{ probing ? '探测中' : probe.ok ? '后端在线' : '后端不可达' }}
        </span>
        <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新全部</el-button>
      </template>
    </PageHeader>

    <div class="console-grid">
      <!-- 1. 系统健康 -->
      <article class="panel panel--wide">
        <header class="panel__head">
          <div>
            <h2 class="panel__title">系统健康</h2>
            <p class="panel__hint">Java 安全链、研究宿主与接口契约；旧规则算法服务不再参与新评估。</p>
          </div>
          <el-button text :icon="Refresh" :loading="probing" @click="probeBackend">重新探测</el-button>
        </header>

        <div class="health-grid">
          <div class="health-tile">
            <span class="health-tile__label">Java 后端</span>
            <strong class="health-tile__value" :class="probe.ok ? 'is-ok' : 'is-bad'">
              {{ probing ? '探测中…' : probe.ok ? '在线' : probe.message }}
            </strong>
            <span class="health-tile__meta">
              {{ probe.latencyMs === null ? '—' : `${probe.latencyMs} ms` }} ·
              {{ info?.appVersion || '—' }} · Java {{ info?.javaVersion || '—' }}
            </span>
            <span class="health-tile__meta mono">{{ info?.gitCommit && info.gitCommit !== 'UNKNOWN' ? info.gitCommit : '构建提交未提供' }}</span>
          </div>

          <div class="health-tile">
            <span class="health-tile__label">研究宿主</span>
            <strong class="health-tile__value" :class="hostState.ok ? 'is-ok' : 'is-bad'">
              {{ hostState.label }}
            </strong>
            <span class="health-tile__meta">{{ host?.baseUrl || '尚未配置服务地址' }}</span>
            <span class="health-tile__meta">
              {{ hostHealth?.available
                ? `响应 ${hostHealth.latencyMs ?? '—'} ms · HTTP ${hostHealth.httpStatus ?? '—'}`
                : hostHealth?.message || '等待探测' }}
            </span>
            <span class="health-tile__meta">动态快照 · 读取超时 {{ host?.readTimeoutSeconds ?? 120 }} 秒</span>
          </div>

          <div class="health-tile">
            <span class="health-tile__label">接口契约</span>
            <strong class="health-tile__value">{{ contractOps }} 个操作</strong>
            <span class="health-tile__meta">{{ contractPaths }} 条路径 · {{ contractVersion }}</span>
            <span class="health-tile__meta mono">前端构建 {{ appVersion }} · {{ buildTime }}</span>
          </div>
        </div>
      </article>

      <!-- 2. 模型与通道 -->
      <article class="panel panel--wide">
        <header class="panel__head">
          <div>
            <h2 class="panel__title">模型与通道</h2>
            <p class="panel__hint">
              模型开关只影响新提交；旧规则评估通道关闭后，<code>POST /api/assessment-tasks</code> 返回 503，
              新评估请走研究表格快照。
            </p>
          </div>
          <el-button text :icon="Refresh" :loading="infoLoading" @click="loadInfo">刷新模型</el-button>
        </header>

        <el-skeleton v-if="infoLoading && models.length === 0" :rows="3" animated />
        <el-empty
          v-else-if="!models.length"
          :description="infoError || '没有已安装的模型版本'"
          :image-size="64"
        />
        <el-table v-else :data="models" size="small" class="model-table">
          <el-table-column prop="model_version" label="模型版本" min-width="250" show-overflow-tooltip />
          <el-table-column label="启用" width="100">
            <template #default="{ row }">
              <el-switch
                :model-value="isEnabled(row)"
                :loading="togglingVersion === row.model_version"
                @change="(value) => toggleModel(row, value)"
              />
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="180">
            <template #default="{ row }">{{ formatTime(row.updated_at) }}</template>
          </el-table-column>
          <el-table-column label="影响" min-width="160">
            <template #default="{ row }">
              <span v-if="isEnabled(row)" class="state state--on">可创建新评估</span>
              <span v-else class="state state--off">仅保留历史结果</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="channel-row">
          <div class="channel-row__text">
            <strong>旧规则评估通道</strong>
            <span>
              {{ policyDraft ? '开启：仅用于兼容历史演示，不再进入新评估主流程' : '关闭：旧规则评分不再进入新评估' }}
            </span>
            <span v-if="policy?.updatedAt" class="meta">上次变更 {{ formatTime(policy.updatedAt) }}</span>
          </div>
          <div class="channel-row__control">
            <el-switch
              v-model="policyDraft"
              inline-prompt
              active-text="开"
              inactive-text="关"
              :disabled="policySaving"
            />
            <el-button type="primary" :loading="policySaving" @click="savePolicy">保存通道</el-button>
          </div>
        </div>
      </article>

      <!-- 3. 研究宿主 -->
      <article class="panel">
        <header class="panel__head">
          <div>
            <h2 class="panel__title">研究宿主</h2>
            <p class="panel__hint">Java 把冻结快照交给该地址的 Python 研究服务；Token 只写不读。</p>
          </div>
          <span class="state" :class="host?.configured ? 'state--on' : 'state--off'">
            {{ host?.configured ? '已配置' : '未配置' }}
          </span>
        </header>

        <el-form label-position="top" class="host-form" @submit.prevent>
          <el-form-item label="服务地址">
            <el-input
              v-model="hostForm.baseUrl"
              placeholder="https://research.example.org 或 http://127.0.0.1:8091"
              clearable
            />
            <p class="field-hint">远程必须 HTTPS；HTTP 仅允许 127.0.0.1 / localhost。不能带路径与查询参数。</p>
          </el-form-item>

          <el-form-item label="Bearer Token">
            <el-input
              v-model="hostForm.bearerToken"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="host?.bearerTokenConfigured ? '已配置，留空保持不变' : '尚未配置，首次保存必填'"
            />
          </el-form-item>

          <div class="form-row">
            <el-form-item label="读取超时（秒）">
              <el-input-number v-model="hostForm.readTimeoutSeconds" :min="1" :max="600" controls-position="right" />
            </el-form-item>
            <el-form-item label="启用状态">
              <el-switch v-model="hostForm.enabled" active-text="启用" inactive-text="停用" />
            </el-form-item>
          </div>

          <div class="form-actions">
            <el-button type="primary" :loading="hostSaving" @click="saveHost">保存研究宿主</el-button>
            <el-button :disabled="hostSaving" @click="resetHostForm">还原</el-button>
            <span v-if="host?.updatedAt" class="meta">上次变更 {{ formatTime(host.updatedAt) }}</span>
          </div>
        </el-form>
      </article>

      <!-- 4. 账号与保留策略 -->
      <article class="panel">
        <h2 class="panel__title">账号与保留策略</h2>

        <div class="account-summary">
          <div>
            <span class="account-summary__label">账号总数</span>
            <strong class="account-summary__value">{{ accountsTotal ?? '—' }}</strong>
          </div>
          <el-button text type="primary" @click="goUsers">前往账号管理 →</el-button>
        </div>

        <ul class="role-matrix">
          <li><span class="role role--admin">ADMIN</span>账号、角色、审计、系统控制</li>
          <li><span class="role role--research">RESEARCHER</span>档案、导入、质控、研究评估</li>
          <li><span class="role role--doctor">DOCTOR</span>授权档案、复核、随访、报告</li>
          <li><span class="role role--patient">PATIENT</span>仅本人摘要与主治医生联系方式</li>
        </ul>

        <div class="retention-block">
          <div class="retention-block__head">
            <strong>审计日志保留</strong>
            <span class="meta">仅清理 <code>audit_log</code>；临床评估与报告永不自动删除。</span>
          </div>
          <div class="retention-block__control">
            <el-input-number v-model="retentionDraft" :min="30" :max="3650" :step="30" controls-position="right" />
            <span class="unit">天</span>
            <el-button type="primary" :loading="retentionSaving" @click="saveRetention">保存策略</el-button>
          </div>
          <span v-if="retention?.updatedAt" class="meta">上次变更 {{ formatTime(retention.updatedAt) }}</span>
        </div>
      </article>
    </div>

    <p class="console-foot">
      控制台写入均需 ADMIN，并记录 <code>SYSTEM_*</code> 审计事件；模型开关仍走
      <code>PATCH /api/system/models/{version}</code>。
    </p>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import request from '@/api/request.js'
import {
  getAssessmentPolicy,
  getResearchHost,
  getRetentionPolicy,
  getSystemInfo,
  saveAssessmentPolicy,
  saveResearchHost,
  saveRetentionPolicy,
  updateModelConfig
} from '@/api/system.js'
import { listUsers } from '@/api/users.js'

const router = useRouter()

const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit'
})

function formatTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : timeFormatter.format(date)
}

const appVersion = typeof __APP_VERSION__ === 'string' ? __APP_VERSION__ : 'dev'
const buildTime = typeof __BUILD_TIME__ === 'string' ? formatTime(__BUILD_TIME__) : '—'
const contractVersion = typeof __CONTRACT_VERSION__ === 'string' ? __CONTRACT_VERSION__ : '—'
const contractOps = typeof __CONTRACT_OPS__ === 'number' ? __CONTRACT_OPS__ : '—'
const contractPaths = typeof __CONTRACT_PATHS__ === 'number' ? __CONTRACT_PATHS__ : '—'

const loading = ref(false)
const probing = ref(false)
const probe = ref({ ok: false, latencyMs: null, message: '未探测' })

const info = ref(null)
const infoLoading = ref(false)
const infoError = ref('')
const models = computed(() => (Array.isArray(info.value?.models) ? info.value.models : []))
const togglingVersion = ref('')

const policy = ref(null)
const policyDraft = ref(false)
const policySaving = ref(false)

const host = ref(null)
const hostSaving = ref(false)
const hostHealth = computed(() => info.value?.researchHost || null)
const hostState = computed(() => {
  if (!host.value) return { ok: false, label: '未配置' }
  if (!host.value.enabled) return { ok: false, label: '已停用' }
  if (!host.value.configured) return { ok: false, label: '待补全' }
  const health = hostHealth.value
  if (!health) return { ok: true, label: '已配置' }
  if (health.available) {
    const latency = Number(health.latencyMs)
    return { ok: true, label: Number.isFinite(latency) ? `在线 · ${latency} ms` : '在线' }
  }
  return { ok: false, label: health.message || '不可达' }
})
const hostForm = reactive({
  baseUrl: '',
  bearerToken: '',
  readTimeoutSeconds: 120,
  enabled: true
})

const retention = ref(null)
const retentionDraft = ref(365)
const retentionSaving = ref(false)
const accountsTotal = ref(null)

function isEnabled(row) {
  const value = row?.enabled
  return value === true || value === 1 || value === '1'
}

async function loadInfo() {
  infoLoading.value = true
  infoError.value = ''
  try {
    info.value = await getSystemInfo()
  } catch (problem) {
    infoError.value = problem?.message || '系统信息读取失败（需要管理员权限）'
  } finally {
    infoLoading.value = false
  }
}

async function loadPolicy() {
  try {
    const data = await getAssessmentPolicy()
    policy.value = data
    policyDraft.value = Boolean(data?.testRuleEnabled)
  } catch {
    // request.js 已提示；保留默认值，避免误保存
  }
}

async function loadHost() {
  try {
    const data = await getResearchHost()
    host.value = data
    resetHostForm()
  } catch {
    // 保持空表单
  }
}

async function loadRetention() {
  try {
    const data = await getRetentionPolicy()
    retention.value = data
    retentionDraft.value = Number(data?.auditLogDays) || 365
  } catch {
    // 保持默认
  }
}

async function loadAccountTotal() {
  try {
    const page = await listUsers({ page: 1, pageSize: 1 })
    accountsTotal.value = Number(page?.total) || 0
  } catch {
    accountsTotal.value = null
  }
}

function resetHostForm() {
  hostForm.baseUrl = host.value?.baseUrl || ''
  hostForm.bearerToken = ''
  hostForm.readTimeoutSeconds = Number(host.value?.readTimeoutSeconds) || 120
  hostForm.enabled = host.value?.enabled !== false
}

async function saveHost() {
  if (hostForm.enabled && !hostForm.baseUrl.trim()) {
    ElMessage.warning('启用研究宿主前请填写服务地址')
    return
  }
  hostSaving.value = true
  try {
    const saved = await saveResearchHost({
      baseUrl: hostForm.baseUrl.trim(),
      bearerToken: hostForm.bearerToken,
      readTimeoutSeconds: hostForm.readTimeoutSeconds,
      enabled: hostForm.enabled
    })
    host.value = saved
    hostForm.bearerToken = ''
    hostForm.baseUrl = saved?.baseUrl || ''
    ElMessage.success('研究宿主已保存')
    await loadInfo()
  } finally {
    hostSaving.value = false
  }
}

async function savePolicy() {
  policySaving.value = true
  try {
    const saved = await saveAssessmentPolicy(policyDraft.value)
    policy.value = saved
    policyDraft.value = Boolean(saved?.testRuleEnabled)
    ElMessage.success(`旧规则评估通道已${policyDraft.value ? '开启' : '关闭'}`)
  } finally {
    policySaving.value = false
  }
}

async function saveRetention() {
  retentionSaving.value = true
  try {
    const saved = await saveRetentionPolicy(retentionDraft.value)
    retention.value = saved
    retentionDraft.value = Number(saved?.auditLogDays) || retentionDraft.value
    ElMessage.success(`审计日志保留 ${retentionDraft.value} 天`)
  } finally {
    retentionSaving.value = false
  }
}

async function toggleModel(row, value) {
  const version = row?.model_version
  if (!version) return
  togglingVersion.value = version
  try {
    await updateModelConfig(version, value)
    ElMessage.success(`已${value ? '启用' : '停用'} ${version}`)
  } finally {
    togglingVersion.value = ''
    await loadInfo()
  }
}

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

function goUsers() {
  router.push('/admin/users')
}

async function loadAll() {
  loading.value = true
  try {
    await Promise.all([
      probeBackend(),
      loadInfo(),
      loadPolicy(),
      loadHost(),
      loadRetention(),
      loadAccountTotal()
    ])
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.console-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.console-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.panel {
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: #fff;
  min-width: 0;
}

.panel--wide {
  grid-column: 1 / -1;
}

.panel__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.panel__title {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.3px;
  color: var(--ink);
}

.panel__hint {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
}

.health-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}

.health-pill--ok {
  color: #2f6f5e;
  background: rgba(63, 122, 118, 0.1);
}

.health-pill--bad {
  color: #a33d3d;
  background: rgba(163, 61, 61, 0.1);
}

.health-pill__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}

.health-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.health-tile {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: linear-gradient(180deg, #f8fbfd 0%, #fff 100%);
}

.health-tile__label {
  font-size: 11px;
  letter-spacing: 0.4px;
  text-transform: uppercase;
  color: var(--soft);
}

.health-tile__value {
  font-size: 18px;
  font-weight: 700;
  color: var(--ink);
}

.health-tile__value.is-ok {
  color: #2f6f5e;
}

.health-tile__value.is-bad {
  color: #a33d3d;
}

.health-tile__meta {
  font-size: 12px;
  line-height: 1.6;
  color: var(--muted);
  word-break: break-all;
}

.model-table {
  margin-bottom: 4px;
}

.state {
  font-size: 12px;
  font-weight: 600;
}

.state--on {
  color: #2f6f5e;
}

.state--off {
  color: var(--soft);
}

.channel-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-top: 14px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #f8fbfd;
}

.channel-row__text {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 13px;
  color: var(--muted);
}

.channel-row__text strong {
  color: var(--ink);
  font-size: 14px;
}

.channel-row__control {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.host-form {
  margin-top: 4px;
}

.field-hint {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--soft);
}

.form-row {
  display: flex;
  gap: 18px;
}

.form-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.meta {
  font-size: 12px;
  color: var(--soft);
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
}

.account-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #f8fbfd;
}

.account-summary__label {
  display: block;
  font-size: 11px;
  letter-spacing: 0.4px;
  text-transform: uppercase;
  color: var(--soft);
}

.account-summary__value {
  font-size: 24px;
  color: var(--ink);
}

.role-matrix {
  list-style: none;
  margin: 14px 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.role-matrix li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--muted);
}

.role {
  min-width: 92px;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  text-align: center;
}

.role--admin {
  color: #7a4b1f;
  background: rgba(196, 141, 74, 0.16);
}

.role--research {
  color: #2b5f86;
  background: rgba(79, 141, 196, 0.14);
}

.role--doctor {
  color: #2f6f5e;
  background: rgba(63, 122, 118, 0.14);
}

.role--patient {
  color: #6b5a86;
  background: rgba(128, 103, 168, 0.14);
}

.retention-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}

.retention-block__head {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 13px;
  color: var(--muted);
}

.retention-block__head strong {
  color: var(--ink);
  font-size: 14px;
}

.retention-block__control {
  display: flex;
  align-items: center;
  gap: 8px;
}

.unit {
  font-size: 13px;
  color: var(--muted);
}

.console-foot {
  margin: 0;
  font-size: 12px;
  line-height: 1.8;
  color: var(--soft);
}

code {
  padding: 1px 5px;
  border-radius: 4px;
  background: rgba(47, 111, 143, 0.08);
  color: #2b5f86;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}

@media (max-width: 1100px) {
  .console-grid {
    grid-template-columns: 1fr;
  }

  .health-grid {
    grid-template-columns: 1fr;
  }
}
</style>
