<template>
  <el-dialog v-model="open" title="测试模型快照" width="min(640px, 94vw)" @open="openHistory">
    <p>仅用于演练。测试快照保存模型使用的七项输入，档案质控仍读取已保存的访视和检验影像记录。</p>
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
    <el-form v-else label-position="top">
      <el-form-item label="评估日期" required>
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-row :gutter="12">
        <el-col v-for="field in fields" :key="field.key" :span="12">
          <el-form-item :label="field.label" required>
            <el-input-number v-model="values[field.key]" :min="field.min" :max="field.max" :step="field.step" />
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>
    <template #footer>
      <el-button @click="open = false">关闭</el-button>
      <template v-if="editing">
        <el-button @click="editing = false">返回已保存快照</el-button>
        <el-button type="primary" :disabled="!date || !complete" :loading="saving" @click="save">保存测试快照</el-button>
      </template>
      <template v-else>
        <el-button @click="startNew">新增测试快照</el-button>
        <el-button v-if="selected" @click="useForVisit">预填测试访视</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/api/request.js'

const props = defineProps({ subjectId: { type: Number, required: true } })
const open = defineModel({ type: Boolean, default: false })
const emit = defineEmits(['use-for-visit'])
const route = '/research/table-assessment-tasks/snapshots/doctor-intake'
const fields = [
  { key: 'age_years', label: '年龄（岁）', min: 0, max: 120, step: 1 },
  { key: 'education_years', label: '受教育年限', min: 0, max: 40, step: 1 },
  { key: 'mmse', label: 'MMSE', min: 0, max: 30, step: 1 },
  { key: 'cdr_sb', label: 'CDR-SB', min: 0, max: 18, step: 0.5 },
  { key: 'cdr_global', label: 'CDR 全局评分', min: 0, max: 3, step: 0.5 },
  { key: 'adas__total13', label: 'ADAS-Cog 13', min: 0, max: 85, step: 1 },
  { key: 'faq__faqtotal', label: 'FAQ', min: 0, max: 30, step: 1 }
]
const date = ref('')
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
const complete = computed(() => fields.every(field => Number.isFinite(values.value[field.key])))

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

function openHistory() {
  page.value = 1
  editing.value = false
  load()
}

function startNew() {
  date.value = ''
  values.value = {}
  editing.value = true
}

function useForVisit() {
  if (!selected.value) return
  open.value = false
  emit('use-for-visit', selected.value)
}

async function save() {
  saving.value = true
  try {
    const snapshot = await request.post(route, {
      subjectId: props.subjectId, indexDate: date.value, features: values.value
    })
    ElMessage.success(`已保存测试快照 #${snapshot.snapshotId}`)
    editing.value = false
    page.value = 1
    await load()
  } catch {
    // The API client displays the exact error.
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.snapshot-select { display: block; max-width: 100%; margin: 8px 0 16px; }
</style>
