<template>
  <div class="patient-home">
    <div class="patient-grid">
      <section class="hcard hcard--visit" aria-labelledby="visit-title">
        <h1 id="visit-title" class="hcard__label">{{ selectedAppointmentId ? '复诊详情' : '下次复诊' }}</h1>
        <p class="hcard__value" :class="{ 'is-empty': !appointment }" aria-live="polite">
          {{ followUpText }}
        </p>
        <p v-if="appointment?.purpose" class="hcard__note">
          <strong>复诊事项</strong> {{ appointment.purpose }}
        </p>
        <template v-if="appointment">
          <dl class="visit-details">
            <div><dt>去哪里</dt><dd>{{ appointment.visitLocation || '请查看医院通知或联系就诊科室。' }}</dd></div>
            <div><dt>带什么</dt><dd>{{ appointment.preparation || '暂无具体准备事项，请以医院通知为准。' }}</dd></div>
          </dl>
          <p v-if="responseText" class="hcard__note booking-feedback" role="status">{{ responseText }}</p>
          <p v-if="bookingError" class="hcard__note booking-error" role="alert">{{ bookingError }}</p>
          <div v-if="appointment.canRespond && !rescheduleOpen && !waitingForClinic" class="booking-actions">
            <button v-if="appointment.responseStatus !== 'CONFIRMED'" type="button" class="booking-button primary"
              :disabled="bookingBusy" @click="sendResponse('CONFIRM')">确认按时复诊</button>
            <button type="button" class="booking-button" :disabled="bookingBusy" @click="openReschedule">申请改期</button>
          </div>
          <form v-if="appointment.canRespond && rescheduleOpen && !waitingForClinic" class="booking-form" @submit.prevent="sendResponse('REQUEST_RESCHEDULE')">
            <label for="reschedule-note">希望什么时候复诊（选填）</label>
            <textarea id="reschedule-note" ref="rescheduleInput" v-model="rescheduleNote" maxlength="280" rows="3"
              aria-describedby="reschedule-hint" />
            <p id="reschedule-hint" class="hcard__note">请填写方便到院的日期或时段。医院回复前，原复诊时间仍然有效。</p>
            <div class="booking-actions">
              <button type="submit" class="booking-button primary" :disabled="bookingBusy">提交改期申请</button>
              <button type="button" class="booking-button" :disabled="bookingBusy" @click="rescheduleOpen = false">返回</button>
            </div>
          </form>
        </template>
        <div v-if="selectedAppointmentId" class="booking-actions">
          <button type="button" class="booking-button" :disabled="bookingBusy" @click="selectAppointment(null)">返回下次复诊</button>
        </div>
        <details v-else-if="otherAppointments.length" class="research-notes">
          <summary>其他复诊和医院回复</summary>
          <ul class="appointment-list">
            <li v-for="item in otherAppointments" :key="item.id">
              <button type="button" class="booking-button" :disabled="bookingBusy" @click="selectAppointment(item.id)">
                {{ formatDateTime(item.scheduledAt) }} · {{ item.purpose }} · {{ appointmentState(item) }}
              </button>
            </li>
          </ul>
        </details>
        <p v-if="followUp?.overdueCount > 0" class="hcard__note">
          有 {{ followUp.overdueCount }} 次复诊已过原定时间，请向医院确认安排。
        </p>
        <el-button v-if="followUpState === 'degraded'" class="hcard__retry" @click="loadFollowUp">
          重新读取复诊安排
        </el-button>
      </section>

      <section class="hcard" aria-labelledby="doctor-title">
        <h2 id="doctor-title" class="hcard__label">联系医院</h2>
        <p v-if="doctorState === 'loading'" class="hcard__note" role="status">正在读取联系方式…</p>
        <template v-else-if="doctorState === 'degraded'">
          <p class="hcard__note" role="status">暂时无法读取联系方式。</p>
          <el-button class="hcard__retry" @click="loadDoctor">重新读取联系方式</el-button>
        </template>
        <template v-else>
          <p v-if="doctor?.name" class="hcard__value">{{ doctor.name }}</p>
          <p v-if="doctor?.department || doctor?.officeHours" class="hcard__note">
            {{ [doctor.department, doctor.officeHours].filter(Boolean).join(' · ') }}
          </p>
          <template v-if="phoneNumber">
            <a class="call-link" :href="`tel:${phoneNumber}`">拨打电话 <span>{{ doctor.phone }}</span></a>
            <p class="print-phone">联系电话 {{ doctor.phone }}</p>
          </template>
          <p v-else class="hcard__note">这里暂未提供联系电话。请查看医院的复诊通知或联系原就诊科室。</p>
        </template>
      </section>
    </div>

    <section class="hcard hcard--research" aria-labelledby="research-title">
      <div class="hcard__heading">
        <h2 id="research-title" class="hcard__label">评估进度</h2>
        <span class="research-use">研究资料，仅供参考</span>
      </div>
      <p class="hcard__status" aria-live="polite">{{ researchText }}</p>
      <div v-if="research?.publishedMessage" class="patient-message">
        <h3>医生给您的说明</h3>
        <p>{{ research.publishedMessage }}</p>
      </div>
      <el-button v-if="researchState === 'degraded'" class="hcard__retry" @click="loadResearch">重新加载</el-button>
      <p v-if="research?.subjectCode" class="hcard__note">
        当前查看的档案 {{ research.alias || research.subjectCode }}（{{ research.subjectCode }}）
      </p>
      <details class="research-notes">
        <summary>这项评估有什么用？</summary>
        <p class="hcard__note">这里可以查看研究评估进度和医生发给您的说明。诊断和就诊安排请向医生确认。</p>
        <p v-if="research?.notice" class="hcard__note">{{ research.notice }}</p>
        <ul v-if="research?.limitations?.length">
          <li v-for="item in research.limitations" :key="item">{{ item }}</li>
        </ul>
      </details>
    </section>

    <div class="print-actions">
      <el-button :icon="Printer" @click="printSummary">打印摘要</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { Printer } from '@element-plus/icons-vue'
