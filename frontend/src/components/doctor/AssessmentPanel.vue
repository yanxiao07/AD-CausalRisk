<template>
  <ResearchAssessmentPanel
    v-if="canResearch"
    :subject-id="subjectId"
    @completed="onCompleted"
  />
  <section v-else class="assessment-unavailable">
    <h3 class="assessment-unavailable__title">研究模型评估</h3>
    <p class="assessment-unavailable__text">
      旧规则评分通道已停用，新评估统一使用受控登记的冻结快照与已训练的七变量逻辑回归。
    </p>
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="当前账号没有研究评估权限"
      description="需要研究员或管理员角色。请联系研究员在「研究证据 → 研究表格评估」中提交，或由管理员在用户管理中调整角色。"
    />
  </section>
</template>

<script setup>
import { computed } from 'vue'
import ResearchAssessmentPanel from '@/components/assessment/ResearchAssessmentPanel.vue'
import { useUserStore } from '@/stores/user.js'

defineProps({
  subjectId: { type: [Number, String], required: true }
})

const emit = defineEmits(['completed'])
const userStore = useUserStore()

const canResearch = computed(() => userStore.hasRole(['ADMIN', 'RESEARCHER']))

function onCompleted(task) {
  emit('completed', task)
}
</script>

<style scoped>
.assessment-unavailable {
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: var(--surface);
  padding: 18px 20px 20px;
}

.assessment-unavailable__title {
  margin: 0 0 6px;
  font-size: 16px;
  font-weight: 700;
  color: var(--text);
}

.assessment-unavailable__text {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--muted);
}
</style>
