# AD-CausalRisk 子 Agent 任务板

- 配套文档：`docs/Project_Handoff_2026-11-15.md`
- 用途：把项目拆成可独立交给子 Agent 的任务卡
- 原则：**一个会话只做一张任务卡；共享文件串行；没有授权不 commit/push**

---

## 1. 任务总表

| ID | 任务 | 优先级 | 依赖 | 并行组 | 主要产物 |
|---|---|---|---|---|---|
| T0.1 | 提交受控登记改动 | P0 | 无 | A（先做） | commit |
| T0.2 | 推送分支/PR/CI | P0 | T0.1 | A | PR + 绿 CI |
| T0.3 | 更新交接基线 | P1 | T0.1 | A | 文档 |
| T1.1 | 动态宿主方案设计 | P0 | T0.1 | B | 设计说明 |
| T1.2 | 实现动态宿主 | P0 | T1.1 | B | Python 代码 |
| T1.3 | 后端联调 + 快照 #2 验证 | P0 | T1.2 | B | 真实 COMPLETED |
| T1.4 | 宿主回归测试/文档 | P1 | T1.3 | B | 测试 + 文档 |
| T2.1 | 203 七变量数据采集 | P1 | T1.1 | C | 数据清单 |
| T2.2 | 203 数据导入/登记 | P1 | T2.1 | C | 快照 |
| T2.3 | 203 真实结果验收 | P1 | T1.3/T2.2 | C | 验收记录 |
| T3.1 | 患者主索引 schema | P1 | T0.1 | D（远期） | V15 迁移 |
| T3.2 | 回填与兼容 | P1 | T3.1 | D | 迁移脚本 |
| T3.3 | 患者 APIs | P1 | T3.1 | D | 接口 |
| T3.4 | 患者中心前端 | P1 | T3.3 | D | 页面 |
| T3.5 | 权限/迁移测试 | P1 | T3.4 | D | 测试 |
| T4.1 | 安全策略接口 | P2 | T0.1 | E | 接口/页面 |
| T4.2 | 演示数据重置 | P2 | T0.1 | E | 接口/审计 |
| T4.3 | 宿主测试连接 | P2 | T1.3 | E | 按钮/接口 |
| T4.4 | 保留策略落地 | P2 | T0.1 | E | 清理测试 |
| T5.1 | 批量任务持久化/历史 | P2 | T0.1 | F | 列表/回读 |
| T5.2 | 错误态/角色引导 | P2 | T0.1 | F | UI |
| T5.3 | 患者中心 UI | P2 | T3.4 | F | 页面 |
| T5.4 | 前端 E2E | P2 | T5.1 | F | 脚本 |
| T6.1 | 课程文档审计 | P2 | 管理员决策 | G | 清单 |
| T6.2 | 归档/清理引用 | P2 | T6.1 | G | 文档 |
| T6.3 | 用户文案/标签 | P2 | T6.1 | G | 文案 |
| T7.1 | Compose/部署 | P2 | T0.1 | H | 部署文件 |
| T7.2 | 密钥/备份 | P2 | T7.1 | H | 脚本/文档 |
| T7.3 | CI/E2E | P2 | T0.1 | H | workflow |
| T8.1 | 验收矩阵 | P1 | T1/T2/T3 | I（最后） | 验收表 |
| T8.2 | 发布清单 | P2 | T8.1 | I | 发布说明 |

---

## 2. 第一批可立即开的会话

### 会话 1：W0 交付卫生（必须先做）

任务：T0.1 + T0.2 + T0.3

原因：当前 10 个文件未提交，任何并行开发都会混入未提交改动。

提示词：

```text
你是 AD-CausalRisk 的交付卫生子 agent，负责任务 T0.1-T0.3。

先读：
- docs/Project_Handoff_2026-11-15.md
- docs/Subagent_Task_Board.md 的 T0.1-T0.3
- AGENTS.md

工作目录：C:\Users\34079\Desktop\AD-CausalRisk
分支：feat/backend-integration

目标：
1. 检查当前 10 个未提交文件的 diff，确认只包含受控快照登记。
2. 运行后端 clean verify、前端 npm run build、Python 契约测试。
3. 汇总结果，等待负责人明确授权后 commit。
4. 授权后 commit；如需 push/PR，必须再次获得明确授权。
5. 更新交接文档中的 HEAD/状态。

不要：
- 不覆盖 .tmp/evidence 之外的未跟踪文件。
- 不改业务逻辑。
- 不 force push。

完成后报告：任务、commit、改动文件、验证结果、风险、下一步。
```

### 会话 2：W1 动态研究宿主（核心）

任务：T1.1 + T1.2 + T1.3

依赖：T0.1 完成。

提示词见第 5 节 W1。

### 会话 3：W3 患者主索引设计（可先只出设计）

任务：T3.1 设计

依赖：T0.1。

用户已同意做，但列为远期；本轮只做设计/迁移草案，不改生产数据。

### 会话 4：W4/W6/W7 可并行

- W4：系统控制台二期
- W6：课程文档审计（先出清单，等管理员决策）
- W7：部署/CI

---

## 3. P0 任务卡

