# 研究实验配置的限定导出

本项服务四环节第1→3→4环节的FR-22工程支撑。已有研究计划和计算接口继续复用，本模块补充可交换的配置元数据导出，不创建新的训练器、读取器、执行宿主或服务。FR-22的全部研究结果/图像/报告导出和真实模型业务接入并未因此完成。

## 接口与格式

`research_runtime.experiment_config.export_experiment_config(selection)`返回确定性的UTF-8 JSON字节；`load_experiment_config(raw, expected_sha256=...)`检查字节摘要并解析配置。二者均只操作内存，无文件、临床数据或模型访问。调用方负责保存/传输字节，并从独立渠道保留预期摘要；自己重算来件摘要不证明来源真实性。

配置选择必须显式包含：

| 字段 | 范围 |
| --- | --- |
| specification / family | seven或multimodal_values；logistic、random_forest或xgboost |
| predictionPolicy / predictionWindowMonths | 原始、未校准24月两原因CIF策略；窗口严格整数24 |
| referencePolicy | SAVED_PIPELINE_MEDIAN或SAVED_PIPELINE_MEAN；不导出参考数值 |
| availabilityPolicy | RETROSPECTIVE_SNAPSHOT或AS_OF_INDEX；选择不等于历史资格已获认证 |
| splitPolicy | EXTERNAL_FROZEN_SUBJECT_SPLIT或FINAL_MODEL_NO_SPLIT；折成员不导出 |
| randomSeed | 0至2³²−1的整数，bool不当整数 |
| hashes | 清单、参数、训练计划、运行源码和依赖锁的5项发布级SHA-256声明 |

特征顺序和单位复用`input_admission.SPECS/UNITS`，不从任意来件推断。输出固定为`purpose=REPRODUCIBILITY_METADATA_ONLY`、`executionAuthorized=false`、`clinicalUse=false`。JSON可以记录调用方从已有计划选出的上述字段，但不是完整的旧计划格式，也不提供参数或受控输入的自动查找/重建。

表格分支保持原六组合及`research-experiment-config-v1`输出字节；新增图分支见下节。可获得性置0对照、校准策略及训练调度未纳入。不能将已有最终模型的配置改称已经完成的新训练或临床实时模型。

## 图模型配置

仍使用同一导出/回读API；`family=conditional_graph_attention`是配置格式标签，不是对原表格加载器、真实输入准入或Java契约增加图支持。图输出独立使用`research-graph-experiment-config-v1`，不能与表格schema互换。

图选择使用十变量`multimodal_values`、原始24月CIF及`SAVED_GRAPH_MEDIAN`/`SAVED_GRAPH_MEAN`参考声明；不含表格的`randomSeed`或单个`artifactSha256`字段。公共hashes为manifest、训练计划、源码、软件锁及原`graphProtocolSha256`。固定协议原字节SHA为`ee74d09fff5f530e086dff5c0f3dd49513ffe6db42cb6ef8f05c8a4799413c49`，未知协议拒绝；原协议的历史状态字段不改写当前实现状态。

新增graph字段必须完整：

| 字段 | 范围 |
| --- | --- |
| arm | self_only、prior、semantic_permutation；沿用原三臂，不新增边或替换图 |
| initializationSeeds | 单种子时为原20260926/27/28之一的单元素列表；三种子时严格为原三者固定顺序 |
| aggregationPolicy | SINGLE_SEED_CIF，或MEAN_3_SEED_CIF；后者声明对三种子两原因CIF等权均值，不平均logit/危险率或选最好种子 |
| artifactSha256BySeed | 精确覆盖所列种子的参数摘要映射；不得漏项、多项或用同一摘要冒充多个种子 |

该子集覆盖9个单模型声明和3个三种子聚合声明。它只记录选定artifact/策略，既不打开文件验证摘要，也不执行求均值、归因或训练；声明正确不认证模型资格或解释稳定性。图结构/超参数由固定协议身份约束，不把参数数组、私有折、参考数值或个人图内容塞入配置。

```powershell
# 人工三种子CIF均值配置
python -B -S -m research_runtime.examples.graph_experiment_config_constructed
# 人工单模型配置
python -B -S -m research_runtime.examples.graph_experiment_config_constructed --arm self_only --single-seed 20260927
```

示例发布摘要为人工值（协议身份除外），不得当作实际参数已核验。图模型原生源码、协议机器文件和依赖锁保持原字节，旧图增益阴性与全部已关闭研究不重跑。

## 人工运行示例

从仓库根目录运行：

```powershell
python -B -S -m research_runtime.examples.experiment_config_constructed
```

示例只使用人工发布摘要和元数据，将配置输出到stdout，并按独立保留的本次字节摘要做内存回读核对。它不证明实际模型摘要已核验，也不产生预测许可。输出如需落盘，由调用者显式保存；此命令不写文件。

原语可按以下方式调用，`selection`应事先从允许导出的发布元数据中明确选取：

```python
from research_runtime.experiment_config import export_experiment_config, load_experiment_config

raw = export_experiment_config(selection)
restored = load_experiment_config(raw, expected_sha256=independently_retained_digest)
```

## 数据与权限边界

输入使用完整字段白名单；未知字段直接拒绝。个人记录/身份、逐人hash、私有折、数据路径、参数数组、参考值、访问凭据及推理许可均无可导出字段，不将整个旧计划原样交给本接口。缺字段、重复JSON键、非有限常量、超长字节、错误摘要、特征/单位错配和权限标志改写会被拒绝，错误只返回稳定代码。

发布摘要仍是调用方声明，不能从64位字符串格式认证其来源或阻止调用方故意把个人摘要冒充发布摘要；本工具不是脱敏认证、差分隐私或授权系统。完整复现还需要受控输入、模型、环境、原折与资格依据，配置本身不包含这些材料。

## 验收与下一项

原17项表格人工检查保持；新增17项图检查覆盖9单模型/3聚合往返、表格六组合封存字节、协议/特征/种子/arm/参数映射一致、错聚合与私有字段拒绝、schema隔离、确定性/副本隔离以及独立`-S`解释器无需模型依赖。当前配置专题34项，连同原功能量/界限检查，标准库层69项。新增检查加入现有标准库CI；远端结果以发布后的Actions为准，本地检查不冒充已运行远端矩阵。

本配置子集的终点是可调用导出/回读和人工验收，不以配置替代真实参数核验。实际模型状态核验及其首轮停止结果见[模型组件核验](Model_Artifact_Admission.md)。真实宿主独立来源、QC/首次可用与权限仍需证据，固定研究结论及673/36拟合账本保持。
