# Python输出审查

## 产品文案与历史兼容

2026-11-10：新生成的CDR-SB解释使用“CDR-SB 信号在当前排序中贡献上移”；运行不存在和缓存已满的错误说明使用业务表述，继续说明进程缓存易失及历史结果应由业务服务读取。HTTP状态、错误码、字段、评分和来源不变。

校验端仅对CDR-SB这一个文案变更增加**精确的旧字符串**读取兼容，不作子串/模糊匹配；旧结果原文不重写，不接受其他病因/治疗声明。报告、客户端、缓存与只读恢复共用新校验端。发布时须将Python服务和使用严格文本校验的Python消费者更新到同一版本：新校验器可读旧结果，旧校验器不能接受新CDR措辞。Java字符串字段结构不变；本次没有部署服务。

`serviceMode=COURSE_SYNTHETIC`、`dataSource=SYNTHETIC_DEMO`和原API/模型版本仍是当前计算模式及追溯标识，不能靠更名声明真实模型已部署。真实业务模型接入尚未完成。历史文档与冻结例子的原文保留。

FR20在当前Python四阶段中的工程范围是：保证原合成规则输出的数值、来源、图与说明一致，拒绝夹带未经支持的因果、治疗、确定性或临床认证文字。固定规则使用逐字段确定性词表，不通过敏感词黑名单推断任意医学文本是否真实。研究模型、自由文本生成器及实际临床安全认证仍需各自协议和证据。

`validate_course_result(result, request, CourseContract())`现在同时审查文字。工作流返回、易失缓存提交、客户端POST/GET、只读恢复、原课程报告和批处理共用此入口。原版本、Schema、字段、评分公式和正常输出保持；不增加第五个虚构阶段或新HTTP操作。

审查覆盖所有解释标签/message/interpretation、图节点标签、边方向/说明与证据、全部原阶段summary键集、原三条限制。已有原数值/质量/版本/来源校验继续执行。说明使用原始未舍入贡献符号，特别保留零项和MMSE纵向变化的原措辞。CDR零项的原“贡献上移”文案表示该规则项的方向性描述，不代表严格正贡献；数值仍为0，不能据此宣称有效治疗。

此前允许附加limitations和未知summary元数据；这些容器也可能夹带正面临床主张，现在拒绝所有未经规则评审的扩展，包括看似额外的限制。此处收紧语义准入，原线协议Schema不变。新增说明或元数据时，先评审对应规则与调用方，不能静默降级成未审内容。普通输入里的合法中文、访视编号及原值保留；它们是来源数据，不作为模型解释或临床结论。

## 离线审查记录

```python
from python_service.course_safety import review_course_output

review = review_course_output(original_request, returned_result)
# review['status']: ACCEPTED / REJECTED
# review['findings']: 只含固定错误代码，不回显坏文案或原始输入
```

只接受原DEMO请求/结果；请求最多128KiB，结果最多2MiB（紧凑、排序UTF-8 JSON）。先有界复制再校验，不调用工作流、评分工具、网络或模型。记录绑定提供的请求/结果JSON摘要、冻结契约摘要及本地审查规则相关源码摘要；字典键序不影响摘要，数组顺序是内容的一部分。摘要只用于内容追踪，不认证来源、实际执行、用户身份、授权或Java持久审计。

ACCEPTED只表示原课程输出与规则一致。reviewRequired=true，manualReviewCompleted=false；clinicalUseAllowed、clinicalValidityEstablished、causalEffectIdentified、sourceAuthenticityVerified、upstreamExecutionVerified、modelExecutedByReview和persisted始终false。合法BLOCKED可以审查通过，但predictionAvailable=false、原分数null/UNAVAILABLE；REJECTED也始终predictionAvailable=false，不修补或输出替代预测。审查记录不能充当Java医生人工复核、患者摘要、业务授权或导出许可。

工具坏输出返回原HTTP500/ASSESSMENT_FAILED，不写缓存；客户端收到结构合法但语义不合格的HTTP200，POST保留request_outcome_unknown=true，GET/恢复保持失败并且不重发。原报告拒绝该结果；批处理停止后续请求，不保存不可信结果或报告。错误文字不透出工具私有详情。

## 验证

`python -m unittest discover -s tests -v`运行原合成用例与新审查测试。测试包括独立Schema仍合法的文字/元数据反例、未舍入零界、BLOCKED、摘要内容绑定、有界异常输入、不执行评分/网络，实际临时HTTP500、上游篡改POST/GET/只读恢复、报告和批处理停止。无需临床数据、研究环境或预先启动服务。
