<template>
  <el-dialog v-model="open" :title="editing ? '补齐测试评估输入' : '测试输入记录'" width="min(640px, 94vw)"
    class="doctor-test-intake" append-to-body :close-on-click-modal="false"
    :show-close="!saving" :close-on-press-escape="!saving" @open="openIntake">
    <p>此处用于 DEMO 档案演练。每次评估会保存一份输入记录，便于回看模型当时使用了哪些资料。</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <template v-if="!editing">
      <p v-if="loading" role="status">正在读取已保存的测试快照…</p>
      <div v-else-if="loadError">
        <p role="alert">{{ loadError }}</p>
        <el-button @click="load">重新读取</el-button>
      </div>
      <template v-else-if="selected">
        <label for="saved-test-snapshot">已保存的快照（{{ total }} 份）</label>
        <el-select id="saved-test-snapshot" v-model="selectedId" class="snapshot-select">
          <el-option v-for="item in history" :key="item.snapshotId" :value="item.snapshotId"
            :label="`快照 #${item.snapshotId} · ${item.indexDate}`" />
        </el-select>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="评估日期">{{ selected.indexDate }}</el-descriptions-item>
          <el-descriptions-item label="模型宿主">
            {{ syncLabel[selected.hostSyncStatus] || '同步状态未知' }}
          </el-descriptions-item>
          <el-descriptions-item v-for="field in fields" :key="field.key" :label="field.label">
            {{ selected.features[field.key] ?? '未记录' }}
          </el-descriptions-item>
        </el-descriptions>
        <p>可将评估日期、MMSE 和 CDR-SB 填入一张新访视表单。访视编号和 MoCA 仍需按实际记录填写。同日期已有访视时会转到现有记录。</p>
        <el-pagination v-if="total > pageSize" v-model:current-page="page" :page-size="pageSize"
          :total="total" layout="prev, pager, next" @current-change="load" />
      </template>
      <p v-else>本档案还没有保存测试快照。</p>
    </template>
    <template v-else>
      <p v-if="readingVisit" role="status">正在读取最近访视…</p>
      <p v-if="visitError" role="alert">{{ visitError }}</p>
      <el-button v-if="visitError" :disabled="readingVisit || saving" @click="refreshVisit">重新读取访视</el-button>
      <template v-if="visit && !readingVisit">
        <el-descriptions title="已从最近访视带入" :column="1" border>
          <el-descriptions-item label="访视日期">{{ visit.visitDate }}</el-descriptions-item>
          <el-descriptions-item label="MMSE">{{ visit.mmse }}</el-descriptions-item>
          <el-descriptions-item label="CDR-SB">{{ visit.cdrSb }}</el-descriptions-item>
        </el-descriptions>
        <p>补齐下面五项演练输入。年龄以访视当天为准，量表使用该案例的记录。已有检验影像结果不用再次录入。</p>
        <el-form label-position="top" :disabled="saving">
          <div class="input-grid">
            <el-form-item v-for="field in supplementalFields" :key="field.key" :label="field.label" required>
              <el-input-number v-model="values[field.key]" :min="field.min" :max="field.max" :step="field.step" />
            </el-form-item>
          </div>
        </el-form>
      </template>
    </template>
    <template #footer>
      <div class="intake-actions">
        <el-button :disabled="saving" @click="open = false">关闭</el-button>
        <template v-if="editing">
          <el-button :disabled="saving" @click="showHistory">查看历史记录</el-button>
          <el-button :disabled="!canSave || saving" @click="save(false)">仅保存测试输入</el-button>
          <el-button type="primary" :disabled="!canSave" :loading="saving" @click="save(true)">保存并提交测试评估</el-button>
        </template>
        <template v-else>
          <el-button @click="startNew">从最近访视准备测试输入</el-button>
          <el-button v-if="selected" @click="useForVisit">预填测试访视</el-button>
        </template>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { getAssessmentRequest, requestAssessment } from '@/api/assessmentRequests.js'
import { getSubjectDetail } from '@/api/subjects.js'
import request from '@/api/request.js'
import { CONSTRUCTED_VALIDATION } from '@/utils/researchResults.js'

const props = defineProps({
  subjectId: { type: Number, required: true },
  initialView: { type: String, default: 'history' }
})
const open = defineModel({ type: Boolean, default: false })
const emit = defineEmits(['use-for-visit', 'updated'])
const route = '/research/table-assessment-tasks/snapshots/doctor-intake'
const supplementalFields = [
  { key: 'age_years', label: '年龄（岁）', min: 0, max: 120, step: 1 },
  { key: 'education_years', label: '受教育年限', min: 0, max: 40, step: 1 },
  { key: 'cdr_global', label: 'CDR 全局评分', min: 0, max: 3, step: 0.5 },
  { key: 'adas__total13', label: 'ADAS-Cog 13', min: 0, max: 85, step: 1 },
  { key: 'faq__faqtotal', label: 'FAQ', min: 0, max: 30, step: 1 }
]
const fields = [{ key: 'mmse', label: 'MMSE' }, { key: 'cdr_sb', label: 'CDR-SB' }, ...supplementalFields]
const visit = ref(null)
const readingVisit = ref(false)
const visitError = ref('')
const notice = ref('')
const values = ref({})
const saving = ref(false)
const loading = ref(false)
const editing = ref(false)
const loadError = ref('')
const page = ref(1)
const pageSize = 20
const total = ref(0)
const history = ref([])
const selectedId = ref(null)
const selected = computed(() => history.value.find(item => item.snapshotId === selectedId.value))
const syncLabel = { SYNCED: '已同步', PENDING: '等待同步', FAILED: '已保存，模型宿主同步失败' }
const canSave = computed(() => Boolean(visit.value) && !visitError.value && !readingVisit.value
  && supplementalFields.every(field => Number.isFinite(values.value[field.key])))
