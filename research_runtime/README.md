# 离线研究计算、固定训练与图模型

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

入口只向标准输出返回JSON，不保存结果或请求，也不启动HTTP服务。失败以退出码1和不含输入值的类型化错误返回。三个已有HTTP通道及Java契约保持原行为；该命令不意味着Java AgentClient已经实现。

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

剩余接入工作：版本化真实输入契约与字段映射、来源/QC/历史可获得性政策、受控参数交付及目标机部署、Java AgentClient/权限/任务和结果持久化的联合验收。共享契约变化先协调后实施。固定表格训练/校准、三臂图研究和图解释诊断均已阶段完成；历史队列重建、全部表格比较/解释并未整体迁移。当前CLI仍输出原始CIF，新训练产物不自动部署。严格个人因果、SHAP/LIME、跨重训/独立和临床验证继续开放，当前执行顺序取[Python需求状态](../docs/Python_Requirement_Status.md)。

输出为合成输入下的研究模型计算值，`clinicalUse=false`；工程一致性不能证明临床概率精度、算法优越或个体因果效应。

## 本次分组归因与输入适配检查

在上述固定CPU图环境运行全部新增方法/适配/编排检查：

```powershell
.venv-research-graph/Scripts/python.exe -m unittest research_runtime.tests.test_group_shapley research_runtime.tests.test_group_lime research_runtime.tests.test_group_xai_admission research_runtime.tests.test_group_xai_dtypes research_runtime.tests.test_grouped_xai_study research_runtime.tests.test_table_explanation research_runtime.tests.test_table_xai_study research_runtime.tests.test_input_admission research_runtime.tests.test_input_admission_compatibility research_runtime.tests.test_inmemory_inference research_runtime.tests.test_inmemory_cif_bridge -v
```

本次106项检查通过；在外层禁止所有风险/预处理fit、参数加载、网络和受控路径读取，实际1023次人工参数CIF、34次局部替代回归，25.282秒。局部回归与风险模型训练分开计数。完整60/1200编排矩阵使用模拟归因，不代表1200次实际回归或真实研究；原生图的人工状态由现有构造辅助函数赋值，不调用优化器。

输入一致性校验模块只用标准库。两个独立构造示例：

```powershell
.venv-research-graph/Scripts/python.exe -S -B -m research_runtime.examples.input_admission_constructed
.venv-research-graph/Scripts/python.exe -B -m research_runtime.examples.inmemory_constructed
```

图/完整表格研究编排复用既有图聚合模块，因此使用图环境；单独表格参考/归因适配不依赖Torch。Linux、其他Python版本及未验收机器须另行核对，现有课程CI不会自动运行上述可选研究检查。真实输入/参数/私有折、宿主单次读取封装及内部授权不随源码发布；本地构造通过不等于真实业务接通或临床验证。

## 训练扰动研究原语（2026-10-28）

同算法跨训练样本解释的[训练子集、交叉参考与流式内存编排](../docs/Training_Perturbation_Attribution.md)已构造验收：最终126项无拟合检查通过，人工回调走完150训练槽位/480游戏。后续[表格真实研究](../docs/Training_Perturbation_Results.md)完成150基础拟合/480游戏，[图真实研究](../docs/Graph_Training_Perturbation_Results.md)完成225基础拟合/720单模型游戏，累计673基础/36校准。固定研究关闭，个人因果与来源/时序等证据仍未完成，不为源码发布重训。原固定模型evaluate入口继续0拟合；新evaluate_training_perturbations入口明确委托既有训练实现，需由受控宿主绑定输入、来源、授权和硬停止。图接口与隔离验收见[图流式编排](../docs/Graph_Training_Perturbation_Streaming.md)。
