# 七/十变量模型：Python接入候选

状态：Python内部候选，供调用方评审。本文不新增HTTP接口、共享DTO、生产适配器或模型部署。

本任务支撑第1→3→4环节。现有七/十变量模型具备离线合成验收、输入声明校验与单次内存推理能力，业务8091仍执行课程规则。缺口是调用方尚不能直接把旧请求与旧返回套到新模型。候选复用现有API，终点为字段差异、调用责任和人工样例；不重新实现计算或宣称已接通真实模型/Java。

## 四个入口分别复用什么

| 入口 | 已实现用途 | 与本候选的关系 |
| --- | --- | --- |
| 8091 / `deepagent-v1` | DEMO课程请求、加性排序、runId内存缓存、四阶段输出 | 保持现状；字段、模型版本、分数含义不能静默替换 |
| `contracts/research-runtime.openapi.json` | `adni-v8-http-v1` / `adni-v8-research-v1`，旧V8本机合成图请求 | 已有专用通道，不是新七/十变量模型的HTTP契约 |
| `python -m research_runtime` | `research-model-smoke-v1`，显式可信清单和最终参数的离线合成验收 | 复用严格JSON/最终模型加载语义；本候选不运行参数加载 |
| `input_admission` + `inmemory_inference` | `research-real-input-admission-v1`输入与独立上下文、单次内存许可/回调 | 作为候选的直接调用基础；已有请求级调度，无需先造新控制器 |

来源：[原离线入口](../research_runtime/inference.py)、[输入准入](Real_Input_Admission.md)、[单次内存推理](InMemory_Research_Inference.md)、[现有业务集成](Integration_Contract.md)。

## 支持范围：九个离线组合与六个表格准入组合

| 输入方案 | 离线CLI的LR/RF/XGBoost | 现有内存准入的LR/RF/XGBoost | 本候选 |
| --- | --- | --- | --- |
| `seven` | 支持 | 支持 | 纳入 |
| `multimodal_values` | 支持 | 支持 | 纳入 |
| `modality_availability` | 支持 | 不支持，`MODEL_SPECIFICATION` | 明确拒绝，不伪装为multimodal_values |

算法标识为`logistic`、`random_forest`、`xgboost`。九种离线“算法×方案”组合不等于九个模型都能进入当前内存接口。可获得性对照会将新增变量的已观测值置零并保留原缺失，不能通过重命名方案或临时改输入绕过准入。

内存准入另接受十变量`prior_graph`最终角色，但它不在本次六个表格候选中；图臂、角色和图协议摘要仍按原规格，不将其他图臂或表格模型改名接入。

## 请求差异与禁止隐式转换的项目

| 项目 | 当前课程请求/输出 | 新模型候选沿用的已有规则 |
| --- | --- | --- |
| 请求结构 | `visits`及枚举`features`、`contractVersion`/`modelVersion` | 冻结索引下的`records[].features`、`schemaVersion`及完整`model`；不是旧JSON重命名 |
| 七变量 | MMSE/MoCA/CDR-SB访视 | age_years、education_years、mmse、cdr_sb、cdr_global、adas__total13、faq__faqtotal；MoCA不自动替代缺失变量 |
| 十变量新增 | APOE及演示海马值 | apoe4_count和左右海马mm3体积；保留原缺失 |
| 单位 | 演示MRI为`normalized` | MRI严格`mm3`且正值；没有自动换算，`normalized`报FEATURE_UNIT |
| APOE | count | 严格JSON整数0/1/2；1.0也拒绝，不舍入 |
| 索引与时间 | 访视/记录日期及课程融合规则 | 明确indexDate、measuredDate、availableDate、dateBasis；来源首次可用未知保留未知 |
| 时间政策 | 课程索引选择 | RETROSPECTIVE_SNAPSHOT与AS_OF_INDEX必须与独立上下文相同，来件不能自行降级 |
| 元数据 | 课程DEMO来源 | 每项观测/缺失的sourceRef、qc、completion、missingReason、scanId、evidenceRef及独立来源映射 |
| 窗口 | 请求允许6—60整数，当前演示非24月返回BLOCKED | 准入只接受严格整数24，其他值报ONLY_24_MONTH_WINDOW；不套课程200/BLOCKED语义 |
| 模型选择 | 固定课程modelVersion | 显式family/specification/最终角色/有序特征和绑定摘要；不自动选模或接受外折角色 |
| 输出 | riskScore/riskLevel及课程贡献、阶段 | 两原因ad_cif_24m / other_dementia_cif_24m，和不超过原容差上界；不补riskLevel或临床阈值 |
| 输出策略 | 课程固定加性规则 | 固定raw_uncalibrated_24month_CIF；不因bundle内有校准参数自动应用校准 |
| 幂等 | Python runId易失缓存，Java业务幂等另管 | 同一内存permit只可用一次，无跨进程重放/缓存；不能冒充Java Idempotency-Key |
| HTTP错误 | 冻结课程状态码和错误结构 | AdmissionError/InferenceError为现有Python固定码；本候选不分配HTTP状态码或套Java Envelope |

