# P2 数据验证报告：受试者 203 缺失值登记与双原因 24 个月 CIF

| 项目 | 内容 |
| --- | --- |
| 报告日期 | 2026-11-15 |
| 阶段 | P2 真实数据登记与缺失值约束 |
| 受试者 | 203（`DEMO-SMOKE-252019`） |
| 研究快照 | #9 |
| 评估任务 | #7 |
| 结论 | 机制验收 **PASS**；临床有效性 **不成立**，仅用于研究验证 |

## 1. 验收结论

本次验收验证了以下链路：

1. 把受试者 203 的已知真实访视值（MMSE 28、CDR-SB 0.5）录入受控登记接口；
2. 其余五项七变量按 `NOT_COLLECTED` 记录，不补 0、不插均值；
3. 快照自动同步到动态研究宿主；
4. Java 创建任务并调用 Python 七变量逻辑回归；
5. 返回双原因 24 个月 CIF，同时保留缺失值、预测许可与临床使用边界。

验收通过的是**数据登记、同步、任务执行与缺失值约束机制**。本次结果不构成完整七变量预测、临床诊断、校准概率或个人因果效应。

## 2. 受试者与数据来源

| 字段 | 值 |
| --- | --- |
| 受试者 ID | 203 |
| 档案编号 | `DEMO-SMOKE-252019` |
| 档案状态 | `ARCHIVED` |
| 档案数据版本 | `demo-51278b00-1672-4b77-85bd-df6ab50d0674` |
| 唯一真实访视 | `V-PROBE-9` |
| 访视日期 | 2026-11-13 |
| 访视来源 | 现有档案访视记录（MMSE 28、MoCA 27、CDR-SB 0.5） |
| 结构化特征 | 无 `subject_feature` 记录 |

七变量中仅 MMSE 与 CDR-SB 有真实访视值；年龄、受教育年限、CDR 全局、ADAS-Cog13、FAQ 总分均未采集。

## 3. 受控登记内容

### 3.1 登记请求

| 字段 | 值 |
| --- | --- |
| 接口 | `POST /api/research/table-assessment-tasks/snapshots/register` |
| `subjectId` | 203 |
| `indexDate` | 2026-11-13 |
| `dataVersion` | `manual-snapshot-2026-11-13` |
| `runId` | `run-manual-9aa37378789249d4` |
| `dataScope` | `CONSTRUCTED_VALIDATION` |

### 3.2 七变量登记值

| 变量 | 值 | 单位 | QC | Completion | Missing reason |
| --- | ---: | --- | --- | --- | --- |
| `age_years` | null | years | UNKNOWN | INCOMPLETE | `NOT_COLLECTED` |
| `education_years` | null | years | UNKNOWN | INCOMPLETE | `NOT_COLLECTED` |
| `mmse` | 28 | score | PASS | COMPLETE | null |
| `cdr_sb` | 0.5 | score | PASS | COMPLETE | null |
| `cdr_global` | null | score | UNKNOWN | INCOMPLETE | `NOT_COLLECTED` |
| `adas__total13` | null | score | UNKNOWN | INCOMPLETE | `NOT_COLLECTED` |
| `faq__faqtotal` | null | score | UNKNOWN | INCOMPLETE | `NOT_COLLECTED` |

缺失观测的 `measuredDate`、`availableDate`、`scanId` 均为 null，`dateBasis=UNKNOWN`。有值观测的日期均为 2026-11-13，`dateBasis=MEASUREMENT_DATE`。

### 3.3 快照标识

| 字段 | 值 |
| --- | --- |
| 快照 ID | 9 |
| 授权用户 ID | 2（researcher） |
| 宿主同步状态 | `SYNCED` |
| 同步时间 | 2026-11-15 09:49:28 |
| `request_sha256` | `7d73cd86bb3cbc7143346e0dec46314a9889ab6ef857f7ad75e0dff3d65fee79` |
| `input_sha256` | `2258991206ac24639040b0ad7f4ad27a6e2aa98ca07a161451d8beaf49efec79` |
| `context_sha256` | `7cd0c4cc2279403f8f4ec8323bce415a686ad41547c8ac7ba793715a29b712e0` |
| `wire_sha256` | `7d73cd86bb3cbc7143346e0dec46314a9889ab6ef857f7ad75e0dff3d65fee79` |
| Bundle 大小 | 8,403 bytes |

### 3.4 来源映射说明

本次登记的 `sourceRef` 为 `CONSTRUCTED-SRC-*`，`dataset=CONSTRUCTED`。这表示数据通过人工受控登记进入验证通道，**不代表**来自真实 EMR、ADNI 原表或生产数据仓库。

## 4. 宿主同步与任务

| 阶段 | 结果 |
| --- | --- |
| Bundle 生成 | 成功 |
| 宿主注册 | `SYNCED`（首次登记时因目录存在重复 `runId` 文件返回 400，清理重复文件后重试成功） |
| Java 任务 | #7 |
| 任务状态 | `COMPLETED` |
| 任务创建/完成 | 2026-11-15 09:49:32 |
| `error_code` | null |

## 5. 双原因 24 个月 CIF

| 结局 | CIF（小数） | CIF（百分比） |
| --- | ---: | ---: |
| AD 24 个月 | 0.052025946757113264 | **5.20%** |
| 其他痴呆 24 个月 | 0.1782662116032392 | **17.83%** |

参考基线（`SAVED_TABLE_MEDIAN`）：

| 结局 | 参考 CIF |
| --- | ---: |
| AD | 0.051878392421027486 |
| 其他痴呆 | 0.2749765605809505 |

