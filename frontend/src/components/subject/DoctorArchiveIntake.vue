<template>
  <el-dialog v-model="open" class="doctor-archive-intake" title="新建患者档案"
    width="min(920px, 96vw)" append-to-body :close-on-click-modal="false" :close-on-press-escape="!busy"
    :show-close="!busy" :before-close="confirmClose">
    <p class="intro">可先保存基本资料，也可一次录入本次访视及已有报告。</p>

    <form id="doctor-archive-form" ref="form" novalidate @submit.prevent="save(false)">
      <fieldset class="card">
        <legend>基本资料 <small>必填</small></legend>
        <div class="fields two">
          <label>
            档案编号
            <input v-model.trim="draft.code" autocomplete="off" maxlength="50" placeholder="例如 2026-001"
              required :disabled="busy || !!archive"
              :aria-invalid="!!errors.code" :aria-describedby="errors.code ? 'intake-code-error' : undefined"
              @input="clearError('code')" />
            <span v-if="errors.code" id="intake-code-error" class="field-error">{{ errors.code }}</span>
          </label>
          <label>
            档案称呼
            <input v-model.trim="draft.alias" maxlength="60" autocomplete="off"
              required :disabled="busy || !!archive" :aria-invalid="!!errors.alias"
              :aria-describedby="errors.alias ? 'intake-alias-error' : undefined" @input="clearError('alias')" />
            <span v-if="errors.alias" id="intake-alias-error" class="field-error">{{ errors.alias }}</span>
          </label>
        </div>
        <p v-if="archive" class="success">已建档 {{ archive.subjectCode }}</p>
      </fieldset>

      <details open class="card">
        <summary>
          <span>本次访视量表</span>
          <small :class="{ success: saved.visit }">{{ rowStatus('visit', visit) }}</small>
        </summary>
        <fieldset :disabled="busy || saved.visit || uncertain.visit">
          <legend class="visually-hidden">本次访视量表</legend>
          <p class="hint">评估质控需要最近访视的 <TermHelp name="coreScales" />。按实际结果填写，未测项可留空。</p>
          <div class="fields two">
            <label>
              访视日期
              <input v-model="visit.date" type="date" :aria-invalid="!!errors.visitDate"
                :aria-describedby="errors.visitDate ? 'intake-visit-date-error' : undefined" @input="clearError('visitDate')" />
              <span v-if="errors.visitDate" id="intake-visit-date-error" class="field-error">{{ errors.visitDate }}</span>
            </label>
            <label>
              量表来源
              <input v-model.trim="visit.source" maxlength="200" placeholder="例如 本次门诊量表"
                :aria-invalid="!!errors.visitSource"
                :aria-describedby="errors.visitSource ? 'intake-visit-source-error' : undefined" @input="clearError('visitSource')" />
              <span v-if="errors.visitSource" id="intake-visit-source-error" class="field-error">{{ errors.visitSource }}</span>
            </label>
          </div>
          <div class="fields three">
            <label v-for="scale in scales" :key="scale.key">
              {{ scale.label }}
              <input v-model.trim="visit[scale.key]" inputmode="decimal" :placeholder="`0–${scale.max}`"
                :aria-invalid="!!errors[scale.key]"
                :aria-describedby="errors[scale.key] ? `intake-${scale.key}-error` : undefined" @input="clearError(scale.key)" />
              <span v-if="errors[scale.key]" :id="`intake-${scale.key}-error`" class="field-error">{{ errors[scale.key] }}</span>
            </label>
          </div>
          <VisitModelInputFields v-model="modelInputs" :errors="errors" @clear="clearError" />
          <label v-if="hasInput(visit) && scales.some(scale => visit[scale.key] === '')">
            未填量表的原因
            <input v-model.trim="visit.missingReason" maxlength="500" placeholder="例如 本次未做 MoCA"
              :aria-invalid="!!errors.missingReason"
              :aria-describedby="errors.missingReason ? 'intake-missing-reason-error' : undefined" @input="clearError('missingReason')" />
            <span v-if="errors.missingReason" id="intake-missing-reason-error" class="field-error">{{ errors.missingReason }}</span>
          </label>
        </fieldset>
        <p v-if="rowErrors.visit" tabindex="-1" class="field-error row-error" role="alert">{{ rowErrors.visit }}</p>
      </details>

      <details class="card" :open="reportsOpen" @toggle="reportsOpen = $event.target.open">
        <summary><span>检验和影像结果</span><small>可稍后补录 · 已保存 {{ savedFeatures }}/3</small></summary>
        <p class="hint">已有报告可以逐项填写。没有结果的指标整项留空。海马指标仅接收 0–1 的归一化值。</p>
        <div v-for="(field, code) in featureFields" :key="code" class="report-row">
          <fieldset :disabled="busy || saved[code] || uncertain[code]">
            <legend>{{ field.label }} <small :class="{ success: saved[code] }">{{ rowStatus(code, reports[code]) }}</small></legend>
            <div class="fields report">
              <label>
                报告结果
                <select v-if="field.unit === 'count'" v-model="reports[code].value"
                  :aria-label="`${field.label}报告结果`" :aria-invalid="!!errors[code + 'Value']"
                  :aria-describedby="errors[code + 'Value'] ? `intake-${code}-value-error` : undefined" @change="clearError(code + 'Value')">
                  <option value="">请选择</option>
                  <option v-for="value in [0, 1, 2]" :key="value" :value="String(value)">{{ value }} 个</option>
                </select>
                <input v-else v-model.trim="reports[code].value" inputmode="decimal" placeholder="0–1 归一化"
                  :aria-label="`${field.label}报告结果`" :aria-invalid="!!errors[code + 'Value']"
                  :aria-describedby="errors[code + 'Value'] ? `intake-${code}-value-error` : undefined" @input="clearError(code + 'Value')" />
                <span v-if="errors[code + 'Value']" :id="`intake-${code}-value-error`" class="field-error">{{ errors[code + 'Value'] }}</span>
              </label>
              <label>
                检验或检查日期
                <input v-model="reports[code].date" type="date" :aria-label="`${field.label}检查日期`"
                  :aria-invalid="!!errors[code + 'Date']"
                  :aria-describedby="errors[code + 'Date'] ? `intake-${code}-date-error` : undefined" @input="clearError(code + 'Date')" />
                <span v-if="errors[code + 'Date']" :id="`intake-${code}-date-error`" class="field-error">{{ errors[code + 'Date'] }}</span>
              </label>
              <label>
                报告来源
                <input v-model.trim="reports[code].source" maxlength="200" placeholder="例如 本院报告及报告号"
                  :aria-label="`${field.label}报告来源`" :aria-invalid="!!errors[code + 'Source']"
                  :aria-describedby="errors[code + 'Source'] ? `intake-${code}-source-error` : undefined" @input="clearError(code + 'Source')" />
                <span v-if="errors[code + 'Source']" :id="`intake-${code}-source-error`" class="field-error">{{ errors[code + 'Source'] }}</span>
              </label>
            </div>
          </fieldset>
          <p v-if="rowErrors[code]" tabindex="-1" class="field-error row-error" role="alert">{{ rowErrors[code] }}</p>
        </div>
      </details>
    </form>

    <section v-if="notice || quality" ref="feedback" tabindex="-1" class="feedback" aria-label="录入和质控结果">
      <p v-if="notice" :role="hasProblems || noticeError ? 'alert' : 'status'">{{ notice }}</p>
      <template v-if="quality">
        <h3>档案质控 <StatusTag :type="quality.qualityStatus" /></h3>
        <p v-if="hasPending">以下结果对应已保存的资料。新输入保存后请重新质控。</p>
        <p v-if="quality.qualityStatus === 'BLOCKED'">请按下方提示补充或更正，再重新质控。</p>
        <p v-if="saved.visit && missingCore.length">评估还需要 {{ missingCore.join('、') }}。取得结果后，可以打开档案更正这次访视。</p>
        <ul v-if="qualityIssues.length">
          <li v-for="issue in qualityIssues" :key="issue">{{ issue }}</li>
        </ul>
        <p v-if="optionalMissing.length">可稍后补录 {{ optionalMissing.join('、') }}。</p>
      </template>
    </section>

    <template #footer>
      <div class="intake-footer">
        <el-button :disabled="busy" @click="archive ? finish() : confirmClose(() => { open = false })">
          {{ archive ? '查看档案' : '关闭' }}
        </el-button>
        <div class="save-actions">
          <el-button form="doctor-archive-form" native-type="submit" :loading="activeAction === 'save'"
            :disabled="busy || (!!archive && !hasPending)">保存资料</el-button>
          <el-button type="primary" :loading="activeAction === 'quality'" :disabled="busy || hasUncertain" @click="save(true)">
            {{ archive && !hasPending ? '质控检查' : '保存并质控' }}
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import TermHelp from '@/components/common/TermHelp.vue'
import {
  createSubject, createSubjectObservation, getFeatureSources, getSubjectDetail, getVisitSources, runQualityCheck
} from '@/api/subjects.js'
import { featureFields, isValidFeatureValue } from './featureFields.js'
import VisitModelInputFields from './VisitModelInputFields.vue'
import { newVisitModelDraft, visitModelErrors, visitModelPayload } from './visitModelInputs.js'

