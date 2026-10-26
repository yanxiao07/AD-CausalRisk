import { createRouter, createWebHistory } from 'vue-router'

import LoginView from '@/views/LoginView.vue'

// Only the login surface exists so far; later modules add their own routes here.
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'login',
      component: LoginView,
    },
  ],
})

export default router
