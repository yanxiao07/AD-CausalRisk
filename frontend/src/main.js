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
