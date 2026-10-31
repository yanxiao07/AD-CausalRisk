# AD-CausalRisk 前端

Vue 3 + Vite + Element Plus 的单页应用，对接 Java 业务 API（Python 由 Java 的 `AgentClient` 调用，
浏览器不直接访问 Python）。

## 环境与命令

- Node.js ≥ 18（本地验证版本 v24.22.1，npm 11.19.1）
- 安装：`npm install`
- 开发：`npm run dev`（默认 http://localhost:5173，`/api` 代理到 http://localhost:8080）
- 构建：`npm run build`（产物在 `dist/`）
- 预览：`npm run preview`

后端未启动时页面会提示“无法连接服务器”，不再使用本地 Mock 数据。

## 目录结构

```
src/
├─ api/                 # 契约映射层：request.js 是唯一 Axios 实例
│   ├─ request.js       # Envelope 脱壳、401/403 处理、60s 超时
│   ├─ auth.js          # login / logout / fetchCurrentUser
│   ├─ subjects.js      # GET /api/subjects（page / pageSize）
│   └─ imports.js       # POST /api/import-jobs（multipart + Idempotency-Key）/ commit
├─ layouts/             # AppShell + 4 种布局 + 菜单注册表
│   ├─ AppShell.vue     # 按 route.meta.layout 选布局，统一页面过渡
│   ├─ BlankLayout.vue  # 登录 / 异常页
│   ├─ SidebarLayout.vue# 医生、研究员（侧边栏 + 顶栏）
│   ├─ PatientLayout.vue# 患者（无侧栏、单列、大字号）
│   ├─ ScreenLayout.vue # 管理员数据大屏（深色、无侧栏）
│   └─ menuRegistry.js  # 后端 menus key -> 文案/图标/路径
├─ components/
│   ├─ common/          # 原子组件：PageHeader / DataCard / StatusTag / EmptyState
│   ├─ BrainCanvas.vue  # 3D 登录页 Three.js 场景（保留原有逻辑）
│   └─ LoginCard.vue    # 身份选择 + 账号表单
├─ data/                # 角色元信息与 3D 场景文案
├─ directives/          # v-permission（按 roles/permissions 控制元素）
├─ router/              # 路由表 + 守卫（登录态、meta.roles）
├─ stores/              # Pinia：user（roles / menus / permissions，持久化）
├─ styles/
│   ├─ tokens.css       # 设计令牌（唯一真源）
│   ├─ element-overrides.css  # Element Plus 主题覆盖
│   └─ login-3d.css     # 3D 登录页专属样式，仅该路由加载
└─ views/
    ├─ doctor/ researcher/ admin/ patient/   # 四角色首页
    └─ exception/       # 401 / 404 / 500（共用 ExceptionPage）
```

## 布局与菜单

- 路由用 `meta.layout` 决定外壳：`blank`（登录/异常）、`sidebar`（医生、研究员）、
  `patient`（患者，**不挂载侧边栏**）、`screen`（管理员大屏）。
- 侧边栏菜单 = 固定「工作台」+ 后端 `/api/me` 下发的 `menus`（`menuRegistry` 负责文案与图标）。
  后端没给的 key 不渲染；尚未交付的页面渲染成禁用项并标「待建」。
- 路由守卫：未登录 → `/login?redirect=…`；角色不匹配 → `/401`；已登录访问 `/login` → 回到工作台。

## 请求层约定

所有后端响应都是统一 Envelope `{ code, message, data, errorCode, traceId, timestamp }`：

- `code === 0`：拦截器直接返回 `data`，业务代码不再解包；
- `code !== 0` 或非 2xx：统一 `ElMessage` 提示并 reject `{ code, errorCode, message }`；
- `401`：清理登录态并跳回 `/login?redirect=...`（登录接口自身的 401 除外，交由表单内联提示）；
- `403`：提示无权限，不跳转；
- 超时 60s（评估任务需要等待 Python 计算完成）；
- **不发送 `X-Allow-OOR`**：越界值必须由后端正常拦截。

## 设计令牌

颜色、圆角、阴影、字体、动效曲线全部定义在 `styles/tokens.css`，组件里不写死色值：
主色 `--brand-600: #0a73ad`、深色 `--brand-700`、浅底 `--brand-50`、页面底 `--bg`，
圆角 10/14/20/32，阴影用双层环境阴影（不用纯黑），动效统一
`cubic-bezier(.22,1,.36,1)`，并尊重 `prefers-reduced-motion`。

Element Plus 通过 `styles/element-overrides.css` 覆盖：主色、12px 圆角、
大按钮 56px 高、卡片 20px 圆角。

## 当前进度

