# 前端产品化优化方案

日期：2026-11-14
范围：frontend 为主，含必要的最小后端契约补充（单独标注）
基线：`d6f474c`（feat/backend-integration），后端 62 个 API，Python 七变量 LR 研究链路已通

## 0. 结论

当前前端已经不是“再补几个字段”的阶段。四个痛点背后是三块结构性缺口：

1. **患者主索引缺失**：`subject` 是档案，不是患者；一个患者的多份档案没有归属关系，前端无法真正合并。
2. **系统控制面缺失**：后端只有系统信息、模型开关、个人偏好三类接口，没有研究宿主、评估通道、保留策略、安全策略等写接口。
3. **角色化工作流缺失**：账号创建是一张通用表单；批量研究评估没有队列与抽屉；管理员首页仍是数据大屏而不是控制台。

建议按四期推进：

| 期 | 内容 | 依赖 | 预计 |
| --- | --- | --- | --- |
| P0 | 角色化开户向导 + 批量研究评估抽屉 | 纯前端 | 3–4 天 |
| P1 | 系统控制台（现有能力 + 3 个配置接口） | 后端小改 | 3–4 天 |
| P2 | 患者主索引 + 跨时段档案合并 + 患者中心 | 后端 V14 | 5–7 天 |
| P3 | 视觉与交互打磨、性能、E2E、文档 | 无 | 2–3 天 |

设计北极星：**一名患者、一条时间线、一个控制台**。不换肤，保留现有蓝白临床风格与设计变量，只重做信息架构、页面骨架和关键组件。

## 1. 现状核查（带证据）

### 1.1 账号创建

- `frontend/src/views/admin/UserAdmin.vue`：新增/编辑共用同一个 560px 通用表单，字段为 username/password/roles（多选）/status/displayName/department/phone/officeHours/dataScope；所有角色看到同一张表单。
- 后端 `UserManagementService.save` 已支持上述字段，PATIENT 必须单独角色，新账号强制 `ASSIGNED`。
- 患者绑定不在创建流程里：需要另到“档案授权”页给患者 `SUMMARY_READ`，再给医生 `VIEW/MANAGE`；没有原子端点，失败会留下半成品账号。
- `/api/patients/me/doctor` 的医生联系方式，来自对该档案有 `VIEW/MANAGE` 且 `display_name` 非空的 DOCTOR 账号。因此“患者绑定医生”本质是“给医生授档案权限”，不是独立关系字段。

### 1.2 系统设置

- `frontend/src/views/admin/SystemSettings.vue`：前端构建信息、Java 探测、Python 版本、OpenAPI 统计、模型开关、固定规则说明。
- 后端 `SystemController` 只有：
  - `GET /api/system/info`
  - `GET/PATCH /api/me/preferences`
  - `PATCH /api/system/models/{version}`
- 没有研究宿主配置、评估通道开关、审计/报告保留、会话与密码策略、演示数据重置等写接口。当前页面只能“看”，不能“控”。

### 1.3 患者档案

- `subject` 表字段仅 `id/subject_code/alias/status/data_version/created_at`，没有 `patient_id` 或患者主键。
- `frontend/src/views/doctor/PatientList.vue` 一行一个 subject；抽屉 4 个 tab：
  - 概览与操作
  - 全量报告库
  - 生命周期（患者 360 时间线，但只聚合当前 subject 的 SUBJECT/VISIT/ASSESSMENT/REVIEW/FOLLOWUP）
  - 就诊与随访记录
- 没有跨 subject 的分组、合并、拆分、对比；报告库只是同一档案的多次评估。
- 患者账号通过 `subject_permission.SUMMARY_READ` 绑定单个档案；合并后无法天然看到多档案汇总。

### 1.4 批量研究评估

- 研究评估入口是单档案：`ResearchAssessmentPanel` 选择 subject → snapshots → POST/GET。
- 旧批量评估链路（`stores/batch.js`、`BatchProgressPanel.vue`）已在上一轮删除；`TaskList.vue` 目前只有单档案“研究模型评估”弹窗。
- 后端没有 batch 端点，但可以前端按 subject 扇出；snapshots 与 idempotency 已具备，适合做客户端队列。

> 说明：模型与研究链路已具备；以上缺口是数据模型、控制面与工作流，不是模型能力问题。

## 2. 目标信息架构

### 2.1 角色工作台

