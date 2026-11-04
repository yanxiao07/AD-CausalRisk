<template>
  <div class="doctor-home">
    <PageHeader title="医生工作台" subtitle="今天需要你处理的事项，以及正在进行中的随访。">
      <template #extra>
        <el-button :icon="Refresh" @click="refresh">刷新</el-button>
      </template>
    </PageHeader>

    <!-- 今日待复核：本页最重要的动作，给最大视觉权重 -->
    <DataCard class="review-hero" padding="lg">
      <div class="review-hero__inner">
        <div>
          <p class="review-hero__eyebrow">今日待复核</p>
          <p class="review-hero__count" :class="{ 'is-empty': pendingReview === null }" aria-live="polite">
            {{ pendingReview ?? '待接入' }}
          </p>
          <p class="review-hero__hint">来自研究员的评估结果，需要医生确认后才会对患者可见。</p>
        </div>
        <div class="review-hero__actions">
          <el-button type="primary" size="large" :disabled="!hasData" @click="goReviews">
            去复核
          </el-button>
          <span class="review-hero__note">P2 接入真实数据后可用</span>
        </div>
      </div>
    </DataCard>

    <div class="metric-grid">
      <DataCard v-for="metric in metrics" :key="metric.key" hover>
        <div class="metric">
          <div class="metric__head">
            <el-icon class="metric__icon"><component :is="metric.icon" /></el-icon>
            <span class="metric__label">{{ metric.label }}</span>
          </div>
          <p class="metric__value" :class="{ 'is-empty': metric.value === null }">
            {{ metric.value ?? '待接入' }}
          </p>
          <p class="metric__hint">{{ metric.hint }}</p>
        </div>
      </DataCard>
    </div>

    <DataCard title="今日随访" subtitle="按计划时间排列，逾期的会标红">
      <EmptyState
        title="还没有随访数据"
        description="随访列表将在 P2 接入 GET /api/subjects/{subjectId}/follow-ups，并补充跨患者的日历视图。"
        :icon="Timer"
      />
    </DataCard>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Bell, Refresh, Timer, User } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import EmptyState from '@/components/common/EmptyState.vue'

const router = useRouter()

/**
 * P1 只搭骨架：这里不做任何假数据填充。
 * null 表示"尚未接入"，模板渲染成"待接入"而不是 0 或破折号，避免被误读成真实数值。
 */
const hasData = ref(false)
const pendingReview = ref(null)

const metrics = [
  { key: 'followups', label: '今日随访', value: null, hint: '含逾期未完成', icon: Timer },
  { key: 'high-risk', label: '高危预警', value: null, hint: '高风险等级受试者', icon: Bell },
  { key: 'subjects', label: '在管受试者', value: null, hint: '授权范围内的档案', icon: User }
]

function refresh() {
  window.location.reload()
}

function goReviews() {
  router.push('/reviews')
}
</script>

<style scoped>
.doctor-home {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.review-hero {
  border-color: rgba(10, 115, 173, 0.22);
  background: linear-gradient(120deg, #ffffff 0%, #f2fbff 100%);
}

.review-hero__inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  flex-wrap: wrap;
}

.review-hero__eyebrow {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.4px;
  color: var(--brand-600);
}

.review-hero__count {
  margin: 0;
  font-family: var(--font-display);
  font-size: clamp(40px, 5vw, 56px);
  line-height: 1.05;
  font-weight: 700;
  color: var(--ink);
}

.review-hero__count.is-empty {
  font-size: 26px;
  color: var(--muted);
  letter-spacing: 0;
}

.review-hero__hint {
  margin: 8px 0 0;
  font-size: 14px;
  color: var(--muted);
}

.review-hero__actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
}

.review-hero__note {
  font-size: 12px;
  color: var(--soft);
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
}

.metric__head {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: 13px;
  font-weight: 600;
}

.metric__icon {
  color: var(--brand-600);
}

.metric__value {
  margin: 10px 0 4px;
  font-family: var(--font-display);
  font-size: 32px;
  font-weight: 700;
  color: var(--ink);
}

.metric__value.is-empty {
  font-size: 18px;
  color: var(--soft);
}

.metric__hint {
  margin: 0;
  font-size: 12px;
  color: var(--soft);
}
</style>
