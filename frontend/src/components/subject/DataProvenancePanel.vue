<template>
  <section class="provenance">
    <header class="provenance__head">
      <h3 class="provenance__title">数据溯源</h3>
      <el-button text :icon="Refresh" size="small" :loading="loading" @click="load">刷新</el-button>
    </header>

    <!-- 能力判定：后端按角色 + 对象授权算出来的，前端只展示 -->
    <div class="caps">
      <el-tag
        v-for="cap in capabilityList"
        :key="cap.key"
        :type="cap.allowed ? 'success' : 'info'"
        effect="plain"
        size="small"
      >
        {{ cap.label }}：{{ cap.allowed ? '可' : '不可' }}
      </el-tag>
    </div>

    <el-skeleton v-if="loading" :rows="3" animated />

    <template v-else>
      <h4 class="provenance__sub">特征来源（{{ featureRows.length }}）</h4>
      <el-table
        v-if="featureRows.length"
        :data="featureRows"
        size="small"
        max-height="220"
        class="provenance__table"
      >
        <el-table-column
          v-for="col in featureColumns"
          :key="col"
          :prop="col"
          :label="columnLabel(col)"
          min-width="120"
          show-overflow-tooltip
        />
      </el-table>
      <p v-else class="provenance__empty">还没有结构化特征记录。</p>

      <h4 class="provenance__sub">访视来源（{{ visitRows.length }}）</h4>
      <el-table
        v-if="visitRows.length"
        :data="visitRows"
        size="small"
        max-height="220"
        class="provenance__table"
      >
        <el-table-column
          v-for="col in visitColumns"
          :key="col"
          :prop="col"
          :label="columnLabel(col)"
          min-width="110"
          show-overflow-tooltip
        />
      </el-table>
      <p v-else class="provenance__empty">还没有访视记录。</p>
    </template>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import {
  getFeatureSources,
  getSubjectCapabilities,
  getVisitSources
} from '@/api/subjects.js'

const props = defineProps({
  subjectId: { type: [Number, String], required: true }
})

const loading = ref(false)
const capabilities = ref({})
const featureRows = ref([])
const visitRows = ref([])

const CAP_LABELS = {
  readRaw: '读取原始数据',
  manage: '管理档案',
  record: '补录记录',
  writeClinical: '写入临床数据',
  requestAssessment: '发起评估'
}

const capabilityList = computed(() =>
  Object.keys(CAP_LABELS).map((key) => ({
    key,
    label: CAP_LABELS[key],
    allowed: Boolean(capabilities.value?.[key])
  }))
)

/** 后端返回的是 SQL 行（键可能是下划线命名的列名），列在运行时决定，避免字段名猜错后空白。 */
const KNOWN_LABELS = {
  id: 'ID',
  subject_id: '受试者',
  feature_name: '特征',
  feature_value: '值',
  recorded_at: '记录日期',
  unit: '单位',
  source: '来源',
  missing_reason: '缺失原因',
  data_version: '数据版本',
  import_job_id: '导入任务',
  visit_code: '访视编号',
  visit_date: '访视日期',
  raw_mmse: 'MMSE',
  raw_moca: 'MoCA',
  raw_cdr_sb: 'CDR-SB',
  visitCode: '访视编号',
  visitDate: '访视日期',
  rawMmse: 'MMSE',
  rawMoca: 'MoCA',
  rawCdrSb: 'CDR-SB',
  missingReason: '缺失原因',
  dataVersion: '数据版本',
  importJobId: '导入任务',
  featureName: '特征',
  featureValue: '值',
  recordedAt: '记录日期'
}

function columnLabel(key) {
  return KNOWN_LABELS[key] || key
}

function columnsOf(rows) {
  const seen = new Set()
  for (const row of rows) {
    Object.keys(row || {}).forEach((key) => seen.add(key))
  }
  // 把 id 放到最后，主体字段优先
  return [...seen].sort((a, b) => (a === 'id' ? 1 : b === 'id' ? -1 : 0))
}

const featureColumns = computed(() => columnsOf(featureRows.value))
const visitColumns = computed(() => columnsOf(visitRows.value))

async function load() {
  if (!props.subjectId) return
  const subjectId = props.subjectId
  loading.value = true
  try {
    const [caps, features, visits] = await Promise.allSettled([
      getSubjectCapabilities(subjectId),
      getFeatureSources(subjectId),
      getVisitSources(subjectId)
    ])
    // 抽屉已经切到别人：丢弃过期响应
    if (props.subjectId !== subjectId) return
    capabilities.value = caps.status === 'fulfilled' ? caps.value || {} : {}
    featureRows.value =
      features.status === 'fulfilled' && Array.isArray(features.value) ? features.value : []
    visitRows.value =
      visits.status === 'fulfilled' && Array.isArray(visits.value) ? visits.value : []
  } finally {
    loading.value = false
  }
}

watch(() => props.subjectId, load, { immediate: true })
</script>

<style scoped>
.provenance {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.provenance__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.provenance__title {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink);
}

.provenance__sub {
  margin: 8px 0 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--muted);
}

.caps {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.provenance__table {
  width: 100%;
}

.provenance__empty {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--soft);
}
</style>
