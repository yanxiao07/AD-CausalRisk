<template>
  <DataCard v-if="canGrant" title="验证资料授权" subtitle="按医院已批准的研究项目登记或撤销病例访问。">
    <template #header-extra>
      <BatchValidationGrant @saved="load(1)" />
    </template>
    <el-form label-position="top" @submit.prevent="saveGrant(false)">
      <div class="grant-form">
        <el-form-item label="项目编号"><el-input v-model.trim="grant.project" maxlength="50" /></el-form-item>
        <el-form-item label="患者档案编号"><el-input v-model.trim="grant.subjectCode" maxlength="50" /></el-form-item>
        <el-form-item label="研究员账号"><el-input v-model.trim="grant.researcher" maxlength="50" /></el-form-item>
        <el-form-item label="授权依据编号"><el-input v-model.trim="grant.authorizationRef" maxlength="150" /></el-form-item>
      </div>
      <el-button type="primary" native-type="submit" :loading="saving && !revoking"
        :disabled="saving || !canChangeGrant || !grant.authorizationRef">登记授权</el-button>
      <el-button :loading="saving && revoking" :disabled="saving || !canChangeGrant"
        @click="saveGrant(true)">撤销授权</el-button>
      <p v-if="grantMessage" role="status">{{ grantMessage }}</p>
      <p v-if="grantError" role="alert">{{ grantError }}</p>
    </el-form>
  </DataCard>

  <DataCard v-if="canRead" title="获授权验证队列" subtitle="每例关联首次成功的预测快照和责任医生登记的结局。">
    <template #header-extra>
      <el-button :loading="loading" @click="load(page)">刷新队列</el-button>
    </template>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <el-skeleton v-else-if="loading && !result" :rows="3" animated />
    <el-empty v-else-if="!result?.total" description="当前账号还没有获授权的病例。由管理员按研究项目登记后显示。" />
    <template v-else>
      <p class="summary">已授权 {{ result.total }} 例。本页 {{ paired }} 例同时有预测概率和可判定的 24 个月结局。</p>
      <el-table :data="result.items" row-key="caseCode" v-loading="loading">
        <el-table-column type="expand">
          <template #default="{ row }">
            <dl class="snapshot">
              <div><dt>验证编号</dt><dd>{{ row.caseCode }}</dd></div>
              <div><dt>24 个月截止</dt><dd>{{ row.horizonDate || '—' }}</dd></div>
              <div><dt>结局记录日期</dt><dd>{{ row.observedDate || '未登记' }}</dd></div>
              <div><dt>模型产物摘要</dt><dd>{{ row.artifactSha256 || '—' }}</dd></div>
              <div><dt>冻结输入摘要</dt><dd>{{ row.inputSha256 || '—' }}</dd></div>
              <div v-for="(value, key) in row.inputs" :key="key"><dt>{{ inputLabels[key] }}</dt><dd>{{ value }}</dd></div>
            </dl>
          </template>
        </el-table-column>
        <el-table-column prop="project" label="研究项目" min-width="110" />
        <el-table-column label="验证编号" min-width="132">
          <template #default="{ row }">{{ row.caseCode.slice(0, 12) }}</template>
        </el-table-column>
        <el-table-column label="预测访视" min-width="112">
          <template #default="{ row }">{{ row.indexDate || '—' }}</template>
        </el-table-column>
        <el-table-column label="模型 / 用途" min-width="160">
          <template #default="{ row }">
            {{ row.model || predictionLabels[row.predictionStatus] }}
            <small v-if="row.clinicalUse === false" class="model-use">仅供研究演练</small>
          </template>
        </el-table-column>
        <el-table-column label="24 个月 AD 风险" min-width="135">
          <template #default="{ row }">
            {{ row.adRisk == null ? predictionLabels[row.predictionStatus] : percent.format(row.adRisk) }}
          </template>
        </el-table-column>
        <el-table-column label="24 个月结局" min-width="128">
          <template #default="{ row }">{{ outcomeLabels[row.outcome24m] }}</template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="result.total > result.pageSize" class="pager" layout="prev, pager, next"
        :current-page="page" :page-size="result.pageSize" :total="result.total" @current-change="load" />
    </template>
  </DataCard>
  <ValidationRun v-if="canRead && result" :revision="revision" :page-cases="result.items" />
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import DataCard from '@/components/common/DataCard.vue'
import BatchValidationGrant from '@/components/assessment/BatchValidationGrant.vue'
import ValidationRun from '@/components/assessment/ValidationRun.vue'
import { grantValidationCase, listValidationCases, revokeValidationCase } from '@/api/validationCohort.js'
import { useUserStore } from '@/stores/user.js'

