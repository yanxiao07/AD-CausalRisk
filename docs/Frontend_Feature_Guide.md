# 前端功能全景与使用说明书

> **适用分支**：`feature/rebuild-frontend`（P0–P7 全量重构后）
> **技术栈**：Vue 3.5 · Vite 5 · Element Plus 2.14 · Pinia 4 · Vue Router 4 · ECharts 6 · Three.js 0.186
> **代码规模**：`src/` 下 60 个文件（20 个页面、14 个组件文件（13 个 `.vue` + 1 个插画 `.js`）、6 个布局相关文件、9 个 API 模块、20 条路由）
> **定位**：给查错、优化、写项目文档和答辩用的一本"操作 + 原理 + 边界"手册。本文所有数字、路径、接口均来自当前代码，不是设计稿。

---

## 目录

- [0. 五分钟上手](#0-五分钟上手)
- [1. 设计语言与整体架构](#1-设计语言与整体架构)
  - [1.1 三层样式体系](#11-三层样式体系)
  - [1.2 布局即路由：一个 AppShell + 四种外壳](#12-布局即路由一个-appshell--四种外壳)
  - [1.3 角色隔离的三道闸](#13-角色隔离的三道闸)
- [2. 四角色功能全景](#2-四角色功能全景)
  - [2.1 登录与通用能力](#21-登录与通用能力)
  - [2.2 医生端](#22-医生端)
  - [2.3 研究员端](#23-研究员端)
  - [2.4 管理员端](#24-管理员端)
  - [2.5 患者端](#25-患者端)
- [3. 关键交互与防呆机制](#3-关键交互与防呆机制)
  - [3.1 长耗时任务](#31-长耗时任务)
  - [3.2 错误处理矩阵](#32-错误处理矩阵)
  - [3.3 展示层一致性与"不撒谎"原则](#33-展示层一致性与不撒谎原则)
  - [4. 查错与优化指南](#4-查错与优化指南)（完整内容见 [Backlog](./Frontend_Optimization_Backlog.md)）
- [5. 附录](#5-附录)
  - [A. 文件地图](#a-文件地图)
  - [B. 契约 22 操作 ↔ 页面映射](#b-契约-22-操作--页面映射)
  - [C. 演示账号与种子数据](#c-演示账号与种子数据)
  - [D. 命令与 E2E 脚本清单](#d-命令与-e2e-脚本清单)
  - [E. 10 分钟答辩演示脚本](#e-10-分钟答辩演示脚本)

---

## 0. 五分钟上手

### 0.1 启动顺序

| 顺序 | 服务 | 端口 | 命令 | 说明 |
| --- | --- | --- | --- | --- |
| 1 | Python 算法服务 | 8091 | 见 `python_service/README.md` | `/api/health` 可自检 |
| 2 | Java 业务后端 | 8080 | `cd backend; .\mvnw.cmd spring-boot:run` | 启动时 Flyway 自动跑 V1–V9 |
| 3 | 前端 | 5173 | `cd frontend; npm run dev` | `/api` 已代理到 8080 |

> ⚠️ 前端**不需要**配置后端地址，`vite.config.js` 里已把 `/api` 代理到 `http://localhost:8080`，会话 Cookie 同源，不存在 CORS 问题。

### 0.2 演示账号（`V2` 种子，密码统一 `DemoPass!2026`）

| 账号 | 角色 | 登录后落地 | 能看到多少档案 |
| --- | --- | --- | --- |
| `admin` | ADMIN | `/admin` 数据大屏 | 全部（100 条） |
| `researcher` | RESEARCHER | `/researcher` | 全部（100 条） |
| `doctor` | DOCTOR | `/doctor` | 全部（V9 已授权 VIEW+MANAGE） |
| `patient` | PATIENT | `/patient` | 仅 `DEMO-001`（V9 绑定） |
| `research_doctor` | RESEARCHER + DOCTOR | `/researcher` | 全 部（双角色取并集） |

### 0.3 一句话架构

```
浏览器 (Vue SPA)  ── /api ──▶  Java 8080 (鉴权/事务/持久化)  ──▶  Python 8091 (四阶段因果工作流)
        │                                                              │
        └── 只认 Envelope { code, message, data, errorCode, traceId, timestamp }，code===0 才脱壳
```

浏览器**永远不直连 Python**，这是契约硬约束（AGENTS.md）。

---

## 1. 设计语言与整体架构

### 1.1 三层样式体系

样式被刻意拆成三个文件，**各管一层，互不越界**：

| 文件 | 作用 | 何时加载 | 规模 |
| --- | --- | --- | --- |
| `styles/tokens.css` | 设计令牌 + 基础重置（色板/圆角/阴影/字体/动效/玻璃）+ 全局工具类 | 全局，`main.js` 第一顺位 | ~140 行 |
| `styles/element-overrides.css` | Element Plus 变量与结构覆盖（主色、12px 圆角、56px 大按钮） | 全局，**紧跟 EP 样式之后** | ~120 行 |
| `styles/login-3d.css` | 3D 登录页专属（`.screen` / `.brain-stage` / `.explore-pill` / 详情抽屉） | **仅 `/login` 路由懒加载** | ~1400 行 |

**为什么这么拆**：3D 登录页是从原型搬来的 70KB 全局 CSS，如果继续全局引入，会污染每个业务页（`body{background}`、`#app{display:contents}` 等）。拆开后业务页背景由 tokens 控制，登录页退出时其 `html/body` 规则随 `body.login-3d` 类一起失效。

#### 核心令牌（业务页取色的唯一来源）

| 类别 | 令牌 | 值 | 用在哪 |
| --- | --- | --- | --- |
| 品牌色 | `--brand-600 / -700 / -50` | `#0a73ad` / `#075c8a` / `#e7f4fb` | 主按钮、选中态、浅底 |
| 墨色 | `--ink / --ink-700 / --muted / --soft` | `#11344b` / `#3d5c70` / `#637f91` / `#8ca5b3` | 标题/正文/次要/占位 |
| 页面底 | `--bg` / `--bg-soft` | `#eff9fd` / `#f4fbfd` | 业务页背景 |
| 圆角 | `--r-sm/md/lg/xl` | `10 / 14 / 20 / 32px` | 标签/输入框/卡片/大面板 |
| 阴影 | `--shadow-panel / --shadow-card / --shadow-hover` | 双层环境阴影，**从不用纯黑** | 抽屉、卡片、悬浮 |
| 动效 | `--ease-out` / `--dur-fast/base/slow` | `cubic-bezier(.22,1,.36,1)` / `120/200/320ms` | 全站统一手感 |
| 玻璃 | `--glass-blur` | `blur(18px) saturate(160%)` | 顶栏、侧栏、悬浮面板 |

"科技医疗风"具体落在三件事上：

1. **底色不是纯白**，是浅冰蓝 `#eff9fd` + 一层极淡的多点径向光晕（`--page-glow`），避免"后台管理系统"的塑料感；
2. **白色卡片 + 20/32px 大圆角 + 双层环境阴影**，卡片边缘不发黑；
3. **玻璃拟态只用在"外壳"上**（侧栏、顶栏、悬浮进度面板），内容区保持实底白，保证可读性。

### 1.2 布局即路由：一个 AppShell + 四种外壳

`App.vue` 只有一行 `<AppShell />`，AppShell 根据 **路由 `meta.layout`** 动态选外壳：

| `meta.layout` | 组件 | 用在 | 特征 |
| --- | --- | --- | --- |
| `blank` | `BlankLayout.vue` | 登录、401/404/500 | 不加任何壳，直接铺满视口 |
| `sidebar` | `SidebarLayout.vue` | 医生、研究员 | 可折叠侧栏（232↔76px）+ 玻璃顶栏 + 面包屑 + 用户菜单 |
| `patient` | `PatientLayout.vue` | 患者 | **不挂载侧栏**，单列 720px，`--el-font-size-base: 20px`，控件 56/64px |
| `screen` | `ScreenLayout.vue` | 管理员全部页面 | 深色全屏、无侧栏、顶部横向导航 + 实时时钟 |

```js
// AppShell.vue 的核心逻辑
const LAYOUTS = { blank: BlankLayout, sidebar: SidebarLayout, patient: PatientLayout, screen: ScreenLayout }
const layout = computed(() => {
  const declared = route.meta?.layout
  if (declared && LAYOUTS[declared]) return LAYOUTS[declared]
  return userStore.primaryRole === 'PATIENT' ? PatientLayout : SidebarLayout  // 兜底
})
```

**兜底规则的意义**：即使将来有人新加路由忘了写 `meta.layout`，患者也**不会**看到侧栏——患者端适老是"结构上不可达"，不是靠 CSS 隐藏。

页面切换统一走 `AppShell` 里的 `<transition name="page" mode="out-in">`：240ms 淡入 + 8px 上移；`prefers-reduced-motion` 下自动降级为无动画。

### 1.3 角色隔离的三道闸

| 闸门 | 实现 | 作用 |
| --- | --- | --- |
| ① 路由守卫 | `router.beforeEach` 校验 `isLoggedIn` 与 `meta.roles` | 未登录 → `/login?redirect=`；角色不符 → `/401` |
| ② 菜单下发 | 后端 `/api/me` 返回 `menus`，前端 `menuRegistry` 只做"key → 文案/图标/路径"映射 | 后端不给的菜单不渲染；前端没登记的 key 降级为禁用项 |
| ③ 服务端再鉴权 | Java 里 `SubjectAccessPolicy` / 各 Service 再判定一次 | 前端只负责"别让人误点"，真正的安全边界在 8080 |

> **重要原则**：`meta.roles` 放宽或收紧**必须与后端策略同步**。本次联调就抓到一个真实不一致：`/reviews`、`/followups` 只允许 DOCTOR，但后端允许 RESEARCHER（研究员能在抽屉里复核成功，却进不去复核页）。修复后两者都是 `['DOCTOR','RESEARCHER']`。

---

## 2. 四角色功能全景

### 2.1 登录与通用能力

**`/login`（LoginView + LoginCard + BrainCanvas）**

- 左侧身份卡四选一（医生/研究员/管理员/患者），选中框用 `getBoundingClientRect` 做弹性位移；
- 右侧账号表单，`Enter` 提交，密码可见性切换，错误内联提示 + 抖动；
- 右下角 **`CRYSTAL EXPLORER`** 胶囊 → 展开右侧 3D 脑模型（Three.js，可拖拽旋转），收起即回到单卡；
- 登录成功 → 写 Pinia（`roles/menus/permissions`）+ `window.__ADAuthUser` → 按角色跳 `homePath`；
- 3D 场景有完整生命周期：`onUnmounted` 停止动画循环、`dispose()` 几何/材质/纹理、`forceContextLoss()`、断开 `ResizeObserver`、清理所有 window 监听与定时器。

**通用**：401/404/500 三个异常页共用一个 `ExceptionPage`（大字错误码 + 人话说明 + 回工作台/返回）；侧栏用户菜单可退出登录（先 `POST /api/auth/logout` 再清 store）。

### 2.2 医生端

**菜单（由后端 `menus` 下发）**：工作台 / 随访计划 / 因果图谱 / 评估报告 / 业务复核 / 受试者档案
**路由角色**：`/doctor`、`/subjects`、`/reviews`、`/followups`、`/graph`、`/reports`（另有 `/tasks` 允许 DOCTOR，但菜单未下发，属"深链可达"）

#### 2.2.1 页面能力表

| 页面 | 路由 | 能做什么 | 关键接口 | 空/错态 |
| --- | --- | --- | --- | --- |
| 医生工作台 | `/doctor` | 「今日待复核」大卡 + 3 个指标卡 + 今日随访空态 | 无（P4 起保留骨架，数据待 dashboard 聚合接口） | 指标显示"待接入" |
| 受试者档案 | `/subjects` | 分页表格、当前页关键词过滤、点击行开抽屉（基础信息 + 风险评估 + 随访 + 历史访视） | `GET /api/subjects`、`GET /api/subjects/{id}`、`GET /api/subjects/{id}/follow-ups` | 骨架屏（首屏）/ 刷新遮罩 / 空态 / 错态分离 |
| 待办复核 | `/reviews` | 输入评估 ID 载入 → 全部/待复核筛选 → 复核抽屉（分数 + 因果图 + 意见） | `GET /api/assessments/{id}`、`POST /api/assessments/{id}/review` | 空态；载入失败由 request 层提示 |
| 随访计划 | `/followups` | 选受试者（100 个可选）→ 看随访表 → 新建/标记完成/取消 | `GET /api/subjects/{id}/follow-ups`、`POST /api/follow-ups`、`PATCH /api/follow-ups/{id}` | 未选人/无随访/加载失败三态 |
| 因果图谱 | `/graph` | 输入评估 ID → 大画布力导向图（可拖拽缩放）→ 沉浸模式 / 重新排布 | `GET /api/assessments/{id}` | 无图时提示"可能被 BLOCKED" |
| 评估报告 | `/reports` | 输入评估 ID（或从复核页跳转）→ 排版化报告 → **打印** | `GET /api/reports/{id}` + `GET /api/subjects/{id}` | 未生成空态 |
| 评估任务 | `/tasks` | 输入任务 ID → 状态/runId/四阶段耗时 → 跳报告/复核 | `GET /api/assessment-tasks/{id}` | 未查询空态 |

#### 2.2.2 核心链路（七步，可照着答辩走）

```
①  查档案            /subjects   点行 → 抽屉打开（同时并发拉 detail + follow-ups）
②  发起评估         抽屉内「风险评估 → 发起风险预测」
                    → POST /api/assessment-tasks（自动生成 Idempotency-Key）
③  轮询             每 2s GET /api/assessment-tasks/{taskId}，最多 3 分钟
                    界面显示计时 + 四阶段（数据融合/因果图谱/预警模型/个体解释）
④  出结果           GET /api/assessments/{id} → 大字 riskScore + riskLevel
                    因果图 canvas 渲染（PRIOR 蓝 / MODEL 青 / MISSING 虚线）
⑤  复核            「确认复核」/「要求补充数据」→ 必填意见 → POST .../review
                    成功后抽屉内状态改为「已确认」，并刷新列表
⑥  看报告          /reports?assessmentId=xx → 报告纸（患者信息/结论/解释/图/局限/免责）
                    「打印报告」→ window.print()，打印样式隐藏侧栏与按钮
⑦  建随访          /followups → 选人 → 新建随访（日期 + 目的）→ 标记完成
```

每一步的**数据来源都是真实接口**，没有一处写死业务数据；页面上的"—/待接入"仅出现在"后端确实没有该接口"的位置（如医生工作台指标）。

#### 2.2.3 抽屉内的三段式结构

`PatientList` 的抽屉（720px）依次是：

1. **基础信息**：状态徽标 + 编号 + 别名 + 数据版本 + 建档时间（两列 descriptions）；
2. **`AssessmentPanel`**：评估状态机 `idle → running → done/failed`；含窗口选择（6–60 月，默认 24）、阶段进度、结果、复核按钮；
3. **随访区块**：新建按钮 + 随访表（时间/目的/状态）；
4. **`VisitHistory`**：访视表（编号/日期/MMSE/MoCA/CDR-SB），缺失值显示"—"。

### 2.3 研究员端

**菜单**：工作台 / 因果图谱 / 数据导入 / 数据质控 / 评估报告 / 受试者档案 / 评估任务

| 页面 | 路由 | 能做什么 |
| --- | --- | --- |
| 研究员工作台 | `/researcher` | **四个入口卡片**：数据导入（`/imports`）、批量评估（`/subjects`）、数据质控（`/quality`）、评估任务（`/tasks`）；整卡可点，支持键盘 Enter/Space |
| 数据导入 | `/imports` | 拖拽 CSV → 预检 → 错误明细 → 确认入库 |
| 数据质控 | `/quality` | 按 PENDING/APPROVED/REJECTED/全部筛选 → 通过 / 驳回（**意见必填**） |
| 受试者档案 | `/subjects` | 与医生同一页面，**多出多选列 + 批量评估**（按 `primaryRole==='RESEARCHER'` 显示） |
| 评估任务 | `/tasks` | 按任务 ID 查阶段耗时 |
| 因果图谱/评估报告 | `/graph` `/reports` | 与医生同一页面 |

#### 2.3.1 数据导入的四个阶段

```
① 拖拽/点击选择   el-upload drag，只收 .csv，前端预检 ≤64KB（后端 max-file-size 限制）
② 上传            POST /api/import-jobs（multipart）+ 请求头 Idempotency-Key = crypto.randomUUID()
                  → 返回 ImportJob
③ 预检面板        总计 X 行 / 可入库 Y 行 / 错误 Z 行 + 任务号 + dataVersion
                  Z>0 时黄色告警「仅 Y 行会被写入，Z 行将被跳过」+ 逐行错误表（行号/字段/说明）
④ 确认入库        POST /api/import-jobs/{jobId}/commit
                  仅 PREVIEW_READY 可点；成功后状态变「已入库」，按钮禁用
```

状态机（后端枚举，前端用 `StatusTag` 映射）：`PREVIEW_READY 预检完成 / BLOCKED 被阻断 / COMMITTED 已入库 / FAILED 失败`。

**防呆细节**：
- 非 `.csv` 或超 64KB 直接拦在前端，不浪费一次往返；
- 每次上传都生成新幂等键，避免"重试命中上一次失败结果"；
- `确认入库` 仅 `PREVIEW_READY` 可点（后端 commit 也只接受该状态，重复提交返回 400）；
- 上传成功后「重新上传」按钮清空面板，可连续导入。

#### 2.3.2 数据质控

- 默认筛 `PENDING`，四档切换（待审/已通过/已驳回/全部）；
- 每行展示：受试者（别名 + 编号）、访视、异常项（后端存的 JSON 字符串原文）、状态、提交时间；
- **通过/驳回** → 弹窗填写审核意见（**必填**，≤500 字）→ `POST /api/qc/{id}/audit`；
- 「待审」筛选下处理完该行自动从列表移除，形成"清空队列"的闭环；
- 非 PENDING 行只显示审核人，不再给按钮（后端对非 PENDING 会返回 400）。

#### 2.3.3 批量评估与进度追踪

- 表格多选（`type="selection"`），工具条实时显示「已选 N 位受试者」；
- 点「批量发起评估」→ `stores/batch.js` **串行**逐个 `POST /api/assessment-tasks`（每个都带独立幂等键）；
- 悬浮进度面板挂在 `AppShell` 上（**切页面不丢失**），显示：`完成数/总数`、进度条、`完成/阻断/失败` 计数、失败明细（受试者 id + 错误码）；
- 轮询间隔 2s，上限 5 分钟；单条创建失败不影响其它受试者；面板可点 × 关闭（运行中关闭只是隐藏，任务继续）。

### 2.4 管理员端

**菜单**：数据大屏（固定首项）/ 审计日志 / 账号管理 / 系统设置（深色 `screen` 布局，无侧栏）

#### 2.4.1 数据大屏 `/admin`

| 区域 | 内容 | 数据来源 |
| --- | --- | --- |
| 指标卡 ×4 | 档案总数、待审质控、质控拦截、审计事件 | `GET /api/subjects`（total）、`GET /api/qc/stats`、`GET /api/audit-logs` |
| 折线图 | 近 7 天建档趋势（按 `createdAt` 分日聚合） | 同上 subjects 前 100 条 |
| 环形图 | 档案状态分布（ACTIVE / ARCHIVED） | 同上 |
| 审计流 | 最近 8 条（时间/事件/摘要/操作人/traceId），深色表格 | `GET /api/audit-logs` |

- **每 60 秒自动刷新**（`setInterval`），`onBeforeUnmount` 清理；
- 三个接口各自 try/catch：某一个挂了只让对应卡片显示"—"并给出原因（"无权限读取质控"/"审计接口不可用"），不整页白屏；
- 底部固定一行**统计口径说明**（前 100 条、60s 刷新），避免把"前 100 条的分布"误读成全量。

#### 2.4.2 审计日志 `/admin/audit`

- 同一 `AuditStream` 组件的亮色版本（大屏是深色版），全量展示、`show-overflow-tooltip`；
- 每次复核/审核成功都会新增一条 `ASSESSMENT_REVIEW`，是"业务操作可追溯"的直接证据；
- 契约里 `data` 是数组（**无分页**），页面底部注明"共 N 条；分页要先改契约"。

#### 2.4.3 账号管理 `/admin/users`

纯骨架：固定表头（用户名/角色/状态/最近登录/操作）+ 禁用按钮 + 说明"契约 22 个操作里没有账号管理接口"。**刻意不放任何假数据**。

#### 2.4.4 系统设置 `/admin/system`

只读信息卡，版本号**不是硬编码**，而是构建期从 `package.json` 与 OpenAPI 契约注入（`vite.config.js` 的 `define`）：

| 卡片 | 内容 | 来源 |
| --- | --- | --- |
| 前端构建 | 版本 0.1.0、构建时间、运行模式、技术栈 | `__APP_VERSION__` / `__BUILD_TIME__` |
| Java 后端 | 连通性（`GET /api/me` 实测延迟）、版本不可读的说明 | 运行时探测 |
| Python 算法 | modelVersion / agent contract / workflowVersion | 契约 `AgentResult` 的 `const` |
| OpenAPI 契约 | `course-srs-v3.2`、22 个操作、20 条路径 | 构建期读契约文件统计 |
| 固定业务规则 | 窗口 6–60、BLOCKED 语义、患者脱敏、审计仅 ADMIN、复核一次性、幂等 | 实现事实 |

### 2.5 患者端

**路由 `/patient`**，`PatientLayout`：无侧栏、单列 720px、字号变量 20px、按钮 64px。

三张大卡：

| 卡片 | 内容 | 数据/降级 |
| --- | --- | --- |
| 风险评估状态 | 30–38px 大字「**您的报告正在等待医生复核**」 | 契约未实现患者摘要端点，此为**固定结论**，不可伪造分数 |
|  | 副文案：已绑定时显示「已绑定档案：合成档案001（DEMO-001）」 | `GET /api/subjects`（patient 仅见 DEMO-001） |
| 下次复诊 | 大字号日期（如「2026年12月15日星期二」）+ 复诊事项 | `GET /api/subjects/{id}/follow-ups` 取最近 `PLANNED` |
| 一键联系医生 | 68px 通栏按钮 → 引导弹窗（含档案信息 + 120 提示） | 契约无联系方式字段，故为引导文案 |

**适老化具体点**：无侧栏（不是隐藏）、20px 基础字号、≥56px 触控目标、结论字号随视口 `clamp(30px,5.2vw,38px)`、正文对比度 ≥7:1、不用 hover-only 交互、`aria-live` 播报状态。

**三种降级**（都有真实证据）：
- 未绑定 → 「您的账号还没有绑定健康档案」+「重新加载」；
- 403 → 「暂时无法查看您的档案，请联系医生确认授权」；
- 网络/服务异常 → 「网络连接异常，请稍后重试」。
无论哪种，**主卡片始终保留"等待医生复核"**，不会出现"无数据"或"0%"。

---

## 3. 关键交互与防呆机制

### 3.1 长耗时任务

| 场景 | 机制 | 参数 | 失败/超时 |
| --- | --- | --- | --- |
| 单次评估 | 前端轮询任务状态 | 每 **2s**，上限 **3 分钟** | 超时提示"任务仍在后端运行，可稍后回档案查看"；`FAILED` 显示 `errorCode + message`；`BLOCKED` 显示"不可用"而非 0 |
| 批量评估 | 串行创建 + 逐条轮询 | 每 **2s**，上限 **5 分钟** | 单条失败不影响其它；失败清单常驻面板 |
| 大屏 | 定时全量刷新 | 每 **60s** | 各接口独立 catch，单卡片降级 |
| 列表刷新 | **不卸载表格**的遮罩 + 请求序号 | — | 旧响应到达时按序号丢弃，避免"旧数据覆盖新数据" |

**为什么不用 WebSocket**：Python 执行是同步的、run store 是易失的，任务本身只有"跑完/失败"两态；轮询的复杂度远低于维护长连接与断线重连，且契约里没有推送通道。

**轮询的生命周期**：`AssessmentPanel` 在抽屉 `destroy-on-close` 时卸载 → `onBeforeUnmount` 清掉 `setTimeout` 与计时器，**关掉抽屉就停止轮询**，不会在后台偷偷打接口。

### 3.2 错误处理矩阵

| HTTP | 前端行为 | 代码位置 |
| --- | --- | --- |
| `401` | 清 Pinia 登录态 → `router.replace('/login?redirect=<当前路径>')` → 提示"登录已过期" | `api/request.js` 的 `redirectToLogin()`（动态 import，避免循环依赖） |
| `401`（登录接口自身） | **特判**：不跳转、不弹全局消息，交给登录卡片内联报错（"账号或密码错误"） | `request.js` 的 `isLoginAttempt` |
| `403` | 只 `ElMessage.error(后端 message)`，不跳转 | 同上 |
| `400` | 弹后端 message（如"该评估已被他人复核，请刷新"） | 同上 |
| `5xx` | 弹 message，页面进入错态（可重试） | 同上 |
| 无响应 | "无法连接服务器，请确认后端已启动（默认 8080）" | `!error.response` 分支 |
| 60s 超时 | 同 5xx 路径（Axios timeout） | `timeout: 60000` |

**空态 ≠ 错态**：每个列表页都分开处理——`EmptyState`（无数据，带引导文案）与"加载失败（带重试）"是两个组件分支，不允许用"暂无数据"掩盖错误。`PatientList` 还额外用 `.patient-list .empty-state` 作用域区分"过渡期上一页的残留空态"。

**表单防呆清单**：

| 表单 | 规则 |
| --- | --- |
| 登录 | 账号/密码非空，Enter 提交，提交中禁用按钮，失败抖动 + 内联提示 |
| 评估 | 窗口 6–60 整数；`modelVersion` 固定常量；`Idempotency-Key` 每次 `crypto.randomUUID()` |
| 复核 | 意见必填、≤2000 字（与契约一致）；已复核的评估隐藏按钮 |
| 随访 | 日期必选、目的必填 ≤1000 字；本地时间转 **ISO-8601 UTC**（后端是 `Instant`，不带时区会 400） |
| 导入 | 仅 `.csv`、≤64KB；确认入库仅 `PREVIEW_READY` 可点 |
| 质控 | 审核意见**必填**（后端 `@NotBlank`，空提交会 400——这是联调抓到的真实缺陷） |

### 3.3 展示层一致性与"不撒谎"原则

1. **Envelope 只在 request.js 拆一次**，业务代码拿到的是 `data`；错误统一 reject 成 `{ code, errorCode, message, traceId }`。
2. **`StatusTag` 是唯一的枚举渲染器**：后端枚举 → 中文 + 色调（`PENDING` 在任务语义是"排队中"，在复核语义是"待复核"，通过 `:label` 覆盖）。
3. **BLOCKED 的三段式表达**：数值处显示「不可用」+ 黄色告警说明"未给出数值" + 界面文案强调"绝不用 0 或插值补分"。
4. **因果图的免责声明**：图下方的浅灰小字（"连线表示条件性关联，不是病因证明"）+ 边的 tooltip 里同样一句；图上不放长句。
5. **没有数据的字段显示"—"或"待接入"**，不显示 0、不编造示例值。

---

## 4. 查错与优化指南

> 完整内容已拆分为独立文档：[docs/Frontend_Optimization_Backlog.md](./Frontend_Optimization_Backlog.md)
> （含 22 条前端优化、10 条契约建议、9 条技术债、11 条排错手册，按 P0–P3 排好序）

**一句话摘要**：真正的产品级缺口只有三个——`GET /api/assessments` 列表、`GET /api/assessment-tasks` 列表、患者摘要端点；
其余都是体验打磨。演示前最值得做的两件小事是"列表刷新改顶部细进度条"和"抽屉改 Tab 化"。

---

## 5. 附录

### A. 文件地图

```
frontend/src/
├─ api/                     # 9 个模块，唯一 axios 实例在 request.js
│  ├─ request.js            # Envelope 脱壳 / 401/403 / 60s 超时
│  ├─ auth.js               # login / logout / fetchCurrentUser
│  ├─ subjects.js           # listSubjects / getSubjectDetail
│  ├─ assessments.js        # createTask / getTask / getAssessment / review
│  ├─ imports.js            # createImportJob(FormData+幂等键) / commit
│  ├─ qc.js                 # list / stats / audit
│  ├─ followups.js          # list / create / update
│  ├─ reports.js            # getReport
│  └─ audit.js              # listAuditLogs
├─ layouts/                 # AppShell + 4 布局 + menuRegistry
├─ components/
│  ├─ common/               # PageHeader / DataCard / StatusTag / EmptyState
│  ├─ charts/               # CausalGraph（力导向）/ EChart（折线+环形）
│  ├─ admin/                # AuditStream（亮/暗双主题）
│  ├─ assessment/           # BatchProgressPanel（全局悬浮）
│  ├─ doctor/               # AssessmentPanel（评估状态机）/ VisitHistory
│  ├─ followup/             # FollowUpDialog
│  ├─ BrainCanvas.vue       # 3D 脑模型（Three.js，生命周期完整）
│  └─ LoginCard.vue         # 身份卡 + 账号表单
├─ views/
│  ├─ doctor/               # DoctorHome / PatientList / ReviewBoard / FollowUpList / ReportList / GraphView
│  ├─ researcher/           # ResearchHome / ImportCenter / QcList / TaskList
│  ├─ admin/                # AdminHome / AuditLog / UserAdmin / SystemSettings
│  ├─ patient/              # PatientHome
│  ├─ exception/            # 401/404/500 + ExceptionPage
│  └─ LoginView.vue
├─ stores/                  # user（roles/menus/permissions，持久化）/ batch（批量评估）
├─ router/index.js          # 20 条路由（含 catch-all）
├─ styles/                  # tokens / element-overrides / login-3d
└─ data/                    # 角色元信息、3D 场景文案
```

### B. 契约 22 操作 ↔ 页面映射

| # | 操作 | 前端位置 |
| --- | --- | --- |
| 1–3 | `POST /auth/login`、`POST /auth/logout`、`GET /me` | `LoginCard` / 侧栏用户菜单 / 系统设置探测 |
| 4–7 | `GET/POST /subjects`、`GET/PATCH /subjects/{id}` | `PatientList`（PATCH 暂无 UI，后端支持） |
| 8 | `POST /subjects/{id}/visits` | 暂无 UI（访视由导入产生） |
| 9 | `POST /subjects/{id}/features` | 暂无 UI |
| 10–12 | `/import-jobs` 三件套 | `ImportCenter` |
| 13 | `POST /subjects/{id}/quality-checks` | 暂无 UI（后端为计算型端点） |
| 14–15 | `/assessment-tasks` | `AssessmentPanel`、`TaskList` |
| 16 | `GET /assessments/{id}` | 抽屉、复核页、图谱页 |
| 17 | `POST /assessments/{id}/review` | `AssessmentPanel`、`ReviewBoard` |
| 18–20 | `/follow-ups` | `FollowUpList`、`FollowUpDialog` |
| 21 | `GET /reports/{id}` | `ReportList` |
| 22 | `GET /audit-logs` | `AdminHome`、`AuditLog` |

> 扩展端点（不在契约内）：`/api/qc/list`、`/api/qc/stats`、`/api/qc/{id}/audit`、`GET /assessments/{id}/reviews`。

### C. 演示账号与种子数据

- 账号：见 §0.2；密码 `DemoPass!2026`（BCrypt 存于库，明文只在测试与 README）。
- **V9 迁移**（本次补的）：给 `doctor` 授权所有 `DEMO-%` 档案的 `VIEW+MANAGE`；给 `patient` 绑定 `DEMO-001` 的 `VIEW+SUMMARY_READ`；插入 2 条 PENDING 质控记录（DEMO-002 / DEMO-003）。
- 数据规模：100 个受试者，每人多次访视；`fixtures/*.synthetic.csv` 提供三类导入样例（subjects / visits / features）。

### D. 命令与 E2E 脚本清单

```bash
cd frontend
npm install
npm run dev            # 5173
npm run build          # 产物 dist/（scripts/build.mjs 会把 TEMP 指到项目内，规避 Windows 上 esbuild 删临时文件失败）
npm run preview
```

| 脚本 | 覆盖 | 依赖 |
| --- | --- | --- |
| `scripts/e2e-roles.mjs` | 四角色落地页 / 布局 / 菜单 / 守卫 | 打桩，不需要后端 |
| `scripts/e2e-p2.mjs` | 档案列表（空态/表格/抽屉）、导入（预检/错误/入库） | 打桩 |
| `scripts/e2e-p3.mjs` | 评估轮询 → 结果 → 因果图 → 复核 | 打桩 |
| `scripts/e2e-p4.mjs` | 大屏指标/图表/审计、患者三态 | 打桩 |
| `scripts/e2e-p5.mjs` | 随访创建/完成、批量评估面板、复核页、账号页 | 打桩 |
| `scripts/e2e-p6.mjs` | 报告页 + 打印、图谱沉浸 | 打桩 |
| `scripts/e2e-list-refresh.mjs` | 刷新竞态与去重回归 | 打桩 |
| `scripts/e2e-live.mjs` | **真实联调**（5173 + 8080 + 8091），医生全链路 | 三端都启动 |

> 打桩脚本都用 `page.setRequestInterception` 拦截 `/api/*`；**只有 `e2e-live.mjs` 会真的写库**（会新增评估/随访/导入记录）。

### E. 10 分钟答辩演示脚本

1. **0:00 登录页**（30s）：四角色卡 + 点右下角胶囊展开 3D 脑模型，说明"3D 只做入口氛围，业务页保持可读性"。
2. **0:30 医生**（4min）：档案 100 条 → 打开 DEMO-001 → 发起评估（讲 2s 轮询、阶段耗时）→ 出 77.0% → 放大因果图（讲 PRIOR/MODEL/缺失 + 免责声明）→ 确认复核（必填意见）→ 打开报告（打印预览）。
3. **4:30 研究员**（3min）：导入一个 CSV（讲 64KB、幂等键、预检/错误明细/确认入库）→ 质控通过一条（讲必填意见）→ 勾选 2 个受试者批量评估（讲全局进度面板、切页不丢）。
4. **7:30 管理员**（1.5min）：大屏四个指标与口径 → 审计里能看到刚才的复核事件 → 系统设置讲"版本来自构建期注入"。
5. **9:00 患者**（1min）：无侧栏、大字卡、一键联系；说明"摘要端点未实现，所以只给结论不给数字，宁可降级也不编造"。

**被问到的常见问题，标准答法**：
- "为什么医生能发起评估？" → 后端策略：ADMIN/RESEARCHER 任意；DOCTOR 仅限有 VIEW/MANAGE 授权的档案（`canRequestAssessment`）；建档仍只允许前两者。
- "为什么患者看不到分数？" → 契约规定患者摘要不含原始分数/图权重，且摘要端点未实现，前端宁可显示"等待复核"。
- "图谱的箭头是因果吗？" → 不是，`direction: CONDITIONAL`，界面有免责声明；真正的个体因果效应属于研究目标，不是当前交付声明。

---

**文档维护约定**：本文与 `frontend/README.md` 分工——README 讲"怎么跑、目录怎么放、当前进度"，本文讲"每个页面能做什么、为什么这么设计、哪里还能更好"。改了页面能力或契约映射时，两处都要同步。
