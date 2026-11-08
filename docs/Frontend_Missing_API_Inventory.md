# 前端功能完备性 —— 缺失接口清单

> **扫描范围**：`frontend/src/views`（20 个 `.vue`）+ `frontend/src/components`（13 个 `.vue`），共 33 个组件；对照 `contracts/java-business.target.openapi.json`（22 个操作）与 `backend` 全部 Controller（含 6 个契约外的扩展端点）。
> **扫描方式**：逐个文件提取 API 调用点、本地 `filter()`、写死的 `pageSize`、手输 ID 入口，并与后端 `@RequestParam` 实际支持的参数比对。
> **结论**：前端有 **16 处**功能是靠"手输 ID / 只在当前页过滤 / 拉前 100 条自己算 / 写死文案"撑起来的。其中 **3 处属于阻断业务闭环（P0）**，**8 处体验极差（P1）**，**5 处可优化（P2）**。

---

## 目录

- [一、现状总览：前端是怎么"凑合"的](#一现状总览前端是怎么凑合的)
- [二、P0：阻断业务闭环（3 条）](#二p0阻断业务闭环3-条)
- [三、P1：体验极差（8 条）](#三p1体验极差8-条)
- [四、P2：可优化（5 条）](#四p2可优化5-条)
- [五、反向清单：后端已有、前端没接（3 条）](#五反向清单后端已有前端没接3-条)
- [六、接口设计约定（新增接口请遵守）](#六接口设计约定新增接口请遵守)
- [七、建议实施顺序](#七建议实施顺序)
- [八、影响面矩阵](#八影响面矩阵)

---

## 一、现状总览：前端是怎么"凑合"的

| 页面 | 依赖接口 | 现状（凑合方式） | 证据 |
| --- | --- | --- | --- |
| 待办复核 `/reviews` | `GET /assessments/{id}` | **手输评估 ID**，一次只能载入一条；"全部/待复核"在前端 `filter` | `ReviewBoard.vue:22` 页面自述、`:151` 前端过滤 |
| 评估任务 `/tasks` | `GET /assessment-tasks/{id}` | **手输任务 ID**，无历史列表 | `TaskList.vue:40` |
| 评估报告 `/reports` | `GET /reports/{id}` | **手输评估 ID**（或从别的页带 `?assessmentId=` 跳过来） | `ReportList.vue:30` |
| 因果图谱 `/graph` | `GET /assessments/{id}` | **手输评估 ID** | `GraphView.vue:32` |
| 受试者档案 `/subjects` | `GET /subjects?page&pageSize` | 关键词**只过滤当前 20 行**；分页每行都重新请求 | `PatientList.vue:248-260` 注释明确写了这点 |
| 随访计划 `/followups` | `GET /subjects?pageSize=100` + `GET /subjects/{id}/follow-ups` | 受试者下拉**最多 100 人**（后端 pageSize 上限 100），超出的人选不到 | `FollowUpList.vue:149` |
| 医生工作台 `/doctor` | 无 | 指标全是 `null` → 渲染成"**待接入**" | `DoctorHome.vue:15,36,67` |
| 数据大屏 `/admin` | `GET /subjects?pageSize=100` + `/qc/stats` + `/audit-logs` | 拉**前 100 条**，趋势/状态分布在前端 `filter` + 分日聚合 | `AdminHome.vue:72,177-178,203` |
| 审计日志 `/admin/audit` | `GET /audit-logs` | **全量**返回，无分页、无筛选 | `AuditLog.vue` |
| 数据导入 `/imports` | 上传 + 预检 + commit | **无历史任务**，刷新即丢；只能看到当前这一次 | `ImportCenter.vue`（无 list 调用） |
| 账号管理 `/admin/users` | 无 | 纯骨架，表头写死 | `UserAdmin.vue` |
| 系统设置 `/admin/system` | 仅 `GET /me`（连通性探测） | 版本/契约信息是**构建期常量**，非运行时数据 | `SystemSettings.vue` |
| 患者端 `/patient` | `GET /subjects?pageSize=1` + `/subjects/{id}/follow-ups` | 用"列表取第一条"当"我的档案"；结论**写死**"您的报告正在等待医生复核" | `PatientHome.vue:79,53` |

---

## 二、P0：阻断业务闭环（3 条）

> **状态（2026-11-07）**：P0-1 / P0-2 / P0-3 已实现，`feat/missing-p0-endpoints` 分支。
> 实际落地与下方"建议接口"的差异：列表只支持 `page` / `pageSize`（未做 `reviewStatus` 等服务端筛选，
> 页面明确标注"筛选仅作用于当前页"）；患者摘要走独立的 `GET /api/patients/me/summary`，
> 返回比 `PatientAssessmentSummary` 多出 `subjectCode/alias/hasAssessment/pendingReview`，
> 以便区分"未绑定 / 已绑定但无评估"。原始分数可见性规则与详情接口一致：PATIENT 与
> `SUMMARY_READ`-only 医生在列表、任务历史、任务详情上都拿不到 `riskScore` / `runId`。

### P0-1 评估结果列表

**现象**
`/reviews` 页面上写着一行字（`ReviewBoard.vue:22`）：

> "契约里只有 POST /api/assessment-tasks、GET /api/assessment-tasks/{id}、GET /api/assessments/{id}。所以这里按评估 ID 载入后在前端按 reviewStatus 过滤。"

也就是说：医生**必须事先知道评估 ID** 才能复核。评估 ID 从哪来？只能从档案抽屉里点进去看，或者从任务页跳。真实场景（"今天有 12 份待复核"）根本无法呈现；「全部/待复核」这个筛选器是在前端对**已手动载入的几条**做过滤，数据量永远是 0 或 1 起步。

**建议接口**

```http
GET /api/assessments?page=1&pageSize=20&reviewStatus=PENDING&subjectId=&riskLevel=&from=&to=&sort=createdAt,desc
```

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `page` / `pageSize` | int | 1 起，`pageSize ≤ 100`（与 subjects 一致） |
| `reviewStatus` | enum | `PENDING / ACKNOWLEDGED / NEEDS_DATA / REJECTED`，可省 |
| `subjectId` | long | 指定受试者，可省 |
| `riskLevel` | enum | `LOW / MEDIUM / HIGH / UNAVAILABLE`，可省 |
| `from` / `to` | ISO date-time | 按 `createdAt` 过滤，可省 |
| `sort` | string | 默认 `createdAt,desc` |

响应（`PageResult<AssessmentSummary>`，**不含** `agentResult`，避免列表拖几百 KB 的图）：

```json
{
  "code": 0, "message": "成功", "errorCode": null, "traceId": "…", "timestamp": "…",
  "data": {
    "items": [{
      "assessmentId": 19,
      "subjectId": 1,
      "subjectCode": "DEMO-001",
      "subjectAlias": "合成档案001",
      "taskId": 21,
      "riskScore": 0.77,
      "riskLevel": "HIGH",
      "qualityStatus": "WARN",
      "reviewStatus": "PENDING",
      "createdAt": "2026-10-30T17:18:53Z"
    }],
    "page": 1, "pageSize": 20, "total": 12
  }
}
```

**权限**：DOCTOR 看自己授权档案的结果；RESEARCHER / ADMIN 全量（与现有 `canReadRawScores` / `hasFullVisibility` 一致）。
**前端改造点**：`ReviewBoard.vue` 把 `loadById()` 换成 `listAssessments()`；`ReportList.vue` 增加"最近报告"列表（复用同一接口）。
**验收**：医生登录后进入 `/reviews` 直接看到 12 条待复核，无需输入任何 ID。

---

### P0-2 评估任务列表

**现象**
`/tasks` 页面自述（`TaskList.vue:40`）："按任务 ID 查询；批量评估的进度请用「受试者档案」页右上角的进度面板。"

批量评估 20 个人后，只要刷新页面或关掉面板，**这 20 个任务就再也找不到了**——除非你记得每个 taskId。这是"数据进去了、结果拿不出来"的典型断链。

**建议接口**

```http
GET /api/assessment-tasks?page=1&pageSize=20&status=&subjectId=&from=&to=
```

| 参数 | 说明 |
| --- | --- |
| `status` | `PENDING/RUNNING/COMPLETED/BLOCKED/FAILED/CANCELLED` |
| `subjectId` | 可选 |
| `from` / `to` | 按 `createdAt` |

响应 `PageResult<AssessmentTask>`（结构完全复用现有 `AssessmentTask`，含 V10 起持久化的 `errorCode/message`）。

**前端改造点**：`TaskList.vue` 顶部加"最近的评估任务"表（含状态筛选、失败原因列），把 ID 输入框降级为"按 ID 精确查找"的次要入口；`BatchProgressPanel` 增加"查看历史任务"链接。
**验收**：刷新后仍能看到最近 20 次评估任务及其失败原因。

---

### P0-3 患者个人摘要（契约已声明 schema，后端未实现）

**现象**
`PatientHome.vue:53` 的注释写得很直白：

> "契约里没有已实现的患者摘要端点（GET /api/assessments/{id} 对 PATIENT 返回 403），因此状态卡固定展示'等待医生复核'。"

于是患者永远看到同一句话，**不管他有没有评估、评估结果是什么**。这是"功能存在但永远说一句话"的假页面。

**关键事实**：契约里 `GET /api/assessments/{assessmentId}` 的 200 响应已经是 `oneOf: [Assessment, PatientAssessmentSummary]`，`PatientAssessmentSummary` 的 schema 也定义好了（`summary` / `followUpSummary` / `limitations`）。缺的不是 schema，而是**后端没走这个分支**。

**建议接口（二选一）**

方案 A（最小改动，复用现有路径）：

```http
GET /api/assessments/{assessmentId}
Cookie: JSESSIONID=…
→ PATIENT 且能读自己的档案时，返回 PatientAssessmentSummary 而不是 403
```

```json
{ "code": 0, "data": {
    "assessmentId": 19,
    "summary": "目前风险偏高，建议 3 个月后复诊并关注记忆力变化。",
    "followUpSummary": "已为您安排 2026-12-15 复诊。",
    "limitations": ["结论基于既往数据，不能替代医生面诊"]
}}
```

方案 B（更贴合患者心智，推荐）：

```http
GET /api/patients/me/summary
→ {
    "subject": { "subjectCode": "DEMO-001", "alias": "合成档案001" },
    "latestAssessment": PatientAssessmentSummary | null,
    "nextFollowUp": { "scheduledAt": "…", "purpose": "…" } | null,
    "pendingReview": true
  }
```

**约束**（AGENTS.md 硬要求）：患者响应**不得**包含 `riskScore` 原值、`graph` 权重、`explanations.contribution` 或任何内部追踪字段。
**前端改造点**：`PatientHome.vue` 状态卡改为渲染 `latestAssessment.summary`；`pendingReview=true` 时才显示"等待复核"。
**验收**：患者登录看到的是"结论句 + 复诊时间"，而不是固定文案。

---

## 三、P1：体验极差（8 条）

### P1-1 受试者列表的关键词搜索

**现象**：`GET /api/subjects` 只有 `page` / `pageSize`。`PatientList.vue:252-260` 的 `filteredItems` 只过滤**当前页**，界面文案也承认了这一点。副作用很严重：`FollowUpList` 用 `pageSize: 100` 取全量下拉，**超过 100 个受试者就选不到人**（后端硬上限 100）。

**建议接口**：`GET /api/subjects?keyword=张&page=1&pageSize=20&status=ACTIVE`
- `keyword`：对 `subjectCode` / `alias` 做 `LIKE %kw%`（建议后端转义 `%` `_`），大小写不敏感；
- 响应结构不变（`PageSubject`），只是 `total` 变成过滤后的总数。

**前端改造点**：输入框加 300ms 防抖 → 带 `keyword` 请求；`FollowUpList` 的下拉改为**远程搜索**（`el-select filterable remote`），顺带解决 100 人上限。
**验收**：库里 100 条时搜 `DEMO-050` 能命中；101 条时仍能选到第 101 个人。

---

### P1-2 审计日志分页与筛选

**现象**：`GET /api/audit-logs` 无参数、全量返回。`AuditLog.vue` 直接 `listAuditLogs()` 一次性渲染，`AuditStream.vue` 再截取 8 条给大屏用。事件一多（每次复核 +1 条）页面会越来越慢。

**建议接口**：

```http
GET /api/audit-logs?page=1&pageSize=20&eventType=ASSESSMENT_REVIEW&actorId=4&from=&to=
→ PageResult<AuditEvent>
```

**前端改造点**：`AuditLog.vue` 换 `el-pagination` + 事件类型下拉；`AdminHome` 用 `pageSize=8` 只取首页（不再全量拉）。
**兼容性提醒**：这是契约变更（现在 `data` 是数组），建议**新增** `GET /api/audit-logs/page` 或加 `page` 参数时保持"无参数=旧行为"的过渡期，避免前端大屏直接崩。

---

### P1-3 数据大屏聚合统计

**现象**：`AdminHome.vue:72` `const PAGE_SIZE = 100`，`:203` 拉前 100 条档案，`:177-178` 用 `filter` 数 ACTIVE/ARCHIVED，`:125-129` 在前端按日期分桶算趋势。页面底部自己也写了"统计口径：前 100 条"。

真实数据超过 100 条后，卡片上的"档案总数"是 100 而不是真实总数（因为用的是 `items.length` 还是 `total`，取决于实现——当前用的是 `total`，但**趋势与状态分布**仍是前 100 条的切片）。

**建议接口**：

```http
GET /api/dashboard/summary?range=30d
→ {
    "subjects": { "total": 128, "active": 120, "archived": 8, "newInRange": 12 },
    "assessments": { "total": 64, "pendingReview": 9, "highRisk": 14, "blocked": 3 },
    "quality": { "pending": 2, "approved": 31, "rejected": 4 },
    "trend": [{ "date": "2026-10-01", "created": 3 }, …],
    "recentAudit": [ AuditEvent × 8 ]
  }
```

一次请求替代现在 3 个请求 + 前端聚合；`recentAudit` 内联后大屏只剩 1 次轮询。
**前端改造点**：`AdminHome.vue` 的 `loadAll()` 简化为单请求；`PAGE_SIZE` / 分日聚合 / `filter` 全部删除。
**验收**：库里 500 条档案时，大屏数字与 SQL `count(*)` 一致。

---

### P1-4 导入任务历史

**现象**：`ImportCenter.vue` 只有"当前这一次"上传/预检/入库的面板，`ImportJob` 接口只有 `POST` 和 `GET /{jobId}`。刷新页面后，上一次导入的记录（任务号、行数、错误明细）全部消失。

**建议接口**：`GET /api/import-jobs?page=1&pageSize=20&status=COMMITTED&from=&to=`
→ `PageResult<ImportJob>`（结构不变）。

**前端改造点**：`/imports` 下半屏加"最近导入"表，点击某行回填预检面板。

---

### P1-5 报告列表

**现象**：`/reports` 只能手输评估 ID。复核完的评估想打印报告，得记住 ID 或从复核页跳。

**建议接口**：两个选择——
- 复用 P0-1 的 `GET /api/assessments`（加 `hasReport=true` 过滤），前端用同一份列表；
- 或独立 `GET /api/reports?page&pageSize&subjectId` → `PageResult<{ assessmentId, subjectId, riskLevel, createdAt }>`。

**推荐**：复用 P0-1，避免为同一份数据再造一个端点。

---

### P1-6 跨受试者的随访待办

**现象**：随访只能"先选一个受试者，再看这个人的随访"（`GET /api/subjects/{id}/follow-ups`）。医生的真实问题是"**今天/本周我要随访哪些人**"，现在无法回答；`DoctorHome` 的"今日随访"卡片也只能是空的。

**建议接口**：

```http
GET /api/follow-ups?from=2026-11-05&to=2026-11-12&status=PLANNED&subjectId=&page=1&pageSize=50
→ PageResult<FollowUpView>   // FollowUpResult + subjectCode + subjectAlias + overdue:boolean
```

**前端改造点**：`DoctorHome` 的"今日随访"与 `/followups` 顶部都换成这个接口；原来的"按受试者查"保留为档案抽屉内的局部视图。

---

### P1-7 单个受试者的随访列表分页

**现象**：`GET /api/subjects/{id}/follow-ups` 返回**全量数组**，前端自己排序。长期随访的人（每 3 个月一次 × 5 年）会有 20+ 条，暂时不致命，但没有 `status` 过滤，患者端要自己 `filter` 出 `PLANNED`。

**建议接口**：给现有路径加分页与状态：
`GET /api/subjects/{id}/follow-ups?status=PLANNED&page=1&pageSize=20&order=scheduledAt,asc`
（为兼容，不传分页时仍返回数组；或统一返回 `PageResult` 并同步改前端——**建议一次性统一为 PageResult**，越晚改成本越高。）

---

### P1-8 用户与档案授权管理

**现象**：`/admin/users` 是纯骨架。更严重的是**授权无法配置**：医生能看到哪些档案完全由 `V9` 迁移里的种子数据决定，产品内没有任何入口给新医生授权（这是之前医生端"0 条档案"的根因）。

**建议接口**：

```http
GET   /api/users?page=1&pageSize=20&role=DOCTOR&keyword=
PATCH /api/users/{userId}            // { status: ACTIVE|DISABLED }
GET   /api/subjects/{subjectId}/permissions
POST  /api/subjects/{subjectId}/permissions   // { userId, permissionType: VIEW|MANAGE|SUMMARY_READ }
DELETE /api/subjects/{subjectId}/permissions/{id}
```

**约束**：仅 ADMIN；授权变更必须写审计事件（`SUBJECT_PERMISSION_GRANTED`）。
**前端改造点**：`UserAdmin.vue` 接列表 + 启用/禁用；档案抽屉加"授权"按钮。

---

## 四、P2：可优化（5 条）

### P2-1 报告 PDF 导出

**现象**：`ReportList.vue` 用 `window.print()`，打印样式已经调好，但无法直接下载文件；不同浏览器打印结果不一致。
**建议接口**：`GET /api/reports/{assessmentId}?format=pdf`（`Content-Type: application/pdf`），后端用模板渲染，保证与屏上一致；或 `POST /api/reports/{id}/render` 返回文件 ID。
**注意**：PDF 生成需要中文字体与图表渲染，建议服务端用 HTML→PDF，并把免责声明一并排版。

### P2-2 患者端"联系医生"的真实联系方式

**现象**：`PatientHome.vue` 的弹窗只能写"请联系您的主治医生"，因为没有联系方式字段。
**建议接口**：`GET /api/patients/me/doctor` → `{ name, department, phone, officeHours }`（**仅暴露必要字段**，不要带医生账号 ID）。
**前端**：弹窗直接展示电话（大字号、可点击拨号 `tel:`）。

### P2-3 审计事件详情（前后值）

**现象**：列表只有 `summary` 一句话，无法回答"他把复核改成了什么"。
**建议**：`GET /api/audit-logs/{id}` → `{ …, payload: { before: {...}, after: {...} } }`；或列表直接带 `payload`（注意脱敏）。
**前端**：行展开显示 diff。

### P2-4 系统信息接口

**现象**：`/admin/system` 里的"Java 后端版本""Python 版本"来自**构建期常量**，只有"在线/延迟"是真实探测。评审若问"后端到底跑的是哪个版本"，页面给不出运行时答案。
**建议接口**：`GET /api/system/info` → `{ appVersion, gitCommit, javaVersion, contractVersion, python: { version, health, latencyMs } }`（仅 ADMIN）。
**前端**：替换 `SystemSettings.vue` 里的 `__CONTRACT_VERSION__` 等常量。

### P2-5 图谱版本对比（研究向）

**现象**：图谱页只能看单次评估的图；研究场景常问"同一患者两次评估的节点/边有何变化"。
**建议接口**：`GET /api/subjects/{id}/graph-diff?from={assessmentId}&to={assessmentId}` → `{ addedNodes, removedNodes, changedEdges }`。
**优先级理由**：属于研究增值功能，不阻塞临床闭环。

---

## 五、反向清单：后端已有、前端没接（3 条）

这三条**不需要后端写代码**，是前端欠的债，列出来避免重复开发。

| # | 已有能力 | 现状 | 建议 |
| --- | --- | --- | --- |
| R-1 | `GET /api/assessments/{assessmentId}/reviews`（复核历史） | 前端从未调用（全项目 grep 无此调用） | 复核抽屉加"复核记录"时间线，直接展示谁在何时改判/要求补数据 |
| R-2 | `GET /api/qc/list` 支持 `subjectCode` / `subjectAlias` | 前端只传 `status/page/pageSize` | 质控页加搜索框（**后端已就绪，纯前端改动**） |
| R-3 | `PATCH /api/subjects/{subjectId}` | 无编辑入口 | 档案抽屉加"编辑别名/状态"（注意：修改需审计） |

---

## 六、接口设计约定（新增接口请遵守）

1. **统一 Envelope**：`{ code, message, data, errorCode, traceId, timestamp }`，成功 `code=0`。列表接口的 `data` 用 `PageResult<T> { items, page, pageSize, total }`（与 `/subjects` 一致），**不要**再出现"直接返回数组"的端点（`follow-ups`、`audit-logs` 已是遗留形态）。
2. **分页上限**：`page ≥ 1`，`pageSize ∈ [1,100]`，超界**钳制**而不是 400（与现有 `SubjectService` 一致）。
3. **排序**：默认 `createdAt,desc`；允许 `sort=field,asc|desc`，字段白名单校验。
4. **列表不返回大字段**：`AssessmentSummary` 不带 `agentResult`；需要时再单独 `GET /assessments/{id}`。
5. **权限**：对象级授权在 Java 内判定（AGENTS.md 要求），DOCTOR 只看授权档案；PATIENT 只读自己，且**禁止**返回原始分数/图权重。
6. **审计**：任何写操作（复核、审核、授权、编辑）都要落 `audit_event`，`eventType` 用稳定常量。
7. **幂等**：创建类接口沿用 `Idempotency-Key`（现有 `POST /assessment-tasks`、`POST /import-jobs` 已实现）。
8. **契约同步**：新增/变更必须更新 `contracts/java-business.target.openapi.json` 与 `docs/Integration_Contract.md`，并补契约测试（现有 `EnvelopeContractTest` / `ContractSchemas`）。

---

## 七、建议实施顺序

### 迭代 1（打通闭环，约 3 个接口）
1. `GET /api/assessments`（P0-1）——同时解决"待办复核"和"报告列表"
2. `GET /api/assessment-tasks`（P0-2）——批量评估结果可追溯
3. `GET /api/patients/me/summary`（P0-3）——患者端不再说假话

> 做完这三个，前端有四五个页面的"手输 ID"提示可以整段删掉。

### 迭代 2（补齐体验，约 4 个接口）
4. `GET /api/subjects?keyword=`（P1-1）——顺带修掉随访下拉 100 人上限
5. `GET /api/audit-logs?page=`（P1-2）
6. `GET /api/dashboard/summary`（P1-3）——大屏不必再"前 100 条自己算"
7. `GET /api/follow-ups?from&to&status`（P1-6）——医生"今日随访"才有数据

### 迭代 3（管理与研究，约 3 组接口）
8. 用户/授权（P1-8）——消除"医生看不到档案"这类环境性故障
9. 导入历史（P1-4）+ 随访分页（P1-7）
10. PDF 导出 / 联系方式 / 系统信息 / 图谱对比（P2）

---

## 八、影响面矩阵

| 缺失接口 | 受影响页面 / 组件 | 不做的后果 |
| --- | --- | --- |
| P0-1 评估列表 | `ReviewBoard`、`ReportList`、`DoctorHome`（待复核数）、`AssessmentPanel`（历史） | 复核流程必须靠记忆 ID；"待办复核"名不副实 |
| P0-2 任务列表 | `TaskList`、`BatchProgressPanel`（历史）、`DoctorHome` | 批量评估后结果不可回溯 |
| P0-3 患者摘要 | `PatientHome` | 患者端永远一句话，等于没有功能 |
| P1-1 keyword | `PatientList`、`FollowUpList`（下拉）、`ImportCenter`（关联档案） | 100 条以上档案无法精确查找；随访选不到第 101 人 |
| P1-2 审计分页 | `AuditLog`、`AdminHome`（审计流） | 事件累积后页面变慢，无法按人/类型追查 |
| P1-3 大屏聚合 | `AdminHome` | 指标只统计前 100 条，口径会被质疑 |
| P1-4 导入历史 | `ImportCenter` | 刷新即丢，无法解释"昨天导了什么" |
| P1-6 随访待办 | `DoctorHome`、`FollowUpList` | "今日随访"永远空 |
| P1-8 用户授权 | `UserAdmin`、整个医生端可见性 | 新医生只能靠改数据库授权 |
| P2-5 图谱对比 | `GraphView` | 研究场景缺少前后对比视角 |

---

## 附：本次扫描的判定依据（可复核）

```bash
# 前端所有 API 调用点
grep -rn "await list\|await get\|await create\|await update\|await submit\|await commit" frontend/src/views frontend/src/components

# 手输 ID 入口
grep -rn "输入评估 ID\|输入任务 ID\|inputId" frontend/src/views

# 前端本地过滤（假装搜索/统计）
grep -rn "\.filter(" frontend/src/views frontend/src/components

# 写死的取样上限
grep -rn "pageSize: 100\|PAGE_SIZE" frontend/src

# 契约操作清单
node -e "const c=require('./contracts/java-business.target.openapi.json');console.log(Object.keys(c.paths).length)"
```

---

**结论一句话**：前端已经把"没有接口"的地方都做成了**可解释的降级**（提示、空态、口径说明），但这些降级叠加起来，会让系统在评审眼里像"能点但不好用"。补上迭代 1 的 3 个接口，体验会有质变；补完迭代 1+2，才算"100% 体验完善"。
