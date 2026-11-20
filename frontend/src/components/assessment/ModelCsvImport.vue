<template>
  <DataCard title="生成七变量测试快照" subtitle="用于 DEMO 档案。先保存量表并通过当前访视质控。">
    <p>系统读取已保存的 MMSE 和 CDR-SB。请用模板补入同次访视的年龄、教育年限、CDR 总体、ADAS-Cog 13、FAQ。</p>
    <p>APOE 和归一化 MRI 仍可通过档案特征表入库，用于对应资料和质控。七变量模型使用自己的输入字段。</p>
    <el-button @click="download">下载七变量模型模板</el-button>
    <label class="model-csv-file">选择七变量 CSV <input type="file" accept=".csv" :disabled="busy" @change="choose" /></label>
    <el-button :disabled="!file || busy" @click="submit(true)">预检模型资料</el-button>
    <el-button type="primary" :disabled="!canSave || busy" @click="submit(false)">保存通过的快照</el-button>
    <p v-if="error" role="alert">{{ error }}</p>
    <el-table v-if="rows.length" :data="rows" row-key="row" size="small">
      <el-table-column prop="row" label="行" width="55" />
      <el-table-column prop="subjectCode" label="档案编号" />
      <el-table-column label="处理结果" min-width="180">
        <template #default="{ row }">
          {{ row.status === 'READY' ? '可以保存' : row.message || `快照 #${row.snapshotId} · ${row.hostSyncStatus === 'SYNCED' ? '已同步' : '待同步'}` }}
        </template>
      </el-table-column>
    </el-table>
    <p v-if="saved" role="status">已保存本轮通过的快照。到「研究资料」选择档案和快照，再提交模型预测。</p>
  </DataCard>
</template>

<script setup>
import { computed, ref } from 'vue'
import DataCard from '@/components/common/DataCard.vue'
import { importModelCsv } from '@/api/research.js'
import { downloadCsv } from '@/utils/csv.js'

const file = ref(null)
const busy = ref(false)
const rows = ref([])
const error = ref('')
const saved = ref(false)
const canSave = computed(() => rows.value.some(row => row.status === 'READY'))

function choose(event) {
  file.value = event.target.files[0] || null
  rows.value = []
  error.value = ''
  saved.value = false
}

function download() {
  downloadCsv('demo-seven-model.csv', ['subjectCode', 'visitDate', 'age_years', 'education_years', 'cdr_global',
    'adas__total13', 'faq__faqtotal'], [['DEMO-CSV-001', '2025-01-01', 73, 12, 0.5, 14, 3]])
}

async function submit(preview) {
  busy.value = true
  error.value = ''
  saved.value = false
  try {
    rows.value = await importModelCsv(file.value, preview)
    saved.value = !preview && rows.value.some(row => row.status === 'SAVED')
  } catch (problem) {
    rows.value = []
    error.value = problem?.message || '读取模型 CSV 失败'
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.model-csv-file { display: block; margin: 1rem 0; }
.model-csv-file input { margin-inline-start: .5rem; }
</style>