| 角色 | 首页 | 核心页面 | 首屏主角 / 主动作 |
| --- | --- | --- | --- |
| ADMIN | 系统控制台 | 账号与授权、系统控制、审计、质控、模型 | 系统健康 + 待处理控制项 / 进入控制 |
| RESEARCHER | 研究工作台 | 患者中心、档案、导入、质控、批量评估、任务、证据 | 队列与任务状态 / 发起批量研究评估 |
| DOCTOR | 临床工作台 | 我的患者、患者 360、复核、随访、报告、图谱 | 今日待办与高风险患者 / 查看患者 |
| PATIENT | 我的健康 | 摘要、时间线、医生联系方式、随访 | 当前风险结论 / 联系医生 |

### 2.2 路由调整（保留旧路径兼容）

新增：

- `/patients`：患者目录（DOCTOR/RESEARCHER/ADMIN）
- `/patients/:patientId(\\d+)`：患者 360
- `/admin/console`：系统控制台（可复用 `/admin` 或新增，建议 `/admin` 直接升级）

保留：

- `/subjects`：档案视角，面向数据操作；在页面内增加“按患者 / 按档案”切换。
- `/tasks`：任务历史 + 批量评估入口。
- `/admin/system`：系统控制台（页面升级，路径不变）。

### 2.3 导航

菜单仍由后端 `MenuCatalog` 下发，前端只做映射。新增菜单项需要后端菜单表补数据；在未补之前，先用页面内入口与路由守卫兜底，不硬编码出后端不存在的权限。

## 3. 四项专项设计

### 3.1 角色化开户向导

**目标**：管理员选角色 → 只填该角色需要的字段 → 患者一步完成“账号 + 档案 + 主治医生”绑定，并能预览患者将看到的医生联系方式。

**交互**：

1. 第一步选账号类型（卡片）：管理员 / 医生 / 研究员 / 患者。
2. 第二步动态字段：
   - 管理员：用户名、姓名、初始密码、状态；数据范围固定 ALL。
   - 医生：用户名、姓名、科室、联系电话、出诊时间、数据范围；可勾选负责档案（VIEW+MANAGE）。
   - 研究员：用户名、姓名、科室/团队、联系电话、数据范围；可勾选授权档案。
   - 患者：用户名、姓名、患者联系电话、绑定档案（单选，可搜索）、主治医生（单选，可搜索）；展示“患者端将看到：王医生 · 神经内科 · 138****”。
3. 提交：
   - 患者：`createUser(PATIENT)` → `grantSubjectPermission(subject, SUMMARY_READ)` → 如医生尚无权限，`grantSubjectPermission(subject, VIEW)` + `MANAGE`。
   - 每一步显示进度；失败时保留已成功步骤，提供“重试授权 / 删除刚建账号”，不假装成功。
   - 医生无手机号时阻止选择为“主治医生”，并提示先去补全医生资料。
4. 编辑：角色不可从 PATIENT 直接改为员工（后端会拒绝），前端给出明确引导：先撤销患者档案授权。

**组件与文件**：

- 新建 `frontend/src/components/admin/UserProvisioningDialog.vue`
- 新建 `frontend/src/components/admin/roles/PatientFields.vue`、`DoctorFields.vue`、`ResearcherFields.vue`、`AdminFields.vue`
- 改造 `frontend/src/views/admin/UserAdmin.vue`：列表增加“账号类型/绑定档案/主治医生”列与角色筛选；新增/编辑走向导。
- `frontend/src/api/users.js` 增加 `provisionPatientAccount()` 编排函数（非新端点）。

**可选后端增强（推荐）**：`POST /api/users/patient-provisioning` 在一个事务里完成建号 + 双授权，避免半成品。若不做，前端必须实现可恢复的半失败流程。

**验收**：

- 新建患者后，患者账号登录能看到医生姓名、科室、电话、出诊时间。
- 数据库授权正确：患者仅 `SUMMARY_READ`，医生 `VIEW+MANAGE`。
- 中途失败可重试，不产生重复账号/重复授权。
- 非 ADMIN 调用相关接口返回 403。

### 3.2 系统控制台

**目标**：把 `/admin` 从“数据大屏”升级为“系统控制台”，把分散在账号、模型、质控、审计里的管理能力收拢到一个有状态、有审计、有危险操作确认的界面。

**页面结构**：

