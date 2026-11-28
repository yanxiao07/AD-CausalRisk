<template>
  <div class="followup-page">
    <section class="hcard hcard--visit" aria-labelledby="followup-title">
      <div class="hcard__heading">
        <button class="back-link" type="button" @click="router.push('/patient')">← 返回首页</button>
      </div>
      <h1 id="followup-title" class="hcard__label">复诊自助</h1>
      <p class="hcard__note">这里可以确认复诊、申请改期或取消，也可以把复诊加进手机日历。</p>

      <p v-if="loadState === 'loading'" class="hcard__note" role="status">正在读取复诊安排…</p>
      <div v-else-if="loadState === 'degraded'" class="hcard__note" role="alert">
        暂时无法读取复诊安排。
        <el-button text class="hcard__retry" @click="load">重新读取</el-button>
      </div>

      <template v-else-if="!bound">
        <p class="hcard__note">您的账号还没有绑定健康档案。绑定后这里会显示复诊安排，如需绑定请联系医院。</p>
      </template>

      <template v-else>
        <p v-if="!appointment" class="hcard__value is-empty">目前没有已安排的复诊</p>
        <template v-else>
          <p class="hcard__value" aria-live="polite">
            {{ formatDateTime(appointment.scheduledAt, { weekday: 'long' }) }}
          </p>
          <p class="state-chip" :class="{ 'state-chip--warn': stateWarn }">{{ stateText }}</p>
          <dl class="visit-details">
            <div><dt>复诊事项</dt><dd>{{ appointment.purpose || '以医院通知为准。' }}</dd></div>
            <div><dt>去哪里</dt><dd>{{ appointment.visitLocation || '请查看医院通知或联系就诊科室。' }}</dd></div>
            <div><dt>带什么</dt><dd>{{ appointment.preparation || '暂无具体准备事项，请以医院通知为准。' }}</dd></div>
          </dl>

          <p v-if="actionMessage" class="hcard__note booking-feedback" role="status">{{ actionMessage }}</p>
          <p v-if="actionError" class="hcard__note booking-error" role="alert">{{ actionError }}</p>

          <div v-if="canRespond" class="booking-actions">
            <button v-if="canAct && appointment.responseStatus !== 'CONFIRMED'" type="button" class="booking-button primary"
              :disabled="busy" @click="sendResponse('CONFIRM')">确认按时复诊</button>
            <button v-if="canAct" type="button" class="booking-button" :disabled="busy" @click="openReschedule">申请改期</button>
            <button type="button" class="booking-button danger" :disabled="busy" @click="askCancel">取消本次复诊</button>
          </div>
          <p v-if="waitingForClinic && canRespond" class="hcard__note">
            改期申请正在等医院回复，原复诊时间仍然有效；如果这段时间不方便，仍可点「取消本次复诊」。
          </p>

          <form v-if="canAct && rescheduleOpen" class="booking-form" @submit.prevent="sendResponse('REQUEST_RESCHEDULE')">
            <label for="reschedule-note">希望什么时候复诊（选填）</label>
            <textarea id="reschedule-note" ref="rescheduleInput" v-model="rescheduleNote" maxlength="280" rows="3" />
            <p class="hcard__note">医院回复前，原复诊时间仍然有效。</p>
            <div class="booking-actions">
              <button type="submit" class="booking-button primary" :disabled="busy">提交改期申请</button>
              <button type="button" class="booking-button" :disabled="busy" @click="rescheduleOpen = false">返回</button>
            </div>
          </form>

          <div class="travel-actions">
            <a v-if="appointment.visitLocation" class="booking-button" :href="routeUrl" target="_blank" rel="noopener">
              查看路线
            </a>
            <button type="button" class="booking-button" @click="addToCalendar">加入手机日历</button>
          </div>
          <p class="hcard__note">「加入手机日历」会下载一个提醒文件，用手机打开即可导入，并在复诊前一天提醒您。</p>
        </template>

        <p v-if="followUp?.overdueCount > 0" class="hcard__note">
          有 {{ followUp.overdueCount }} 次复诊已过原定时间，请联系医院核对安排。
        </p>

        <details v-if="others.length" class="other-appointments">
          <summary>其他复诊安排（{{ others.length }}）</summary>
          <ul class="appointment-list">
            <li v-for="item in others" :key="item.id">
              <button type="button" class="booking-button" @click="select(item.id)">
                {{ formatDateTime(item.scheduledAt) }} · {{ item.purpose }} · {{ stateOf(item) }}
              </button>
            </li>
          </ul>
        </details>
        <div v-if="selectedId != null" class="booking-actions">
          <button type="button" class="booking-button" @click="select(null)">返回下次复诊</button>
        </div>
      </template>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelFollowUp, getMyFollowUp, respondToFollowUp } from '@/api/patients.js'