七变量精确覆盖前七项、十变量精确覆盖全部十项。值可为有限数值或null；null须带原规定的缺失声明和证据，不能补零。MRI双侧需同扫描、日期和处理版本，维持365天窗口。量表临床界值、ICV/扫描仪支持及源字段映射不能由这些格式检查认证。

## 调用责任与复用顺序

1. 受控宿主独立固定模型身份、来源映射和逐条证据，按原规则准备`trusted_context`。它是宿主参数，不是未来浏览器请求中的“可信”字段；实际建立和授权尚未在本候选完成。
2. 以`validate_json(raw, trusted_context=...)`或`validate_snapshot`核声明；它不读数据或参数，不预测，返回METADATA_CONSISTENT仍有predictionAllowed=false。
3. 直接调用已有`run_in_memory`。元数据阻断时返回BLOCKED、predictions=null、零回调；相容但无permit时返回PERMISSION_REQUIRED、零回调。
4. 只有宿主已独立核验模型和执行权限时，才能用原严格加载器/身份验证、`bind_existing_model`、`authorize_once`和`run_in_memory`完成真实模型路径。许可绑定完整快照和上下文，来件不能通过authorization_granted=true获得执行权。
5. 宿主负责获授权的结果消费；Java继续负责用户/对象权限、任务、持久化、审计和幂等。Python许可不替代这些业务能力。失败不返回部分预测或自动重试。

本次人工样例仅在步骤4使用`BoundPredictor`固定回调返回0.20/0.05，没有调用`bind_existing_model`、原predict_cif、归档解码或任何拟合。它证明既有API的返回/拒绝边界，不能当作LR/RF/XGBoost六个真实模型已运行，也不能把这两个数当研究结果。

### 模型身份不能仅从旧加载器返回值复制

[P2来源映射](Research_Model_Identity_Map.md)已将12个模型字段和7个宿主上下文字段逐项分为直接返回、已核模型解释和独立证据；直接复用旧identity作model的路径因字段不足收口，不新增代填证据的适配层。

`load_final_model`的身份返回只有manifestSha256、artifactSha256、trainingPlanSha256和artifact文件名。内存准入还要求family/specification/role/features、runtimeSourceSha256、softwareLockSha256、sourceMappingSha256、graphProtocolSha256和predictionPolicy；这些额外绑定不能靠填假摘要、从请求复制或现场散列未知文件来补齐。

宿主需要独立定义可信的源码/锁/映射摘要范围并核验它们与实际模型一致；状态指纹须覆盖真实参数和预处理。旧加载器本身不核来源映射、逐条证据和这些全部绑定，不能只把返回字典改键后宣称已满足新准入。

摘要对象也须明确：原受控文件字节、归档还是指定规范内容不能混用。例如Git文本检出可能转换换行，工作树字节摘要不自动等于Git规范对象摘要；本候选不修改锁文件、原摘要或校验规则来消除这种差异。

CLI返回predictionPolicy=`raw`，内存身份使用`raw_uncalibrated_24month_CIF`。二者在各自已限定路径内都意指原始输出，但属于不同格式；后续若映射，必须核原模型角色和策略后显式说明，不能接受来件随意声明等价。