分组贡献（`exact-single-reference-group-shapley-v1`）：

| 变量 | AD 贡献 | 其他痴呆贡献 |
| --- | ---: | ---: |
| `cdr_sb` | +0.0001475543360857784 | −0.0967103489777113 |
| 其余六个变量 | 0 | 0 |

`reconstructionMaxError = 0.0`，说明参考 CIF + 分组贡献可精确重构本次预测。

## 6. 边界条件

| 标记 | 值 | 含义 |
| --- | --- | --- |
| `admission.status` | `METADATA_CONSISTENT` | 元数据/Schema 一致，不代表临床有效 |
| `predictionAllowed` | **false** | **不允许把本次输出当作完整预测使用** |
| `clinicalUse` | **false** | 不可用于临床决策 |
| `individualCausalEffectIdentified` | **false** | 不能解释为个体因果效应 |
| `missingnessEffectsExplained` | **false** | 缺失值对预测的影响未解释 |
| `sourceAuthenticityCertified` | **false** | 来源真实性未认证 |
| `historicalAvailabilityCertified` | **false** | 历史可获得性未认证 |
| `isCausalEffect` | **false** | 归因不是因果效应 |
| `limeStatus` | `NOT_EVALUATED_NO_LOCAL_FIT` | 未做 LIME 局部拟合 |

缺失计数：`age_years`、`education_years`、`cdr_global`、`adas__total13`、`faq__faqtotal` 各 1；`mmse`、`cdr_sb` 为 0；`blockedRecordCount=0`。

## 7. 模型身份

| 字段 | 值 |
| --- | --- |
| `modelKey` | `trained-seven-logistic` |
| family / specification | `logistic` / `seven` |
| predictionPolicy | `raw_uncalibrated_24month_CIF` |
| `manifestSha256` | `46ad244fe612306cc95a2a457c44ac234361db5ab70184c2ee2226527883a511` |
| `artifactSha256` | `66cfc25386ef9d90c9a6572ac13f673269426fce277d6604e7d1a355ff0a1fd5` |
| `trainingPlanSha256` | `47ae229df74070f56165ece9b828a1aebcbf1b316b2005439e3f76fa89b0d54e` |
| `runtimeSourceSha256` | `f69db2e64f0810d6abb18516e3e845067f69cad9c8422c4c09be5e4329b1450e` |
| `softwareLockSha256` | `df6ed05f512953046fda442f5d553deee8c314db1496eb18ae43f455f5c77f68` |
| `sourceMappingSha256` | `2be25c0e3176806765a4350185191640144a2370d6951d62c66a4fdcdfac488f` |

## 8. 复现步骤

```powershell
# 1. 登录 researcher
curl.exe -c cookie.txt -H "Content-Type: application/json" `
  -d '{"username":"researcher","password":"DemoPass!2026"}' `
  http://127.0.0.1:8080/api/auth/login

# 2. 登记快照（indexDate + 七变量，未知项不传）
curl.exe -b cookie.txt -H "Content-Type: application/json" `
  -d '{"subjectId":203,"indexDate":"2026-11-13","features":{"mmse":28,"cdr_sb":0.5}}' `
  http://127.0.0.1:8080/api/research/table-assessment-tasks/snapshots/register

# 3. 提交评估（使用登记返回的 snapshotId）
curl.exe -b cookie.txt -H "Content-Type: application/json" `
  -H "Idempotency-Key: p2-203-demo" `
  -d '{"snapshotId":9}' `
  http://127.0.0.1:8080/api/research/table-assessment-tasks

# 4. 回读任务
curl.exe -b cookie.txt `
  http://127.0.0.1:8080/api/research/table-assessment-tasks/7
```

验收时必须同时检查：

- `status=COMPLETED`；
- `predictionAllowed=false`；
- `clinicalUse=false`；
- `missingFeatureCounts` 为 5 个缺失、2 个已观测；
- `requestSha256`、`inputSha256`、`trustedContextSha256` 与快照登记值一致。

## 9. 限制与改进项

1. **不构成完整七变量预测**：五个变量缺失，`predictionAllowed=false`。CIF 仅用于验证模型调用、解释重构与缺失值链路。
2. **不构成临床证据**：`clinicalUse=false`、`individualCausalEffectIdentified=false`，不能用于诊断、用药或个体因果判断。
3. **来源映射为构造映射**：`CONSTRUCTED-SRC-*` 仅表示受控验证通道，不代表真实数据源。
4. **数据版本未继承档案**：本次请求的 `dataVersion=manual-snapshot-2026-11-13`，与档案 `demo-51278b00-…` 不同。建议后续登记默认继承受试者档案 `data_version`，避免快照与档案版本脱钩（改进项 D-1，非本次阻塞）。
5. **缺失值不插补**：系统不会用均值、0 或模型预测填补缺失项；需要完整七变量时应补采真实数据后重新登记。

## 10. 验收判定

| 项目 | 判定 |
| --- | --- |
| 真实访视值登记 | PASS |
| 缺失项 `NOT_COLLECTED` 约束 | PASS |
| 快照自动同步 | PASS（重试后 `SYNCED`） |
| 任务执行与双 CIF 返回 | PASS |
| 解释重构一致性 | PASS（`reconstructionMaxError=0.0`） |
| 完整七变量预测适用性 | **FAIL / 不适用**（5 项缺失） |
| 临床使用适用性 | **FAIL / 不允许**（`clinicalUse=false`） |

**总判定**：P2 数据登记与缺失值验证机制通过；临床有效性与完整七变量预测不通过，保持边界声明。
