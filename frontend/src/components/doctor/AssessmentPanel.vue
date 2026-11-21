<template>
  <ResearchAssessmentPanel
    v-if="canResearch"
    :subject-id="subjectId"
    :data-scope="dataScope"
    @submitted="$emit('submitted', $event)"
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
      description="请联系获授权的研究员，从「模型验证与更新 → 研究工具 → 研究表格评估」提交。"
    />
  </section>
</template>

<script setup>
import { computed } from 'vue'
import ResearchAssessmentPanel from '@/components/assessment/ResearchAssessmentPanel.vue'
import { useUserStore } from '@/stores/user.js'

defineProps({
  subjectId: { type: [Number, String], required: true },
  dataScope: { type: String, required: true }
})

defineEmits(['submitted'])
const userStore = useUserStore()

const canResearch = computed(() => userStore.hasRole(['ADMIN', 'RESEARCHER']))

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