## 合成请求、响应与错误样例

[可执行样例](../research_runtime/examples/model_integration_candidate.py)复用原`constructed_pair`，生成七/十变量各一份完整请求与人工宿主上下文，并调用原API取得返回。[固定JSON目录](../research_runtime/examples/model_integration_candidate.json)保存同一输出，便于直接评审。

目录顶层的fixtures/responses/errorExamples只是样例索引，不是新HTTP Envelope。fixtures中的CONSTRUCTED编号、来源、日期、摘要和证据全部虚构；重复a/b/c等摘要只用于结构展示，不证明模型、软件或来源真实。人工场景更新其配套证据是构造样例的做法，实际宿主不得按来件反向伪造可信上下文。

| 样例 | 实际原API结果 | 人工回调 |
| --- | --- | --- |
| 六种表格声明组合 | METADATA_CONSISTENT、predictionAllowed=false | 0 |
| 相容、无许可 | PERMISSION_REQUIRED、predictions=null | 0 |
| 有独立人工许可的固定回调 | COMPLETED、两原因固定值、许可COMPLETED | 1 |
| AS_OF_INDEX下MMSE可获得日未知 | BLOCKED、AVAILABILITY_UNKNOWN、predictions=null | 0 |
| 相同未知日期、人工回顾性场景 | WARN，仍为PERMISSION_REQUIRED | 0 |
| 请求模型artifact摘要与上下文不同 | BLOCKED、整批阻断 | 0 |
| 双侧MRI合法缺失 / 七核心全部缺失 | 前者保留null及缺失分母、仍需许可；后者NO_CORE_INPUT并BLOCKED | 0 |
| MRI normalized / APOE 1.0 / 12月 / 可获得性对照 | 分别FEATURE_UNIT / APOE_INTEGER_COUNT / ONLY_24_MONTH_WINDOW / MODEL_SPECIFICATION | 0 |
| 重复JSON键 / 无宿主许可 / 复用同一许可 | 分别DUPLICATE_JSON_KEY / INDEPENDENT_EXECUTION_PERMISSION_REQUIRED / PERMIT_ALREADY_USED | 0新增 |

用任意可用的Python 3.11/3.12解释器，从仓库根目录运行（无第三方依赖）：

```powershell
python -B -S -m research_runtime.examples.model_integration_candidate
python -B -S -m research_runtime.examples.model_integration_candidate --full
```

默认仅打印状态、固定错误码和计数；`--full`打印完整人工目录，不读写文件或开网络。样例含22项针对性核对、合计1次人工固定回调、0实际模型/原CIF/拟合。所有返回继续保留临床、来源、历史可用性和个人因果未认证标记。

## 后续仅核真实衔接缺口

P1到本候选与样例为止；P2已完成来源映射，直接代用旧identity的路径因字段不足收口，真实宿主证据本次未核验，未新增适配层。可获得性对照暂不准入，是否确需支持须另有问题与验收范围，不能为覆盖九模型放宽当前检查。P3的人工案例绑定已完成，真实模型路径仍需独立证据。

P3已完成[同一人工案例的预测/解释绑定候选](Prediction_Explanation_Binding_Candidate.md)，直接复用单次调度和精确分组Shapley，保留LIME未执行及真实宿主证据未核验。固定解析回调不是实际表格模型；该示例不构成生产报告API。

需与Java/前端另行协调的事项包括新传输字段/版本、身份映射、权限来源、业务幂等与存储、错误状态映射、报告用途和受控参数交付。候选不修改contracts、不启动新服务、不切换8091。单次内存返回没有分组Shapley/LIME或课程四阶段DTO；解释输出须绑定同一输入/模型/参考，不能套用旧加性贡献。

这些是工程接入边界，不能关闭同一评估机会策略的行动/首次可用历史补证、真实监控双批、个人因果、独立或临床缺口。旧图阴性、结构不可估、CSF NO_GO、阈值SUPPRESSED与673/36研究拟合账本保持；不为候选开发重训旧研究。
