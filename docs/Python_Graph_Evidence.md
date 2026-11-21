# Python先验图模板与来源路径

本模块提供FR08/09的合成验证工程子集。默认五节点/四方向仍为原`synthetic-prior-v1`，ACTIVE表示输入资格，不表示学习出的边、因果效应或稳定性。真实研究医学图模板、完整人口学/生物标志物节点及临床证据仍须单独协议和验收。

## 查看与检查模板

```python
from python_service.course_graph import (
    default_graph_template, validate_graph_template, inspect_course_graph,
)

template = default_graph_template()  # 独立JSON对象，可由调用方序列化
view = inspect_course_graph(request)  # request为原AgentRequest

candidate = default_graph_template()
candidate['templateVersion'] = 'demo-candidate-1'
candidate['edges'].pop(0)
checked = validate_graph_template(candidate)
candidate_view = inspect_course_graph(request, template=checked)
```

模板字段固定为schemaVersion、mode、templateVersion、nodes、edges。schemaVersion=`course-prior-template-v1`，mode=`SYNTHETIC_DEMO`；节点保留原COGNITION、WARNING、APOE及左右MRI，允许调整显示标签。方向仅允许三来源→COGNITION及COGNITION→WARNING的子集；标签改变或边删减须使用`demo-*`候选版本。重复/未知节点、悬空/逆向/自环/其他环、重复边、任意权重或学习/因果证据声明被拒绝。证据等级/方向/限制说明保持原定义。

`synthetic-prior-v1`内容不能修改或重排序后复用原版本。候选版本名是调用方声明，不是持久版本注册；每次返回templateSha256绑定精确内容，相同版本名不能代替内容核对。模板上限64KiB，原请求上限128KiB，输入先快照再校验；没有网络、模型、文件或数据库操作。

工作流始终调用默认图构建器，未增加模板请求字段，候选配置不进入任何评分器。`inspect_course_graph`只按原索引日/最新非空/缺失/冲突规则实例化图；riskScore恒为null，modelExecuted/modelFitted/predictionProduced/persisted均false。BLOCKED保留空图，原模板且非阻断时的originalScoringEligible只表示符合原资格，不能解读为已经执行评分。候选始终originalScoringEligible=false。

该图不增加未采集的人口学/PET/CSF字段，不证明医学边方向。原左右MRI均可作为上下文ACTIVE，实际演示规则仍左侧优先、缺失才回退右侧；两侧ACTIVE不产生两个MRI贡献。来源历史可获得日和真实性、边稳定性及临床有效性仍未建立。

## 原结果的来源与代数路径

```python
from python_service.course_path_evidence import build_course_path_evidence

# request/result均为调用方已持有的原研究对象；这里不会执行评分或请求HTTP。
evidence = build_course_path_evidence(request, result)
```

输入先取有界快照，再通过原冻结Schema和完整请求绑定校验；伪造贡献、错误身份/版本/质量/图或分数均拒绝。返回requestSha256/resultSha256、原版本、默认模板摘要、原资格图及独立的规则来源图。每项包含实际输入sourceIds、原解释项索引、公式、未舍入贡献、原四位小数贡献/方向/证据等级；不改变原结果。

`sourceNodes`保存原值、单位、visitCode/featureName及声明观察日期。recordIndex是传入数组1基序号，jsonPointer是标准0基数组指针，都不是CSV物理行。历史availableAt没有证据时为null。认知来源严格为原索引访视，较早值不回填当前缺失；MMSE_DELTA使用原规则的全输入历史首末访视，不受另行资料矩阵lookback影响，也不认证真正入组基线。日期并列的非索引访视按原选择策略排序，不额外改写旧算法。

APOE/MRI来源只引用索引日之前实际选中的非空观测。较晚null不擦除旧值，索引日后记录不使用；同日同值重复行全部保留引用，但只形成一个规则项/一个贡献权重。两侧MRI可用时右侧只列入contextOnlySources，不连接至额外贡献。左侧为0仍优先使用；仅左侧不可用时回退右侧。

规则来源图的DERIVED_FROM边不赋数值权重；ADDITIVE_TERM边权重为该项原贡献，单位score，允许负数和零。所有路径都明确causalInterpretationAllowed=false。这里的方向、边与权重是代数来源，不是生物中介、学得强度、个体因果效应或Bootstrap稳定性。

`reconstruction`逐项使用原结果检查器的未舍入规则值，保留固定0.18基准、实际加法顺序、[0.05,0.95]截断调整及最终四位舍入调整。另列原展示贡献之和的截断重构、与返回分数的残差及原舍入上界。贡献四位数之和可以与最终分数有小差异，不把截断或舍入残差伪造为另一条解释。rawContribution与方向阈值保留未舍入语义，显示0.002并不必然意味着原方向NEUTRAL。

未生成的六项通过missingTerms给出具体原因，不补0。BLOCKED保持空资格图、空sourceNodes/termNodes/edges/paths、outputNode和reconstruction为null，全部missingTerms为QUALITY_BLOCKED。upstreamResultScore仅抄录已验证结果（阻断为null），predictionProduced仍false，没有新推理或保存。新图证据对象不是共同HTTP DTO或Java业务审计/授权报告。
