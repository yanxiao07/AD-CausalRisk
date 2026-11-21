<template>
  <div class="authorization-page">
    <PageHeader back title="验证资料授权" subtitle="为已获批的项目登记病例访问范围。" />
    <ValidationCohort />
  </div>
</template>

<script setup>
import PageHeader from '@/components/common/PageHeader.vue'
import ValidationCohort from '@/components/assessment/ValidationCohort.vue'
</script>

<style scoped>
.authorization-page { display: grid; gap: 16px; }
</style>