- **P0（已完成）**：依赖切换（移除 Arco / antd / bw-pro-table / vueuse / dayjs / unocss，
  引入 element-plus 与 icons）、唯一请求实例、Vite 代理与 `@` 别名、路由守卫、Pinia 用户态。
- **P0.5（已完成）**：设计令牌、Element Plus 主题、四个原子组件、
  3D 登录页样式拆分（`tokens.css` + `login-3d.css`）。
- **P1（已完成）**：五种布局（AppShell + Blank/Sidebar/Patient/Screen）、
  由 `menus` 驱动的菜单、四角色首页骨架、Element Plus 按需引入、
  `meta.roles` 守卫；主 chunk 1.07 MB → 381 KB。
- **P2（已完成）**：医生「受试者档案」（分页表格 + 骨架屏 + 空/错态 + 抽屉）
  与研究员「数据导入」（拖拽上传 + Idempotency-Key + 预检面板 + 错误明细 + 确认入库）。
- **P3（已完成）**：抽屉内「发起风险预测」→ 2s 轮询任务 → 结果面板
  （riskScore / riskLevel / explanations / warnings）→ ECharts 力导向因果网络图
  → 人工复核（确认复核 / 要求补充数据）→ 历史访视表格。
- **P4（已完成）**：管理员数据大屏（档案总数 / 质控待审 / 质控拦截 / 审计事件 +
  近 7 天建档折线 + 档案状态环图 + 实时审计流，60 秒自动刷新）与独立审计页；
  患者端三卡适老化首页（等待复核结论 / 下次复诊 / 联系医生），
  未绑定、403、网络失败三种情况都有降级文案。
- **P5（已完成）**：随访计划（抽屉内新建 + 独立随访页，PATCH 状态流转）、
  批量评估（多选 + 全局进度面板 + 轮询）、待办复核（按评估 ID 载入 + 复核抽屉）、
  账号管理骨架页。
- **P6（已完成）**：评估报告页（可打印，含因果图与免责声明）、
  因果图谱独立大屏（沉浸模式 + 画布高度自适应力参数）、
  「因果图谱 / 评估报告」菜单点亮，复核页可跳转报告。
- **P7（收尾）**：系统设置页（前端构建 / Java 连通性探测 / Python 算法常量 / OpenAPI 契约版本，全部只读，版本号由 vite.config.js 在构建期从 package.json 与契约文件注入）；另外补齐了研究员的「数据质控」（真实 /api/qc/list + 通过/驳回）与「评估任务」（按任务 ID 查阶段耗时）；现在四个角色的侧边栏都没有「待建」项。
  近 7 天建档折线 + 档案状态环图 + 实时审计流，60 秒自动刷新）与独立审计页；
  患者端三卡适老化首页（等待复核结论 / 下次复诊 / 联系医生），
  未绑定、403、网络失败三种情况都有降级文案。

## 已知限制（P4）

- 大屏的图表数据来自 `GET /api/subjects` 的前 100 条（契约 `pageSize` 上限），
  档案超过 100 条时趋势与状态分布只是前 100 条的分布，页面底部已注明口径。
- 患者端没有已实现的摘要端点（`GET /api/assessments/{id}` 对 PATIENT 返回 403），
  因此状态卡固定显示"您的报告正在等待医生复核"；档案绑定信息与复诊日期来自真实接口。
- "联系医生"目前只有引导弹窗：契约里没有患者联系方式字段。

## 已知限制（P3）

- 契约里 `graph.edges` 只有 `note` 与 `direction: CONDITIONAL`，**没有权重**，
  因此图是"结构 + 证据等级"的展示，不画粗细/权重；界面明确标注"条件性关联，非病因证明"。
- 复核采用「同一评估只能复核一次」的后端语义：`reviewStatus !== PENDING` 时前端隐藏按钮。
- `GET /api/subjects` 不返回评估/复核状态，所以列表页没有"最近复核"列；
  复核成功后刷新列表，状态在抽屉内体现。

## 已知限制（P2）

- `GET /api/subjects` 的冻结契约只有 `page` / `pageSize`，**没有 keyword**，
  因此列表页的搜索框只过滤当前页，界面文案已注明；服务端检索需要契约新增参数。
- 档案抽屉只展示列表已有字段；访视、评估与因果图待 P3 接入 `GET /api/subjects/{subjectId}`。

## 端到端冒烟（可选）

`scripts/e2e-roles.mjs` 用无头 Chrome 跑四种角色：登录 → 落地页 → 布局 → 菜单 → 守卫，
不依赖后端（拦截 `/api/auth/login` 与 `/api/me`）。运行前需要：

```bash
npm i --no-save puppeteer-core
npm run dev -- --port 5199      # 另开终端
node scripts/e2e-roles.mjs      # 截图输出到 .shots/（已 gitignore）
```
