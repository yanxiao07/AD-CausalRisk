# 十变量离线漂移与缺失计算

`research_runtime.feature_drift`为FR-25提供纯内存计算：比较同一特征/模型契约下的固定训练参考与待比较批次，报告PSI、缺失率变化、分母和可评价状态。仅使用既有NumPy与标准库，复用`input_admission`的十特征顺序及单位，不运行其预测准入流程。

这是监控计算子集。模型校准衰减、结局/亚组性能、真实部署数据、自动报警、模型更新及临床用途分别验收；本接口不加载模型、不预测、不拟合，也不读取或保存文件。

## 调用约定

| 类型/函数 | 作用 |
| --- | --- |
| `FeatureSnapshot` | 数据/清单摘要、模型/特征契约版本、身份命名空间、唯一人员ID tuple与n×10 ndarray |
| `ReferenceBinning` | 同一参考版本/摘要和契约、十个固定切点tuple；每特征1—19个严格递增有限切点 |
| `binning_digest(plan)` | 对固定分箱及其非逐人元数据建立一致性摘要，不估计分箱、不对个人行作hash |
| `evaluate_feature_drift(reference, current, plan, expected_binning_sha256=...)` | 核对冻结摘要与参考/比较契约，执行整表隐私门槛，再计算统计 |

特征顺序及单位复用既有定义：年龄/教育为years，认知量表为score，APOE4为count，双侧海马体积为mm3。模型、特征契约、单位、命名空间或参考版本/清单不一致即阻断；不同当前数据版本及部分重叠人员可比较。两批各自人员ID唯一，矩阵行与ID一一对应，最多各100000行。

只有数值ndarray被接收；NaN是缺失，inf、字符串、布尔、复数及错误形状直接拒绝，不改成缺失。调用会复制只读快照，不修改原数组。ID只用于去重/交叉批次隐私分组，不进入报告。

参考方须在查看待比较批次前确定分箱，记录`binning_digest(plan)`；本函数不会使用当前数据重新定箱。区间为`(-inf,c1]、(c1,c2]、…、(ck,inf)`，边界相等时归左箱，两端超界值保留在尾箱。摘要绑定防止无声换箱，但调用方重新计算摘要不等于独立证明“只使用训练参考”；输出始终保留`referenceBinningIndependentlyVerified=false`。

## 人工例子

```python
from dataclasses import replace
import numpy as np
from research_runtime.feature_drift import (
    FeatureSnapshot, ReferenceBinning, binning_digest, evaluate_feature_drift,
)

reference = FeatureSnapshot(
    data_version="synthetic-reference-v1", manifest_sha256="a" * 64,
    model_version="synthetic-model-v1", feature_schema_version="ten-feature-v1",
    subject_namespace="SYNTHETIC-IDS",
    subject_ids=tuple(f"REFERENCE-{i}" for i in range(30)),
    values=np.repeat(np.array([-1.] * 10 + [1.] * 20)[:, None], 10, axis=1),
)
plan = ReferenceBinning(
    reference.data_version, reference.manifest_sha256, reference.model_version,
    reference.feature_schema_version, reference.subject_namespace, ((0.,),) * 10,
)
frozen_binning = binning_digest(plan)
current = replace(
    reference, data_version="synthetic-current-v1", manifest_sha256="b" * 64,
    subject_ids=tuple(f"CURRENT-{i}" for i in range(30)),
    values=np.repeat(np.array([-1.] * 20 + [1.] * 10)[:, None], 10, axis=1),
)
report = evaluate_feature_drift(
    reference, current, plan, expected_binning_sha256=frozen_binning,
)
assert abs(report["featureResults"][0]["psi"] - 0.46209812037329684) < 1e-12
```

这些是人工数字，非ADNI研究结果。复制声明/摘要不验证真实来源、数据权限、历史可得性或医学适用；本模块不改变已有数据访问与研究范围。

## 统计与未知状态

PSI沿用既有直方图公式：每批按该特征有限观测人数归一化，分布各自逐箱下限为`1e-6`，不再归一化，求和`(q-p)*log(q/p)`。它是约定分箱下的描述性量，不是条件独立检验、显著性或因果指标，不沿用旧自动预警阈值。

缺失率以该批全部人员为分母，`missingRateDelta=current-reference`。两侧分母和观察/缺失人数分别保留；空批次的缺失率/变化为null，完全缺失的PSI为null。正的有限观测人数不足5也不可评价。

任一侧只有一个占用箱时，无法在固定分辨率下区分真实恒定与箱过粗，PSI保留null及原因，不写为零漂移。箱内数值变化不改变本报告，也不额外输出“某一个原值是否不同”的标签。相同且可评价的多箱分布才会得到PSI=0。

## 整表隐私门槛

对两个批次的人员并集，联合划分“参考是否存在及十特征缺失/箱状态 × 当前是否存在及十特征缺失/箱状态”。任何非空联合小组不足5人时，整份报告`SUPPRESSED`，`population`和`featureResults`均为null，PSI计算次数为0；不输出失败小组数量/模式或精确小人数。

因此单特征边际安全而多特征交集仅1人、两批人数均大但差集只有1人，或重叠人员中仅1人转箱，都不能绕过门槛。通过后只输出总体/重叠人数与每特征观察/缺失分母和统计，不输出身份、原值、切点数值、逐箱人数或联合模式。这不是差分隐私，也不认证任意重复、重叠或自适应查询之间的安全性；真实发布边界仍须独立控制。

## 检查与完成边界

27项人工检查通过：独立解析PSI、零箱下限、右闭边界、完整缺失分母、空/常量/分辨率不足、三类联合反推挑战、版本/单位/身份/固定分箱、非法值、确定性及原输入保持。另与封存旧PSI函数在人工数据上的结果一致，只提取其`finite/psi`函数，不运行旧文件流水线或校准拟合。

```powershell
python -B -m unittest research_runtime.tests.test_feature_drift -v
```

相关构造验收启用临床/参数/网络及预测模块导入拒绝，守卫在本测试模块结束后停用。0真实临床/OOF/模型/CIF/优化或校准拟合；并未完成全课程、其他平台或真实模型监控验收。
