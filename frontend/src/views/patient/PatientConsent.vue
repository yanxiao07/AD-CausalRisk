<template>
  <div class="consent-page">
    <section class="hcard hcard--consent" aria-labelledby="consent-title">
      <div class="hcard__heading">
        <button class="back-link" type="button" @click="router.push('/patient')">← 返回首页</button>
      </div>
      <h1 id="consent-title" class="hcard__label">授权中心</h1>
      <p class="hcard__note">您可以随时决定资料怎么用，也可以随时撤回授权。撤回后研究侧立即停止读取和新增使用，历史记录保留可查。</p>

      <p v-if="loadState === 'loading'" class="hcard__note" role="status">正在读取授权状态…</p>
      <div v-else-if="loadState === 'degraded'" class="hcard__note" role="alert">
        暂时无法读取授权状态。
        <el-button text class="hcard__retry" @click="load">重新读取</el-button>
      </div>

      <template v-else-if="!consent?.bound">
        <p class="hcard__note">您的账号还没有绑定健康档案，暂时无法设置授权。如需绑定，请联系医院。</p>
      </template>

      <template v-else>
        <p v-if="!consent.decided" class="hcard__note consent-pending" role="status">
          您还没有做出选择。关闭开关后立即生效；未表态期间，科研使用仍以医院伦理与书面授权流程为准。
        </p>

        <div class="switch-row">
          <div class="switch-text">
            <h2>允许用于医学研究</h2>
            <p>您的资料会被去标识化后用于改进评估方法，不会显示姓名或联系方式。关闭后研究侧不再读取该档案，也不再加入科研队列或录入科研随访；已入组的记录保留，由管理员确认后撤销。</p>
          </div>
          <el-switch v-model="researchUse" size="large" :disabled="busy"
            aria-label="允许用于医学研究" />
        </div>

        <div class="switch-row">
          <div class="switch-text">
            <h2>允许医院联系您随访</h2>
            <p>医院可以通过电话与您确认复诊安排、提醒准备事项。关闭后不再用于科研随访；为保障诊疗安全，必要的临床复诊仍可能联系您。</p>
          </div>
          <el-switch v-model="followUpContact" size="large" :disabled="busy"
            aria-label="允许医院联系您随访" />
        </div>

        <p v-if="saveError" class="hcard__note save-error" role="alert">{{ saveError }}</p>
        <p v-if="saveMessage" class="hcard__note save-ok" role="status">{{ saveMessage }}</p>

        <div class="consent-actions">
          <el-button type="primary" size="large" :loading="busy" @click="save">保存我的选择</el-button>
        </div>

        <details v-if="consent.history?.length" class="consent-history">
          <summary>我的授权记录（{{ consent.history.length }}）</summary>
          <ul>
            <li v-for="(item, index) in consent.history" :key="index">
              {{ formatDateTime(item.recordedAt) }} ·
              科研使用：{{ item.researchUse ? '允许' : '不允许' }} ·
              随访联系：{{ item.followUpContact ? '允许' : '不允许' }}
            </li>
          </ul>
        </details>
        <p v-if="consent.updatedAt" class="hcard__note">
          最近一次更新时间：{{ formatDateTime(consent.updatedAt) }}
        </p>
      </template>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getMyConsent, saveMyConsent } from '@/api/patients.js'
import { formatDateTime } from '@/utils/datetime.js'

const router = useRouter()
const consent = ref(null)
const loadState = ref('loading')
const researchUse = ref(false)
const followUpContact = ref(false)
const busy = ref(false)
const saveError = ref('')
const saveMessage = ref('')

const dirty = computed(() => consent.value?.bound && (
  researchUse.value !== consent.value.researchUse
  || followUpContact.value !== consent.value.followUpContact))

function apply(state) {
  consent.value = state
  researchUse.value = Boolean(state?.researchUse)
  followUpContact.value = Boolean(state?.followUpContact)
  loadState.value = 'ready'
}

async function load() {
  loadState.value = 'loading'
  saveError.value = ''
  saveMessage.value = ''
  try {
    apply(await getMyConsent())
  } catch {
    loadState.value = 'degraded'
  }
}

async function save() {
  if (busy.value) return
  busy.value = true
  saveError.value = ''
  saveMessage.value = ''
  try {
    const state = await saveMyConsent(researchUse.value, followUpContact.value)
    apply(state)
    saveMessage.value = '已保存。您的选择立刻生效，之后可以随时再次修改或撤回。'
    ElMessage.success('已保存')
  } catch (problem) {
    saveError.value = problem?.message || '保存失败，请稍后再试。'
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.consent-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.hcard {
  padding: 28px 24px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-card);
}

.hcard--consent {
  background: linear-gradient(150deg, #ffffff 0%, #f5f3ff 100%);
  border-color: rgba(109, 40, 217, 0.22);
}

.hcard__heading {
  margin-bottom: 12px;
}

.back-link {
  background: none;
  border: none;
  font-size: 1rem;
  color: var(--brand-700);
  cursor: pointer;
  padding: 0;
}

.back-link:hover {
  text-decoration: underline;
}

.hcard__label {
  margin: 0 0 12px;
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--ink);
}

.hcard__note {
  margin: 12px 0;
  font-size: 1.125rem;
  color: var(--muted);
}

.consent-pending {
  padding: 16px 18px;
  border-radius: 12px;
  background: rgba(245, 158, 11, 0.12);
  color: #92400e;
  font-weight: 600;
}

.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 20px 0;
  border-bottom: 1px solid var(--line);
}

.switch-row:last-of-type {
  border-bottom: none;
}

.switch-text h2 {
  margin: 0 0 6px;
  font-size: 1.25rem;
  color: var(--ink);
}

.switch-text p {
  margin: 0;
  font-size: 1.0625rem;
  color: var(--muted);
  line-height: 1.6;
}

.consent-actions {
  display: flex;
  gap: 12px;
  margin-top: 20px;
}

.save-ok {
  color: #047857;
  font-weight: 600;
}

.save-error {
  color: #b91c1c;
  font-weight: 600;
}

.consent-history {
  margin-top: 24px;
}

.consent-history summary {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--brand-700);
  cursor: pointer;
}

.consent-history ul {
  margin: 12px 0 0;
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 1.0625rem;
  color: var(--muted);
}

@media (max-width: 640px) {
  .switch-row {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
