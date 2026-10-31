<template>
  <div class="research-home">
    <PageHeader
      title="研究员工作台"
      subtitle="选择一条工作流开始：先把数据导进来，再对队列发起批量评估。"
    >
      <template #extra>
        <el-button :icon="Refresh" @click="refresh">刷新</el-button>
      </template>
    </PageHeader>

    <div class="cards">
      <article
        v-for="card in cards"
        :key="card.key"
        class="card"
        role="button"
        tabindex="0"
        :aria-label="card.title"
        @click="go(card)"
        @keydown.enter.prevent="go(card)"
        @keydown.space.prevent="go(card)"
      >
        <div class="card__icon">
          <el-icon :size="22"><component :is="card.icon" /></el-icon>
        </div>

        <h2 class="card__title">{{ card.title }}</h2>
        <p class="card__desc">{{ card.description }}</p>

        <ul class="card__points">
          <li v-for="point in card.points" :key="point">{{ point }}</li>
        </ul>

        <span class="card__cta">
          {{ card.cta }}
          <el-icon><ArrowRight /></el-icon>
        </span>
      </article>
    </div>

    <p class="research-home__foot">
      数据导入与批量评估需要研究员角色（ADMIN 亦可）；单条档案的发起与复核在「受试者档案」抽屉内完成。
    </p>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { ArrowRight, Checked, Cpu, Refresh, UploadFilled, User } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'

const router = useRouter()

/** 四个入口卡片：都跳到已经做好的真实页面，不再放禁用的占位拖拽区。 */
const cards = [
  {
    key: 'imports',
    title: '数据导入',
    description: '把队列 CSV 拖进来，预检通过后再确认入库。',
    points: ['支持 subjects / visits / features 三类模板', '逐行错误明细，可先修正再入库'],
    cta: '去导入数据',
    path: '/imports',
    icon: UploadFilled
  },
  {
    key: 'subjects',
    title: '批量评估',
    description: '在受试者档案里勾选多人，一次发起风险评估。',
    points: ['多选后点「批量发起评估」', '右上角进度面板实时显示完成情况'],
    cta: '去受试者档案',
    path: '/subjects',
    icon: User
  },
  {
    key: 'quality',
    title: '数据质控',
    description: '审核导入后产生的异常项，通过或驳回。',
    points: ['默认只看待审记录', '审核意见必填，处理完自动移出队列'],
    cta: '去数据质控',
    path: '/quality',
    icon: Checked
  },
  {
    key: 'tasks',
    title: '评估任务',
    description: '按任务 ID 查看四个阶段的耗时与结果。',
    points: ['数据融合 / 因果图谱 / 预警模型 / 个体解释', '可跳转到报告或复核页'],
    cta: '去评估任务',
    path: '/tasks',
    icon: Cpu
  }
]

function go(card) {
  router.push(card.path)
}

function refresh() {
  window.location.reload()
}
</script>

<style scoped>
.research-home {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 16px;
}

.card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 22px 22px 18px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-card);
  cursor: pointer;
  transition:
    transform var(--dur-base) var(--ease-out),
    box-shadow var(--dur-base) var(--ease-out),
    border-color var(--dur-base) var(--ease-out);
}

.card:hover,
.card:focus-visible {
  transform: translateY(-2px);
  border-color: rgba(10, 115, 173, 0.3);
  box-shadow: var(--shadow-hover);
}

.card:focus-visible {
  outline: 2px solid var(--brand-600);
  outline-offset: 2px;
}

.card__icon {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: var(--r-md);
  color: var(--brand-700);
  background: var(--brand-50);
  border: 1px solid rgba(10, 115, 173, 0.16);
}

.card__title {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: var(--ink);
}

.card__desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--muted);
}

.card__points {
  margin: 0;
  padding-left: 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--soft);
}

.card__cta {
  margin-top: auto;
  padding-top: 6px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  font-weight: 700;
  color: var(--brand-600);
}

.research-home__foot {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--soft);
}
</style>
