<template>
  <ExceptionPage
    code="401"
    title="没有访问权限"
    description="当前账号的角色或授权范围不包含这个页面。请联系管理员确认档案授权，或回到自己的工作台。"
  />
</template>

<script setup>
import ExceptionPage from './ExceptionPage.vue'
</script>