import { getMyDoctor, getMyFollowUp, getResearchSummary, respondToFollowUp } from '@/api/patients.js'
import { formatDateTime } from '@/utils/datetime.js'

const doctor = ref(null)
const doctorState = ref('loading')
const followUp = ref(null)
const followUpState = ref('loading')
const research = ref(null)
const researchState = ref('loading')
const bookingBusy = ref(false)
const bookingError = ref('')
const rescheduleOpen = ref(false)
const rescheduleNote = ref('')
const rescheduleInput = ref(null)
const selectedAppointmentId = ref(null)
const otherAppointments = computed(() => (followUp.value?.appointments || []).filter(row => row.id !== followUp.value?.next?.id))
const appointment = computed(() => selectedAppointmentId.value == null ? followUp.value?.next
  : followUp.value?.appointments?.find(row => row.id === selectedAppointmentId.value))

const waitingForClinic = computed(() => appointment.value?.responseStatus === 'RESCHEDULE_REQUESTED')
const responseMessages = {
  CONFIRMED: '您已确认按时复诊。',
  RESCHEDULE_REQUESTED: '改期申请已提交，正在等医院处理。原复诊时间仍然有效。',
  RESCHEDULE_DECLINED: '医院回复：暂时无法改期，请按原时间复诊。',
}
function appointmentState(item) {
  if (item.status === 'CANCELLED') return '医院已取消本次复诊，请留意新的安排。'
  if (item.status === 'COMPLETED') return '本次复诊已完成。'
  if (!item.canRespond) return '原复诊时间已过，请联系医院确认。'
  return responseMessages[item.responseStatus] || (item.rescheduled ? '医院已更新复诊时间，请确认新安排。' : '待确认')
}
const responseText = computed(() => appointment.value ? appointmentState(appointment.value) : '')

