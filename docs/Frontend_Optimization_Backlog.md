# 前端优化与契约待办（Backlog）

> 从 [《前端功能全景与使用说明书》](./Frontend_Feature_Guide.md) 第 4 章拆出，便于逐条排期与勾选。
> **状态图例**：⬜ 待做 · ✅ 已完成（本轮清理）· 🔵 需后端/契约配合
> **维护约定**：完成一条就把行首改成 ✅ 并注明提交/日期；新增问题按"影响面 × 修复成本"插到对应表。

## 本轮已清理（2026-10-31）

| # | 事项 | 处理方式 |
| --- | --- | --- |
| 1 | 删除未使用的 `v-permission` 指令 | `main.js` 去掉 `app.directive(...)` 与 import，删除 `src/directives/permission.js` 及空目录；`npm run build` 通过 |
| 2 | 重写 `views/researcher/ResearchHome.vue` | 过期拖拽占位区 → 四个入口卡片（`/imports`、`/subjects`、`/quality`、`/tasks`），支持键盘 Enter/Space，样式沿用 tokens |

---

## 0. 总体优先级建议

| 阶段 | 做什么 | 理由 |
| --- | --- | --- |
| 交付前（P0） | 4.2 的 P0 两条：评估列表、任务列表；以及患者摘要端点 | 这三个缺口会直接被评审问"为什么只能手输 ID / 患者为什么看不到结论" |
| 有空就做（P1） | 体验类小改：顶部细进度条、抽屉 Tab 化、图谱导出 PNG、traceId 可复制 | 每条 0.5–2 小时，演示观感提升明显 |
| 工程化（P2） | Vitest 单测、TS/JSDoc、E2E 进 CI | 不影响演示，但影响后续维护成本 |
| 长期（P3） | 虚拟滚动、契约扩展（keyword/审计分页/quality_check 落库） | 数据量与产品复杂度上来后再做 |

---

## 1. 前端可优化点（体验 / 工程 / 性能）



### A. 交互体验

| # | 现状 | 建议 | 成本 |
| --- | --- | --- | --- |
| 1 | 列表刷新是整块半透明遮罩 | 改成**顶部 2px 细进度条**（类似 YouTube），列表保持可读 | 小 |
| 2 | 抽屉里"风险评估 + 随访 + 访视"竖排较长，要滚很久 | 改成 `el-tabs`（评估/随访/访视），或把访视折叠 | 小 |
| 3 | 图谱只能用鼠标拖拽缩放 | 加「导出 PNG」「重置视图」按钮（`chart.getDataURL()`） | 小 |
| 4 | 评估失败只显示 `errorCode + message` | 把 **traceId** 也渲染出来并支持一键复制，方便直接查后端日志 | 小 |
| 5 | 随访日期可随便选（含过去时间） | `disabled-date` 限制不早于今天；常用时间（1 周/1 月/3 月后）快捷按钮 | 小 |
| 6 | 质控审核意见每次手打 | 提供常用语下拉（"数据核实无误"/"需回源核对"） | 小 |
| 7 | 报告只能打印，不能下载 | 加"导出 PDF"（浏览器打印已可用；若要精确排版建议后端出 HTML/PDF） | 中 |
| 8 | 医生的「今日待复核」是静态"—" | 等后端列表接口后接真实数据；现在可先加"去待办复核页"引导 | 小 |
| 9 | 表格列多时横向滚动 | 加列设置（显隐/密度切换），或把"数据版本"折进详情 | 中 |
| 10 | 登录页角色卡用鼠标点 | 支持 ←→ 方向键切换 + 空格选中（`role="radio"`），键盘可完整操作 | 小 |

### B. 工程与质量

