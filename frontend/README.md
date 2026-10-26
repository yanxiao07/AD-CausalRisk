# 前端负责人任务

在此目录建立前端应用，完成项目配置、路由、认证会话、四类角色菜单、档案/访视、导入预览、质控、任务、图谱/解释、业务复核、随访、报告和审计界面。

前端对接Java业务API，Python由Java AgentClient调用。先阅读[需求](../docs/Course_SRS_V3_2.md)、[设计参考](../docs/Design_Guide.md)、[目标接口](../contracts/java-business.target.openapi.json)与[AI协作指南](../docs/AI_Collaboration.md)，再编写类型、客户端和页面。Java接口未就绪时使用明确标记的Mock，联调前关闭该功能的Mock。

每页处理加载、空数据、失败、无权限状态。BLOCKED不显示数值排序；患者页面只接收通俗摘要和随访信息。图谱说明条件性解释，不能把箭头写成病因证明。完成桌面/手机、键盘、表单与任务失败流程测试，提交锁文件、构建启动说明、截图及自己的CI。

首次PR建议完成工程、路由、API客户端、登录页和四种状态。模块README记录Node版本、安装、开发、构建和测试命令；配置Java API地址，注明Mock启停方式。组件与页面分开，接口调用集中管理，避免在各组件中重复定义字段和错误处理。

---

# 登录页（`frontend/login-page` 分支）

Vue 3 + Vite 工程骨架与 3D 登录页，由 `frontend/login-demo/index.html`（单文件原型）拆分而来。原型保留原地不动作为视觉参照，工程源码在 `src/`。

## 环境与命令

- Node.js 18+（本次验证使用 Node 24.21.0），npm 随 Node 安装。
- 依赖已全部写入 `package.json`，**不需要**单独安装某个包：

```powershell
cd frontend
npm install          # 一次装齐 vue / vue-router / axios / three / vite / @vitejs/plugin-vue
npm run dev          # 开发服务器 http://localhost:5173
npm run build        # 产物输出到 frontend/dist
npm run preview      # 本地预览 dist
```

已提交 `package-lock.json`（`lockfileVersion: 3`，114 个 `resolved` 全部指向 `registry.npmjs.org`）：`npm ci` 可复现安装。若团队改用 pnpm，请换成 `pnpm-lock.yaml`，两个锁文件不要并存。

## 与 Java 后端的连接

`vite.config.js` 把 `/api` 代理到 `http://localhost:8080`，浏览器只访问 5173，因此 `POST /api/auth/login` 下发的会话 Cookie 是同源的，不需要 CORS 预检。后端未启动时，登录页显示“无法连接服务器，请确认后端已启动”。

登录成功后进入成功态，并尝试 `router.push('/doctor' | '/researcher' | '/admin' | '/patient')`；这些工作台路由属于后续 PR，当前用 `router.resolve()` 守卫，未注册时不跳转，不会白屏。

## 结构

| 文件 | 职责 |
| --- | --- |
| `src/views/LoginView.vue` | 页面容器：全局背景、顶部品牌栏、右下角“探索平台”按钮、居中/左移双屏状态、详情抽屉、帮助弹窗 |
| `src/components/LoginCard.vue` | 角色选择 + 登录表单，`axios.post('/api/auth/login')`、loading、错误提示、成功后跳转 |
| `src/components/BrainCanvas.vue` | Three.js 水晶大脑：`onMounted` 初始化，`onUnmounted` 停止渲染循环、解绑监听、`dispose` 几何体/材质并 `forceContextLoss` |
| `src/assets/main.css` | 原型两份 `<style>` 的完整迁移（背景、EKG 心跳线、卡片、3D 舞台、响应式） |
| `src/assets/brain.glb` | 原型内联的 1.8 MB base64 模型（Z-Anatomy / BodyParts3D，CC BY-SA 4.0）解码为 1.35 MB 二进制，本地优先加载；原型里的两个 CDN 镜像保留为回退 |
| `src/data/roleMeta.js`、`src/data/exploreDetails.js`、`src/components/rolePortraits.js` | 从原型提取的角色文案、3D 标签详情、四张 SVG 头像 |