function selectAppointment(id) {
  selectedAppointmentId.value = id
  bookingError.value = ''
  rescheduleOpen.value = false
  rescheduleNote.value = ''
}

async function openReschedule() {
  bookingError.value = ''
  rescheduleNote.value = ''
  rescheduleOpen.value = true
  await nextTick()
  rescheduleInput.value?.focus()
}

async function sendResponse(action) {
  const current = appointment.value
  if (!current?.canRespond || bookingBusy.value) return
  bookingBusy.value = true
  bookingError.value = ''
  try {
    followUp.value = await respondToFollowUp(current.id, current.appointmentRevision, action, rescheduleNote.value.trim())
    rescheduleOpen.value = false
  } catch (problem) {
    bookingError.value = problem?.message || '提交失败，请稍后再试。'
    if (problem?.status === 409 || problem?.code === 409) {
      rescheduleOpen.value = false
      await loadFollowUp()
    }
  } finally {
    bookingBusy.value = false
  }
}

const followUpText = computed(() => {
  if (followUpState.value === 'loading') return '正在读取复诊安排…'
  if (followUpState.value === 'degraded') return '暂时无法读取复诊安排'
  if (appointment.value?.scheduledAt) {
    return formatDateTime(appointment.value.scheduledAt, { weekday: 'long' })
  }
  return '目前没有已安排的复诊'
})

const researchStatusText = {
  NO_TASK: '目前还没有研究评估安排。',
  IN_PROGRESS: '正在进行研究评估。完成后会交给医生查看。',
  PENDING: '评估已完成，正在等医生查看。',
  NEEDS_DATA: '还需要补充资料，请向医院确认要准备什么。',
  REJECTED: '医生已复核。您可以向医院了解后续安排。',
  CONFIRMED: '医生已复核本次研究验证结果。请按医生通知的安排复诊。',
  UNAVAILABLE: '目前没有可展示的研究评估结论。您可以向医院了解后续安排。'
}

const researchText = computed(() => {
  if (researchState.value === 'loading') return '正在读取评估进度…'
  if (researchState.value === 'degraded') return '暂时无法读取评估进度，请稍后重试。'
  if (!research.value?.subjectCode) return '您的账号还未关联个人档案。需要查看时，请联系医院核对。'
  return researchStatusText[research.value.state] || '请向医院了解本次评估进度。'
})

const phoneNumber = computed(() => {
  const phone = String(doctor.value?.phone || '').replace(/[\s()-]/g, '')
  return /^\+?\d{3,20}$/.test(phone) ? phone : ''
})

async function loadSection(request, data, state) {
  data.value = null
  state.value = 'loading'
  try {
    data.value = await request()
    state.value = 'ready'
  } catch {
    state.value = 'degraded'
  }
}

const loadFollowUp = () => loadSection(getMyFollowUp, followUp, followUpState)
const loadDoctor = () => loadSection(getMyDoctor, doctor, doctorState)
const loadResearch = () => loadSection(getResearchSummary, research, researchState)

function printSummary() {
  window.print()
}

onMounted(() => {
  loadFollowUp()
  loadDoctor()
  loadResearch()
})
</script>

<style scoped>
.patient-home {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.print-actions {
  display: flex;
  justify-content: flex-end;
}

.patient-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 20px;
}

@media (min-width: 900px) {
  .patient-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.hcard {
  min-width: 0;
  padding: 28px 24px;
  overflow-wrap: anywhere;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-card);
}