1. **运行状态**：Java/Python/契约/构建版本、延迟、最近检查时间；刷新。
2. **评估与模型**：
   - 模型注册表（启用/停用，现有接口）。
   - 评估通道：研究表格通道状态；旧规则通道已停用（只读说明）。
   - 研究宿主：地址、启用、超时、测试连接（需新接口）。
3. **账号与权限**：账号/角色统计、待处理授权、跳转账号管理。
4. **数据与质量**：导入任务概览、质控统计、规则阈值（需新接口）。
5. **审计与安全**：最近审计、保留天数、导出（需新接口）。
6. **危险区**：演示数据重置/清理（需新接口，二次确认 + 输入确认词）。

**控制项矩阵**：

| 控制项 | 现有接口 | 需要新增 |
| --- | --- | --- |
| 模型启停 | `PATCH /api/system/models/{version}` | 否 |
| 研究宿主地址/开关/超时 | 无 | `GET/PUT /api/system/research-host`、`POST .../test` |
| 评估通道策略 | 无 | `GET/PUT /api/system/assessment-policy` |
| 审计/报告保留 | 无 | `GET/PUT /api/system/retention` |
| 会话/密码策略 | 无 | `GET/PUT /api/system/security` |
| 演示数据重置 | 无 | `POST /api/system/demo-data/reset`（可选） |

存储建议：新增 `system_setting(key, value_json, updated_by, updated_at)`，所有写操作进审计；运行时读取，避免必须重启。

**原则**：没有后端接口的控制项不放假开关。前端按“能力注册表”渲染：有接口→可操作；无接口→显示“后端未提供”并禁用，附接口名。这样控制台可以随后端逐项点亮，不做假控制。

**组件与文件**：

- 重构 `frontend/src/views/admin/SystemSettings.vue` 为控制台骨架。
- 新建 `frontend/src/components/admin/system/HealthPanel.vue`、`ModelRegistryPanel.vue`、`ResearchHostPanel.vue`、`PolicyPanel.vue`、`DangerZone.vue`。
- `frontend/src/api/system.js` 增加对应函数。

**验收**：

- 模型开关生效并刷新后保持。
- 研究宿主保存后可测试连通；失败有可读错误。
- 所有写操作有审计记录（操作者、前后值、时间）。
- 非 ADMIN 不可见且不可调用。

### 3.3 患者主索引与跨时段档案合并

**这是四个问题里唯一必须动数据模型的。**

**目标模型**：

- 新增 `patient`：`id, patient_code, name, phone, gender, birth_date, primary_doctor_id, created_at, updated_at`。
- `subject` 增加 `patient_id`（可空，外键/索引）。
- 一个 patient 对多个 subject（不同时间段/来源的档案）。
- 患者账号绑定从 `subject_permission` 提升为 `sys_user.patient_id`；迁移时按现有 `SUMMARY_READ` 档案回填，保留旧授权兼容。
- 合并操作只移动 subject 归属，不移动/不重写评估与报告；审计记录源/目标。

**后端最小接口**：

- `GET /api/patients`（分页、keyword、riskLevel、doctorId；聚合档案数、最近评估、最近风险、主治医生）
- `GET /api/patients/{id}`（基本信息、档案列表、主治医生、患者账号绑定状态）
- `POST /api/patients`、`PATCH /api/patients/{id}`
- `POST /api/patients/{id}/subjects`（关联已有档案）
- `DELETE /api/patients/{id}/subjects/{subjectId}`（解除关联）
- `POST /api/patients/merge`（targetId + sourceIds[]，事务 + 审计）
- `GET /api/patients/{id}/timeline?from&to`（可选，聚合事件；也可前端拼装）

**迁移**：

- `V14__patient_master_index.sql`：建表、加列、按现有 subject 1:1 回填（`patient_code = 'P-' || subject_code`），保证零行为变化。
- 回填患者账号的 `patient_id`，保留 `SUMMARY_READ` 授权直到验证完成。
- 提供回滚脚本/开关；合并操作先 dry-run 预览数量。

**前端页面**：

- `/patients` 患者目录：
  - 卡片/表格：患者姓名、编号、电话、档案数、最近风险、最近评估时间、主治医生、下次随访。
  - 筛选：风险等级、医生、关键字；支持“疑似重复”（同姓名+电话/出生日期）提示。
