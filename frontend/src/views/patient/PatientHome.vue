<template>
  <div class="patient-home">
    <!-- 打印：隐藏按钮与导航，只输出白底摘要 -->
    <div class="print-actions no-print">
      <el-button type="primary" :icon="Printer" @click="printSummary">打印摘要</el-button>
    </div>

    <!-- 卡片 1：风险评估状态（来自 /api/patients/me/summary 的脱敏结论） -->
    <section class="hcard hcard--status" aria-labelledby="status-title">
      <p id="status-title" class="hcard__label">风险评估状态</p>

      <p v-if="state === 'loading'" class="hcard__status is-muted">正在读取您的档案…</p>
      <p v-else class="hcard__status" aria-live="polite">{{ statusText }}</p>

      <p class="hcard__note">
        <template v-if="state === 'ready' && summary?.subjectId">
          已绑定档案：{{ summary.alias || '—' }}（{{ summary.subjectCode || '—' }}）。
        </template>
        <template v-else-if="state === 'degraded'">
          {{ degradeReason }}。您可以稍后重试，或直接联系医生。
        </template>
        <template v-else-if="state === 'loading'"> 正在获取档案信息，请稍候。 </template>
      </p>

      <ul v-if="state === 'ready' && summary?.limitations?.length" class="hcard__limitations">
        <li v-for="item in summary.limitations" :key="item">{{ item }}</li>
      </ul>

      <el-button v-if="state === 'degraded'" class="hcard__retry" @click="load">重新加载</el-button>
    </section>

    <!-- 卡片 2：下次复诊 -->
    <section class="hcard" aria-labelledby="visit-title">
      <p id="visit-title" class="hcard__label">下次复诊</p>
      <p class="hcard__value" :class="{ 'is-empty': !summary?.followUpSummary }">
        {{ followUpText }}
      </p>
      <p class="hcard__note">
        {{
          state === 'loading'
            ? '正在读取随访安排…'
            : '复诊安排以医院通知为准；如有变化请电话确认。'
        }}
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
import { Printer } from '@element-plus/icons-vue'
import { getPatientSummary } from '@/api/patients.js'

/**
 * 患者首页只呈现三件事：复核对结论、下次复诊、联系医生。
 *
 * 结论来自 GET /api/patients/me/summary —— 后端已经脱敏，不含 riskScore、因果图或解释贡献；
 * 前端只负责排版，不再自己"猜"状态。
 */
const state = ref('loading') // loading | ready | degraded
const summary = ref(null)
const degradeReason = ref('')

const statusText = computed(() => {
  if (state.value === 'ready' && summary.value?.summary) return summary.value.summary
  if (state.value === 'degraded') return '暂时无法查看您的档案'
  return '正在读取您的档案…'
})

const followUpText = computed(() => {
  if (state.value === 'loading') return '正在读取…'
  if (state.value === 'degraded') return '暂时无法读取'
  return summary.value?.followUpSummary || '目前没有已安排的复诊'
})

async function load() {
  state.value = 'loading'
  degradeReason.value = ''
  try {
    summary.value = await getPatientSummary()
    state.value = 'ready'
  } catch (problem) {
    summary.value = null
    state.value = 'degraded'
    if (problem?.code === 403) degradeReason.value = '暂时无法查看您的档案，请联系医生确认授权'
    else if (problem?.errorCode === 'NETWORK_ERROR') degradeReason.value = '网络连接异常，请稍后重试'
    else degradeReason.value = '档案读取失败，请稍后重试'
  }
}

async function askHelp() {
  const contact = summary.value?.subjectCode
    ? `您的档案：${summary.value.alias || '—'}（${summary.value.subjectCode}）。`
    : '您的档案尚未绑定。'
  await ElMessageBox.alert(
    `${contact}请联系您的主治医生；联系方式将在账号与档案绑定后显示在本页。紧急情况请拨打 120。`,
    '联系医生',
    { confirmButtonText: '我知道了', type: 'info' }
  )
}

/** 打印摘要：由 @media print 规则隐藏导航、按钮与"联系医生"卡片，只留白底摘要。 */
function printSummary() {
  window.print()
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

/* 适老：结论字号 22–30px（结论句比"等待复核"长，字号略收一档），行高宽松 */
.hcard__status {
  margin: 0;
  font-family: var(--font-heading);
  font-size: clamp(22px, 3.6vw, 30px);
  line-height: 1.45;
  font-weight: 700;
  color: var(--brand-700);
}

.hcard__status.is-muted {
  font-size: clamp(20px, 3.2vw, 24px);
  color: var(--muted);
}

.hcard__value {
  margin: 0;
  font-family: var(--font-display);
  font-size: clamp(24px, 4.2vw, 30px);
  font-weight: 700;
  color: var(--ink);
  line-height: 1.5;
}

.hcard__value.is-empty {
  font-size: clamp(20px, 3.4vw, 24px);
  color: var(--muted);
}

.hcard__note {
  margin: 14px 0 0;
  font-size: 16px;
  line-height: 1.7;
  color: var(--muted);
}

.hcard__limitations {
  margin: 16px 0 0;
  padding-left: 20px;
  font-size: 15px;
  line-height: 1.8;
  color: var(--soft);
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

<!-- 打印规则：作用域样式够不到 PatientLayout 的顶栏/页脚，这里用全局规则 -->
<style>
@media print {
  @page {
    margin: 16mm;
  }

  .patient__bar,
  .patient__foot,
  .print-actions,
  .hcard--help,
  .hcard__retry {
    display: none !important;
  }

  .patient__main {
    max-width: none;
    padding: 0;
  }

  body,
  #app,
  .patient {
    background: #fff !important;
    background-image: none !important;
  }

  .hcard {
    box-shadow: none;
    border-color: #d8dee4;
    break-inside: avoid;
  }

  .hcard--status {
    background: #fff;
  }
}
</style>
