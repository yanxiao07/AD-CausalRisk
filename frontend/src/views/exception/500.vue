<template>
  <ExceptionPage
    code="500"
    title="服务暂时不可用"
    description="后端或 Python 子服务没有正常返回。请稍后重试；如果持续出现，请把页面上的 traceId 提供给管理员。"
  />
</template>

<script setup>
import ExceptionPage from './ExceptionPage.vue'
</script>
