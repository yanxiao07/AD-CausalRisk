# W2：受试者 203 七变量数据模板

- 日期：2026-11-15
- 适用任务：T2.1 数据采集、T2.2 受控登记、T2.3 203 真实结果验收
- 适用对象：受试者 `203`（`DEMO-SMOKE-252019`）
- 文档边界：只定义数据采集与核验口径，不写代码、不导入数据、不修改数据库

---

## 1. 目的与边界

W2 的目标不是“让页面有数”，而是给受试者 203 准备一条**来源可追溯、单位明确、日期可核验**的七变量输入，通过受控登记写入 `research_table_snapshot`，再由 W1 动态研究宿主执行真实七变量逻辑回归。

必须保持的边界：

- 这是研究模型输入，不是临床诊断，也不是个体因果效应。
- 没有来源的值不填、不猜、不用测试快照 #2 的值替代。
- 不把 ADNI 原始数据、患者身份信息、量表扫描件放入 Git。
- 允许“明确标记缺失”的字段；不允许“用 0 或均值冒充缺失”。
- 快照的 `data_scope` 按实际来源标记：人工整理/测试数据只能是 `CONSTRUCTED_VALIDATION`，不能写成真实临床数据。

---

## 2. 当前 203 的已知事实

| 项目 | 当前值 | 说明 |
|---|---|---|
| 受试者 ID | `203` | 本次登记目标 |
| 档案编号 | `DEMO-SMOKE-252019` | 登记时 `request.subjectCode` 必须与之一致 |
| 状态 | `ARCHIVED` | 不影响登记，但需确认授权 |
| dataVersion | `demo-51278b00-1672-4b77-85bd-df6ab50d0674` | 登记时可沿用或使用新的受控版本号 |
| 现有访视 | `V-PROBE-9`（2026-11-13） | MMSE 28、MoCA 27、CDR-SB 0.5 |
| 结构化特征 | `features: []` | 目前没有七变量特征记录 |
| 已有研究快照 | `snapshotId=2` | **测试登记数据**（MMSE 26、CDR-SB 1.5 等），不能作为 W2 真实数据 |
| 研究宿主 | W1 进行中 | 当前手动快照执行仍会 `RUN_ID_INVALID` |

> 关键冲突：现有访视记录是 MMSE 28 / CDR-SB 0.5，而测试快照 #2 是 MMSE 26 / CDR-SB 1.5。W2 必须先确认哪一份是真实来源，不能两份混用。

---

## 3. 七变量字段定义

七个字段必须全部出现在快照中；缺失时保留字段并标记缺失原因，不要删除键。

| 字段 | 中文 | 单位 | 有效范围/步长 | 说明 |
|---|---|---|---|---|
| `age_years` | 年龄 | `years` | 18–120，整数 | 按 `indexDate` 计算；优先用出生日期推导，保留推导依据 |
| `education_years` | 受教育年限 | `years` | 0–30，整数 | 明确统计口径（是否含小学/中专/大学） |
| `mmse` | 简易精神状态检查 | `score` | 0–30，整数 | 需注明量表版本/语言；分数越高越好 |
| `cdr_sb` | CDR 总和（Sum of Boxes） | `score` | 0–18，步长 0.5 | 分数越高越差；需与 CDR global 同次评估 |
| `cdr_global` | CDR 全局评分 | `score` | 0、0.5、1、2、3 | 只能取这五个值 |
| `adas__total13` | ADAS-Cog 13 总分 | `score` | 0–85，整数 | 需注明版本；分数越高越差 |
| `faq__faqtotal` | FAQ 总分 | `score` | 0–30，整数 | 需注明知情者来源；分数越高越差 |

### 3.1 单位与格式

- `unit` 只能是：`years`、`score`。
- 日期统一 `YYYY-MM-DD`。
- 数值不要带单位、不要带百分号、不要用中文全角数字。
- CDR-SB 允许 `.5`；CDR global 只允许 `0`、`0.5`、`1`、`2`、`3`。
- 缺失值用 `null`，同时在 `missingReason` 中说明原因。

---

## 4. 每个字段必须附带的元数据

受控登记接口的简化表单只提交七个数值；但完整研究契约要求每个变量是一个 `Observation`，包含以下字段。W2 采集时必须把这些信息记录在案，登记时优先走完整 `{subjectId, request, model}` 路径，而不是只填七个数字。

