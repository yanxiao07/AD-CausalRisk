# Python缺失输入敏感性

这是FR17的合成验证工程子集：有界生成原规则的输入移除组合，随后由调用方显式执行原HTTP/工作流，再分析完整已验证结果。它不生成或选择降级模型，不估计校准可信度，不认证缺失场景的临床可靠性。原模型标识、四阶段、公式、窗口、HTTP、Java职责及研究资料保持。

## 生成场景计划

```python
from python_service.course_missingness import prepare_missingness_requests, validate_missingness_plan

# requests为调用方已有的原AgentRequest列表；run_ids须由调用方业务逻辑显式分配。
plan = prepare_missingness_requests(requests, run_ids, granularity='MODALITY')
verified = validate_missingness_plan(plan)
```

MODALITY默认按COGNITION（MMSE/MoCA/CDR-SB）、GENETICS（APOE）、MRI（左右海马）三组生成8个子集，mask=0为未移除基线。SIGNAL按MMSE、MOCA、CDR_SB、APOE、MRI左、MRI右六项生成64个子集。场景按subjectCode排序、mask递增，依次使用run_ids；没有隐式编号分配、提交、重试或执行。每个编号必须符合原runId格式、全局唯一，并与所有输入基线runId不同；这不认证编号在其他进程/Java中未被使用，调用方仍负责业务分配。

认知值在全部输入访视中置null，保留访视编号/日期和其他值；原本缺失或未提供的字段不伪补值/键。特征按所选名称移除全部历史行，包括重复、null和索引日后记录，避免更早观测重新成为有效来源。未选中的字段/单位/日期/模型/数据版本/窗口保持原样，不插补、不外推。inputChangedBeyondRunId明确记录实际内容是否改变；移除已缺失项不冒称新增证据。

每个场景保留基线/请求摘要、实际置null数量、移除行/非空值数量、原数组sourceMap。sourceMap为子请求保留行对应基线数组的1基序号；后续不能把删除行后的新序号误作原始行。它不是CSV物理行或认证来源ID。

原契约允许不提供featureValue，计划也保留该省略；移除这种行会计入removedFeatureRows，removedFeatureValues只数真实非空值，不能把未提供值算成观测或补零。未移除基线和来源映射仍保留原字段存在性；后续比较可处理这类合法原请求，详见[可选数值缺失](Python_Core_Validation.md#可选数值缺失)。

输入1—100个唯一对象/原runId，同一数据/模型版本与窗口；输入JSON合计上限1MiB。最多1000场景，场景编码总量4MiB、整计划上限6MiB。默认100对象×8可用，信号级16对象×64因超过1000被拒绝；调用方应明确分批和分配ID，不会自动拆批或续跑。validate_missingness_plan会根据原请求/分组/显式编号重新生成全部场景，并检查精确JSON内容（含mask类型、来源映射、摘要和执行标志），拒绝只改hash或冒改字段的计划。

计划生成与校验不调用评分器、HTTP、文件或业务数据库；scoringExecutedByThisCall/networkUsedByThisCall=false，clinicalConfidence=null，alternativeModelSelected=false。原阻断请求仍可被计划化用于诊断，不据此认定其已经合格；不支持的窗口仍保持原值，不能通过缺失组合改成24个月。

## 比较完整结果

```python
from python_service.course_missingness_review import review_missingness_results

# results是调用方已持有、对应全部场景runId的原AgentResult列表。
review = review_missingness_results(plan, results)
```

比较前重新生成并核对计划，再逐一校验原固定Schema和请求绑定语义。每个场景恰好一份结果，顺序可不同；缺失、重复、额外runId、错误身份/版本/分数/贡献/质量/图/阶段均拒绝，不返回部分数值汇总。比较函数不补交/重试，也不证明上游确实执行；upstreamExecutionVerified=false。实际HTTP执行证据须另行保留。

每个对象以mask=0为同输入基线，保留原runId和新的基线场景runId。分数差固定SCENARIO_MINUS_MATCHED_SUBJECT_BASELINE，只有双方COMPLETED才计算；任一BLOCKED则scoreDelta和riskLevelChanged为null。原返回分数仍是演示排序，不能据差值推断缺失风险、因果效应或概率误差。原始阻断原因始终保留；移除冲突模态后能出分只计unblockedDiagnosticSubjects，originalSourceProblemsAdjudicated和clinicalReuseAuthorized仍false。

报告逐项原贡献有无/变化、图节点状态、实际MRI侧与上下文、来源映射和截断调整。未生成贡献保持null并标REMOVED/ADDED，不视为实测0；实测贡献0保留PRESENT_BOTH。移除保护项可能升分，左右MRI回退会改变值，截断可能隐藏丢失贡献，因此不假设单调、可加或分数不变就可靠。临床可信度保持null，不选择替代模型。

sourceUseByTerm把场景数组序号映回输入基线数组，保存日期/单位/字段与来源标识，不复制原始测量值。重复来源保留完整数量和引用摘要，最多展示20个引用，截断显式标记；引用摘要不认证来源。每个mask报告总对象、基线/场景完成数、可比数、新阻断/诊断解除/双方阻断数，均值只在同一双方非阻断对象集合计算，不比较改变后的不同队列。无可比对象的均值/范围为null。

比较输入合计上限12MiB，完整原结果集合摘要另限8MiB；不写业务任务或审计。计划不是执行账本，未知提交不能靠重跑整计划恢复；沿用原runId的只读核对及Java业务历史规则，见[跨语言故障与恢复](Python_Interop_Recovery.md)。本工具不自动执行、更换runId或选取“最佳缺失模式”，不能代替缺失模态的真实模型性能、校准或临床验收。