const open = defineModel({ type: Boolean, default: false })
const emit = defineEmits(['saved', 'view'])
const form = ref(null)
const feedback = ref(null)
const activeAction = ref('')
const busy = computed(() => !!activeAction.value)
const archive = ref(null)
const notice = ref('')
const noticeError = ref(false)
const quality = ref(null)
const reportsOpen = ref(false)
const draft = reactive({ code: '', alias: '' })
const visit = reactive({ date: '', source: '', mmse: '', moca: '', cdrSb: '', missingReason: '' })
const modelInputs = ref(newVisitModelDraft())
const codes = Object.keys(featureFields)
const reports = reactive(Object.fromEntries(codes.map(code => [code, { value: '', date: '', source: '' }])))
const errors = ref({})
const saved = reactive({})
const rowErrors = reactive({})
const uncertain = reactive({})
const scales = [
  { key: 'mmse', label: 'MMSE', max: 30, pattern: /^\d{1,2}$/ },
  { key: 'moca', label: 'MoCA', max: 30, pattern: /^\d{1,2}$/ },
  { key: 'cdrSb', label: 'CDR-SB', max: 18, pattern: /^\d{1,2}(?:\.\d)?$/ }
]

const hasInput = record => Object.values(record).some(value => value !== '')
  || (record === visit && Object.values(modelInputs.value).some(value => value !== ''))
