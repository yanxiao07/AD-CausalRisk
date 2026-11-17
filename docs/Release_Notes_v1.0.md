# Release Notes v1.0

**基线日期**：2026-11-15  
**分支**：`feat/backend-integration`  
**范围**：W0–P3 交付基线

本版本完成从单档案演示到「登记—同步—批量评估—持久历史」研究闭环的收口。真实七变量模型已在受控环境中跑通，但结果仍属于研究验证输出，不构成临床诊断、个人因果效应或生产部署验收。

## 里程碑

### W0 交付卫生

- 统一工作区与提交边界，清理临时脚本、证据目录与调试产物。
- `.gitignore` 纳入 `.tmp/`、运行时快照目录与本地模型文件。
- CI 补齐研究运行时依赖，Python 3.11/3.12 工作流恢复通过。

### W1 动态研究宿主

- 新增 `research_runtime/dynamic_snapshot.py`：定义 `research-dynamic-snapshot-v1` bundle，校验 request/model/context 与三摘要。
- 新增 `research_runtime/dynamic_host.py`：按 `runId` 动态解析快照，支持目录热加载与重复 `runId` 冲突检测。
- 新增受控注册接口 `POST /api/research-table/v1/snapshots`，Bearer 校验、`verify_bundle`、幂等写入。
- 解决 Java 与 Python 的 canonical JSON 摘要一致性问题；Java 端按真实 `Model_Manifest.json` 构造模型身份，拆分模型运行时摘要与宿主代码摘要。

### P0 角色化开户与批量评估入口

- 「新建账号」改为三步向导：基本信息 → 权限与绑定 → 确认提交。
- `PATIENT` 强制绑定受试者档案与主治医生；创建后自动授予患者 `SUMMARY_READ`、医生 `VIEW`/`MANAGE`。
- 研究证据页支持快照多选与批量评估抽屉，展示提交进度、成功/阻断/失败状态。

### P1 快照自动同步与批量评估闭环

- V16 为 `research_table_snapshot` 增加 `bundle_json`、`host_sync_status`、`host_synced_at`。
- Java 登记成功后自动构造 bundle 并调用 Python 宿主注册；状态机为 `PENDING`/`SYNCED`/`FAILED`。
- 未同步快照提交时返回 `RESEARCH_UNAVAILABLE`，前端给出准确提示并阻止批量提交。
- 列表新增「宿主同步」列与失败重试入口。
- Java 回归测试通过 Python `bundle_from_parts` 验证登记产出的 bundle。

### P2 真实缺失值登记

- 受控登记表单允许留空；缺失变量按 `NOT_COLLECTED` 写入 Observation，不补 0、不插均值。
- 以受试者 203 的真实访视（MMSE 28、CDR-SB 0.5）登记快照 #9，其余五项标记缺失。
- 双原因 24 个月 CIF：AD **5.20%**、其他痴呆 **17.83%**。
- 结果标记 `predictionAllowed=false`、`clinicalUse=false`、`missingnessEffectsExplained=false`，明确不能当作完整七变量临床预测。

### P3 持久历史、同步筛选与失败重试

- 新增 `GET /api/research/table-assessment-tasks/history?subjectId=`，按任务时间倒序返回最近 50 条。
- 新增 `POST /api/research/table-assessment-tasks/{id}/retry`，仅允许 `FAILED`/`OUTCOME_UNKNOWN` 重试。
- 前端增加同步状态筛选、任务历史表与失败任务重试；刷新页面后历史从后端重建。
- OpenAPI 操作数在 P3 时增至 83、路径 71；J1 后为 85 个操作、73 条路径。

### P5 产品化文案

- 根 README 重写为产品化 Quick Start，覆盖三端启动、演示账号与验证命令。
- 清理 docs/ 与前端 UI 的历史项目表述，统一为“医疗科研协作平台/受试者研究队列/模型验证环境”。
- 保留历史文档文件名以兼容链接；保留 `SYNTHETIC_DEMO`、`CONSTRUCTED_VALIDATION` 数据标签与历史实验记录。
- 新增本发布说明作为 v1.0 交付基线。

### J1–J4 交付加固

- J1：新增研究验证报告服务端导出（PDF/HTML/JSON），按任务读权限授权并写审计；前端报告页改为下载后端附件。
- J2：新增 `GET /api/research/table-assessment-tasks/metrics`，管理员大屏与医生工作台按独立研究口径展示任务/复核/同步/耗时，不与旧规则混算。
- J3：V20 为 `research_table_task` 增加 `executor_id`、`lease_expires_at` 与租约索引，原子领取、过期恢复和多实例防重复执行；并发/过期测试覆盖。
- J4：启用 Cookie 会话 CSRF，登录豁免，`GET /api/auth/csrf` 发放 token，前端 axios 对写请求自动附加 `X-CSRF-TOKEN`。
- OpenAPI 增至 85 个操作、73 条路径；V1–V20 已在隔离 MySQL 8.0.46 完成 fresh 迁移和 V12→V20 升级。

## 真实七变量闭环

以下结果来自受控七变量模型与动态宿主，属于构造/人工样例验证，不是临床概率：

| 快照 | 变量概况 | AD 24m CIF | 其他痴呆 24m CIF | 任务 |
| --- | --- | --- | --- | --- |
| #7 | 72/14/26/1.5/0.5/18/4 | 19.34% | 0.08% | COMPLETED |
| #8 | 50/30/11/11/3/11/11 | 63.99% | 36.01% | COMPLETED |
| #9 | MMSE 28、CDR-SB 0.5，其余 5 项 NOT_COLLECTED | 5.20% | 17.83% | COMPLETED |

共同标记：

- `dataScope = CONSTRUCTED_VALIDATION`
- `artifactSha256 = 66cfc253…`
- `isCausalEffect = false`
- 解释方法 `exact-single-reference-group-shapley-v1`
- 快照 #9：`predictionAllowed=false`、`clinicalUse=false`

## 验证记录

- 后端：`.\mvnw.cmd -B -ntp clean verify` → 351 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS。
- 前端：`npm run build` → 2393 modules，构建成功。
- Python：`tests.test_contracts_and_fixture` + `tests.test_lock_digest` 7/7；`tests.test_dynamic_host` 6/6。
- CI：Python research primitives、Java backend、Python service 全部通过。

## 交付物

- 受控快照登记接口与七变量表单。
- 动态研究宿主与 bundle 注册协议。
- 快照同步状态机、批量评估抽屉、任务历史与重试。
- 系统控制台：研究宿主、评估通道、审计保留策略。
- 文档：P1 验收报告、P2 数据模板、产品化规划、发布说明。

## 边界与后续

- 不把研究模型输出表述为临床诊断、校准概率或个人因果效应。
- 缺失值不补造；`BLOCKED` 不生成替代分数。
- 患者主索引（跨 subject 合并）为远期规划，本版本不修改底层数据模型。
- 真实患者数据接入、生产部署、备份恢复/任务耐久性与完整临床验证仍需单独验收。
- 模型文件与受控数据不进入 Git；部署时由模型保管方提供 manifest、artifact 与可信 SHA-256。
