# AD-CausalRisk 项目交接与单工作区串行开发规划

- 日期：2026-11-15
- 适用仓库：`C:\Users\34079\Desktop\AD-CausalRisk`
- 当前分支：`feat/backend-integration`
- 当前 HEAD：`cd54b11 feat(research): complete snapshot auto-sync and batch execution loop`
- 文档定位：项目级交接、问题清单、工作流拆分、验收口径、子 Agent 协作规则
- 配套任务板：`docs/Subagent_Task_Board.md`
- P1 验收报告：`docs/P1_Acceptance_Report.md`

---

## 0. 先读什么，怎么用

新会话/子 Agent 开始前，按顺序读：

1. `README.md`
2. `AGENTS.md`
3. `docs/Integration_Contract.md`
4. `docs/Research_Core_Principles.md`
5. 本文档
6. 与任务对应的 `docs/Subagent_Task_Board.md` 任务卡

硬性要求：

- 不要覆盖未提交工作，不要 `git reset --hard`、不要 `force push`。
- 没有明确授权，不 commit、不 push、不创建/合并 PR、不改仓库设置。
- 跨模块或共享契约改动，先说明并取得负责人同意。
- 不为了“看起来成功”改规则、删测试、补假值。
- 研究模型结果不是临床结论，也不是个体因果效应；解释是分组归因，必须保留边界说明。
- `BLOCKED` 必须保持 `riskScore=null`、`riskLevel=UNAVAILABLE`，禁止补分数。
- `DEMO-*`、`demo-*`、`SYNTHETIC_DEMO`、`CONSTRUCTED_VALIDATION` 等标签不得冒充真实临床数据。

---

## 1. 当前基线快照

### 1.1 代码状态

- 分支：`feat/backend-integration`
- HEAD：`cd54b11 feat(research): complete snapshot auto-sync and batch execution loop`
- 相对 `upstream/main`：领先提交数、是否已推送，以 `git rev-list --left-right --count upstream/main...HEAD` 与 `git status -sb` 为准
- `origin/feat/backend-integration` 最近一次推送为 `5fcc7ea`；P1 后续提交（`0298352`、`cd54b11`）尚未推送
- `main` 仍停在 `35055da`，**没有研究证据/研究表格评估页面**
- 演示与联调必须使用 `feat/backend-integration`，不能用 `main`

W0 已完成并推送：

```text
6af5595 feat(research): add controlled snapshot registration
933a4cd docs: add project handoff and subagent task board
59b5844 feat(research): add dynamic host launcher
5fcc7ea docs: switch to single-workspace serial collaboration
0298352 feat(research): auto-sync snapshot bundle to dynamic host and enforce sync status
cd54b11 feat(research): complete snapshot auto-sync and batch execution loop
```

受控快照登记内容：

- 后端 `POST /api/research/table-assessment-tasks/snapshots/register`
- 七变量手工登记
- 写 MySQL `research_table_snapshot`
- 前端「手动登记快照」弹窗

验证状态：

- `.\mvnw.cmd -B -ntp clean verify`：**326 tests，0 failures，BUILD SUCCESS**
- `npm run build`：**2388 modules，成功**
- Python 契约测试：**5/5 OK**
- Python 动态宿主测试：**6/6 OK**
- `git status`：P1 代码提交后干净

### 1.2 运行状态