const pending = (key, record) => !saved[key] && !uncertain[key] && hasInput(record)
const hasPending = computed(() => pending('visit', visit) || codes.some(code => pending(code, reports[code])))
const hasUncertain = computed(() => Object.values(uncertain).some(Boolean))
const hasProblems = computed(() => Object.keys(errors.value).length > 0 || Object.values(rowErrors).some(Boolean) || hasUncertain.value)
const savedFeatures = computed(() => codes.filter(code => saved[code]).length)
const missingCore = computed(() => scales.filter(scale => scale.key !== 'moca' && visit[scale.key] === '').map(scale => scale.label))
const optionalIssues = Object.fromEntries(codes.map(code => [`${code} 缺失`, featureFields[code].label]))
const qualityIssues = computed(() => (quality.value?.issues || []).filter(issue => !optionalIssues[issue]))
const optionalMissing = computed(() => (quality.value?.issues || []).map(issue => optionalIssues[issue]).filter(Boolean))

function rowStatus(key, record) {
  if (saved[key]) return '已保存'
  if (uncertain[key]) return '待核对'
  if (rowErrors[key]) return '保存失败'
  return hasInput(record) ? '待保存' : '可稍后补录'
}

function clearError(key) {
  delete errors.value[key]
}

function visitPayload() {
  const details = visitModelErrors(modelInputs.value)
  const missing = scales.filter(({ key }) => visit[key] === '')
  if (!visit.date) details.visitDate = '请选择访视日期'
  if (!visit.source) details.visitSource = '请填写量表来源'
  if (missing.length && !visit.missingReason) details.missingReason = '请说明未填量表的原因'
  for (const { key, label, max, pattern } of scales) {
    const value = visit[key]
    if (value !== '' && (!pattern.test(value) || Number(value) > max)) {
      details[key] = `${label} 须在 0–${max} 之间${key === 'cdrSb' ? '，最多一位小数' : '，填整数'}`
    }
  }
  if (Object.keys(details).length) {
    Object.assign(errors.value, details)
    return null
  }
  return {
    kind: 'visit', visitCode: `V-${visit.date}`, visitDate: visit.date, source: visit.source,
    missingReason: missing.length ? visit.missingReason : null,
    modelInputs: visitModelPayload(modelInputs.value),
    ...Object.fromEntries(scales.map(({ key }) => [key, visit[key] === '' ? null : Number(visit[key])]))
  }
}