.hcard--visit {
  background: linear-gradient(150deg, #ffffff 0%, #f0f9ff 100%);
  border-color: rgba(10, 115, 173, 0.22);
}

.hcard--research {
  background: linear-gradient(150deg, #ffffff 0%, #fffbeb 100%);
  border-color: rgba(180, 120, 20, 0.22);
}

.hcard__heading {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 16px;
  margin-bottom: 12px;
}

.hcard__heading .hcard__label {
  margin: 0;
}

.hcard__label {
  margin: 0 0 12px;
  font-size: 1.25rem;
  font-weight: 700;
  color: var(--ink);
}

.hcard__status {
  margin: 0;
  font-size: 1.25rem;
  line-height: 1.5;
  font-weight: 700;
  color: var(--brand-700);
}

.hcard__value {
  margin: 0;
  font-size: clamp(1.5rem, 4.2vw, 1.875rem);
  font-weight: 700;
  color: var(--ink);
  line-height: 1.5;
}

.hcard__value.is-empty {
  font-size: 1.25rem;
  color: var(--muted);
}

.hcard__note,
.research-notes {
  margin: 12px 0 0;
  font-size: 1.125rem;
  line-height: 1.65;
  color: var(--muted);
}

.patient-message { margin-top: 1rem; font-size: 1.125rem; line-height: 1.65; }
.patient-message p { white-space: pre-wrap; }

.research-notes summary {
  padding: 10px 0;
  cursor: pointer;
  color: var(--brand-700);
}

.research-use {
  color: var(--muted);
  font-size: 1rem;
}

.hcard__retry {
  margin-top: 14px;
  min-height: 48px;
  max-width: 100%;
  white-space: normal;
}

.visit-details { display: grid; gap: .75rem; margin: 1rem 0; font-size: 1.125rem; line-height: 1.5; }
.visit-details dt, .booking-form label { color: var(--ink); font-weight: 700; }
.visit-details dd { margin: 0; color: var(--muted); white-space: pre-line; }
.booking-actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 16px; }
.appointment-list { padding: 0; list-style: none; }
.appointment-list li + li { margin-top: .6rem; }
.appointment-list button { text-align: start; overflow-wrap: anywhere; }
.booking-button { min-height: 48px; max-width: 100%; padding: .6rem 1rem; border: 1px solid var(--brand-700);
  border-radius: 8px; background: var(--surface); color: var(--brand-700); font: inherit; font-size: 1rem; cursor: pointer; }
.booking-button.primary { background: var(--brand-700); color: white; }
.booking-button:focus-visible, .booking-form textarea:focus-visible { outline: 3px solid var(--ink); outline-offset: 3px; }
.booking-button:disabled { opacity: .65; cursor: wait; }
.booking-form { margin-top: 16px; }
.booking-form textarea { display: block; box-sizing: border-box; width: 100%; min-height: 68px; margin-top: 8px;
  padding: .65rem; border: 1px solid var(--muted); border-radius: 8px; font: inherit; font-size: 1rem; resize: vertical; }
.booking-feedback { color: var(--ink); font-weight: 650; }
.booking-error { color: var(--el-color-danger-dark-2); }
@media (max-width: 440px) {
  .booking-actions { flex-direction: column; }
  .booking-actions .booking-button { width: 100%; }
}
@media print {
  .booking-actions, .booking-form { display: none; }
}

.call-link {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 0 10px;
  margin-top: 16px;
  padding: 10px 14px;
  min-height: 56px;
  font-size: 1.25rem;
  font-weight: 700;
  color: #fff;
  background: var(--brand-700);
  border-radius: var(--r-lg);
  text-align: center;
}

.call-link:focus-visible,
.research-notes summary:focus-visible {
  outline: 3px solid var(--brand-700);
  outline-offset: 4px;
}

.print-phone {
  display: none;
}
</style>

<style>
@media print {
  @page {
    margin: 16mm;
  }

  .patient__bar,
  .patient__foot,
  .patient-home .print-actions,
  .patient-home .call-link,
  .patient-home .hcard__retry {
    display: none !important;
  }

  .patient__main {
    max-width: none;
    padding: 0;
  }

  body:has(.patient-home),
  #app:has(.patient-home),
  .patient {
    background: #fff !important;
  }

  .patient-home .hcard {
    background: #fff;
    box-shadow: none;
    border-color: #d8dee4;
    break-inside: avoid;
  }

  .patient-home .print-phone {
    display: block;
  }
}
</style>