import { formatDateTime, parseInstant } from '@/utils/datetime.js'

const router = useRouter()
const followUp = ref(null)
const loadState = ref('loading')
const busy = ref(false)
const actionError = ref('')
const actionMessage = ref('')
const rescheduleOpen = ref(false)
const rescheduleNote = ref('')
const rescheduleInput = ref(null)
const selectedId = ref(null)

const bound = computed(() => Boolean(followUp.value?.bound))
const others = computed(() =>
  (followUp.value?.appointments || []).filter(row => row.id !== followUp.value?.next?.id))
const appointment = computed(() => selectedId.value == null
  ? followUp.value?.next
  : followUp.value?.appointments?.find(row => row.id === selectedId.value))
const canRespond = computed(() => Boolean(appointment.value?.canRespond))
const waitingForClinic = computed(() => appointment.value?.responseStatus === 'RESCHEDULE_REQUESTED')
const canAct = computed(() => canRespond.value && !waitingForClinic.value)

const responseMessages = {
  CONFIRMED: '您已确认按时复诊。',
  RESCHEDULE_REQUESTED: '改期申请已提交，正在等医院处理。原复诊时间仍然有效。',
  RESCHEDULE_DECLINED: '医院回复：暂时无法改期，请按原时间复诊。',
}

function stateOf(item) {
  if (!item) return ''
  if (item.status === 'CANCELLED') return '已取消，请留意新的安排'
  if (item.status === 'COMPLETED') return '已完成'
  if (!item.canRespond) return '原定时间已过，请联系医院确认'
  return responseMessages[item.responseStatus] || (item.rescheduled ? '医院已更新复诊时间，请确认新安排' : '待确认')
}

const stateText = computed(() => stateOf(appointment.value))
const stateWarn = computed(() => !appointment.value?.canRespond
  || appointment.value?.status === 'CANCELLED'
  || appointment.value?.responseStatus === 'RESCHEDULE_DECLINED')

const routeUrl = computed(() => {
  const location = appointment.value?.visitLocation
  return location ? `https://uri.amap.com/search?keyword=${encodeURIComponent(location)}` : ''
})

async function load() {
  loadState.value = 'loading'
  actionError.value = ''
  try {
    followUp.value = await getMyFollowUp()
    loadState.value = 'ready'
  } catch {
    loadState.value = 'degraded'
  }
}

function select(id) {
  selectedId.value = id
  rescheduleOpen.value = false
  rescheduleNote.value = ''
  actionError.value = ''
  actionMessage.value = ''
}

function isConflict(problem) {
  return problem?.httpStatus === 409 || problem?.code === 409
}

async function refreshAfterConflict(message) {
  await load()
  if (selectedId.value != null && !appointment.value) selectedId.value = null
  actionError.value = message || '复诊安排已被医院更新，已为您刷新，请按最新时间重新操作。'
}

async function openReschedule() {
  actionError.value = ''
  rescheduleNote.value = ''
  rescheduleOpen.value = true
  await nextTick()
  rescheduleInput.value?.focus()
}

async function sendResponse(action) {
  const current = appointment.value
  if (!current?.canRespond || busy.value) return
  busy.value = true
  actionError.value = ''
  actionMessage.value = ''
  try {
    followUp.value = await respondToFollowUp(
      current.id, current.appointmentRevision, action, rescheduleNote.value.trim())
    rescheduleOpen.value = false
    actionMessage.value = action === 'CONFIRM' ? '已确认按时复诊。' : '改期申请已提交。'
    ElMessage.success(actionMessage.value)
  } catch (problem) {
    actionError.value = problem?.message || '提交失败，请稍后再试。'
    if (isConflict(problem)) {
      rescheduleOpen.value = false
      await refreshAfterConflict(problem?.message)
    }
  } finally {
    busy.value = false
  }
}

async function askCancel() {
  const current = appointment.value
  if (!current || busy.value) return
  try {
    await ElMessageBox.confirm(
      `确定取消 ${formatDateTime(current.scheduledAt)} 的复诊吗？取消后请与医院重新约定时间。`,
      '取消复诊',
      { confirmButtonText: '确定取消', cancelButtonText: '再想想', type: 'warning' }
    )
  } catch {
    return
  }
  busy.value = true
  actionError.value = ''
  actionMessage.value = ''
  try {
    followUp.value = await cancelFollowUp(current.id, current.appointmentRevision)
    selectedId.value = null
    actionMessage.value = '本次复诊已取消。请留意医院后续安排。'
    ElMessage.success(actionMessage.value)
  } catch (problem) {
    actionError.value = problem?.message || '取消失败，请稍后再试。'
    if (isConflict(problem)) await refreshAfterConflict(problem?.message)
  } finally {
    busy.value = false
  }
}