| 元数据 | 必填 | 含义 | 示例 |
|---|---|---|---|
| `value` | 是 | 数值或 `null` | `28` |
| `unit` | 是 | 单位 | `score` |
| `measuredDate` | 是 | 实际测量日期 | `2026-11-13` |
| `availableDate` | 是 | 数据在系统中可用的日期 | `2026-11-13` |
| `dateBasis` | 是 | 日期依据 | `MEASUREMENT_DATE` |
| `sourceRef` | 是 | 来源引用（病历号/量表编号/登记表行号） | `MR-203-20261113-MMSE` |
| `qc` | 是 | 质控状态 | `PASS` / `FAIL` / `UNKNOWN` / `NOT_APPLICABLE` |
| `completion` | 是 | 完成度 | `COMPLETE` / `INCOMPLETE` / `UNKNOWN` / `NOT_APPLICABLE` |
| `missingReason` | 是 | 缺失原因；不缺失填 `null` | `NOT_COLLECTED` |
| `scanId` | 是 | 扫描/影像编号；无则 `null` | `null` |
| `evidenceRef` | 是 | 证据文件引用/哈希；无则 `null` | `evidence/203/mmse-20261113.pdf` |

`dateBasis` 取值：

- `MEASUREMENT_DATE`：量表/检查实际执行日期
- `REGISTRY_PROXY`：登记系统日期代替
- `DERIVED_AT_INDEX`：由出生日期等推导（如年龄）
- `UNKNOWN`：无法确认

`missingReason` 取值：

- `null`：不缺失
- `UNKNOWN`：缺失但原因不明
- `NOT_COLLECTED`：本次未采集
- `UNAVAILABLE`：数据存在但当前不可得
- `QC_FAILED`：质控未通过

---

## 5. 203 数据采集表（待填）

> 每个字段一行；“值”为空时必须在缺失原因中说明，不得跳过。

| 字段 | 值 | 单位 | measuredDate | availableDate | dateBasis | sourceRef | qc | completion | missingReason | evidenceRef |
|---|---|---|---|---|---|---|---|---|---|---|
| `age_years` |  | years |  |  |  |  |  |  |  |  |
| `education_years` |  | years |  |  |  |  |  |  |  |  |
| `mmse` |  | score |  |  |  |  |  |  |  |  |
| `cdr_sb` |  | score |  |  |  |  |  |  |  |  |
| `cdr_global` |  | score |  |  |  |  |  |  |  |  |
| `adas__total13` |  | score |  |  |  |  |  |  |  |  |
| `faq__faqtotal` |  | score |  |  |  |  |  |  |  |  |

### 5.1 采集时必须回答的问题

1. **索引日期**：本次快照以哪一天为 `indexDate`？是 2026-11-13 访视日、2026-11-15 登记日，还是其他随访日？
2. **MMSE/CDR-SB 冲突**：采用访视 `V-PROBE-9` 的 MMSE 28 / CDR-SB 0.5，还是另有来源？两份记录的日期和量表版本分别是什么？
3. **年龄**：是否有出生日期？没有出生日期时，年龄的来源是什么？
4. **受教育年限**：统计口径是否包含学前教育、中专、大专、本科、研究生？
5. **ADAS-Cog 13**：是否有同次评估？版本是 ADAS-Cog 13 的哪一版？
6. **FAQ**：知情者是谁？是否本人自评？总分是否已按 0–30 口径换算？
7. **CDR global**：与 CDR-SB 是否同次评估？评分者是谁？
8. **数据权限**：谁授权将这份数据用于研究登记？快照的 `data_scope` 应标记为什么？

---

## 6. 数据来源与证据规则

### 6.1 来源优先级

1. 原始量表/检查记录（有日期、评分者、版本）
2. 医院病历/随访记录（有就诊日期和医生签名）
3. 受控登记表（有录入人、复核人、录入日期）
4. 访谈转述（只能作为线索，不能单独作为 `PASS` 依据）

### 6.2 证据引用

- `sourceRef` 必须能定位到具体记录，例如：`MR-203-20261113`、`SCALE-MMSE-203-20261113`。
- `evidenceRef` 指向受控存储中的文件/哈希，不把原始文件提交到 Git。
- 同一字段有多次测量时，记录全部候选值，并说明为什么选择其中一次作为 `indexDate` 的值。

### 6.3 缺失处理

- 缺失字段保留键，`value=null`，并填写 `qc`、`completion`、`missingReason`。
- 不允许用 0、均值、上一次访视值、测试快照值填充。
- 如果关键字段缺失导致模型 `BLOCKED`，保留 `BLOCKED`，不要为了让结果变成 `COMPLETED` 而补值。

---

## 7. 登记路径与模板

### 7.1 推荐：完整契约登记

用于保留每个变量的日期、来源、质控信息。请求体结构如下。**下面所有数值、日期、哈希缩写和 runId 都是结构占位，不是 203 的真实数据；登记前必须替换为核验后的值。**