| 服务 | 地址 | 状态 |
|---|---|---|
| Java 后端 | `http://127.0.0.1:8080` | 运行中，连接本机 MySQL |
| 前端 Vite | `http://127.0.0.1:5173` | 运行中 |
| MySQL | `127.0.0.1:3306/ad_causalrisk` | 运行中，Flyway V16 |
| Python 研究 Agent | 8091 | 已退役，不参与当前主链路 |
| 动态研究宿主 | `http://127.0.0.1:16301` | 运行中，`mode=dynamic` |
| 真实模型文件 | `C:\controlled-model\` | 已交付并核验 |

本机 MySQL 当前账号（仅本地演示，不要写入仓库）：

- 用户名：`root`
- 密码：`root`
- 数据库：`ad_causalrisk`

种子账号：

- `admin / DemoPass!2026`
- `researcher / DemoPass!2026`
- `doctor / DemoPass!2026`
- `patient / DemoPass!2026`
- `research_doctor / DemoPass!2026`

### 1.3 真实模型状态

模型文件：

- `C:\controlled-model\Model_Manifest.json`
- `C:\controlled-model\final__logistic.joblib`

哈希：

- manifest：`46ad244fe612306cc95a2a457c44ac234361db5ab70184c2ee2226527883a511`
- joblib：`66cfc25386ef9d90c9a6572ac13f673269426fce277d6604e7d1a355ff0a1fd5`
- training plan：`47ae229df74070f56165ece9b828a1aebcbf1b316b2005439e3f76fa89b0d54e`

已验证：

- `.venv-trained` 加载模型成功
- `trained-check` 输出 `"mode":"trained"`、`"status":"COMPLETED"`
- `--serve` 启动后，前端通过 16300 代理提交，真实结果：
  - `adCif24m=19.34%`
  - `otherDementiaCif24m=0.08%`

注意：

- `.venv-trained` 已在收尾时删除；主仓库 `.venv` 已安装 joblib/scikit-learn/numpy 等研究依赖。
- 当前运行的是**动态宿主**：登记快照后 Java 自动调用 `POST /api/research-table/v1/snapshots` 注册 bundle，宿主落盘到 `runtime/dynamic-snapshots/` 并热加载。
- 旧的“只接受预注册 runId”限制已解除；同步状态由 `research_table_snapshot.host_sync_status` 持久化。
- `PENDING`/`FAILED` 快照不能提交，前端可点击“重试”调用 `POST /api/research/table-assessment-tasks/snapshots/{id}/sync`。
- P1 完整验收见 `docs/P1_Acceptance_Report.md`。

### 1.4 数据库样例

当前 MySQL 保留三条已同步的人工登记样例：

- `subjectId=203`，`subjectCode=DEMO-SMOKE-252019`
- `data_scope=CONSTRUCTED_VALIDATION`
- `modelKey=trained-seven-logistic`
- `manifestSha256=46ad244f...83a511`
- `artifactSha256=66cfc253...0a1fd5`
- `snapshotId=7 / taskId=5`：AD 19.34% / 其他 0.08%
- `snapshotId=8 / taskId=6`：AD 63.99% / 其他 36.01%
- `snapshotId=5 / taskId=4`：与 #7 同输入，已通过重试同步
- 三条 `host_sync_status` 均为 `SYNCED`
- 详细数字与哈希见 `docs/P1_Acceptance_Report.md`

---

## 2. 项目现在是什么状态

一句话：

> **前端 P0/P1 已落地；受控快照可登记、自动同步到动态宿主并真实执行；患者档案仍不是患者。**

三个闭环，分开看：

### L1 研究执行闭环

`受试者 → 七变量/快照 → 批量提交 → Python 宿主 → 真实模型结果`

现状：

- 登记：已通
- MySQL：已通
- 动态宿主同步：已通（`SYNCED`）
- 新快照执行：**已通**（快照 #7 / #8 均 `COMPLETED`）
- 界面：批量抽屉可展示同步状态、阻止未同步快照、失败可重试
- 验收报告：`docs/P1_Acceptance_Report.md`

### L2 患者业务闭环

`患者 → 多份档案 → 跨时段时间线 → 多次评估 → 复核/随访 → 报告`

现状：

- 单档案业务基本完整
- 患者主索引：**未做**
- 跨 subject 合并：**未做**
- 真实数据：**不足**

### L3 产品化闭环

`多角色开户 → 系统控制 → 真实数据治理 → 部署 → 监控 → 审计保留`

现状：

- 角色化开户：已完成 P0
- 系统控制台：已完成 P1
- 真实数据治理：未做
- 部署/监控/备份：未做
- 历史文档/历史命名：已清理（P5）

---

## 3. 模块状态

| 模块 | 已完成 | 主要缺口 |
|---|---|---|
| 前端 | 登录/四角色路由、档案/访视/特征/观测、导入、质控、任务、报告、图谱、复核随访、审计、系统控制台、开户向导、批量抽屉、宿主同步状态/重试 | 患者主索引、批量任务历史、部分角色引导、P3 打磨 |
| 后端 | 72 个 OpenAPI 操作、V16、系统控制台、受控登记、自动同步、重试、任务历史、审计、326 测试 | 患者主索引、batch 端点、部署配置 |
| Python | 真实七变量 LR、动态宿主、快照注册、合成验证 Agent | 常驻服务、真实数据接入、监控 |
| 数据 | MySQL 本地库、快照 #5/#7/#8/#9（均 SYNCED） | 203 缺真实七变量、患者分组缺失 |
| 交付 | 本地可运行、构建/测试通过、P1/P3 代码已提交 | 提交未推送、无部署 |

---

## 4. 问题清单

| ID | 优先级 | 问题 | 影响 | 归属 |
|---|---|---|---|---|
| P0-1 | P0 | ~~Python 宿主只认预注册 runId~~ **已解决（P1）** | 动态宿主 + 自动同步 + 重试；手工快照可执行 | W1 |
| P0-2 | P0 | ~~受控登记 10 文件未提交~~ **已解决** | P1 代码已提交（`0298352`/`cd54b11`） | W0 |
| P0-3 | P0 | 分支仍有未推送提交 | CI/协作/回滚以远端为准 | W0 |
| P1-1 | P1 | `subject` 没有 `patient_id` | 患者跨时段合并无法实现 | W3 |
| P1-2 | P1 | 203 缺七变量真实数据 | 真实模型无法在 203 上验收 | W2 |
| P1-3 | P1 | ~~批量任务只存在前端内存~~ **已解决（P3）** | 任务历史持久化、失败重试 | W5 |
| P1-4 | P1 | ~~历史文档/历史命名仍大量存在~~ **已解决（P5）** | 文档与 UI 已完成产品化表述 | W6 |
| P1-5 | P1 | 无正式部署/密钥/备份 | 不能交给他人稳定运行 | W7 |
| P1-6 | P1 | 医生端看到“没有研究评估权限”但无引导 | 角色体验困惑 | W5 |
| P2-1 | P2 | 系统控制台仍缺安全策略/演示重置/宿主测试连接 | “控制”不完整 | W4 |
| P2-2 | P2 | 审计保留策略只清理 audit_log | 保留口径需产品确认 | W4 |
| P2-3 | P2 | 前端无 CI/E2E 流水线 | 回归靠手工 | W7 |
| P2-4 | P2 | 研究模型解释边界未在页面统一提示 | 误读为临床/因果结论的风险 | W5/W6 |
| P2-5 | P2 | `main` 无研究页面 | 发布/演示分支混乱 | W0 |

---

## 5. 目标闭环与验收

### 5.1 L1 研究执行闭环

验收：

1. 用 `researcher` 登录。
2. 给 203 登记一条七变量快照（或使用快照 #2）。
3. 在批量抽屉中提交。
4. 返回 `COMPLETED`，不是 503、不是 `RUN_ID_INVALID`。
5. 结果里的 `manifestSha256`、`artifactSha256`、`requestSha256`、`inputSha256`、`contextSha256` 与数据库快照一致。
6. 页面显示模型版本、24 个月双 CIF、同次分组归因、`isCausalEffect=false`。
7. 刷新后能回读同一任务。

### 5.2 L2 患者业务闭环

验收：

1. 一个患者可关联多份 subject/档案。
2. 患者中心显示跨档案合并时间线。
3. 患者账号只看到本人摘要。
4. 医生只看到授权档案。
5. 评估、报告、复核、随访仍可按档案追溯。

### 5.3 L3 产品化闭环

验收：

1. 全新环境可按文档启动。
2. 密钥不进仓库。
3. MySQL 可备份/恢复。
4. 历史文档/历史命名已清理（P5，2026-11-15）。
5. CI 覆盖 Java、Python、前端构建/关键 E2E。
6. 演示脚本可重复执行。

---

## 6. 工作流拆分

### W0 交付卫生（P0，先做）

目标：把当前成果变成可共享、可回滚的基线。

任务：

- 确认 10 个文件 diff。
- 经负责人授权后 commit。
- push 分支，创建 PR（如获授权）。
- 触发并观察 CI。
- 记录 main 与功能分支差异。
- 更新本交接文档的 HEAD/状态。

验收：

- `git status` 干净（`.tmp/evidence/` 可移出仓库或忽略）。
- CI 绿。
- 分支可被其他人拉取。

### W1 动态研究宿主（P0，核心）

目标：让新登记的快照能真正执行。

建议方案：

- 方案 A（推荐）：宿主按请求动态构建 agent。
  - 从可信 manifest/reference context 读取模型与上下文。
  - 每次请求用 `runId` 建立临时 agent，不依赖静态 `agents` map。
  - 后端不需要新增公开写接口。
- 方案 B：宿主提供受控注册接口。
  - backend 在登记快照时调用宿主注册 `runId + request + model`。
  - 需要鉴权、幂等、失败回滚。

关键约束：

- 客户端不能决定 trusted context。
- `requestSha256/inputSha256/contextSha256` 必须与宿主实际计算一致。
- 不允许用构造样例冒充真实请求。
- 不允许把 `RUN_ID_INVALID` 吞掉当成功。

涉及文件：

- `research_runtime/examples/research_table_http.py`
- `research_runtime/research_table_agent.py`（或实际 agent 文件）
- `research_runtime/inference.py`
- `backend/src/main/java/com/adcausalrisk/backend/research/ResearchTableClient.java`
- `backend/src/main/java/com/adcausalrisk/backend/research/ResearchTableAssessmentService.java`
- `docs/Research_Table_Agent_Contract.md`

验收：

- 快照 #2（或新登记快照）返回 `COMPLETED`。
- 503 / `RUN_ID_INVALID` 不再出现。
- `ResearchTableAssessmentTest` 增加动态 runId 用例。
- 真实模型结果可回读。

### W2 真实数据与 203 七变量（P1，依赖 W1）

目标：让 203 有一条可解释的真实/受控数据快照。

任务：

- 收集 203 的 age、education、MMSE、CDR-SB、CDR global、ADAS13、FAQ。
- 记录 measuredDate/availableDate、单位、来源。
- 通过现有特征补录/受控登记接口写入。
- 用七变量表单登记快照。
- 跑真实模型，核对结果与摘要。

验收：

- 203 的 `subject_feature` 或快照 request 含七变量。
- 快照 `data_scope=CONSTRUCTED_VALIDATION`（若仍是人工样例）。
- 真实结果可重复。

### W3 患者主索引（P1，用户已同意但列远期）

目标：把 `subject` 从“患者”降为“档案”，新增 patient 维度。

建议模型：

```sql
patient(
  id BIGINT PK,
  patient_code VARCHAR(64) UNIQUE,
  name VARCHAR(128),
  phone VARCHAR(32),
  gender VARCHAR(16),
  birth_date DATE,
  primary_doctor_id BIGINT,
  created_at DATETIME,
  updated_at DATETIME
)
subject.patient_id BIGINT NULL
```

任务：

- V14/V15 迁移（additive，不破坏旧数据）。
- 回填演示数据。
- `GET /api/patients`、`GET /api/patients/{id}`。
- 患者 360 跨 subject 时间线。
- 权限：患者只读本人；医生只读授权。
- 合并/拆分操作要审计，不重写历史评估。

验收：

- 一个 patient 关联 2 个 subject。
- 患者中心显示合并时间线。
- 旧 `/subjects` 仍可用。
- 迁移可回滚/可重跑。

### W4 系统控制台二期（P2，排在 W1 之后）

目标：把“控制台”补成真正可运维。

任务：

- `GET/PUT /api/system/security`：会话/密码策略（若产品确认）。
- `POST /api/system/demo-data/reset`：二次确认 + 审计（可选）。
- 研究宿主“测试连接”按钮：调用宿主 `/status` 或健康检查。
- 审计/报告保留策略落地：明确清理范围，新增测试。
- 危险操作二次确认。

验收：

- 每个开关都有真实接口。
- 写操作全部审计。
- 非 ADMIN 403。

### W5 前端体验与批量任务（P2）

目标：补齐“真实使用感”。

任务：

- 批量任务持久化/历史：后端列表或前端 localStorage + 任务回读。
- 503/`RUN_ID_INVALID`/`FAILED`/`BLOCKED` 的明确文案与下一步。
- 医生端“没有研究评估权限”的引导：去研究证据或联系管理员。
- 研究结果页统一免责声明：研究模型、非临床、非因果。
- 患者中心 UI（依赖 W3）。
- 关键页面空态/错误态/移动端。

验收：

- 刷新后批量任务不丢失。
- 每个失败态都有可执行下一步。
- 无 pageerror。

### W6 产品化文案（P5，已完成）

目标：让文档和用户文案匹配“医疗科研协作平台”。

已完成：

- 重写根 `README.md` 为产品化 Quick Start。
- 全量扫描 Markdown 与前端 UI 文案，清理历史项目表述与演示口径。
- 保留历史文档文件名以兼容既有链接；`Course_SRS_V3_2.md` 仅作为历史需求基线保留。
- 保留 `SYNTHETIC_DEMO`、`CONSTRUCTED_VALIDATION` 等数据标签，不改代码标识符、表名和模型协议字段。
- 新增 `docs/Release_Notes_v1.0.md` 作为交付基线。

验收：

- 文档导航不再出现历史项目叙事。
- 用户页面无历史演示措辞。
- 历史实验记录仍可追溯，数据边界标记完整。

### W7 运维与部署（P2）

任务：

- Docker Compose：MySQL + Java + Python 宿主（可选）。
- `.env.example`，密钥不进仓库。
- MySQL 备份/恢复脚本。
- 前端生产构建 + Nginx/静态托管。
- CI：Java、Python、前端 build、关键 E2E。
- 日志与健康检查。

验收：

- 全新机器按 README 可启动。
- 备份可恢复。
- CI 对 PR 强制执行。

### W8 验收与发布（收尾）

任务：

- 端到端矩阵：四角色 × 核心流程。
- L1/L2/L3 验收清单。
- 演示脚本（10 分钟）。
- 发布说明与回滚方案。

---

## 7. 里程碑与串行策略

### M0 交付卫生

- W0
- 产出：干净工作区、push 分支、CI 绿

### M1 研究执行闭环

- W1 + W2（最小数据）
- 产出：快照 #2 或新快照真实 `COMPLETED`

### M2 患者业务闭环

- W3 + W5（患者中心）
- 产出：一个患者多档案合并时间线

### M3 产品化

- W4 + W6 + W7 + W8
- 产出：可部署、可运维、可演示

串行策略：

- 所有任务进入同一任务队列，按 W0 → W1 → W2 → W3 → W4 → W5 → W6 → W7 → W8 顺序调度。
- 为避免依赖环境重复配置，所有主/子 Agent 共享同一个主工作区（AD-CausalRisk）及同一套 `.venv` / `node_modules`。采用任务队列串行调度，当前任务未 commit 前，不启动下一个任务。
- 当前一张任务卡未 commit、未验证完成前，不启动下一张任务卡。
- 依赖关系不变：W2 依赖 W1；W5 依赖 W3；W8 最后。
- 涉及共享契约/路由/README 的改动由当前任务负责人完成并 commit，下一位再开始。

---

## 8. 单工作区 Agent 协作规则

### 8.1 一个会话一个工作流

每个子 Agent 只负责一个 W 或一张任务卡，不跨工作流改文件。

### 8.2 单工作区强制串行

为避免依赖环境重复配置，所有主/子 Agent 共享同一个主工作区（AD-CausalRisk）及同一套 `.venv` / `node_modules`。采用任务队列串行调度，当前任务未 commit 前，不启动下一个任务。

- 不再创建 worktree，不再复制独立 `.venv` / `node_modules`。
- 子 Agent 直接在主工作区按任务卡顺序执行；同一时刻只允许一个 Agent 修改文件。
- 当前任务完成后，由负责人 commit（或明确记录未提交原因），再启动下一张任务卡。
- 若任务确实需要隔离验证，使用临时目录/临时数据库，不创建物理 worktree。
- 禁止多个 Agent 同时修改同一工作区。

### 8.3 文件所有权

| 范围 | 负责工作流 |
|---|---|
| `research_runtime/**` | W1 |
| `backend/**/research/**` | W1 |
| `backend/**/patient/**`、`subject/**`、迁移 | W3 |
| `frontend/src/views/doctor/**`、患者中心 | W3/W5 |
| `frontend/src/views/admin/SystemSettings.vue` | W4 |
| `frontend/src/components/assessment/**` | W1/W5 |
| `docs/**`、README、AGENTS | W6/W0 |
| `.github/workflows/**`、部署 | W7 |

共享文件（必须串行）：

- `contracts/java-business.target.openapi.json`
- `frontend/src/router/index.js`
- `backend/**/MenuCatalog.java`
- `README.md`、`AGENTS.md`
- `pom.xml`、`frontend/package.json`

### 8.4 提交与推送

- 默认只改代码，不 commit。
- 负责人明确授权后才 commit。
- 不在子 Agent 中 push/merge/PR，除非任务卡明确授权。
- 不修改仓库成员/设置/部署。

### 8.5 交接格式

每个子 Agent 完成后报告：

```text
任务：
分支/commit：
改动文件：
验证命令与结果：
未完成/风险：
下一步建议：
```

### 8.6 子 Agent 提示词模板

```text
你是 AD-CausalRisk 的子 agent，只负责任务 <TASK-ID>。

