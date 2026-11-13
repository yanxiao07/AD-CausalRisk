# 离线研究计算、固定训练与图模型

[独立表格研究通道](../docs/Research_Table_Agent_Contract.md)提供宿主绑定的内存适配、标准库结果校验和环回合成 HTTP 宿主。用[联调启动器](../backend/README.md#本地研究任务联调)启动 Java 与 Python、取得人工快照 ID，或在真实登录会话下验收两规格完成和 QC 阻断。真实研究宿主仍待独立准入。

[统一表格评估入口](../docs/Table_Assessment_Integration.md)将原许可预测与保存参考SHAP连接成同一输入/模型状态的内存结果，保留来源准入、独立解释权限和失败无部分数值。它是后续真实业务接入的计算步骤，不读取真实数据/参数、不新增HTTP或更换默认服务。

新七/十变量模型的[Python接入候选](../docs/Research_Model_Integration_Candidate.md)区分课程/旧V8 HTTP、离线CLI与现有内存API，提供字段差异和纯标准库人工请求/响应示例。候选不新增服务或共享契约；离线九组合与内存表格六组合的支持边界分别保留。

同一人工解析案例的[预测/解释绑定候选](../docs/Prediction_Explanation_Binding_Candidate.md)复用原精确分组Shapley与单次调度，附固定JSON及16项错绑/数值检查；只需原锁NumPy。它不是实际研究模型运行或生产报告API，LIME与真实宿主证据保持未执行/未核验。

## 实验配置导出与参数核验

[FR-22 配置元数据导出](../docs/Research_Experiment_Config.md)提供标准库内存导出与回读，涵盖两规格三算法、原始 24 月策略、保存参考、种子、划分与发布摘要。个人内容、路径、参数及许可不导出。人工示例：`python -B -S -m research_runtime.examples.experiment_config_constructed`。

[实际参数组件核验](../docs/Model_Artifact_Admission.md)覆盖六个表格模型的参数、状态与保存参考。九个人工案例验证预测与解释绑定；完整宿主资格仍为 NO_GO。

[源码/锁及安装记录检查](../docs/Research_Runtime_Binding.md)补充表格数值8成员范围、两种锁字节对象和12版本/6274带哈希安装成员的一致性证据；3875无哈希成员、受阻模块导入、发行来源/缓存身份不升级为认证。未改原锁或环境，未签发可信上下文/许可。

[图实验配置导出](../docs/Research_Experiment_Config.md#图模型配置)复用同一API，新增独立图schema、固定协议三臂和种子，区分单种子CIF与三种子CIF等权均值；各参数摘要按种子绑定，拒绝漏种子/重复参数/错误聚合策略。`python -B -S -m research_runtime.examples.graph_experiment_config_constructed`输出人工均值配置，`--arm self_only --single-seed 20260927`输出单模型配置。无需Torch/NumPy/参数读取；新增17检查加入标准库层，当前69项，原六表格v1字节不变。

## 离线评价与监测

以下纯内存接口支持结构评价和监测，独立于业务服务。

| 模块 | 接口与限制 |
| --- | --- |
| [自然模态分层](../docs/Graph_Modality_Strata.md) | `graph_explanation.aggregate_modality_strata` / `evaluate_modality_strata`，保留三臂共同排序分母与空MRI-only组 |
| [结构选择稳定性](../docs/Graph_Structure_Stability_Primitives.md) | 固定正则秩精度分数、100抽样/至少90共同有效、无向候选及不可估null；四折不可估，后续选边预测比较NO_GO |
| [固定阈值评价](../docs/Table_Threshold_Evaluation.md) | `table_xai_study.evaluate_thresholds`，九方案原外折身份先核对，再按联合小组门槛发布；固定真实报告整表SUPPRESSED |
| [未知结局补全界限](../docs/Decision_Curve_Bounds.md) | `decision_curve`，固定24月分类/DCA及同人差界限，早截尾不补0，非置信区间 |
| [已知概率核功能量](../docs/Competing_Event_Causal_Functional.md) | `competing_event_functional`，精确Fraction传播；只检查规定数学模型，不识别观察数据的因果效应 |
| [漂移/缺失](../docs/Feature_Drift_Monitoring.md) | `feature_drift`，固定参考分箱、十特征/单位/版本及跨批联合隐私门槛，缺支持保留null |
| [性能/亚组](../docs/Performance_Monitoring.md) | `performance_monitor`，无拟合Brier/固定箱校准/ECE补全界限及预定义亚组，不估计KM/IPCW |
| [联合生命周期报告](../docs/Lifecycle_Monitoring.md) | `lifecycle_monitor`，同一快照绑定并先做统一联合隐私门槛，再调用上述两个计算器 |

监测依赖仅标准库及现有NumPy 2.4.6；不加载模型、预测、拟合、自动告警或更新。来源、历史可用性、真实双批及临床资格不由元数据相符认证。真实监控输入尚未准入，没有部署监测结论。

从仓库根目录按依赖选择专题；前两组的最小环境安装见下节：

```powershell
# 标准库专题
python -B -S -m unittest research_runtime.tests.test_competing_event_functional research_runtime.tests.test_decision_curve research_runtime.tests.test_experiment_config research_runtime.tests.test_graph_experiment_config -v

# NumPy专题，无需pandas、Torch或预测依赖
.venv-research/Scripts/python.exe -B -m unittest research_runtime.tests.test_feature_drift research_runtime.tests.test_performance_monitor research_runtime.tests.test_lifecycle_monitor research_runtime.tests.test_graph_structure_stability -v

# 图/表格编排需现有图锁环境；人工参数与模拟回调，不重放真实研究
.venv-research-graph/Scripts/python.exe -B -m unittest research_runtime.tests.test_graph_modality_strata research_runtime.tests.test_graph_modality_evaluation research_runtime.tests.test_table_thresholds -v
```

受控数据、模型权重、折外预测、私有折和执行授权不在 Git 中。测试使用人工数据。

## 研究原语分层CI

[独立工作流](../.github/workflows/python-research-primitives.yml)在push和pull request时分别运行两层，每层配置Windows/Ubuntu × Python 3.11/3.12。它为第1→2→3→4环节的离线计算提供工程回归，不运行训练、受控模型或真实监测。远端是否通过以对应提交的Actions结果为准。

| 层 | 显式测试模块（`research_runtime.tests`下） | 依赖与范围 |
| --- | --- | --- |
| 标准库，69项 | `test_competing_event_functional`、`test_decision_curve`、`test_experiment_config`、`test_graph_experiment_config` | `-S`禁用site packages；精确核功能量、未知结局界限及配置绑定/受限导出 |
| NumPy，139项 | `test_feature_drift`、`test_performance_monitor`、`test_lifecycle_monitor`、`test_graph_structure_stability`、`test_prediction_explanation_candidate` | 仅安装原锁中的NumPy 2.4.6；人工监测/隐私/结构稳定性及预测/解释错绑检查 |

最小NumPy环境可用以下命令建立；Linux/macOS将解释器路径换成`.venv-primitives/bin/python`：

```powershell
py -3.11 -m venv .venv-primitives
.venv-primitives/Scripts/python.exe -m pip install --only-binary=:all: --constraint research_runtime/requirements.lock numpy
.venv-primitives/Scripts/python.exe -B -S -m unittest research_runtime.tests.test_competing_event_functional research_runtime.tests.test_decision_curve research_runtime.tests.test_experiment_config research_runtime.tests.test_graph_experiment_config -v
.venv-primitives/Scripts/python.exe -B -m unittest research_runtime.tests.test_feature_drift research_runtime.tests.test_performance_monitor research_runtime.tests.test_lifecycle_monitor research_runtime.tests.test_graph_structure_stability research_runtime.tests.test_prediction_explanation_candidate -v
```

`--constraint`复用现有NumPy版本，不安装锁中其余训练依赖。不要用全目录发现替代这些模块列表，其他专题可能需要pandas/sklearn/Torch或执行人工拟合。

测试只使用仓库内源码及人工内存输入。漂移测试保留旧stage15来源摘要与同一五箱人工样例，改用独立解析式对照，不再执行仓库外脚本或导入pandas；性能测试以AST读取仓库内`evaluation.py`的固定箱边界，不导入其训练依赖。四个数值专题的受控I/O守卫以当前仓库为根，不依赖维护主机的目录深度。计算实现与统计判据保持原样。

此CI不覆盖图/表格训练、全部研究编排或临床验证；固定图阴性、结构不可估、CSF NO_GO、阈值SUPPRESSED及真实监控输入未准入的结论保持。

发布前的本地验收从只含Git源码的副本，在新建Windows/Python 3.11.9和3.12.13环境分别通过35+123项检查，零跳过；数值环境除环境自带工具外只安装NumPy 2.4.6。工作流通过actionlint检查。该本地记录不包含Linux与GitHub托管环境结果；远端记录以对应提交的Actions为准。

接入候选发布时将16项人工预测/解释绑定测试加入现有NumPy作业，没有新增依赖或执行真实模型。Windows/Python 3.11.9和3.12.13的Git源码副本各通过35+139项，零跳过；原35+123项记录保留为上一发布的范围。

## 既有研究与源码移植范围

[图训练人员扰动真实研究](../docs/Graph_Training_Perturbation_Results.md)已完成225基础拟合/0校准、12780 CIF；累计真实673基础/36校准。两原因/双序聚合、固定共同排名分母及结果限制已交付；本固定问题关闭，不重复训练或切换默认。

图训练扰动的[流式聚合接口](../docs/Graph_Training_Perturbation_Streaming.md)已加入现有图解释模块：三种子有符号贡献/CIF先平均、双序四游戏、共同排名分母及24 hash/12 Brier前置门槛，输出仅含聚合和尝试/完成计数。完整模拟使用解析回调，不执行优化器；225次真实矩阵已执行并聚合核验。

图训练扰动新增[受控拟合与跨参考原语](../docs/Graph_Training_Perturbation_Primitives.md)：复用未改动的原生估计器/图协议，单次训练预算及两种原有顺序的显式参考入口已构造验收。37项无优化检查、9次人工子集拟合通过，真实新拟合0；225次真实图矩阵已完成固定执行，不自动运行旧构造训练或全量训练。

新增[分组预测归因](../docs/Grouped_Prediction_Attribution.md)、[表格归因编排](../docs/Table_Grouped_Study_Execution.md)、[研究输入一致性门槛](../docs/Real_Input_Admission.md)与[单次内存CIF适配](../docs/InMemory_Research_Inference.md)。这些API接收宿主独立核实的内存输入和模型，不读取临床文件、不加载真实参数、不替换默认模型；元数据一致不授予预测许可，也不认证来源/临床/因果。图固定归因的结果和参考/局部忠实度限制见[既有图研究结果](../docs/Grouped_XAI_Results.md)。

本目录提供表格估计器、人月展开、两原因24月CIF、固定训练/嵌套校准，以及三臂图预测和折外解释计算。公开CLI只接受明确标记的人工合成快照；图API接收调用方已合格的内存队列。没有临床源表读取器、Java业务服务或默认模型切换。

这里没有临床源表读取器。固定五外折/三内折训练和嵌套校准现由`training.py`独立提供，数值评价由`evaluation.py`复用原函数；历史队列构造、全部比较和解释流水线没有整体迁移。原固定真实研究任务已完成；后续复用受控模型，无需为整理源码重新训练。源码本地可用与Git发布、参数交付、服务部署分别记录。

## 安装与测试

从仓库根目录使用独立的Python 3.11环境：

```powershell
py -3.11 -m venv .venv-research
.venv-research/Scripts/python.exe -m pip install -r research_runtime/requirements.lock
.venv-research/Scripts/python.exe -m unittest research_runtime.tests.test_runtime research_runtime.tests.test_training -v
```

Linux/macOS将解释器路径换成`.venv-research/bin/python`。已验证的是Windows/Python 3.11；新机器仍需运行这些检查。锁文件与现有模型清单记录的版本相同，不修改课程服务依赖。14项原验收测试使用九次小型合成拟合及一次分类器状态拟合；13项新训练测试使用90人构造队列、42次基础/12次校准拟合验证两条路径，不扫描或加载真实交付目录。构造拟合不计入真实研究账本。

## 图模块环境与检查

图模块单独使用Python 3.11 CPU环境；图锁包含特定平台wheel的SHA256，当前验收平台为Windows x64。其他平台须另行解析、审核并验收依赖，不能把安装成功当跨平台验收。

```powershell
py -3.11 -m venv .venv-research-graph
.venv-research-graph/Scripts/python.exe -m pip install -r research_runtime/requirements.graph.lock
.venv-research-graph/Scripts/python.exe -m unittest research_runtime.tests.test_graph research_runtime.tests.test_graph_native research_runtime.tests.test_graph_training research_runtime.tests.test_graph_explanation -v
```

这四组测试只使用人工常数/构造队列和mock编排，无真实权重读取或优化拟合。`graph_acceptance`另有9次优化构造拟合，已经完成验收，不能因源码交付自动重跑；表格测试则包含人工数据拟合。按需要选择专题，不在仅安装表格依赖的环境中发现整个研究测试目录。

`graph_protocol.json`保留规格阶段的原始字节与历史状态；其`native_training_implemented=false`不是当前交付状态。原生实现、54次真实研究及解释诊断均有阶段交付，当前结论见[需求状态](../docs/Python_Requirement_Status.md)。图增益未确立，个人因果和临床用途仍未成立。

图归档校验协议和`graph_native.py`的原始字节。`.gitattributes`对这两份文件关闭换行转换，并保留文本diff；复制、重新格式化或外部工具换行转换后可能被严格身份检查拒绝。不要改摘要或绕过拒绝来加载旧权重。

## 固定训练与嵌套校准

本入口支撑主线第1/3/4环节的预测基线复用，不认证个体因果图、图增益或临床概率。只生成程序内90条人工构造记录，不接受临床文件路径：

```powershell
.venv-research/Scripts/python.exe -m research_runtime.training `
  --output runtime/fixed-training-synthetic `
  --specification seven --family logistic
```

`--specification`可为`seven`或`multimodal_values`，`--family`可为`logistic`、`random_forest`、`xgboost`或`all`。输出目录必须不存在；失败记录保留，不覆盖、不自动重试。研究依赖仍只用当前锁文件，不额外安装开发包。

| 路径 | 每算法基础/校准拟合 | 三算法基础/校准拟合 | 交付 |
| --- | --- | --- | --- |
| 从头固定训练 | 24 / 6 | 72 / 18 | 每算法5外折+1最终bundle，含校准参数 |
| 复用已提供基础模型 | 18 / 6 | 54 / 18 | 每算法6份JSON校准参数，不再保存或重训原基础模型 |

两条路径由同一个`run_nested`编排。全部外折及内折在拟合前检查受试者隔离、覆盖和类别；插补、标准化、月编码和删失KM仅在相应训练折拟合。外折校准器使用外折训练集三折crossfit分数；最终校准器使用全队列三折crossfit分数，配对最终基础模型。保持原固定参数、seed安排、IPCW Brier全人数分母、1000次条件受试者区间和两原因CIF缩放。校准差区间上限低于0时内部研究保留校准，否则原始；该规则不触发业务默认模型变化。

Python API：`run_nested(frame, output, specification, families, splits=..., existing_models=..., expected_oof=...)`。`frame`为调用者已构建的内存DataFrame，包含唯一`subject_id`、整数`observed_month`（1–24）、`event_type`（0/1/2）及指定特征，缺失为NaN。调用者负责资格、日期、单位、QC、历史可获得性、冻结输入和受控来源；此API不重新认证这些条件。`splits`可传冻结的五外折位置，省略时按固定seed构造。

复用模式必须同时提供每算法五外折及最终内存模型，键为`(family, 1..5或None)`，以及各算法完整原始AD折外预测的SHA256（按队列行序、小端float64字节）。所有原始OOF摘要先核对，再执行新拟合；内存模型始终不重训。对象必须来自调用者已经核验的可信参数，文件hash、原模型角色/信息集/折身份应在提供对象之前按原清单核对。JSON中的`base_state_joblib_hash`仅为来源状态摘要，不替代原文件SHA256或可信来源验证。

训练计划绑定输入指纹、折划分、源码及软件版本，每个模型/校准产物引用计划SHA256。产物为`Training_Plan.json`、`Training_Result.json`、`Fit_Ledger.jsonl`、`Folds.private.json`、`Run_State.json`及相应模型或校准参数。仅记录聚合指标/分箱/预测摘要，不保存个体预测。保存加载分别核对文件hash、身份字段和原始/校准后两原因CIF；不能以序列化前后内存对象摘要相等代替模型复现。

新产物格式为`research-fixed-nested-training-v1`，使用`PORTABLE_OUTER/PORTABLE_FINAL`角色，**不冒充旧Model_Manifest或Java DTO**。原`python -m research_runtime`合成加载入口和工作台仍只接受原受控清单、原始CIF；不会自动消费此训练目录。不得把生成目录、模型、私有折位置、虚拟环境或受控数据提交进Git。

维护主机已用仅复制源码的临时目录、`-I -B`隔离解释器验证三算法完整构造路径72/18拟合、18个bundle保存加载；进程审计拒绝访问宿主工作区其他来源。该检查证明可移植运行，不是新研究比较或外部验证。原封存宿主脚本保留作证据，后续固定基线工作复用本模块，不再复制一套训练器。

## 使用受控模型进行合成验收

模型维护方应通过受控渠道提供原`Model_Manifest.json`、其中的最终`.joblib`及独立可信的清单SHA256。清单与参数放在同一受控目录；不要提交到Git。Joblib会执行pickle代码，只能加载可信维护方的产物；SHA256用于核对字节，不能使未知来源文件变可信。调用者必须从独立可信渠道取得清单摘要，不能将未知文件自己计算的摘要当作来源证明。

```powershell
.venv-research/Scripts/python.exe -m research_runtime `
  --manifest '<受控目录>/Model_Manifest.json' `
  --manifest-sha256 '<维护方提供的64位SHA256>' `
  --request research_runtime/examples/seven.synthetic.json
```

入口只向标准输出返回JSON，不保存结果或请求，也不启动HTTP服务。失败以退出码1和不含输入值的类型化错误返回。三个已有HTTP通道及Java契约保持原行为；该CLI不提供Java研究模型适配，现有AgentClient仍调用课程合成服务。

受控维护主机现另有同一工作台内的“模型与研究审查”页面，以独立进程调用本模块进行九模型合成计算，并展示既有聚合研究结果。它属于维护主机的本地Python演示接入，未成为Java共享API；本仓库的CLI继续保持上述离线范围。

输入的`specification`明确选择`seven`、`modality_availability`或`multimodal_values`，`family`明确选择`logistic`、`random_forest`或`xgboost`。不存在自动推荐算法或默认切换。七变量使用七变量清单，两个十变量方案使用多模态清单；只加载相应最终全量模型，拒绝外折模型。输出使用已交付的原始CIF；七变量校准参数仍留在受控bundle中，不应用于当前输出，更不会移用到十变量。

`modality_availability`仅把新增三列的已观测值置0，保持原缺失位置。返回的`sources`保留输入原值；它们与实际送入该对照模型的变换值有明确区别。

## 本地输入格式

`research-model-smoke-v1`是本工具的Python本地文件格式，没有加入Java/HTTP共同契约。顶层字段必须完整：`schemaVersion`、`purpose`、`specification`、`family`、`dataVersion`、`indexDate`、`predictionWindowMonths`和`records`。仅接受`purpose=SYNTHETIC_ACCEPTANCE`、`synthetic-*`版本、唯一`SYNTHETIC-*`编号和24个月窗口，最多100条。

| 特征 | 单位 | 所属方案 |
| --- | --- | --- |
| age_years、education_years | years | 全部 |
| mmse、cdr_sb、cdr_global、adas__total13、faq__faqtotal | score | 全部 |
| apoe4_count | count，严格整数0/1/2 | 两个十变量方案 |
| mri_left_hippocampus_volume、mri_right_hippocampus_volume | mm3，正值 | 两个十变量方案 |

每项特征必须显式提供`value`、`unit`、`measuredDate`、`availableDate`、`source`和`qc`。缺失值为`null`，同时两个日期为`null`；不把缺失补为0。`source`和`qc`当前均要求`SYNTHETIC`。测量日不得晚于索引，已知可获得日不得早于测量日；未知可获得日允许`null`。海马必须同一扫描日、同处理可获得日、双侧同时存在，扫描距离索引不超过365天。两侧缺失允许保留；原核心七变量全缺则拒绝。

可获得日晚于索引的合成情况会保留以检查回顾性快照语义，输出始终`historicalAvailabilityCertified=false`。这些元数据由调用者声明，工具不验证真实来源，也不重新实现或认证原队列的全部选择/QC政策。将真实值改成合成编号不构成合成数据，使用者仍须遵守数据授权。

## 源码与后续边界

`discrete.py`保留原人月、预处理和CIF实现；`estimators.py`整理原七/十变量候选规格。仓库根目录`adni_algorithm_candidates.py`只保留原模型pickle引用的`NotEstimable`/`StrictHazards`类，类AST保持一致。这一兼容路径让现有模型无需重训或重新序列化即可加载。来源摘要与范围见[Source_Provenance.json](Source_Provenance.json)。

当前 CLI 输出原始 CIF。固定表格训练、校准、三臂图研究和解释诊断已有阶段交付。Java 与 Python 的人工快照联调已通过；真实接入仍需独立来源、量表完成与 QC、首次可用时间、执行许可及目标环境验收。科研进度见[Python 需求状态](../docs/Python_Requirement_Status.md)。

输出为合成输入下的研究模型计算值，`clinicalUse=false`；工程一致性不能证明临床概率精度、算法优越或个体因果效应。

## 分组归因与输入适配检查

在固定 CPU 图环境运行分组归因和输入适配检查：

```powershell
.venv-research-graph/Scripts/python.exe -m unittest research_runtime.tests.test_group_shapley research_runtime.tests.test_group_lime research_runtime.tests.test_group_xai_admission research_runtime.tests.test_group_xai_dtypes research_runtime.tests.test_grouped_xai_study research_runtime.tests.test_table_explanation research_runtime.tests.test_table_xai_study research_runtime.tests.test_input_admission research_runtime.tests.test_input_admission_compatibility research_runtime.tests.test_inmemory_inference research_runtime.tests.test_inmemory_cif_bridge -v
```

检查使用人工参数与构造输入。局部替代回归与风险模型训练分别计数，模拟编排不代表真实研究执行。

输入一致性校验模块只用标准库。两个独立构造示例：

```powershell
.venv-research-graph/Scripts/python.exe -S -B -m research_runtime.examples.input_admission_constructed
.venv-research-graph/Scripts/python.exe -B -m research_runtime.examples.inmemory_constructed
```

图/完整表格研究编排复用既有图聚合模块，因此使用图环境；单独表格参考/归因适配不依赖Torch。Linux、其他Python版本及未验收机器须另行核对，现有课程CI不会自动运行上述可选研究检查。真实输入/参数/私有折、宿主单次读取封装及内部授权不随源码发布；本地构造通过不等于真实业务接通或临床验证。

## 训练扰动研究原语（2026-10-28）

同算法跨训练样本解释的[训练子集、交叉参考与流式内存编排](../docs/Training_Perturbation_Attribution.md)已构造验收：最终126项无拟合检查通过，人工回调走完150训练槽位/480游戏。后续[表格真实研究](../docs/Training_Perturbation_Results.md)完成150基础拟合/480游戏，[图真实研究](../docs/Graph_Training_Perturbation_Results.md)完成225基础拟合/720单模型游戏，累计673基础/36校准。固定研究关闭，个人因果与来源/时序等证据仍未完成，不为源码发布重训。原固定模型evaluate入口继续0拟合；新evaluate_training_perturbations入口明确委托既有训练实现，需由受控宿主绑定输入、来源、授权和硬停止。图接口与隔离验收见[图流式编排](../docs/Graph_Training_Perturbation_Streaming.md)。
