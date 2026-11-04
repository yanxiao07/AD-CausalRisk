# 无拟合24月校准描述与预定义亚组监控

`research_runtime.performance_monitor`接收已保存的调用方概率及结局内存，输出参考/待评批次、总体和预定义亚组的Brier、固定分箱校准描述和未知结局补全界限。没有文件入口、模型加载、CIF、校准回归、KM/IPCW估计、自动报警或模型更新。

复用既有`decision_curve`的人员/24月结局/概率校验及分母界限；固定分箱与`evaluation.BIN_EDGES`一致。它不调用旧校准拟合或旧真实评价流水线。

## 输入与绑定

| 类型/函数 | 行为 |
| --- | --- |
| `ModelBinding(version, artifact_sha256, prediction_policy)` | 声明保存模型及raw或已保存校准策略；同一模型在两批的绑定必须相同 |
| `SubgroupBinding(name, rule_version, rule_sha256)` | 预定义亚组的规则版本与摘要，最多8个，允许组间重叠 |
| `MonitoringProtocol` | 固定参考版本/清单、24月终点、队列锚点、身份命名空间、1—9模型和亚组规则 |
| `MonitoringBatch` | 批次版本/清单与同一契约、`HorizonObservation` tuple、逐模型`{人员ID:概率}`及`{亚组名:人员ID tuple}` |
| `protocol_digest(protocol)` | 对预定义元数据建立一致性摘要，不对个人行作hash |
| `evaluate_performance_monitor(reference, current, protocol, expected_protocol_sha256=...)` | 先核绑定及整表联合隐私，后计算全部描述；不读取或核验源文件 |

概率为有限原生number、范围[0,1]。每批人员唯一，所有模型须完整覆盖同批人员，按ID连接，字典插入顺序不定义配对。每批最多10000人。允许空批、空亚组及重叠亚组；未知成员、重复成员、漏模型或漏概率均拒绝。

两批共享ID表示同一锚点下的同一条固定事件历史，`event_type/observed_month`必须一致。后续访视更新、记录修订或新landmark需要明确的新问题和绑定，不能静默用同一ID覆盖旧历史。本接口不计算两批之间的配对差。

摘要和声明匹配不等于真实来源、OOF、实际规则成员或预定义过程已被独立认证；这些标记始终false。调用方必须在查看待评数据前冻结协议，并另外核实来源及访问范围。

## 结局与统计含义

固定24月AD首次事件：cause1为已知AD，cause2为其他痴呆竞争首次事件并计已知非AD，cause0/月24为已知非AD；cause0/月24前为未知，不能补成0。这里只使用上述记录定义，不认证疾病因果、独立删失或临床适用性。

每组分别输出已知AD/非AD/未知人数、全部人员平均预测、已知子集Brier及全组Brier补全界限。已知子集描述不能当全组性能；只有单一已知类别时仍可描述Brier，但不产生AUC、校准斜率或区分度结论。完全未知结局时已知子集指标为null，仍可计算有限样本的可能补全范围。

全组Brier对每个未知结局分别考虑`y=0/1`，求可达最小/最大平方误差。同组模型比较使用每个人**同一个未知结局**求RIGHT_MINUS_LEFT差的界限，不减两个互不相关区间；相同模型的成对差恒为0。

校准分箱固定为`[0,.2)、[.2,.4)、[.4,.6)、[.6,.8)、[.8,1]`。输出箱内全部预测均值、已知子集AD率、全箱AD率范围、AD率减预测均值的有符号差及绝对差界限；空箱/空组分母为0时保留null与分母标记。

ECE界限使用可达整数事件数。例：5人全部结局未知，预测均为0.25；可实现AD率只有0、0.2、…、1，最小绝对校准差为0.05，不能按连续区间误写为0。每个指标的上下界是该指标的可达极值；不同指标、模型、亚组的端点不保证由同一补全同时实现，不是置信区间或因果效应。

## 整表联合隐私

对两批人员并集联合划分：双方是否存在、记录结局状态、全部模型的固定概率箱、全部预定义亚组成员资格。任一非空联合小组<5时整份`SUPPRESSED`、`batchResults=null`，描述与成对差函数调用次数均0。

这同时覆盖逐模型/亚组边际均安全但交集仅1人、结局小组、两批单人差集及单人转箱。报告不输出身份、逐人概率/事件历史/亚组成员或失败小组模式。此门槛不认证差分隐私、自适应重复查询安全，亦不能将本报告与另一份独立“安全”报告直接拼接并继承联合安全结论。

## 最小人工调用

```python
from research_runtime.performance_monitor import (
    HorizonObservation, ModelBinding, MonitoringProtocol, MonitoringBatch,
    protocol_digest, evaluate_performance_monitor,
)

models = (ModelBinding("synthetic-model-v1", "a" * 64,
                       "raw_uncalibrated_24month_CIF"),)
protocol = MonitoringProtocol("synthetic-reference-v1", "b" * 64,
    "synthetic-AD-first-event-24m", "synthetic-first-MCI-anchor", "SYNTHETIC", models)
frozen = protocol_digest(protocol)

def batch(version, manifest, prefix):
    ids = tuple(f"{prefix}-{i}" for i in range(10))
    rows = tuple(HorizonObservation(s, int(i >= 5), 24)
                 for i, s in enumerate(ids))
    scores = {s: .1 if i < 5 else .9 for i, s in enumerate(ids)}
    return MonitoringBatch(version, manifest, protocol.endpoint_id,
        protocol.cohort_anchor_id, protocol.subject_namespace, models, (),
        rows, {models[0].version: scores}, {})

report = evaluate_performance_monitor(
    batch("synthetic-reference-v1", "b" * 64, "REFERENCE"),
    batch("synthetic-current-v1", "c" * 64, "CURRENT"), protocol,
    expected_protocol_sha256=frozen,
)
# 人工示例，非真实监控结论；总体Brier为0.01，固定分箱ECE为0.1。
```

29项人工检查通过，含81组Brier/ECE及27组成对差的独立补全枚举、1024个公开调用补全真值、边界0/1、早截尾/cause2、空/单类、绑定、排序及五类隐私挑战。验收启用受控文件/网络/预测模块导入拒绝，0真实临床/OOF/模型/CIF/优化或校准拟合；不声称真实模型监控、全课程CI或临床验收完成。

```powershell
python -B -m unittest research_runtime.tests.test_performance_monitor -v
```