先读：
- docs/Project_Handoff_2026-11-15.md
- docs/Subagent_Task_Board.md 的 <TASK-ID>
- AGENTS.md

工作目录：C:\Users\34079\Desktop\AD-CausalRisk
分支：<branch>
只改任务卡列出的文件；共享契约/路由/README 不要动。

约束：
- 不 commit/push。
- 不覆盖未提交工作。
- 不造假数据、不弱化测试。
- 保持 BLOCKED 语义和 SYNTHETIC_DEMO/CONSTRUCTED_VALIDATION 标签。

验收：<acceptance>

完成后按“任务/分支/改动/验证/风险/下一步”报告。
```

---

## 9. 风险与待决策

| 风险/决策 | 说明 | 需要谁决定 |
|---|---|---|
| 动态宿主方案 A/B | 动态 agent 还是宿主注册接口 | 负责人 + Python |
| 真实数据来源 | 203 的七变量从哪来、是否允许人工录入 | 负责人/数据保管方 |
| 患者主索引迁移 | 是否现在做、如何回填 | 负责人（已同意远期） |
| 历史文档处置 | 已完成：保留历史文件名，清理历史叙事（P5） | 已执行 |
| 部署目标 | 本机/内网/云 | 负责人 |
| 分支策略 | `feat/backend-integration` 是否作为发布分支 | 负责人 |
| 模型结果表述 | 研究模型/非临床/非因果 | 负责人/研究 |

---

## 10. 附录

### 10.1 后端

```powershell
cd C:\Users\34079\Desktop\AD-CausalRisk\backend
$env:SPRING_PROFILES_ACTIVE='local'
$env:ADCR_DB_URL='jdbc:mysql://127.0.0.1:3306/ad_causalrisk?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC'
$env:ADCR_DB_USERNAME='root'
$env:ADCR_DB_PASSWORD='root'
.\mvnw.cmd -B -ntp clean verify
.\mvnw.cmd spring-boot:run
```

### 10.2 前端

```powershell
cd C:\Users\34079\Desktop\AD-CausalRisk\frontend
npm ci
npm run dev -- --port 5173 --strictPort
npm run build
```

### 10.3 Python 测试

```powershell
cd C:\Users\34079\Desktop\AD-CausalRisk
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

