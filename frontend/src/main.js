import { createApp } from 'vue'

import App from './App.vue'
import router from './router'

// One global stylesheet: the design's background, ambient orbs, EKG heartbeat line
// and the 3D stage all share page-level selectors, so they stay unscoped.
import './assets/main.css'

createApp(App).use(router).mount('#app')
