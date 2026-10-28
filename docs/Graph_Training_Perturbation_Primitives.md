# 图训练扰动的拟合与跨参考原语

[完整流式编排](Graph_Training_Perturbation_Streaming.md)及[225次真实固定研究](Graph_Training_Perturbation_Results.md)已完成；以下为可复用原语及构造验收。

本模块服务第2→3→4环节已锁定的[图训练扰动设计](Graph_Training_Perturbation_Design.md)。跨参考解释及单次受控拟合原语已构造验收；225次真实图研究已完成并有聚合交付。现有图增益阴性与个人因果限制保持。

## 计算接口

`graph_training.GraphFitBudget(limit, ...)`只在内存保留次数和资源计数，不读取输入文件或保存参数。`fit_graph_subjects(frame, arm, seed, budget=...)`接受调用方已核实、隔离的训练人员，重置行索引后使用原`person_period`及`make_graph_estimator(...).fit`，每次仅拟合一条原生图管线。原40epoch、优化器和预处理均在未改动的graph_native中执行。

| 计数 | 含义 |
| --- | --- |
| attempts | 原生管线调用前预记的一次尝试 |
| fit_returns | 原生管线已返回；可能随后因输入、预处理或资源核验失败 |
| completed | 输入未变、训练内中位数/均值/尺度、三类/图方案、40轮有限loss及资源检查均通过 |

失败会锁定已开始拟合的预算，不隐式重试。资源检查发生在拟合前后，不能中断正在运行的拟合；真实研究仍须独立宿主计数与父进程硬停止。该原语不选择折、不接收测试数据训练、不做校准/内折/全量训练、不保存参数，不替代来源或训练人员隔离准入。

`graph_explanation.telescoping(model, frame, baseline, calls, reference_values=...)`新增可选的原量纲十维有限数值参考。默认省略时沿用模型原中位数；显式参考只替换已观测的输入，不改模型的插补、尺度、参数或路由。保持九组、联合双侧海马、缺失和原正反顺序，每个游戏仍为17次CIF，返回值结构保持。

测试输入在副本上转为浮点列，支持整数/nullable integer的分数参考；拒绝部分双侧MRI、布尔/复数、无穷值、坏参考及非法两原因输出。调用方须先核对并缓存同一模型/输入的完整预测baseline；其复用不能代替原始OOF或最后重放检查。解释保持有序预测分解，不称Shapley/LIME或因果效应。

`graph_explanation.Calls`保留默认`limit=1230`及原`count`（尝试数），新增`completed`、`state_rows_attempted/completed`、可选状态行上限和只含计数的事件回调。尝试发生前记账，CIF或事件失败后锁定；事件失败时完成数可与尝试数不同。调用方不能把失败后的已返回数或接受数混称完整矩阵。

```python
import numpy as np
from research_runtime.graph_training import GraphFitBudget, fit_graph_subjects
from research_runtime.graph_explanation import Calls, telescoping

# qualified_train/test and original_training_medians are caller-bound memory.
budget = GraphFitBudget(1)
model = fit_graph_subjects(qualified_train, 'prior', 20260926, budget=budget)
calls = Calls(19, max_state_rows=19 * len(qualified_test))
baseline = calls(model, qualified_test)
reference, values, error = telescoping(
    model, qualified_test, baseline, calls,
    reference_values=original_training_medians,
)
assert np.array_equal(calls(model, qualified_test), baseline)
```

示例只说明原语组合：19次含一次完整预测、17次游戏及一次结束重放；没有输入读取、临床使用或新研究授权。返回的个人数组仅在获准内存使用，不能默认落盘。真实三种子贡献/CIF先平均，再做L1/排名/Brier，并按原协议固定人员分母；完整矩阵聚合已在图解释模块内实现。

## 实际验收

20项新原语检查连同8项原图解释、9项精确归因兼容检查，共37项首次通过。风险训练、预处理拟合及优化步均禁止；包含1398次常量构造CIF及明确标记的模拟拟合返回，不计真实训练。

另在90人纯人工队列的一个原折内，固定57名训练子集、18名测试人员，三臂×三种子共9次人工基础管线拟合首次通过；原模型端仅赋常量参数，没有额外优化。训练子集的APOE列全部缺失、排除/测试人员仍有观测，核对训练内0回退与尺度1，以及三臂初始化、批次、输入一致。9套预处理中各有一次imputer与scaler，均包含在9管线内，不是18次基础拟合。

四游戏/双原因/正反序共648次构造CIF，最大重构误差约4.16e-17、有符号M/R/I/T误差0，完整预测重放相同、原/新模型和输入未变。实际运行约17.078秒，峰值413171712字节。0真实队列/旧参数加载/校准/局部回归，未保存个人结果或新参数，该构造阶段主线为448基础/36校准，当前图研究完成后为673/36。

旧模型归档绑定的graph_native.py与graph_protocol.json原始字节保持；历史冻结运行源码不修改。新接口在graph_training/graph_explanation实现，不切换默认，不改变Java/HTTP/UI/论文。

固定原语、九次人工优化拟合及完整编排验收已关闭并复用，不为源码交付重新训练。