### T0.1 提交受控登记改动

- 目标：把 `snapshots/register`、七变量表单、契约、测试、README 变成一个 commit。
- 涉及：
  - `backend/README.md`
  - `backend/src/main/java/com/adcausalrisk/backend/research/ResearchTableAssessmentController.java`
  - `.../ResearchTableAssessmentService.java`
  - `.../ResearchTableProtocol.java`
  - `backend/src/test/java/com/adcausalrisk/backend/research/ResearchTableAssessmentTest.java`
  - `contracts/java-business.target.openapi.json`
  - `frontend/src/api/research.js`
  - `frontend/src/components/assessment/ResearchBatchAssessment.vue`
  - `frontend/src/views/researcher/ResearchEvidence.vue`
  - `tests/test_contracts_and_fixture.py`
- 验收：
  - `clean verify` 323/0
  - `npm run build` 成功
  - Python `test_contracts_and_fixture` 5/5
  - `git diff --check` 干净
  - commit message 清晰
- 注意：commit 需负责人明确授权。

### T0.2 推送/PR/CI

- 目标：让分支可共享。
- 验收：
  - push 成功
  - PR 描述包含改动/验证/风险
  - Java/Python/前端 CI 绿
- 注意：push/PR 需明确授权。

### T0.3 更新交接基线

- 更新 `docs/Project_Handoff_2026-11-15.md`：
  - HEAD
  - commit
  - 未提交文件
  - CI 状态
- 验收：文档与 `git log/status` 一致。

### T1.1 动态宿主方案设计

- 目标：确定方案 A（动态 agent）或 B（宿主注册接口）。
- 必须回答：
  - runId 如何生成/绑定？
  - trusted context 从哪里来？
  - request/input/context SHA 如何与后端一致？
  - 失败如何返回？
  - 如何防止客户端伪造 context？
- 产物：`docs/Research_Dynamic_Host_Design.md`
- 验收：负责人 + Python 负责人确认。

### T1.2 实现动态宿主

- 目标：`run-manual-*` 也能执行。
- 涉及：
  - `research_runtime/examples/research_table_http.py`
  - `research_runtime/research_table_agent.py`
  - `research_runtime/inference.py`
  - 测试
- 验收：
  - 新 runId 不再 `RUN_ID_INVALID`
  - 快照 #2 可 COMPLETED
  - 真实模型哈希正确
- 注意：不能把构造样例结果返回给新请求。

### T1.3 后端联调 + 快照 #2 验证

- 目标：从 MySQL 取快照 #2 → 提交 → 结果落库。
- 验收：
  - `POST /api/research/table-assessment-tasks` 返回 COMPLETED
  - `research_table_task` 有行
  - 页面显示双 CIF + 归因
  - 刷新可回读
- 产物：验收记录 + 截图。

### T1.4 宿主回归测试/文档

- 增加动态 runId 测试。
- 更新 `docs/Research_Table_Agent_Contract.md`、`backend/README.md`。
- 验收：`clean verify` 全绿。

---

## 4. P1 任务卡

### T2.1 203 七变量数据采集

- 字段：age_years、education_years、mmse、cdr_sb、cdr_global、adas__total13、faq__faqtotal。
- 每个字段记录：值、单位、measuredDate、availableDate、来源。
- 验收：数据清单经负责人确认。

### T2.2 203 数据导入/登记

- 通过受控登记接口写快照。
- 验收：MySQL 有行，`data_scope=CONSTRUCTED_VALIDATION`，摘要正确。

### T2.3 203 真实结果验收

- 提交并记录结果。
- 验收：COMPLETED、双 CIF、归因、免责声明。

### T3.1 患者主索引 schema

- 新增 `patient` 表、`subject.patient_id`。
- additive migration；不破坏旧数据。
- 验收：迁移在 H2/MySQL 均通过。

### T3.3 患者 APIs

- `GET /api/patients`、`GET /api/patients/{id}`。
- 权限：患者本人；医生授权；研究员/管理员按范围。
- 验收：越权 403。

### T3.4 患者中心前端

- 路由 `/patients`、`/patients/:id`。
- 合并时间线、档案列表、主治医生。
- 验收：一个患者 2 个 subject 可见合并。

### T8.1 验收矩阵

- 四角色 × 核心流程。
- 验收：每项有命令/截图/结果。

---

## 5. 子 Agent 提示词

### W1 动态研究宿主

```text
你是 AD-CausalRisk 的 W1 子 agent，负责 T1.1-T1.3：让新登记的受控快照真正执行。

先读：
- docs/Project_Handoff_2026-11-15.md
- docs/Subagent_Task_Board.md 的 T1.1-T1.3
- docs/Research_Table_Agent_Contract.md
- research_runtime/examples/research_table_http.py
- research_runtime/research_table_agent.py
- backend/src/main/java/com/adcausalrisk/backend/research/ResearchTableAssessmentService.java

背景：
- MySQL 已有 snapshotId=2（subject 203，run-manual-*）。
- 当前宿主只接受预注册 runId，提交会 RUN_ID_INVALID。
- 真实模型文件在 C:\controlled-model。

目标：
1. 设计动态 runId/context 方案，写 docs/Research_Dynamic_Host_Design.md。
2. 实现宿主动态执行；不能伪造构造样例结果。
3. 用快照 #2 跑通真实 COMPLETED，核对 request/input/context 摘要。
4. 增加测试，更新契约/README。

约束：
- 不 commit/push。
- 不改前端 UI，除非联调必须。
- 不降低测试标准。
- 保持研究模型/非临床/非因果边界。

验收：
- 快照 #2 返回 COMPLETED。
- 无 503 / RUN_ID_INVALID。
- 结果哈希与数据库一致。
- clean verify 全绿。

完成后报告：任务、分支、改动、验证、风险、下一步。
```