`src/layout/MainLayout.vue` 是既有团队文件，本分支未改动。

## 已知差异与后续

- 原型用内联 `<script>` 操作 DOM，重建时改为 Vue 响应式；`body.scene-open` 等页面级状态类仍由 JS 切换，因为样式表用 `body.*` 选择器驱动双屏布局。
- 接口调用暂在 `LoginCard.vue` 内直接使用 axios（按本次需求），后续应抽到 `src/api/` 统一封装，避免各组件重复定义字段与错误处理。
- GLB 未使用 Draco 压缩，因此不会下载 Draco 解码器；`DRACOLoader` 仅作兼容保留。模型加载失败时退化为本地程序化水晶脑。
- 尚未补：单元测试与 E2E、四类角色工作台路由、会话恢复（刷新后用 `/api/me` 校验登录态）。

## 复刻验收方式（可重复）

视觉一致性不是靠"看起来对"判断的，这里用无头 Chrome 实测：

```powershell
npm run dev
# 默认态：/ ，双屏态：/?scene=1（页面支持该深链，便于截图与回归）
& "C:\Program Files\Google\Chrome\Application\chrome.exe" --headless=new --disable-gpu `
  --enable-unsafe-swiftshader --window-size=1600,1000 --virtual-time-budget=9000 `
  "--screenshot=$pwd\.shots\default.png" 'http://127.0.0.1:5173/'
```

对照 `frontend/login-demo/index.html`（`file://` 直接打开）后逐像素比对：

| 区域 | 与原版差异 |
| --- | --- |
| 登录卡片 560×650 | **0.00%**（逐像素相同） |
| 顶栏 | **0.00%** |
| 双屏态卡片 | 0.04%（仅 EKG 心跳线经过的 60px 高度，动画相位不同） |
| CSS 文本 | `main.css` 与原型两个 `<style>` **逐字节相同** |

布局几何（CDP 实测，Vue 与原型完全一致）：双屏态卡片 `x=66 y=164 500×598`、3D 舞台 `x=631 y=78 947×824`、`<canvas>` 属性尺寸 `946×824`、展开态角色网格 `109.25px × 4 / gap 7px`、默认态 `195.16px × 2 / gap 12px`。

## 本轮修复的问题

- **`THREE is not defined`**：拆分时误删了 SFC 头部的四个 `three` 导入，`onMounted` 直接抛错，导致 3D 场景、标签、抽屉全部不存在。已恢复。
- **角色网格塌成三行**：`selection-outline` 在 `is-enhanced` 未生效时仍是网格项，占掉第一格把医生卡挤到第二列。改为静态 `is-enhanced`（原版也是 JS 一开始就加），仅 `is-positioned` 等测量完成后追加。
- **`#app` 破坏了原版的 `body > *` 布局前提**：Vue 挂载点改为 `display: contents`（写在 `index.html`），使 `.screen`/`.brain-stage`/`.explore-pill` 等成为彼此的兄弟节点，与原文件结构一致。
- **首帧尺寸为 0**：展开时先 `api.resize()`（内部走 `renderer.setSize` + `camera.updateProjectionMatrix`）再 `start()`，并在下一帧再量一次。
- **开发服务器被文件监听打断**：`server.watch.ignored` 忽略 `**/.*.tmpdir/**` 与 `**/*.tmp`——某些编辑器/工具通过 `src/` 下的临时目录落盘，Windows 上会让 chokidar 抛 `EBUSY` 并终止 dev server。

> 角色网格间距沿用原版的 `gap:12px`（原型如此）。若确实要改成 16px，只需改 `main.css` 中 `.role-grid` 的一处，但会与原型的像素比对不一致。
