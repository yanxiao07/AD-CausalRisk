import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'
import { readFileSync } from 'node:fs'

const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8'))
const contract = JSON.parse(
  readFileSync(new URL('../contracts/java-business.target.openapi.json', import.meta.url), 'utf8')
)
const httpMethods = new Set(['get', 'post', 'patch', 'put', 'delete'])
const operationCount = Object.values(contract.paths).reduce(
  (total, pathItem) => total + Object.keys(pathItem).filter((key) => httpMethods.has(key)).length,
  0
)
const constOf = (schema, property) => contract.components.schemas?.[schema]?.properties?.[property]?.const ?? ''

export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需注册：模板里出现 <el-xxx> 才引入对应组件与 JS。
    // importStyle: false —— 样式统一由 main.js 里的一份 index.css 提供，
    // 保证我们自己 element-overrides.css 的层叠顺序稳定。
    Components({
      resolvers: [ElementPlusResolver({ importStyle: false })],
      dts: false
    })
  ],
  // 构建期常量：只把版本号这类标量注入，不把整份 OpenAPI 打进包。
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
    __BUILD_TIME__: JSON.stringify(new Date().toISOString()),
    __CONTRACT_VERSION__: JSON.stringify(contract.info?.version ?? ''),
    __CONTRACT_TITLE__: JSON.stringify(contract.info?.title ?? ''),
    __CONTRACT_OPS__: JSON.stringify(operationCount),
    __CONTRACT_PATHS__: JSON.stringify(Object.keys(contract.paths ?? {}).length),
    __MODEL_VERSION__: JSON.stringify(constOf('AgentResult', 'modelVersion')),
    __AGENT_CONTRACT_VERSION__: JSON.stringify(constOf('AgentResult', 'contractVersion')),
    __WORKFLOW_VERSION__: JSON.stringify(constOf('AgentResult', 'workflowVersion'))
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      // 浏览器只访问 Java 业务 API；Java 再按需调用 Python 子服务。
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    },
    watch: {
      // Windows 上编辑器/格式化的短命写入会让 chokidar 报 EBUSY。
      ignored: ['**/.*.tmpdir/**', '**/*.tmp']
    }
  }
})
