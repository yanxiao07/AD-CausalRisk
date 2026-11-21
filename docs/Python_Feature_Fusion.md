# Python多源选择与纵向特征

本模块对应研究DATA_FUSION及研究FR-06/07的工程准备。当前范围仍是原AgentRequest中的DEMO合成记录、认知量表、APOE与normalized左右海马数值，不开放真实数据导入，也不改变原评分公式或模型输入。

## 共享选择规则

`course_fusion.select_fusion`为已校验、由调用方持有的请求提供内部融合视图；原工作流、CSV质量预检和响应语义校验共享此实现。它不读文件、网络或模型，不修改请求。

索引日为原最近访视日；最近日多访视仍阻断，认知值仍只取最近访视且不回填。特征只使用索引日及以前记录，取最近非空值，晚null不擦除早非空值；同特征同日值不同（包括null与非空）仍阻断，同日同值重复不增加独立来源。没有访视时不能拿特征日期猜索引日。零值始终作为观测。

冻结契约允许省略认知数值及featureValue；省略按原缺失政策处理，原请求不补null或0。矩阵中不存在数值字段的来源位置带fieldPresent=false，明确它不是实际观测；已有显式null/完整来源结构保持。原内容与请求身份摘要仍区分省略/null，参见[可选数值缺失](Python_Core_Validation.md#可选数值缺失)。

共享实现消除三个入口独立维护同一规则的差异；其本身不构成独立验证。构造回归、冻结原示例及维护方对合并main历史工作流的逐值对照继续约束兼容性。原接口响应、质量代码、顺序、版本和正常分数保持。

## 纵向特征矩阵

```python
import json
from pathlib import Path
from python_service.course_fusion import build_fusion_matrix

profiles = json.loads(Path('fixtures/synthetic_subjects_100.json').read_text(encoding='utf-8'))['subjects']
matrix = build_fusion_matrix(profiles, lookback_days=365, min_span_days=30)
assert matrix['subjectCount'] == 100
assert len(matrix['columnOrder']) == 26
```

也可把`prepare_csv`在整批合法时返回的typed requests传入；没有评分或HTTP提交，CSV内部占位runId仍只是输入绑定，不是业务任务。输入1—200个唯一DEMO对象/唯一runId、相同数据版本与预测窗口，紧凑UTF-8 JSON总计最多1MiB；非法整批不产生部分矩阵。

输出为`COURSE_SYNTHETIC_FEATURE_MATRIX`/`course-longitudinal-features-v1`，包括版本、配置、输入digest、字典、逐对象行、按同一列顺序排列的缺失矩阵和逐列覆盖率。每项字典记录公式、单位、最少观测日期数与跨度规则；输出对象不引用调用方可变记录。

MMSE、MoCA、CDR-SB和左右MRI各有5列，共25列：

| 列 | 含义与条件 | 单位 |
| --- | --- | --- |
| firstObserved | 声明窗口内最早的有效实测值 | score或normalized |
| latestObserved | 声明窗口内最近的有效实测值；不等于原评分器可回填的输入 | 同原值 |
| change | 最近值减最早值；至少两个不同观测日 | 同原值 |
| endpointRatePerDay | change除以两个端点实际间隔天数；达到min_span_days | 原单位/day |
| slopePerDay | 所有有效观测的中心化最小二乘斜率；达到min_span_days | 原单位/day |

斜率为资料整理中的显式代数计算，不训练或更新预测模型。APOE仅增加1列latestObserved，不构造基因型变化率或斜率。MRI的normalized不当作mm³，CDR-SB不当Global CDR；原研究量表继续允许小数。

窗口闭区间为`[索引日-lookback_days, 索引日]`，lookback_days为1—3650整数，1≤min_span_days≤lookback_days。`firstObserved`只表示此窗口内首个实测值，不认证入组基线、MCI纳入或历史可获得时间。此整理窗口不改变原评分器的特征选择规则；矩阵明确`modelInputCompatible=false`，新列未接入任何现有模型。

## 来源、歧义与缺失

每个字段保留原观测日、值、状态和`table/recordIndex/field`引用，recordIndex为传入AgentRequest数组中从1起算的位置，不是CSV物理行或认证来源ID。输入digest和逐对象requestDigest绑定精确内容，不认证真实性。`availableDate`保持null。

- 空值保持缺失，0是观测；最近null不擦除窗口内更早非空值。认知latestObserved也可能早于最近访视，但currentScoringValue保持原最新访视语义，绝不据此回填评分。
- 同特征同日同值重复保留全部引用，归并为一个观测日；冲突（包括null与非空）不平均或任选。不同访视编号同日即使值相同也标歧义，该字段描述量保持null。
- 单点不生成0变化/斜率。短跨度可以显示实测change，日率/斜率仍为INSUFFICIENT_HISTORY。中间缺失点保留，不插值；斜率只概括实际有效观测，不代表连续完整病程或外推。
- 无访视则无索引日，字段均NO_INDEX；不拿特征日期猜索引。窗口之前/索引之后/无索引无法定位分别计数。
- BLOCKED请求可保留原资料描述，riskScore仍null且eligibleForExistingScoring=false。工程特征不足与原评分质量分开，不能把engineeringStatus=COMPLETE当作临床或模型资格通过。

`missingMatrix`的true表示此列没有可用数字，包括MISSING、NO_INDEX、AMBIGUOUS和INSUFFICIENT_HISTORY；具体原因看对应states，不能把这些情况当作同一种生物缺失。覆盖率分母是唯一DEMO对象数，不是访视数或独立临床样本。

模块不访问输入文件、网络或模型，不补值、评分、训练或保存；modelExecuted/modelFitted/predictionProduced/persisted和所有来源/历史/临床认证均false。该工程子集不关闭真实研究遗传/MRI融合、RF/XGBoost/GAT或跨方法解释验收缺口。

运行`python -m unittest discover -s tests -v`检查共享选择与矩阵。测试包含原100对象、64种缺失组合、非线性序列的手算斜率、窗口/来源/重复歧义、零值、单点/短跨度和不调用模型/网络/输入文件。