```json
{
  "subjectId": 203,
  "request": {
    "contractVersion": "research-table-agent-v1",
    "runId": "run-manual-0000000000000000",
    "modelKey": "trained-seven-logistic",
    "subjectCode": "DEMO-SMOKE-252019",
    "dataVersion": "demo-51278b00-1672-4b77-85bd-df6ab50d0674",
    "indexDate": "YYYY-MM-DD",
    "availabilityPolicy": "RETROSPECTIVE_SNAPSHOT",
    "predictionWindowMonths": 24,
    "features": {
      "age_years": {
        "value": 72,
        "unit": "years",
        "measuredDate": "YYYY-MM-DD",
        "availableDate": "YYYY-MM-DD",
        "dateBasis": "DERIVED_AT_INDEX",
        "sourceRef": "SOURCE-REF",
        "qc": "PASS",
        "completion": "COMPLETE",
        "missingReason": null,
        "scanId": null,
        "evidenceRef": "evidence/203/..."
      }
    }
  },
  "model": {
    "family": "logistic",
    "specification": "seven",
    "role": "FULL_COHORT_SEVEN_VARIABLE_RESEARCH_MODEL",
    "features": [
      "age_years",
      "education_years",
      "mmse",
      "cdr_sb",
      "cdr_global",
      "adas__total13",
      "faq__faqtotal"
    ],
    "predictionPolicy": "raw_uncalibrated_24month_CIF",
    "graphProtocolSha256": null,
    "manifestSha256": "46ad244fe612306cc95a2a457c44ac234361db5ab70184c2ee2226527883a511",
    "artifactSha256": "66cfc25386ef9d90c9a6572ac13f673269426fce277d6604e7d1a355ff0a1fd5",
    "trainingPlanSha256": "47ae229df74070f56165ece9b828a1aebcbf1b316b2005439e3f76fa89b0d54e",
    "runtimeSourceSha256": "f69db2e6…b1450e",
    "softwareLockSha256": "df6ed05f…c77f68",
    "sourceMappingSha256": "2be25c0e…ac488f"
  }
}
```

> 上面的 `runtimeSourceSha256`、`softwareLockSha256`、`sourceMappingSha256` 为缩写，登记时必须使用契约/模型清单中的完整值。

### 7.2 简化：七变量表单

前端“手动登记快照”表单只提交：

```json
{
  "subjectId": 203,
  "indexDate": "YYYY-MM-DD",
  "features": {
    "age_years": 0,
    "education_years": 0,
    "mmse": 0,
    "cdr_sb": 0,
    "cdr_global": 0,
    "adas__total13": 0,
    "faq__faqtotal": 0
  }
}
```

该路径会自动补 `sourceRef=MANUAL-ENTRY`、`qc=PASS`、`dateBasis=MEASUREMENT_DATE`，**不适合需要保留真实来源的 W2 验收**；只适合界面联调。

---

## 8. 登记后的核验清单

- [ ] `subjectId=203`、`subjectCode=DEMO-SMOKE-252019` 一致
- [ ] 七个字段全部存在，缺失字段有明确 `missingReason`
- [ ] `unit` 正确，数值在有效范围内
- [ ] `measuredDate`/`availableDate` 可核验
- [ ] `sourceRef`/`evidenceRef` 可定位
- [ ] `data_scope` 与来源一致（人工整理/测试数据 → `CONSTRUCTED_VALIDATION`）
- [ ] MySQL `research_table_snapshot` 有行，`wire_sha256`/`request_sha256`/`input_sha256`/`context_sha256` 已生成
- [ ] `GET /api/research/table-assessment-tasks/snapshots?subjectId=203` 可见
- [ ] W1 宿主启动后，提交任务返回 `COMPLETED`（不是 `503`/`RUN_ID_INVALID`）
- [ ] 结果包含双 CIF、分组归因、`isCausalEffect=false` 边界说明
- [ ] 刷新后可按 `taskId` 回读同一结果

---

## 9. 禁止事项

- 不伪造年龄、教育年限、量表分数或日期。
- 不把测试快照 #2 的数值当作 203 的真实值。
- 不把 ADNI 原始行、患者姓名/电话/身份证、量表扫描件提交到 Git。
- 不把 `CONSTRUCTED_VALIDATION` 写成真实临床数据。
- 不为了让任务变成 `COMPLETED` 而补值、改模型或改数据范围。
- 不把研究模型输出解释为临床诊断或个体因果效应。

---

## 10. 交付物

W2 完成后应留下：

1. 本模板填写后的 203 数据采集表（受控存储，不入 Git 的部分需说明位置）。
2. 登记接口返回的 `snapshotId` 与 MySQL 行核对记录。
3. W1 宿主执行后的任务结果（双 CIF、归因、哈希）。
4. 一份 `docs/W2_203_Acceptance.md`（验收记录，等待 W1 完成后补）。
