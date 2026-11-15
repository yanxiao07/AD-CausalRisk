# P1 验收报告：快照自动同步与批量评估闭环

- 日期：2026-11-15
- 阶段：P1
- 结论：**通过（P1 完成）**
- 对应提交：`0298352`（自动同步与状态强制）、`cd54b11`（批量闭环与重试）
- 关联文档：`docs/P1_Acceptance_Report.md`、`docs/Project_Handoff_2026-11-15.md`

---

## 1. 里程碑结论

P1 的核心目标是把“受控登记的七变量快照”变成“可以被研究宿主实际执行的冻结快照”，并在 UI 上让研究员清楚看到同步状态、只在同步成功后才允许提交。

本次验收完成了四件事：

1. 登记快照后，Java 自动构造 bundle 并调用 Python 宿主的受控注册接口，落盘后回写 `SYNCED`。
2. 只有 `SYNCED` 快照允许提交；`PENDING`/`FAILED` 在前后端都会被拦截或给出明确提示。
3. 用两条不同风险水平的样本跑出真实模型权重结果（任务 #5、#6 均为 `COMPLETED`）。
4. 快照列表支持同步失败后的“重试”，重试接口幂等，可与宿主已有 bundle 对齐。

因此，**“登记 → 自动同步 → 批量提交 → 真实模型结果”这条链路已经闭合**。

---

## 2. 真实执行数据

数据来源：本机 MySQL `ad_causalrisk`，受试者 `203`（`DEMO-SMOKE-252019`），`data_scope=CONSTRUCTED_VALIDATION`。两条均为**人工登记的七变量样例**，不是真实患者数据，也不构成临床或因果结论。

### 2.1 样本与结果

| 项目 | 快照 #7 / 任务 #5 | 快照 #8 / 任务 #6 |
|---|---|---|
| 受试者 | 203 `DEMO-SMOKE-252019` | 203 `DEMO-SMOKE-252019` |
| 年龄 `age_years` | 72 | 50 |
| 教育年限 `education_years` | 14 | 30 |
| MMSE | 26 | 11 |
| CDR-SB | 1.5 | 11 |
| CDR 全局 | 0.5 | 3 |
| ADAS-Cog13 | 18 | 11 |
| FAQ 总分 | 4 | 11 |
| **AD 24 个月 CIF** | **0.1933752820629015（19.34%）** | **0.6399198957125027（63.99%）** |
| **其他痴呆 24 个月 CIF** | **0.000795670798321653（0.08%）** | **0.36007373475314064（36.01%）** |
| 任务状态 | `COMPLETED` | `COMPLETED` |
| `runId` | `run-manual-75ca0a48a1094176` | `run-manual-a05f3b742efe4453` |
| `requestSha256` | `869faabf02a0eac431fff36d24ca7a2f6314fd386a9ca8aa4b8b5aeafb1664d5` | `997cd0ac0d6d1235db8be6c327dee2e414a65e5442df51e9593b804c0c2d7992` |
| `inputSha256` | `24d0b7f9a80d44dfcfbf82ac90f368f9d0410117e5195065cd9baf749a42c09d` | `3827d0bf1177afbe75823c2eb4792b492903a9636ccc4fe0a1a711e9666a0588` |
| `contextSha256` | `3323cce356d6fdaf979c2ef7e32cfabf6a6f77c14ed182d67e08917f5e3a2c21` | `4d8ecb82e7a4eef36e4092eaa4acd628abc0ba487a13608d2f28d1089cbc96bd` |
| `artifactSha256` | `66cfc25386ef9d90c9a6572ac13f673269426fce277d6604e7d1a355ff0a1fd5` | 同左 |
| `manifestSha256` | `46ad244fe612306cc95a2a457c44ac234361db5ab70184c2ee2226527883a511` | 同左 |
| 解释方法 | `exact-single-reference-group-shapley-v1` | 同左 |
| `isCausalEffect` | `false` | `false` |

### 2.2 补充说明

- 快照 #5 / 任务 #4 使用与 #7 相同的输入，是最早的人工样例；它在 P1 验证中先处于 `PENDING`，随后通过重试接口同步成功，结果同为 19.34% / 0.08%。
- 两条结果使用同一份可信模型清单与同一次 artifact，差异只来自输入特征，说明模型接口确实在按输入计算，而不是固定返回。
- 两条结果都在 `research_table_task.result_json` 中保留了模型身份、`dataScope`、解释分组与重构误差，可由后端 `validateResponse` 复核。

---

## 3. 动态同步机制

### 3.1 登记

`POST /api/research/table-assessment-tasks/snapshots/register`

请求可以是：

```json
{
  "subjectId": 203,
  "indexDate": "2026-11-15",
  "features": {
    "age_years": 72,
    "education_years": 14,
    "mmse": 26,
    "cdr_sb": 1.5,
    "cdr_global": 0.5,
    "adas__total13": 18,
    "faq__faqtotal": 4
  }
}
```

后端行为：