function featurePayload(code) {
  const entry = reports[code]
  const { unit } = featureFields[code]
  const details = {}
  if (!entry.date) details[code + 'Date'] = '请选择检查日期'
  if (!entry.source) details[code + 'Source'] = '请填写报告来源'
  if (!isValidFeatureValue(unit, entry.value)) {
    details[code + 'Value'] = unit === 'count' ? '请选择报告确认的个数' : '请输入 0–1 的归一化值，最多六位小数'
  }
  if (Object.keys(details).length) {
    Object.assign(errors.value, details)
    return null
  }
  return {
    kind: 'feature', featureName: code, featureValue: Number(entry.value),
    recordedAt: entry.date, unit, source: entry.source
  }
}

async function confirmObservation(payload) {
  const subjectId = archive.value.id
  try {
    if (payload.kind === 'feature') {
      const rows = await getFeatureSources(subjectId)
      return rows.some(row => row.featureName === payload.featureName && row.recordedAt === payload.recordedAt
        && row.featureValue === payload.featureValue && row.unit === payload.unit && row.source === payload.source)
    }
    const rows = await getVisitSources(subjectId)
    const matched = rows.find(row => row.visitCode === payload.visitCode && row.visitDate === payload.visitDate
      && row.source === payload.source && (row.missingReason || null) === payload.missingReason
      && scales.every(({ key }) => {
        const raw = row[`raw${key[0].toUpperCase()}${key.slice(1)}`]
        return (raw == null ? null : Number(raw)) === payload[key]
      }))
    if (!matched) return false
    const detail = await getSubjectDetail(subjectId)
    const actual = detail.visits?.find(row => row.id === matched.id)?.modelInputs
    return Object.entries(payload.modelInputs).every(([key, expected]) => (actual?.[key] ?? null) === expected)
  } catch {
    return false
  }
}

async function saveObservation(key, payload) {
  delete rowErrors[key]
  try {
    await createSubjectObservation(archive.value.id, payload)
    saved[key] = true
  } catch (error) {
    if (!error.httpStatus || error.httpStatus >= 500) {
      if (await confirmObservation(payload)) saved[key] = true
      else {
        uncertain[key] = true
        rowErrors[key] = '系统未确认是否保存。请打开档案核对该项，再决定是否补录。'
      }
    } else {
      rowErrors[key] = error.message || '保存失败，请核对这一项后重试。'
    }
  }
}

async function showFeedback() {
  await nextTick()
  const invalid = form.value?.querySelector('[aria-invalid="true"]:enabled') || form.value?.querySelector('.row-error')
  if (invalid) {
    const section = invalid.closest('details')
    if (section) section.open = true
    invalid.focus()
  } else {
    feedback.value?.focus()
  }
}

async function save(checkQuality) {
  if (busy.value || (checkQuality && hasUncertain.value)) return
  errors.value = {}
  notice.value = ''
  noticeError.value = false
  quality.value = null
  if (!archive.value) {
    if (!/^[A-Za-z0-9][A-Za-z0-9-]{0,49}$/.test(draft.code) || /^(DEMO-|ADNI-REVIEW-)/i.test(draft.code)) {
      errors.value.code = '请输入普通档案编号，以字母或数字开头，可使用短横线'
    }
    if (!draft.alias) errors.value.alias = '请填写档案称呼'
    if (Object.keys(errors.value).length) {
      await showFeedback()
      return
    }
  }

  const entries = []
  if (pending('visit', visit)) entries.push(['visit', visitPayload()])
  for (const code of codes) {
    if (pending(code, reports[code])) entries.push([code, featurePayload(code)])
  }

  activeAction.value = checkQuality ? 'quality' : 'save'
  try {
    if (!archive.value) {
      try {
        archive.value = await createSubject({ subjectCode: draft.code, alias: draft.alias, status: 'ACTIVE', archiveType: 'CARE' })
        emit('saved', archive.value)
      } catch (error) {
        noticeError.value = true
        notice.value = !error.httpStatus || error.httpStatus >= 500
          ? '系统未确认是否完成建档。请先按档案编号搜索核对，再重新提交。'
          : error.message || '创建档案失败。请核对档案编号后重试。'
        return
      }
    }
    for (const [key, payload] of entries) {
      if (payload) await saveObservation(key, payload)
      else delete rowErrors[key]
    }
    if (hasProblems.value) {
      notice.value = '档案和标为「已保存」的资料已入库。请处理标出的项目；其余资料可以稍后补录。'
    } else if (checkQuality) {
      try {
        quality.value = await runQualityCheck(archive.value.id)
        notice.value = '资料已保存，质控完成。'
      } catch {
        noticeError.value = true
        notice.value = '资料已保存，暂时未能完成质控。可以单独重试质控检查。'
      }
    } else {
      notice.value = '资料已保存。可继续填写，或打开档案查看。'
    }
  } finally {
    activeAction.value = ''
    await showFeedback()
  }
}