function addToCalendar() {
  const current = appointment.value
  const start = parseInstant(current?.scheduledAt)
  if (!current || !start) return
  const stamp = (date) => date.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '')
  const end = new Date(start.getTime() + 60 * 60 * 1000)
  const summary = `复诊：${current.purpose || '医院复诊'}`
  const description = current.preparation ? `准备事项：${current.preparation}` : '请按医院通知准时到院。'
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//AD-CausalRisk//Patient Follow-Up//CN',
    'CALSCALE:GREGORIAN',
    'BEGIN:VEVENT',
    `UID:follow-up-${current.id}-${current.appointmentRevision}@adcausalrisk`,
    `DTSTAMP:${stamp(new Date())}`,
    `DTSTART:${stamp(start)}`,
    `DTEND:${stamp(end)}`,
    `SUMMARY:${summary}`,
    `LOCATION:${current.visitLocation || ''}`,
    `DESCRIPTION:${description}`,
    'BEGIN:VALARM',
    'TRIGGER:-PT24H',
    'ACTION:DISPLAY',
    `DESCRIPTION:${summary}提醒`,
    'END:VALARM',
    'END:VEVENT',
    'END:VCALENDAR'
  ]
  const blob = new Blob([lines.join('\r\n')], { type: 'text/calendar;charset=utf-8' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `复诊提醒-${start.toISOString().slice(0, 10)}.ics`
  link.click()
  URL.revokeObjectURL(link.href)
}

onMounted(load)
</script>

<style scoped>
.followup-page {
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

.hcard--visit {
  background: linear-gradient(150deg, #ffffff 0%, #eff6ff 100%);
  border-color: rgba(37, 99, 235, 0.22);
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

.hcard__value {
  margin: 8px 0;
  font-size: 1.75rem;
  font-weight: 700;
  color: var(--ink);
}

.hcard__value.is-empty {
  font-size: 1.375rem;
  font-weight: 600;
  color: var(--muted);
}

.state-chip {
  display: inline-block;
  margin: 4px 0 16px;
  padding: 6px 14px;
  border-radius: 999px;
  background: rgba(16, 185, 129, 0.14);
  color: #047857;
  font-size: 1.0625rem;
  font-weight: 600;
}

.state-chip--warn {
  background: rgba(245, 158, 11, 0.16);
  color: #b45309;
}

.visit-details {
  margin: 8px 0 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.visit-details div {
  display: flex;
  gap: 12px;
  align-items: baseline;
}

.visit-details dt {
  flex: 0 0 84px;
  font-weight: 700;
  color: var(--ink);
  font-size: 1.125rem;
}

.visit-details dd {
  margin: 0;
  font-size: 1.125rem;
  color: var(--muted);
  overflow-wrap: anywhere;
}

.booking-feedback {
  color: #047857;
  font-weight: 600;
}

.booking-error {
  color: #b91c1c;
  font-weight: 600;
}

.booking-actions,
.travel-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin: 12px 0;
}

.booking-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 56px;
  padding: 0 24px;
  border-radius: 12px;
  border: 1px solid var(--line);
  background: #fff;
  color: var(--ink);
  font-size: 1.125rem;
  font-weight: 600;
  text-decoration: none;
  cursor: pointer;
}

.booking-button.primary {
  background: var(--brand-600);
  border-color: var(--brand-600);
  color: #fff;
}

.booking-button.danger {
  border-color: rgba(185, 28, 28, 0.4);
  color: #b91c1c;
}

.booking-button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.booking-form {
  margin: 12px 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.booking-form label {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--ink);
}

.booking-form textarea {
  width: 100%;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  font-size: 1.125rem;
  font-family: inherit;
  resize: vertical;
}

.other-appointments {
  margin-top: 20px;
}

.other-appointments summary {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--brand-700);
  cursor: pointer;
}

.appointment-list {
  list-style: none;
  padding: 0;
  margin: 12px 0 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.appointment-list li .booking-button {
  width: 100%;
  justify-content: flex-start;
  text-align: left;
}

@media (max-width: 640px) {
  .visit-details div {
    flex-direction: column;
    gap: 4px;
  }

  .booking-button {
    width: 100%;
  }
}
</style>