const user = useUserStore()
const canGrant = computed(() => user.hasRole('ADMIN') && !user.hasRole('PATIENT'))
const canRead = computed(() => user.hasRole('RESEARCHER') && !user.hasRole('PATIENT'))
const grant = reactive({ project: '', subjectCode: '', researcher: '', authorizationRef: '' })
const canChangeGrant = computed(() => grant.project && grant.subjectCode && grant.researcher)
const saving = ref(false)
const revoking = ref(false)
const grantMessage = ref('')
const grantError = ref('')
const loading = ref(false)
const error = ref('')
const result = ref(null)
const page = ref(1)
const revision = ref(0)
const paired = computed(() => result.value?.items.filter(row => row.adRisk != null && row.outcome24m !== 'UNKNOWN').length ?? 0)
const percent = new Intl.NumberFormat('zh-CN', { style: 'percent', maximumFractionDigits: 2 })
const predictionLabels = { PENDING: '待预测', BLOCKED: '质控阻断', FAILED: '预测失败', OUTCOME_UNKNOWN: '任务结果待确认', COMPLETED: '概率不可用' }
const outcomeLabels = { AD: '记录 AD', OTHER_DEMENTIA: '记录其他痴呆', NO_EVENT: '未记录 AD 或其他痴呆', UNKNOWN: '结局待确认' }
const inputLabels = { age_years: '年龄', education_years: '受教育年限', mmse: 'MMSE', cdr_sb: 'CDR-SB', cdr_global: 'CDR 全局', adas__total13: 'ADAS-Cog 13', faq__faqtotal: 'FAQ 总分' }

async function load(target) {
  if (!canRead.value || loading.value) return
  loading.value = true
  error.value = ''
  try {
    const next = await listValidationCases(target)
    const lastPage = Math.max(1, Math.ceil(next.total / next.pageSize))
    result.value = target > lastPage ? await listValidationCases(lastPage) : next
    page.value = result.value.page
    revision.value += 1
  } catch (problem) {
    error.value = problem.message || '验证队列读取失败，请重试。'
  } finally {
    loading.value = false
  }
}

async function saveGrant(revoke) {
  if (saving.value || !canChangeGrant.value || (!revoke && !grant.authorizationRef)) return
  const { project, subjectCode, researcher, authorizationRef } = grant
  saving.value = true
  revoking.value = revoke
  grantMessage.value = ''
  grantError.value = ''
  try {
    if (revoke) {
      try {
        await ElMessageBox.confirm('撤销后，该研究员将失去此项目下对该病例的查看权限。', '撤销验证资料授权', {
          confirmButtonText: '确认撤销', cancelButtonText: '取消'
        })
      } catch {
        return
      }
      await revokeValidationCase({ project, subjectCode, researcher })
      grantMessage.value = '授权已撤销。'
    } else {
      await grantValidationCase({ project, subjectCode, researcher, authorizationRef })
      grantMessage.value = '授权已登记。'
    }
    await load(1)
  } catch (problem) {
    grantError.value = problem.message || '保存授权失败，请重试。'
  } finally {
    saving.value = false
  }
}

onMounted(() => load(1))
</script>

<style scoped>
.grant-form { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 180px), 1fr)); gap: 0 16px; }
.summary { margin-top: 0; }
.model-use { display: block; color: var(--text-secondary); }
.snapshot { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 210px), 1fr)); gap: 12px 20px; margin: 16px; }
.snapshot dt { font-weight: 600; }
.snapshot dd { margin: 0; overflow-wrap: anywhere; }
.pager { margin-top: 16px; justify-content: flex-end; }
</style>