1. 校验研究员/管理员角色与档案原始数据权限。
2. 用服务端 `ResearchModelIdentity` 构造模型身份，不再接受客户端传入的假 model。
3. 规范化七个 `Observation` 的 `unit`、`qc`、`completion`、`sourceRef`、`evidenceRef`，构建 `sourceMappings` 与 `evidence` 上下文。
4. 计算 `requestSha256`、`inputSha256`、`contextSha256`，写入 `research_table_snapshot`，并生成 `bundle_json`。
5. 调用 Python 宿主的 `POST /api/research-table/v1/snapshots`。

### 3.2 宿主注册

Python 动态宿主收到 bundle 后：

1. 校验 Bearer token。
2. 调用 `verify_bundle` 校验 schema、三摘要与模型身份。
3. 校验 manifest、artifact、training plan、software lock。
4. 写入 `runtime/dynamic-snapshots/<runId>.json` 并热加载。
5. 返回 `SYNCED`；同一 runId + 相同摘要重放返回 `idempotent=true`；同一 runId 但内容不同返回 `RUN_ID_CONFLICT`。

### 3.3 状态机

`host_sync_status`：

| 状态 | 含义 | UI |
|---|---|---|
| `PENDING` | 已登记，尚未成功同步（宿主未配置/网络失败） | “待同步”，显示“重试” |
| `SYNCED` | bundle 已落盘宿主，可提交 | “已同步”，允许批量提交 |
| `FAILED` | 同步失败，可重试 | “同步失败”，显示“重试” |

重试接口：

`POST /api/research/table-assessment-tasks/snapshots/{id}/sync`

重试是幂等的：宿主已有同摘要 bundle 时返回 `SYNCED`，不会产生重复任务。

### 3.4 提交前强制

后端在 `submit` 的保留阶段之前检查 `host_sync_status`；非 `SYNCED` 直接返回：

```json
{
  "code": 503,
  "message": "该快照尚未同步到研究宿主，请等待登记完成或联系管理员",
  "errorCode": "RESEARCH_UNAVAILABLE"
}
```

这保证即使绕过 UI，也不会把不可执行的快照送进模型。

---

## 4. UI 拦截与反馈

### 4.1 列表

`frontend/src/components/assessment/ResearchBatchAssessment.vue`：

- 新增“宿主同步”列，显示 `待同步 / 已同步 / 同步失败`。
- 非 `SYNCED` 行显示“重试”链接，调用重试接口并就地更新状态。
- 重试成功提示“快照已同步到研究宿主”。

### 4.2 批量拦截

选中快照后点击“批量评估”时：

```text
所选快照尚未同步到研究宿主，请稍后刷新或重新登记。
```

只要有一个选中项不是 `SYNCED`，抽屉不会打开，因此不会出现“提交后才报 RUN_ID_INVALID”的体验。

### 4.3 登记提示

研究证据页的旧“预注册 runId”灰色提示已替换为：

```text
登记后系统会自动将快照同步到 Python 研究宿主。若同步失败，请在列表中点击重试。
```

---

## 5. 验证记录

| 检查 | 命令 | 结果 |
|---|---|---|
| Java 全量测试 | `.\mvnw.cmd -B -ntp clean verify` | 324 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS（约 2:01） |
| 前端构建 | `npm run build` | 2388 modules，20.89s，exit 0 |
| Python 契约 | `.\.venv\Scripts\python.exe -m unittest tests.test_contracts_and_fixture -v` | 5/5 OK |
| Python 动态宿主 | 同上 `tests.test_dynamic_host` | 6/6 OK |
| API 提交 #7 | `POST /api/research/table-assessment-tasks` | task 5 `COMPLETED` |
| API 提交 #8 | 同上 | task 6 `COMPLETED` |
| PENDING 拦截 | 提交 #5（重试前） | HTTP 503，未创建 task |
| 重试 | `POST /snapshots/5/sync` | 最终 `SYNCED` |

构建与测试的警告仅包括既有的大 chunk 与动态/静态混合导入提示，不影响验收。

---

## 6. 边界与未完成事项

1. **不是真实患者数据**：快照 #7/#8 是人工七变量样例，`data_scope=CONSTRUCTED_VALIDATION`；不能对外表述为真实 203 患者结果。
2. **不是临床结论**：模型输出为 24 个月 CIF，解释为单参考组精确 Shapley，`isCausalEffect=false`，不能用于诊断、治疗或因果推断。
3. **宿主依赖**：动态同步要求 16301 宿主已启动、Java 研究宿主配置已完成；宿主离线时登记会进入 `PENDING`，可通过重试恢复。
4. **真实 203 数据仍缺**：age/education/MMSE/CDR-SB/CDR 全局/ADAS13/FAQ 的完整真实值尚未采集，需进入 P2。
5. **批量任务历史**：当前批量执行仍以抽屉内状态为主，刷新后的历史持久化属于后续 P2/P3。

---

## 7. 阶段标记

- [x] P1 快照受控登记写入 MySQL
- [x] P1 自动同步到 Python 动态宿主
- [x] P1 同步状态列表与失败重试
- [x] P1 批量评估闭环（真实模型权重返回）
- [x] P1 前后端双重未同步拦截
- [x] P1 全量测试与构建通过

**P1（快照自动同步与批量评估闭环）已完成。**
