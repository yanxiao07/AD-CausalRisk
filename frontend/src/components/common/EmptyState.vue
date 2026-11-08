<template>
  <div class="empty-state">
    <div class="empty-state__icon" aria-hidden="true">
      <el-icon :size="30"><component :is="icon" /></el-icon>
    </div>
    <h3 class="empty-state__title">{{ title }}</h3>
    <p v-if="description" class="empty-state__desc">{{ description }}</p>

    <div v-if="$slots.default || actionText" class="empty-state__actions">
      <slot>
        <el-button v-if="actionText" type="primary" @click="emit('action')">
          {{ actionText }}
        </el-button>
      </slot>
    </div>
  </div>
</template>

<script setup>
import { Document } from '@element-plus/icons-vue'

defineProps({
  title: { type: String, default: '暂无数据' },
  description: { type: String, default: '' },
  /** 有值时渲染一个主按钮并抛出 action 事件 */
  actionText: { type: String, default: '' },
  /** @element-plus/icons-vue 的图标组件 */
  icon: { type: Object, default: () => Document }
})

const emit = defineEmits(['action'])
</script>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 48px 24px;
  text-align: center;
}

.empty-state__icon {
  display: grid;
  place-items: center;
  width: 64px;
  height: 64px;
  border-radius: var(--r-lg);
  color: var(--brand-600);
  background: var(--brand-50);
  border: 1px solid var(--line);
  margin-bottom: 6px;
}

.empty-state__title {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
}

.empty-state__desc {
  max-width: 420px;
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--muted);
}

.empty-state__actions {
  margin-top: 8px;
}
</style>