| # | 现状 | 建议 |
| --- | --- | --- |
| 11 | 未用 TypeScript，接口返回类型靠注释 | 引入 TS 或至少 JSDoc + `// @ts-check`，把 9 个 api 模块的返回类型固化 |
| 12 | 无单元测试，只有浏览器级 E2E | 加 Vitest 覆盖纯函数（`shortNote`、`formatTime`、`dedupe`、`layoutScaleOf`） |
| 13 | ~~`directives/permission.js` 注册但全项目零使用~~ | ✅ **本轮已删除**：移除 `main.js` 的注册与整个 `directives/` 目录 |
| 14 | E2E 脚本依赖手工装 `puppeteer-core`，未进 CI | 抽成 `npm run e2e`，CI 里跑 headless（或改用 Playwright + 官方浏览器） |
| 15 | `live`/`roles`/`p2`–`p6` 六个脚本有重复的登录辅助函数 | 抽一个 `scripts/lib/session.mjs` 公共库 |
| 16 | 组件 props 校验只覆盖了一部分 | 补齐 `defineProps` 的类型与必填 |
| 17 | `vite.config.js` 的 `define` 常量在 SST 里没有类型声明 | 加 `env.d.ts` 声明 `__APP_VERSION__` 等 |

### C. 性能

| # | 现状 | 建议 |
| --- | --- | --- |
| 18 | 首屏 `index-*.js` ~398 KB（Element Plus 全量 CSS + 部分 JS） | 已是按需 JS；CSS 可再按组件拆（`importStyle: 'css'` + 提升覆盖优先级），预计再省 ~150KB |
| 19 | ECharts 已由 Rollup 拆成共享 chunk（442 KB） | 若只在少数页用，可改为动态 `import()` 让首屏完全不加载 |
| 20 | 3D 登录页 chunk 680 KB + Draco 702 KB | 已懒加载，但 Draco 走 CDN（可能被墙）；可把 `draco` 解码器放 `public/` 本地化 |
| 21 | 表格一次性渲染 20 行 | 数据量上来后上 `el-table-v2` 虚拟滚动 |
| 22 | 图片/字体无预加载 | 首屏关键字体 `preload`，插画 `loading="lazy"` |

## 2. 后端与契约的优先建议

> 这是本次联调里**真正卡住产品闭环**的地方，按优先级排。

| 优先级 | 接口 | 为什么需要 | 现在前端怎么凑合 |
| --- | --- | --- | --- |
| **P0** | `GET /api/assessments?status=&page=&pageSize=` | 「待办复核」「评估报告」都没有列表可拉，只能手输评估 ID | 复核页放一个 ID 输入框 + 前端按 `reviewStatus` 过滤 |
| **P0** | `GET /api/assessment-tasks?page=` | 「评估任务」页同样只能按 ID 查；批量评估后无法回看历史 | 按 ID 查 + 批量面板只活在前端内存里 |
| **P1** | `GET /api/users` + `PATCH /api/users/{id}` + 授权接口 `POST /api/subjects/{id}/permissions` | 账号管理是骨架；医生能看到哪些档案完全依赖 V9 的种子授权，无法在产品里配置 | 账号管理页写死空表 + 说明 |
| **P1** | `GET /api/assessments/{id}` 增加患者分支（实现 `PatientAssessmentSummary`） | 患者端现在永远显示"等待复核"，无法展示真实摘要（403） | 固定文案 + 绑定信息兜底 |
| **P2** | `GET /api/audit-logs?page=&pageSize=` | 审计页全量渲染，数据一多就卡 | 前端截断（大屏只取 8 条） |
| **P2** | `GET /api/dashboard/summary`（今日新增 / 高危 / Python 健康） | 大屏现在用"前 100 条档案"近似统计，口径不严谨；医生工作台的指标也只能空着 | 明示统计口径 |
| **P2** | `GET /api/health/python`（或后端代理 Python `/api/health`） | 系统设置里"Python 版本"只能读契约常量，读不到运行时真实值 | 标注"契约声明值" |
| **P3** | 档案列表加 `keyword` / 评估列表加 `riskLevel` 过滤 | 现在的搜索只过滤当前页，用户容易误解 | 界面写"当前页内搜索" |
| **P3** | `quality_check` 落库 + 把 `/api/qc/*` 纳入契约 | 质控审核是"扩展端点"，契约里没有，联调/验收时容易被质疑 | 前端按扩展端点标注 |
| **P3** | 评估任务补 `error_code/error_message` 持久化 | `FAILED` 任务重查时原因为 `null`，只能看到"失败" | 显示当次响应里的 message |

