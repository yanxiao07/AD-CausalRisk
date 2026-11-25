<template>
  <div class="timeline-page">
    <section class="hcard hcard--timeline" aria-labelledby="timeline-title">
      <div class="hcard__heading">
        <button class="back-link" type="button" @click="router.push('/patient')">← 返回首页</button>
      </div>
      <h1 id="timeline-title" class="hcard__label">健康时间轴</h1>
      <p class="hcard__note">按时间回看历次访视、量表记录、医生结论和复诊安排。</p>

      <p v-if="loadState === 'loading'" class="hcard__note" role="status">正在读取健康记录…</p>
      <div v-else-if="loadState === 'degraded'" class="hcard__note" role="alert">
        暂时无法读取健康记录。
        <el-button text class="hcard__retry" @click="load">重新读取</el-button>
      </div>

      <template v-else-if="!timeline?.bound">
        <p class="hcard__note">您的账号还没有绑定健康档案。绑定后这里会显示历次记录，如需绑定请联系医院。</p>
      </template>

      <template v-else>
        <p class="hcard__note" v-if="timeline.subjectCode">
          当前查看的档案 {{ timeline.alias || timeline.subjectCode }}（{{ timeline.subjectCode }}）
        </p>

        <div v-if="events.length === 0" class="empty-tip">
          <p>目前还没有可显示的健康记录。</p>
          <p class="hcard__note">完成首次访视后，这里会按时间顺序出现记录。</p>
        </div>

        <ol v-else class="timeline-list">
          <li v-for="(event, index) in events" :key="index" class="timeline-item">
            <div class="timeline-rail" aria-hidden="true">
              <span class="timeline-dot" :class="`dot--${event.type.toLowerCase()}`" />
              <span v-if="index < events.length - 1" class="timeline-line" />
            </div>
            <div class="timeline-body">
              <div class="timeline-head">
                <span class="timeline-kind">{{ kindLabel(event.type) }}</span>
                <time class="timeline-time">{{ formatDateTime(event.occurredAt) }}</time>
              </div>
              <h2 class="timeline-title">{{ event.title }}</h2>
              <p v-if="event.detail" class="timeline-detail">{{ event.detail }}</p>
              <p v-if="event.status" class="timeline-status">{{ event.status }}</p>
              <ul v-if="event.values?.length" class="timeline-values">
                <li v-for="value in event.values" :key="value.label">
                  <span class="value-label">{{ value.label }}</span>
                  <span class="value-text">{{ value.value }}</span>
                </li>
              </ul>
            </div>
          </li>
        </ol>
      </template>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getMyTimeline } from '@/api/patients.js'
import { formatDateTime } from '@/utils/datetime.js'

const router = useRouter()
const timeline = ref(null)
const loadState = ref('loading')

const events = computed(() => timeline.value?.events || [])

const kindLabels = {
  VISIT: '访视',
  ASSESSMENT: '医生结论',
  FOLLOW_UP: '复诊',
}

function kindLabel(type) {
  return kindLabels[type] || '记录'
}

async function load() {
  loadState.value = 'loading'
  try {
    timeline.value = await getMyTimeline()
    loadState.value = 'ready'
  } catch {
    loadState.value = 'degraded'
  }
}

onMounted(load)
</script>

<style scoped>
.timeline-page {
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

.hcard--timeline {
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

.empty-tip {
  text-align: center;
  padding: 32px 0;
}

.empty-tip p {
  font-size: 1.25rem;
  margin: 0 0 8px;
  color: var(--ink);
}

.timeline-list {
  list-style: none;
  margin: 20px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.timeline-item {
  display: flex;
  gap: 16px;
}

.timeline-rail {
  position: relative;
  flex: 0 0 22px;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.timeline-dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  margin-top: 6px;
  background: var(--brand-600);
}

.dot--visit { background: #2563eb; }
.dot--assessment { background: #7c3aed; }
.dot--follow_up { background: #059669; }

.timeline-line {
  flex: 1;
  width: 2px;
  margin-top: 6px;
  background: var(--line);
}

.timeline-body {
  flex: 1;
  min-width: 0;
  padding-bottom: 24px;
}

.timeline-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 10px;
}

.timeline-kind {
  padding: 3px 10px;
  border-radius: 999px;
  background: rgba(37, 99, 235, 0.12);
  color: #1d4ed8;
  font-size: 0.95rem;
  font-weight: 600;
}

.timeline-time {
  font-size: 1rem;
  color: var(--muted);
}

.timeline-title {
  margin: 8px 0 6px;
  font-size: 1.375rem;
  font-weight: 700;
  color: var(--ink);
}

.timeline-detail {
  margin: 0;
  font-size: 1.125rem;
  line-height: 1.65;
  color: var(--ink);
  overflow-wrap: anywhere;
}

.timeline-status {
  margin: 8px 0 0;
  font-size: 1.0625rem;
  font-weight: 600;
  color: #b45309;
}

.timeline-values {
  list-style: none;
  margin: 10px 0 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.timeline-values li {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 6px 14px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid var(--line);
}

.value-label {
  font-size: 1rem;
  color: var(--muted);
}

.value-text {
  font-size: 1.125rem;
  font-weight: 700;
  color: var(--ink);
}
</style>