- `/patients/:id` 患者 360：
  - 顶部身份卡 + 主治医生 + 患者账号状态；操作：编辑、关联档案、合并、发起研究评估、新建随访。
  - 档案切换 chips（按时间/来源）；“全部档案”时时间线合并。
  - 统一时间线：访视、特征、评估、报告、复核、随访，支持类型筛选与时间范围。
  - 风险趋势图：AD/其他痴呆 24 个月 CIF 随时间的折线（有评估时）。
  - 档案列表：每份档案的编号、时间段、数据版本、评估次数、操作。
- 合并对话框：选目标患者、源患者、预览将移动的档案/评估/报告数量，输入确认词；完成后刷新患者 360 并写审计。
- 档案视角 `/subjects` 保留，增加“所属患者”列与“按患者分组”开关。

**权限**：

- ADMIN/RESEARCHER 可合并；DOCTOR 只能查看获授权患者，不能合并；PATIENT 只看本人。
- 合并后的患者可见性 = 其任一档案对用户可见（或患者级授权，二选一，建议按档案聚合，复用现有对象权限）。

**验收**：

- 两个档案合并为一个患者后，患者 360 同时显示两份档案的时间线与报告；原评估/报告 ID 不变。
- 患者账号能看到合并后的摘要与主治医生。
- 解除关联后恢复原状；合并/解除均有审计。
- 非授权医生看不到患者；合并接口非管理员返回 403。

**若不改后端**：只能做“单档案时间线增强”（把生命周期提到首屏、增加时间筛选与跨访视对比），不能真正合并多份档案。建议不要用 alias 猜测分组，数据一多就会错。

### 3.4 批量研究评估抽屉

**目标**：研究员选中多个患者/档案后，在一个抽屉里看到每个对象的快照、任务状态、双 CIF、错误与重试，不再逐档案点。

**入口**：

- `TaskList.vue` 右上“批量研究评估”（RESEARCHER/ADMIN）。
- `PatientList.vue` 多选工具条“批量研究评估”（RESEARCHER）。
- 患者目录按患者选择时，展开为该患者的全部档案。

**抽屉结构**（`ResearchBatchDrawer.vue`，宽 760–900px）：

1. 顶部摘要：总数 / 读取中 / 已完成 / 被阻断 / 失败 / 结果未知；开始、暂停、取消、导出 CSV。
2. 队列表格：
   - 受试者/患者、档案、快照（下拉，默认最新；无快照显示“未登记”）
   - 状态（排队/加载快照/提交中/轮询/完成/阻断/失败/未知）
   - taskId、AD CIF、其他痴呆 CIF、错误、操作（查看详情/重试/打开报告）
3. 行详情抽屉/弹层：复用 `ResearchAssessmentPanel` 的结果区，展示模型、artifactSha256、解释边界。

**执行逻辑**：

- 并发 3；每个对象：`listResearchSnapshots` → 有 taskId 则 `getResearchTableTask`，否则 `submitResearchTableTask`（幂等键按 snapshot 持久化）→ 2s 轮询，终态停止。
- 409：刷新快照并提示已存在任务，改为读取；404/405/501：该行标记“后端未提供快照列表”，允许手输。
- 503：整批暂停，顶部展示“当前未配置研究宿主…”；恢复后继续。
- 刷新页面：队列存 `sessionStorage`，恢复未完成任务；不重复提交（幂等键 + taskId）。
- 上限 50 个/批，超出提示分批。

**状态存储**：

- 新建 `frontend/src/stores/researchBatch.js`（Pinia）：`items[]`、`concurrency`、`running`、`paused`、`summary`；动作 `start/pause/resume/cancel/retry/export`。
- 不复用已删除的旧 `batch.js`（旧的是规则评估批量，语义不同）。

**验收**：

- 10 个档案批量提交，抽屉实时显示各自状态；已存在任务的档案直接回读，不重复创建。
- 单个失败不影响其他；可单独重试。
- 503/403/409 有明确行级与批级提示。
- 关闭再打开页面可恢复；无重复任务。

## 4. 跨领域改造

### 4.1 设计系统

- 保留现有 `PageHeader/DataCard/EmptyState/StatusTag/FilterBar` 变量与蓝白风格，不整体换肤。
- 新增 `PatientIdentityCard`、`PatientTimeline`、`ArchiveSwitcher`、`SystemControlCard`、`RoleProvisioningDialog`、`ResearchBatchDrawer`、`ConfirmDangerDialog`。
- 统一筛选条：搜索、状态下拉、日期范围、重置；列表页首屏不超过两行工具。
- 密度：管理端 14px/紧凑；患者端 16px+/宽松。

