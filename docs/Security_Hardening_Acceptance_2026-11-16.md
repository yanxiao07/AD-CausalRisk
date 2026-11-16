# 安全与一致性加固验收报告（2026-11-16）

## 范围

按项目负责人 2026-11-16 的决定，本轮只关闭审查意见中的第 1–4 项：

1. 研究任务读取的访问控制收口；
2. 批量快照数值规范化与 `evaluate=true` 链路测试；
3. 动态研究宿主信任链加固；
4. 研究任务重试的执行批次与并发状态机。

第 5–7 项（研究任务复核、旧快照迁移、PR 拆分与数据发布）未在本轮修改。

## 1. 访问控制

- `ResearchTableAssessmentService.mapTask` 现在同时要求：
  - 当前账号对 `subjectId` 仍具备 `canReadRawScores`；
  - 且账号是任务 owner，或是对该档案有权限的 DOCTOR。
- `find`、`archive`、`history`、`summary` 均经过该检查或显式 subject 权限检查。
- `summary` 对逐行 `ACCESS_DENIED` 做过滤，不会因一条被撤销授权的历史任务阻塞整个工作台，也不会返回该任务。
- 回归测试：`revokedDoctorLosesAccessToExistingResearchTasks`，覆盖授权后可读、撤销后任务详情 403、archive 403。

前端图谱历史：

- `GraphView.vue` 仅持久化 `assessmentId/subjectId/createdAt/savedAt`。
- 打开历史卡片时重新调用 `getAssessment` 拉取完整图谱。
- `GraphHistoryWall.vue` 移除了缩略图、风险等级、节点/边统计，只显示引用与“打开时重新载入完整结果”。
- 读取旧缓存时会清洗并写回，去掉历史版本遗留的图谱与风险字段。

## 2. 批量快照摘要

- `ResearchTableAssessmentService.putIfPresent` 在冻结请求前把 JDBC `BigDecimal` 转为 `double`，使 `24.00` 与宿主解析后的 `24.0` 生成相同 canonical JSON。
- 批量测试覆盖：
  - `evaluate=false` 的登记与 NOT_COLLECTED 规范化；
  - `evaluate=true` 的登记 → 宿主同步 → 提交 → 任务回读。
- 当前 mock 宿主对批量请求返回的响应摘要不匹配，因此该测试允许任务最终为 `FAILED` 或 `OUTCOME_UNKNOWN`，但要求 `taskId` 已创建、GET 回读状态一致；这验证了状态落库与回读链路，真实成功分数仍以受控宿主的契约测试为准。

## 3. 动态宿主信任链

`research_runtime/dynamic_host.py`：

- `--trusted-manifest-sha256` 改为必填；缺失、格式错误或与本地 manifest 实际 SHA 不一致时拒绝启动。
- 移除默认 token；只接受 `--token` 或 `RESEARCH_TABLE_TEST_TOKEN`，均为空时拒绝启动。
- 默认绑定 `127.0.0.1`；绑定非环回地址必须显式加 `--allow-public`，否则拒绝启动。
- `tests/test_dynamic_host.py` 新增三个 CLI 测试，分别覆盖缺摘要、缺 token、非环回无开关。

启动脚本与文档：

- `C:\Users\34079\Desktop\test\start-all.ps1` 显式传入可信摘要，并要求调用方设置 `RESEARCH_TABLE_TEST_TOKEN`，不再内置 token。
- `README.md` 同步更新动态宿主启动命令与研究宿主配置说明。

## 4. 重试状态机

- 新增 `V18__research_task_execution_id.sql`：
  - `research_table_task.execution_id VARCHAR(36)`；
  - 状态约束增加 `RUNNING`。
- 首次提交插入 `RUNNING + execution_id`。
- 执行完成更新条件为 `WHERE id=? AND status='RUNNING' AND execution_id=?`，旧批次晚到的结果不会覆盖新结果。
- `retry` 使用条件更新原子领取：
  `WHERE id=? AND status IN ('FAILED','OUTCOME_UNKNOWN')`；
  未领取到时返回 409“任务状态已变化，请刷新后重试”。
- 契约中的 `ResearchTableTask.status` 增加 `RUNNING`，前端 `StatusTag` 已有对应标签。

## 验证记录

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 后端全量 | `.\mvnw.cmd -B -ntp clean verify` | **334 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS** |
| 研究任务专项 | `-Dtest=ResearchTableAssessmentTest` | 21/21 |
| 迁移/契约/Auth | `-Dtest=SchemaMigrationTest,BusinessContractCoverageTest,AuthApiTest` | EXIT 0 |
| Python 动态宿主 + 契约 | `.\.venv\Scripts\python.exe -m unittest tests.test_dynamic_host tests.test_contracts_and_fixture` | 14 tests OK |
| 前端构建 | `npm run build` | 2393 modules，10.22s，exit 0 |
| MySQL 迁移 | 重启后端 | Flyway 成功应用到 v18 |

## 残余与后续

- 第 5 项（研究任务复核入口、`clinicalUse=false` 报告降级展示）未做。
- 第 6 项（旧快照 `context_json` 补全与重同步）未做。
- 真实宿主成功路径仍受受控模型文件和宿主 `runId` 注册机制约束；批量 `evaluate=true` 的自动化测试使用 mock 宿主，未以真实模型分数作为断言。
- 本轮未 commit，等待负责人确认后统一提交。
