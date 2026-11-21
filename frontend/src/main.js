import { createApp } from 'vue'
import { createPinia } from 'pinia'
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate'
// Element Plus 样式：组件本身由 unplugin-vue-components 按需注册，
// 这里只引一份完整样式，确保与 element-overrides.css 的覆盖顺序稳定。
import 'element-plus/dist/index.css'
import './styles/tokens.css'
import './styles/element-overrides.css'
import App from './App.vue'
import router from './router'

const pinia = createPinia()
pinia.use(piniaPluginPersistedstate)

const app = createApp(App)
app.use(pinia)
app.use(router)

app.mount('#app')

// 首屏加载层：只有"确实等了一段时间"才值得播放。
// 秒开（本地、缓存命中）时立即撤掉，避免把一个并不存在的等待演成动画。
const BOOT_MIN_VISIBLE_MS = 420
const boot = document.getElementById('boot')
if (boot) {
  const startedAt = Number(window.__bootStartedAt || 0)
  const elapsed = startedAt > 0 ? performance.now() - startedAt : Number.POSITIVE_INFINITY
  if (elapsed >= BOOT_MIN_VISIBLE_MS) {
    boot.classList.add('app-boot--done')
    window.setTimeout(() => boot.remove(), 320)
  } else {
    boot.remove()
  }
}
