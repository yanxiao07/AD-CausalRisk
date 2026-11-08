# 自然模态分层的图解释聚合

[固定真实分层评价](Graph_Modality_Strata_Results.md)已完成：45原外折、855次真实CIF、198360状态行，0拟合，原基线与总体统计通过；MRI-only为空。初次真实尝试因模型解包模式兼容在首CIF前停止，失败保留；修复后的固定评价单次完成，不因源码交付重复读取、补种子或重训。

第2→3→4环节的有界支撑：`research_runtime.graph_explanation.aggregate_modality_strata(frame, contributions)`对已有固定模型解释数组分层聚合，不读取文件、调用模型或训练。

`frame`包含十个原始特征列；`contributions`以`(seed, arm, order)`为键，每个数组为`(N, 9, 2)`。原三种子、三臂和正反序均须完整，N为1至1160。九组中的双侧海马须同有/同缺，缺失组贡献必须为0，正反序总贡献须在1e-12内一致。调用方仍负责输入/折/模型绑定、实际CIF重构和资格；此函数不能授予真实数据执行权限。

自然分组复用`graph_training.subgroup_masks`：both、apoe_only、mri_only、neither。空组保留`EMPTY_STRATUM`，其均值、分位数和比率为null。原固定人群为276/152/0/732；此函数也支持人工构造的非空MRI-only组，不通过合并组改变原样本。

三种子有符号贡献先平均，再计算双序L1。九组L1及同一游戏前七个临床组的L1分量使用组内全部人员；prior减两个对照的分布由同人配对差计算。分位数在相应人员集合上计算，不能加权组分位数拼出总体分位数。

排序只在至少三个观测组且正反序绝对贡献非恒定时定义，三臂使用共同合格人员。符号翻转只使用三臂、两顺序均超过1e-6的共同观测人员-特征组；空分母保持null。输出分别记录两类共同分母和排序排除原因，不用排序资格筛掉L1人员。函数不返回个人值、掩码或排名。

```python
from research_runtime.graph_explanation import aggregate_modality_strata

# 两个参数都由调用方在内存提供；本接口不加载真实数据或权重。
summary = aggregate_modality_strata(frame, contributions)
```

无拟合检查命令（仓库根目录，原图依赖环境）：

```powershell
.venv-research-graph/Scripts/python.exe -B -m unittest research_runtime.tests.test_graph_modality_strata -v
```

现有模块新增`evaluate_modality_strata(frame, splits, loader, candidates, expected, expected_explanation, *, expected_input_fingerprint, expected_counts, ...)`内存评价入口；`loader`由可信宿主提供，`candidates`绑定完整45原外折身份/字节hash/训练输入，`expected`提供原三臂三种子两原因OOF hash/AD Brier/四组性能，`expected_explanation`提供原importance和order_sensitivity。默认真实范围固定1160人/276、152、0、732；`constructed=True`仅用于显式人工验收，输出状态区分人工与真实。

路径先重放45基线，核对24原两原因OOF hash、12 AD Brier及四组全部人数/性能，才进入每原模型17次双序游戏和1次不变性重放。种子有符号均值的两原因重构与原总体importance/order_sensitivity逐项核对。旧总体排序/符号分母继续核对原定义，新分层另用三臂共同分母。输出只有聚合和尝试/完成计数，没有个人数组。

调用上限855、状态行198360、派生人月4760640，1800秒/2GiB；API逐次软检查，实际受控宿主另须独立计数和父进程硬停止。任何门槛/身份/资源/计数失败都不能返回完成，调用方须保留失败并禁止重试；内存API不管理持久启动权限。固定宿主读取范围为原CSV一次13列、原图Folds.private.json一次、45原外折各一次，0拟合/校准/内折/全量/局部回归；同缓冲hash后复用原禁pickle归档校验。

该评价是回顾性、事后描述的固定预测解释敏感性。MRI-only空组不能支持完整APOE×MRI分解，自然缺失组之间的差异不认证因果、公平性、独立或临床有效性。原总体图增益阴性、方向先验及训练/参考敏感性结论继续保留。