### 10.4 模型宿主（需重建 `.venv-trained`）

```powershell
py -3.12 -m venv .venv-trained
.\.venv-trained\Scripts\python.exe -m pip install -r research_runtime\requirements.lock
$env:TEMP='C:\Users\34079\Desktop\AD-CausalRisk\.tmp\java-tmp'
$env:TMP=$env:TEMP
.\.venv-trained\Scripts\python.exe -m research_runtime.examples.run_research_table_integration `
  runtime/trained-ui --serve `
  --trained-manifest C:\controlled-model\Model_Manifest.json `
  --trusted-manifest-sha256 46ad244fe612306cc95a2a457c44ac234361db5ab70184c2ee2226527883a511
```

### 10.5 关键接口

- `GET /api/research/table-assessment-tasks/snapshots?subjectId={id}`
- `POST /api/research/table-assessment-tasks`（body `{"snapshotId":N}` + `Idempotency-Key`）
- `GET /api/research/table-assessment-tasks/{id}`
- `POST /api/research/table-assessment-tasks/snapshots/register`（body `{subjectId,indexDate?,features}`）
- `GET /api/system/info`
- `GET/PUT /api/system/research-host`
- `GET/PUT /api/system/assessment-policy`
- `GET/PUT /api/system/retention`

### 10.6 证据

- `.tmp/evidence/mysql-snapshot-list.png`
- `.tmp/evidence/mysql-register-form.png`
- `.tmp/evidence/mysql-register-success.png`

### 10.7 相关文档

- `docs/Frontend_Productization_Plan.md`
- `docs/Frontend_Missing_API_Inventory.md`
- `docs/Research_Table_Agent_Contract.md`
- `docs/Research_Progress_and_Integration.md`
- `docs/Python_Requirement_Status.md`
- `docs/MySQL8_Acceptance.md`

---

## 11. 交接结论

当前项目不是“从零开始”，也不是“已经产品化”。

准确状态：

> **单档案业务可用；真实模型可在 H2 demo 跑；受控快照可写 MySQL；但新快照不能执行，患者维度不存在，部署/研究去痕未完成。**

下一步只有两条主线：

1. **W1 动态宿主**：把 L1 真正闭合。
2. **W0 交付卫生**：把当前成果变成可共享基线。

在这两件事完成前，不建议继续堆新页面或新功能。