### 4.2 错误与状态

- 所有新页面区分 loading / empty / error / forbidden / partial；不用空白页代替错误。
- 503 统一文案：“当前未配置研究宿主，请联系管理员配置研究宿主。”
- 统一 `silent` 请求，避免全局 toast 与页面内提示重复。

### 4.3 性能

- 路由已懒加载；拆分 LoginView（696 kB）与 element-plus 大依赖，配置 `manualChunks`。
- 患者 360 时间线分页/按需加载；避免一次拉全部评估详情。
- 批量抽屉限制并发与轮询频率；页面隐藏时暂停。

### 4.4 可访问性

- 抽屉焦点陷阱、Esc 关闭、按钮 aria-label；表格键盘导航；颜色对比达标；患者端大字号。
- 表格/时间线不只靠颜色表达风险等级。

### 4.5 测试与文档

- 新增 e2e：开户向导（患者绑定医生）、系统控制台（模型开关/权限）、患者合并（合并/解除）、批量抽屉（成功/阻断/失败/恢复）。
- 保留 `e2e-task-list`、`e2e-roles`；新增 `e2e-patient-merge`、`e2e-batch`。
- 更新 `frontend/README.md`、`docs/Design_Guide.md`、`docs/Frontend_Missing_API_Inventory.md`；本方案留在 `docs/Frontend_Productization_Plan.md`。

## 5. 分期与交付

### P0（纯前端，先见效）

- 角色化开户向导（患者绑定医生 + 档案）。
- 批量研究评估抽屉 + 任务列表入口。
- 患者目录/患者 360 的**前端骨架**（先按单档案聚合，等 P2 接主索引后切换数据源）。
- 验收：`npm run build`、`e2e-roles`、新增开户/批量 e2e。

### P1（系统控制台）

- 控制台骨架 + 运行状态 + 模型注册表 + 账号/权限入口 + 审计入口。
- 后端 3 个配置接口（研究宿主、评估通道、保留策略）与 `system_setting` 表。
- 验收：控制项可保存/回读/审计；非管理员 403。

### P2（患者主索引）

- V14 迁移 + patient API + 患者账号绑定迁移。
- 患者目录/患者 360 接真实数据；合并/解除；患者端摘要改为患者级。
- 验收：合并/解除/权限/患者登录全链路；迁移回滚演练。

### P3（打磨）

- 性能、空态、文案、响应式、可访问性；全量 e2e；文档与截图。

## 6. 风险与取舍

1. **患者主索引是数据模型变更**：迁移、权限、患者账号绑定都要一起改；必须先备份、先 dry-run，不能只做前端。
2. **系统控制项涉及运行时配置**：有些配置可能需要重启或影响正在运行的任务；所有写操作要审计 + 二次确认。
3. **患者绑定医生 = 档案授权**：不是新关系表；如果未来要“主治医生”独立于档案，需再加关系。
4. **批量是客户端扇出**：50 个档案 = 50 次快照 + N 次提交/轮询；需要并发上限与暂停，避免打爆 Java/Python。
5. **演示数据没有重复患者**：合并功能需要构造 fixture（同一人多份档案）才能验证。
6. **研究文案**：当前 UI 仍有多处“模型验证”；是否统一改为产品表述，等管理员决定，不在本方案内擅自删除。

## 7. 需要拍板的三件事

1. **患者主索引**：是否同意做最小后端改造（V14 + 患者 API + 患者账号绑定）？推荐“是”；否则只能做单档案时间线，不能真正合并。
2. **系统控制范围**：先做“现有能力控制台”（模型/账号/权限/审计/健康），还是同时补研究宿主/评估通道/保留策略 3 个后端配置接口？推荐后者，控制台才名副其实。
3. **账号角色模型**：账号采用“单主角色 + 可选附加角色”，还是继续多选？患者是否必须同时绑定档案与主治医生？推荐单主角色、患者两必填。

## 8. 下一步

确认以上三点后，按 P0 → P1 → P2 开工。P0 先交付两个可演示流程：

- 管理员创建患者 → 绑定档案与医生 → 患者登录看到医生联系方式。
- 研究员选择 10 个档案 → 批量抽屉实时显示快照/任务/双 CIF，失败可重试。

每个阶段结束跑构建与对应 e2e，提交前再确认一次。