**一个必须同步的约定**：`/reviews`、`/followups` 的 `meta.roles` 目前放宽到 `['DOCTOR','RESEARCHER']`，与后端"研究员有 `canReadRawScores` / `canView`"一致。若后端将来收紧，前端这两行要同步改。

## 3. 已知技术债清单

| # | 问题 | 影响 | 位置 |
| --- | --- | --- | --- |
| 1 | 评估/复核/任务**均无列表接口** | 只能按 ID 查，产品上像"半成品" | `ReviewBoard.vue` / `TaskList.vue` 顶部注释 |
| 2 | 复核**一次性**（`reviewStatus` 只能从 PENDING 流转） | 改判无入口，重复复核返回 400 | `AssessmentPanel.vue` |
| 3 | 批量评估是 **N 次单点请求** | 50 人 = 50 次 POST + 50 次轮询，网络浪费 | `stores/batch.js` |
| 4 | 因果图边**无权重** | 只能表达"结构 + 证据等级"，不能画粗细 | 契约 `AgentResult.graph.edges` |
| 5 | 患者摘要端点未实现 | 患者端永远"等待复核" | 后端 403 |
| 6 | 大屏统计受 `pageSize ≤ 100` 限制 | 超过 100 条时趋势/分布只是前 100 条 | `AdminHome.vue` 底部已注明 |
| 7 | `v-permission` 指令未使用 | 死代码 | `directives/permission.js` |
| 8 | 演示数据被多轮联调写脏（多次评估/随访/导入） | 截图里会出现重复记录 | 数据库，可用 V9 + 清理 SQL 重置 |
| 9 | ~~`ResearchHome.vue` 过期占位区（拖拽区禁用 + "P2 接入"文案）~~ | ✅ **本轮已重写**：改为「数据导入 / 批量评估 / 数据质控 / 评估任务」四个可点击入口卡片 |

## 4. 排错手册

| 症状 | 最可能原因 | 怎么查 |
| --- | --- | --- |
| 页面全白 + 控制台红字 | Vue 运行时错误（常见：变量 TDZ、模板里用了未定义组件） | 看 `pageerror`；本仓库曾出现 `formatTime` 早于 `timeFormatter` 初始化 |
| 一直跳登录页 | 会话失效 / 未带 Cookie | DevTools → Application → Cookies 看 `JSESSIONID`；`request.js` 的 `withCredentials: true` 是否被改 |
| 接口 403 | `meta.roles` 与后端策略不一致，或 `subject_permission` 无数据 | 直接 `curl` 同一接口对比；查 V9 是否 applied |
| 接口 400 `MALFORMED_JSON` | 用 PowerShell `curl -d` 引号被吞 | 用文件传参：`--data-binary "@body.json"` |
| 评估一直 `FAILED` | Python 8091 未启动 / 版本不匹配 | 看任务 `errorCode`（`AGENT_UNAVAILABLE` / `AGENT_TIMEOUT` / `AGENT_VALIDATION`）；`curl :8091/api/health` |
| 上传 400 | CSV 表头必须**精确匹配** `fixtures/*.synthetic.csv` | 看预检错误明细的 `field/message`（如 `visitCode: 该档案下已存在`） |
| 因果图空白 | 容器高度 0，或 `echarts.use([...])` 漏注册 GraphChart | 检查 `.causal-graph__canvas` 是否有高度 |
| 图表挤压/越界 | 画布高度与力参数不匹配 | `CausalGraph` 的 `layoutScaleOf(height)` 会按 380px 基准缩放 |
| 表格出现重影/重复行 | 历史上"刷新时卸载表格"导致重绘残影 | 已修：改用遮罩 + 请求序号；若复现先看是否有重复 `el-table` 实例 |
| 登录页样式串到业务页 | `login-3d.css` 被全局引入 | 确认只在 `LoginView.vue` 里 `import '@/styles/login-3d.css'` |
| 构建报 `Cannot read properties of undefined (reading 'type')` | 模板里 `<template #footer>` 嵌在 `v-else` 内 | 具名插槽必须是组件直接子级，配 `v-if` 而不是嵌在 `v-else` 里 |

---

---

**返回**：[《前端功能全景与使用说明书》](./Frontend_Feature_Guide.md)
