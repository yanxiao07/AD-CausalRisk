# Python解释一致性

`python_service.explanation_consistency`提供FR-16的合成工程子集：局部解释的可比范围检查、Spearman、Top-k重合、方向变化与按对象汇总。不改变四阶段、原评分器、接口或版本，不自动运行算法、读取文件、训练或保存。当前研究跨模型/跨折/跨方法验收仍待实际实验，不能用同一演示规则的重放一致替代。

## 使用现有研究结果

在仓库根目录运行；`request`与`result`是调用方已经持有的原AgentRequest/AgentResult对象。

```python
from python_service.explanation_consistency import (
    course_explanation, compare_explanations, compare_panels,
)

explanation = course_explanation(request, result)
pair = compare_explanations(explanation, explanation, top_k=3)
panel = compare_panels([explanation], [explanation], top_k=3,
                       resamples=1000, seed=0)
```

适配器先用原冻结Schema及请求绑定语义检查结果，拒绝篡改、串对象和错误版本；它不调用评分器。六项原规则贡献保持四位小数，未产生的项为null并保留原因，BLOCKED全部null。MRI项保留左侧优先、右侧回退的原定义，不拆成两个虚构贡献。贡献是最终分数截断之前的规则项，基准0.18；不是SHAP值或已校准风险概率。

观察摘要绑定对象、数据版本、窗口、原访视和特征，排除运行编号和模型版本。相同观察可比较不同运行；改变访视/来源顺序也改变该精确内容摘要，模块不擅自声明两份不同记录语义等价。`evidence.sha256`绑定适配时的完整请求和结果；耗时/运行编号变化可使证据摘要不同。摘要不是来源或行政授权证明。

## 输入及可比范围

`course_explanation`返回`synthetic-local-explanation-v1`记录，字段如下。也可由调用方构造相同结构的合成解释；所有字段必须提供，额外字段拒绝。

| 字段 | 含义 |
| --- | --- |
| schemaVersion/mode | 固定上述记录版本及SYNTHETIC_DEMO |
| subjectCode/dataVersion | DEMO-*对象与demo-*数据版本 |
| observationSha256 | 同一观察信息集的64位小写十六进制摘要 |
| featureSchema | 特征/来源粒度、推导及归组定义版本 |
| target/outputSpace/reference/unit | 相同目标、解释输出尺度、基准及单位 |
| modelVersion/methodVersion/fold | 双方可以不同，须明确；未分折为NOT_APPLICABLE |
| values | 1—128个命名项，有限数字或null；零是实测贡献 |
| missingReasons | 恰好覆盖所有null项，不能给实测项伪加缺失原因 |
| evidence | kind及sha256；适配器产生VALIDATED_COURSE_PAIR；手工构造使用CALLER_DECLARED_SYNTHETIC |

单对象的subjectCode、全部比较范围字段和特征键集合必须相同；差异抛出`ConsistencyError`。不能直接把局部带符号贡献、全局置换损失、不同背景SHAP值或不同目标当作可比数字。元数据与证据种类在外部传入时仍是调用方声明，不认证值正确、来源真实、折外身份或方法真正被执行。实际研究输入没有开放；重标DEMO不能创造授权。

每批每侧1—200个唯一对象，JSON编码合计上限2MiB。每侧固定一个modelVersion/methodVersion/fold及范围/特征定义；一个对象多折重复提交会拒绝，须逐折比较，不能当独立受试者。双方按subjectCode配对，不按数组位置；缺失对象返回leftOnly/rightOnly，不补全。每对还必须核对其观察摘要。无共同对象时汇总为null，不能当零相关。

## 指标与不确定性

Spearman在双方均非null的项上，先求平均并列秩，再求秩的Pearson相关；分别返回原带符号贡献及绝对贡献的相关。常数秩或不足两项返回null和原因；不输出小样本渐近p值。定义依据[SciPy Spearman说明](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.spearmanr.html)；运行模块只用标准库，SciPy仅在工作区独立验证时使用。

Top-k在每侧自己的非null项中按绝对贡献选取，包含第k位截点上的所有并列项，集合实际大小可大于k；小于k的可用项会标记。提供左右集合、截点、交/并集及Jaccard=`交集大小/并集大小`。任一侧为空则null；不把双方皆缺失报为完美一致。Top集合不只在共有非null项上选，以免掩盖一侧最重要项缺失。全零向量可以有全部并列Top项，但Spearman仍未定义；两者应一起读取。

`signChanges`比较数值贡献的正/零/负符号，包括零值转换；它不比较原研究按未舍入阈值生成的UP/DOWN/NEUTRAL标签，也不推断因果方向。

汇总每个指标的对象均值、definedSubjects/undefinedSubjects；不同指标的有效分母可能不同。默认1000次（允许200—2000）、固定seed的对象级有放回Bootstrap，计算均值分布2.5%/97.5%线性插值百分位。每项仅从该项定义的对象配对中重采样；少于两个有效对象无区间。配置、重采样单位、缺失策略和配对明细随结果返回。

这是给定合成对象的条件经验区间，既不包含模型重拟合/折选择/方法选择不确定性，也不建立总体推断或临床区间覆盖。同规则重放常得到1和[1,1]，只说明一致性计算可重现。来源真实性、折外身份、研究验收和临床有效性均保持false；没有自动判定“稳定”的阈值。