### W2 203 数据

```text
你是 AD-CausalRisk 的 W2 子 agent，负责 T2.1-T2.3：给 203 准备七变量受控快照。

先读交接文档和任务板 T2.1-T2.3。

数据字段：age_years、education_years、mmse、cdr_sb、cdr_global、adas__total13、faq__faqtotal。
每个字段记录值/单位/日期/来源。

步骤：
1. 通过现有接口登记快照（POST /api/research/table-assessment-tasks/snapshots/register）。
2. 核对 MySQL 行和摘要。
3. 提交任务，记录结果。
4. 不把人工样例说成真实临床数据。

约束：不 commit/push；不改后端逻辑；缺数据就列缺口，不猜。

完成后报告：数据来源、快照 ID、提交结果、缺口。
```

### W3 患者主索引

```text
你是 AD-CausalRisk 的 W3 子 agent，负责 T3.1-T3.5：患者主索引与跨时段档案合并。

先读交接文档、Frontend_Productization_Plan 的 3.3、任务板 T3。

目标：
1. 新增 patient 表与 subject.patient_id（additive migration）。
2. 回填演示数据；旧接口兼容。
3. 新增 GET /api/patients、GET /api/patients/{id}。
4. 前端患者中心/合并时间线。
5. 权限与迁移测试。

约束：
- 不动已有评估/报告数据。
- 不破坏 /subjects 旧路径。
- 不 commit/push。
- 先出迁移草案，确认后再改库。

验收：一个患者 2 个 subject，合并时间线可见；越权 403；测试全绿。
```

### W4 系统控制台二期

```text
你是 AD-CausalRisk 的 W4 子 agent，负责 T4.1-T4.4。

目标：
1. 安全策略 GET/PUT（若产品确认）。
2. 演示数据重置（二次确认 + 审计）。
3. 研究宿主“测试连接”。
4. 保留策略落地测试。

约束：所有写操作审计；非 ADMIN 403；不造假开关。

验收：接口 + 页面 + 测试；clean verify 全绿。
```

### W5 批量任务体验

```text
你是 AD-CausalRisk 的 W5 子 agent，负责 T5.1-T5.4。

目标：
1. 批量任务持久化/历史（刷新不丢）。
2. 503/RUN_ID_INVALID/FAILED/BLOCKED 明确文案。
3. 医生端权限引导。
4. 研究结果免责声明。
5. E2E。

约束：不改后端研究协议；不 commit/push。

验收：刷新后任务仍在；失败态有下一步；无 pageerror。
```

### W6 课程文档

```text
你是 AD-CausalRisk 的 W6 子 agent，负责 T6.1-T6.3。

第一步只做审计清单，不删文件：
- docs/Course_SRS_V3_2.md
- docs/AI_Collaboration.md
- docs/Frontend_Feature_Guide.md
- docs/Python_Course_Reports*
- README/AGENTS/CONTRIBUTING/V1 SQL 注释
- 用户可见“课程演示”文案

输出：文件、引用位置、建议（保留/归档/删除）、风险。
等管理员决策后再执行清理。

约束：不 commit/push；不删历史实验记录。
```

### W7 部署与 CI

```text
你是 AD-CausalRisk 的 W7 子 agent，负责 T7.1-T7.3。

目标：
1. Docker Compose（MySQL/Java/Python 可选）。
2. .env.example、密钥不入库。
3. MySQL 备份/恢复脚本。
4. 前端 build + 关键 E2E 的 CI。
5. 健康检查与日志。

约束：不改业务逻辑；不 commit/push；部署文件放 docs/deploy 或根目录。

验收：全新环境可按 README 启动；备份可恢复。
```

---

## 6. 任务完成定义（DoD）

每个任务必须满足：

- 代码/文档改动范围与任务卡一致。
- 运行了任务卡要求的命令，并记录结果。
- 没有新增未跟踪临时文件。
- 没有降低测试/契约标准。
- 失败/限制写清楚，不隐藏。
- 报告包含：任务、分支/commit、改动文件、验证、风险、下一步。

---

## 7. 建议的会话顺序

1. **W0**：提交当前受控登记，push/PR/CI。
2. **W1**：动态宿主设计与实现（核心）。
3. **W2**：203 七变量数据 + 真实结果。
4. **W3**：患者主索引（设计 → 迁移 → 前端）。
5. **W4/W5/W6/W7**：并行。
6. **W8**：验收与发布。

> 在 W0 和 W1 完成前，不建议开新的 UI 功能会话。
