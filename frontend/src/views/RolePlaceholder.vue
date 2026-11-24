<template>
  <div style="padding:40px;text-align:center">
    <h2>{{ role }} 工作台建设中</h2>
    <p>当前角色：{{ role }}，敬请期待</p>
    <router-link to="/login">返回登录</router-link>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const role = computed(() => route.meta.role || 'unknown')
</script>