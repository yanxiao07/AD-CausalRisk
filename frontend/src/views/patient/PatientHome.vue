<template>
  <div class="patient-home">
    <!-- 卡片 1：风险评估状态 -->
    <section class="hcard hcard--status" aria-labelledby="status-title">
      <p id="status-title" class="hcard__label">风险评估状态</p>

      <p v-if="state === 'loading'" class="hcard__status is-muted">正在读取您的档案…</p>
      <p v-else class="hcard__status" aria-live="polite">您的报告正在等待医生复核</p>

      <p class="hcard__note">
        <template v-if="state === 'ready'">
          已绑定档案：{{ subject.alias }}（{{ subject.subjectCode }}）。医生确认后，这里会显示通俗的风险说明。
        </template>
        <template v-else-if="state === 'degraded'">
          {{ degradeReason }}。您可以稍后重试，或直接联系医生。
        </template>
        <template v-else> 正在获取档案信息，请稍候。 </template>
      </p>

      <el-button v-if="state === 'degraded'" class="hcard__retry" @click="load">重新加载</el-button>
    </section>

    <!-- 卡片 2：下次复诊 -->
    <section class="hcard" aria-labelledby="visit-title">
      <p id="visit-title" class="hcard__label">下次复诊</p>
      <p class="hcard__value" :class="{ 'is-empty': !nextVisit }">
        {{ nextVisitText }}
      </p>
      <p class="hcard__note">
        <template v-if="nextVisit">复诊事项：{{ nextVisit.purpose }}</template>
        <template v-else-if="state === 'loading'">正在读取随访安排…</template>
        <template v-else>医生尚未安排复诊；如有变化会电话通知。</template>
      </p>
    </section>

    <!-- 卡片 3：联系医生 -->
    <section class="hcard hcard--help">
      <p class="hcard__label">需要帮助？</p>
      <el-button type="primary" class="help-button" @click="askHelp">联系医生</el-button>
      <p class="hcard__note">工作时间内医生会尽快回复；紧急情况请直接拨打 120。</p>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { listSubjects } from '@/api/subjects.js'
import { listFollowUps } from '@/api/followups.js'

/**
 * 患者端只呈现三件事：状态结论、下次复诊、联系医生。
 * 契约里没有已实现的患者摘要端点（GET /api/assessments/{id} 对 PATIENT 返回 403），
 * 因此状态卡固定展示"等待医生复核"，并尽量补上"是否已绑定档案 / 复诊安排"这类真实信息。
 */
const state = ref('loading') // loading | ready | degraded
const subject = ref(null)
const nextVisit = ref(null)
const degradeReason = ref('')

const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: 'long',
  day: 'numeric',
  weekday: 'long'
})

const nextVisitText = computed(() => {
  if (!nextVisit.value?.scheduledAt) return '暂无安排'
  const date = new Date(nextVisit.value.scheduledAt)
  return Number.isNaN(date.getTime()) ? '暂无安排' : dateFormatter.format(date)
})

async function load() {
  state.value = 'loading'
  degradeReason.value = ''
  nextVisit.value = null
  try {
    const page = await listSubjects({ page: 1, pageSize: 1 })
    const first = Array.isArray(page?.items) ? page.items[0] : null
    if (!first) {
      state.value = 'degraded'
      degradeReason.value = '您的账号还没有绑定健康档案'
      return
    }
    subject.value = first

    try {
      const list = await listFollowUps(first.id)
      const planned = (Array.isArray(list) ? list : [])
        .filter((item) => item.status === 'PLANNED' && item.scheduledAt)
        .sort((a, b) => new Date(a.scheduledAt) - new Date(b.scheduledAt))
      nextVisit.value = planned[0] ?? null
    } catch {
      // 随访读不到不影响主状态卡；这里静默降级为"暂无安排"
      nextVisit.value = null
    }

    state.value = 'ready'
  } catch (problem) {
    state.value = 'degraded'
    if (problem?.code === 403) degradeReason.value = '暂时无法查看您的档案，请联系医生确认授权'
    else if (problem?.errorCode === 'NETWORK_ERROR') degradeReason.value = '网络连接异常，请稍后重试'
    else degradeReason.value = '档案读取失败，请稍后重试'
  }
}

async function askHelp() {
  const contact = subject.value
    ? `您的档案：${subject.value.alias}（${subject.value.subjectCode}）。`
    : '您的档案尚未绑定。'
  await ElMessageBox.alert(
    `${contact}请联系您的主治医生；联系方式将在账号与档案绑定后显示在本页。紧急情况请拨打 120。`,
    '联系医生',
    { confirmButtonText: '我知道了', type: 'info' }
  )
}

onMounted(load)
</script>

<style scoped>
.patient-home {
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

.hcard--status {
  background: linear-gradient(150deg, #ffffff 0%, #f0f9ff 100%);
  border-color: rgba(10, 115, 173, 0.22);
}

.hcard__label {
  margin: 0 0 12px;
  font-size: 18px;
  font-weight: 700;
  color: var(--muted);
}

/* 适老：结论字号 30–38px，行高宽松 */
.hcard__status {
  margin: 0;
  font-family: var(--font-heading);
  font-size: clamp(30px, 5.2vw, 38px);
  line-height: 1.28;
  font-weight: 700;
  color: var(--brand-700);
}

.hcard__status.is-muted {
  font-size: clamp(22px, 3.6vw, 26px);
  color: var(--muted);
}

.hcard__value {
  margin: 0;
  font-family: var(--font-display);
  font-size: clamp(28px, 5vw, 34px);
  font-weight: 700;
  color: var(--ink);
}

.hcard__value.is-empty {
  font-size: clamp(24px, 4vw, 28px);
  color: var(--muted);
}

.hcard__note {
  margin: 14px 0 0;
  font-size: 16px;
  line-height: 1.7;
  color: var(--muted);
}

.hcard__retry {
  margin-top: 14px;
}

.help-button {
  width: 100%;
  height: 68px;
  font-size: 22px;
  font-weight: 700;
  border-radius: var(--r-lg);
}
</style>