let visitReadSequence = 0

async function currentVisit() {
  const detail = await getSubjectDetail(props.subjectId)
  const visits = detail.visits
  const latest = visits.reduce((last, row) => !last || row.visitDate > last.visitDate ? row : last, null)
  if (!latest || !Number.isFinite(latest.mmse) || !Number.isFinite(latest.cdrSb)) {
    throw new Error('请在档案保存最近访视的 MMSE 和 CDR-SB 后再准备测试输入。')
  }
  if (visits.some(row => row.id !== latest.id && row.visitDate === latest.visitDate)) {
    throw new Error('最近日期有多条访视，请先在档案核对本次评估使用的访视。')
  }
  return latest
}

function sameVisit(left, right) {
  return left && left.id === right.id && left.visitDate === right.visitDate
    && left.mmse === right.mmse && left.cdrSb === right.cdrSb
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const result = await request.get(route, { params: { subjectId: props.subjectId, page: page.value, pageSize }, silent: true })
    history.value = result.items
    total.value = result.total
    selectedId.value = result.items[0]?.snapshotId ?? null
  } catch (problem) {
    history.value = []
    loadError.value = problem.message || '暂时无法读取测试快照'
  } finally {
    loading.value = false
  }
}

function showHistory() {
  ++visitReadSequence
  page.value = 1
  editing.value = false
  load()
}

function openIntake() {
  notice.value = ''
  if (props.initialView === 'prepare') startNew()
  else showHistory()
}

function startNew() {
  values.value = {}
  editing.value = true
  notice.value = ''
  refreshVisit()
}

async function refreshVisit() {
  const sequence = ++visitReadSequence
  readingVisit.value = true
  visitError.value = ''
  try {
    const latest = await currentVisit()
    if (sequence !== visitReadSequence) return
    if (!sameVisit(visit.value, latest)) values.value = {}
    visit.value = latest
  } catch (problem) {
    if (sequence !== visitReadSequence) return
    visit.value = null
    visitError.value = problem.message || '暂时无法读取最近访视。'
  } finally {
    if (sequence === visitReadSequence) readingVisit.value = false
  }
}

function useForVisit() {
  if (!selected.value) return
  open.value = false
  emit('use-for-visit', selected.value)
}

async function save(submit) {
  if (!canSave.value || saving.value) return
  saving.value = true
  notice.value = ''
  try {
    const [latest, status] = await Promise.all([currentVisit(), getAssessmentRequest(props.subjectId)])
    if (!sameVisit(visit.value, latest)) {
      visit.value = latest
      values.value = {}
      notice.value = '最近访视已更新。请核对新访视并重新填写对应的五项测试输入。'
      return
    }
    if (status.dataScope !== CONSTRUCTED_VALIDATION || !['PASS', 'WARN'].includes(status.qualityStatus)) {
      notice.value = '当前档案需要重新核对质控。请返回预测评估查看状态。'
      emit('updated')
      return
    }
    const snapshot = await request.post(route, {
      subjectId: props.subjectId, indexDate: latest.visitDate,
      features: { ...values.value, mmse: latest.mmse, cdr_sb: latest.cdrSb }
    }, { silent: true })
    notice.value = `测试输入已保存（#${snapshot.snapshotId}）。`
    if (snapshot.hostSyncStatus !== 'SYNCED') {
      notice.value += '暂未同步到评估服务，请返回预测评估查看状态。'
    } else if (submit) {
      try {
        const ready = await getAssessmentRequest(props.subjectId)
        if (ready.state === 'READY' && ready.dataScope === CONSTRUCTED_VALIDATION) {
          await requestAssessment(props.subjectId)
          notice.value += '已提交测试评估，请在预测评估查看结果。'
        } else {
          notice.value += '提交条件有变化，请返回预测评估查看当前状态。'
        }
      } catch {
        notice.value += '提交结果未确认，请先返回预测评估查看状态。'
      }
    }
    emit('updated')
    showHistory()
  } catch (problem) {
    notice.value = problem.message || '无法确认是否保存。请先查看历史记录再重试。'
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.snapshot-select { display: block; max-width: 100%; margin: 8px 0 16px; }
.input-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 12px; }
.input-grid .el-input-number { width: 100%; }
.intake-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.intake-actions .el-button { margin: 0; min-height: 44px; }
@media (max-width: 560px) {
  .input-grid { grid-template-columns: minmax(0, 1fr); }
}
</style>

<style>
.el-dialog.doctor-test-intake { box-sizing: border-box; max-height: 90dvh; display: flex; flex-direction: column; margin-top: 5dvh; }
.doctor-test-intake .el-dialog__body { min-height: 0; overflow-y: auto; }
.doctor-test-intake .el-dialog__header, .doctor-test-intake .el-dialog__footer { flex: none; }
@media (max-width: 767px) {
  .el-dialog.doctor-test-intake { margin-top: 3dvh !important; max-height: 94dvh; }
}
</style>