async function confirmClose(done) {
  if (busy.value) return
  if (hasPending.value || (!archive.value && hasInput(draft))) {
    try {
      await ElMessageBox.confirm('尚有未保存的输入。关闭后会清除这些输入，已保存的记录仍可在档案中查看。',
        '关闭录入', { confirmButtonText: '关闭', cancelButtonText: '继续录入', type: 'warning' })
    } catch {
      return
    }
  }
  done()
}

function finish() {
  confirmClose(() => {
    open.value = false
    emit('view', archive.value)
  })
}
</script>

<style scoped>
.intro, .hint { margin: 0 0 12px; color: var(--el-text-color-regular); line-height: 1.65; }
.card { min-inline-size: 0; margin: 0 0 14px; padding: 14px; border: 1px solid var(--el-border-color); border-radius: 12px; }
legend, summary { font-weight: 650; color: var(--el-text-color-primary); }
legend { padding-inline: 4px; }
legend small, summary small { margin-inline-start: 8px; color: var(--el-text-color-regular); font-weight: 400; }
summary { cursor: pointer; min-height: 44px; align-content: center; }
.card:has(> summary) { padding-block: 4px 10px; }
fieldset fieldset, details fieldset { padding: 0; border: 0; margin: 0; min-inline-size: 0; }
.report-row { border-top: 1px solid var(--el-border-color-light); padding: 12px 0; }
.report-row:last-child { padding-bottom: 4px; }
.fields { display: grid; gap: 10px 14px; margin-bottom: 10px; }
.two { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.three, .report { grid-template-columns: repeat(3, minmax(0, 1fr)); }
label { display: grid; gap: 4px; font-weight: 500; color: var(--el-text-color-primary); }
input, select { box-sizing: border-box; min-width: 0; width: 100%; min-height: 44px; padding: 7px 10px; border: 1px solid var(--el-border-color); border-radius: 8px; color: var(--el-text-color-primary); background: var(--el-bg-color); font: inherit; }
input:disabled, select:disabled { color: var(--el-text-color-regular); background: var(--el-fill-color-light); }
[aria-invalid="true"] { border-color: var(--el-color-danger); }
:is(input, select, summary, .feedback):focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.field-error { color: var(--el-color-danger-dark-2); font-weight: 500; }
.success { color: var(--el-color-success-dark-2); }
.feedback { padding: 8px 14px; border-radius: 10px; background: var(--el-fill-color-light); line-height: 1.6; }
.feedback h3 { margin: 0 0 8px; font-size: 16px; }
.feedback p, .feedback ul { margin-block: 6px; }
.intake-footer, .save-actions { display: flex; flex-wrap: wrap; gap: 8px; justify-content: space-between; }
.intake-footer .el-button { margin: 0; min-height: 44px; }
.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
@media (max-width: 560px) {
  .two, .three, .report { grid-template-columns: minmax(0, 1fr); }
  .intake-footer { justify-content: flex-end; }
  input, select { font-size: 16px; }
}
</style>

<style>
.el-dialog.doctor-archive-intake { box-sizing: border-box; max-height: 90dvh; display: flex; flex-direction: column; margin-top: 5dvh; }
.doctor-archive-intake .el-dialog__body { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding: 4px 4px 12px; }
.doctor-archive-intake .el-dialog__header, .doctor-archive-intake .el-dialog__footer { flex: none; }
.doctor-archive-intake .el-dialog__footer { border-top: 1px solid var(--el-border-color-light); padding-top: 12px; }
@media (max-width: 767px) {
  .el-dialog.doctor-archive-intake { margin-top: 3dvh !important; max-height: 94dvh; }
}
</style>
