# Java与Python联调约定

Python只承担合成工作流，不访问Java数据库、不管理用户会话、不写业务任务表。Java向Python传当前任务需要的合成输入，按contracts/python-agent.openapi.json校验结果后保存；前端只调用Java。

| Python情况 | HTTP | Java应做什么 |
| --- | --- | --- |
| 完整输入成功 | 200，status=COMPLETED | 保存任务和结果、四阶段轨迹、版本、SYNTHETIC_DEMO标记 |
| 数据质量阻断 | 200，status=BLOCKED、qualityStatus=BLOCKED、riskScore=null | 任务BLOCKED，显示原因，不显示可用分数 |
| 格式/字段错误 | 400、VALIDATION_400 | 显示可读错误、失败审计，修正输入后创建新请求 |
| 契约/模型不匹配 | 409、AGENT_CONTRACT_MISMATCH | FAILED并保留失败原因，不使用Mock结果静默成功 |
| 同runId不同请求 | 409、RUN_ID_CONFLICT | 检查Java幂等和runId生成；禁止覆盖原结果 |
| 缓存内没有runId | 404、RUN_NOT_FOUND | 查询Java已持久化结果；若需重算，保留版本与任务关系 |
| 请求超过128KiB | 413、PAYLOAD_TOO_LARGE | 不无限重试；按100访视/500特征限制检查输入 |
| Content-Type错误 | 415、UNSUPPORTED_MEDIA_TYPE | 使用UTF-8 application/json |
| 缓存1000条已满 | 503、RUN_CAPACITY_REACHED | 展示服务不可用，保留历史结果；本机演示可重启Python |
| 网络超时/连接失败 | 无有效HTTP响应 | FAILED，记录超时或服务不可用；不能伪造阶段成功 |

所有请求日期使用YYYY-MM-DD，未来日期不合法。同日相同特征值冲突会阻断；索引访视之后的特征被排除并产生WARN。缺失模态允许WARN；没有任何可用核心认知输入时BLOCKED。

predictionWindowMonths在请求层接受6–60整数，当前演示规则实际仅支持24个月；其他有效窗口返回HTTP200/BLOCKED且无分数。前端默认24个月，并如实展示不支持的原因，不按窗口倍数插值伪造结果。BLOCKED图为空nodes/edges/paths，可以没有graphVersion；完成图必须有graphVersion。

相同runId和相同语义JSON摘要重放返回原缓存内容，包括第一次运行的durationMs；JSON字段顺序不影响摘要。返回结果经副本保护，并发同请求只生成一次缓存记录。服务端没有提供SSE、WebSocket或取消接口。

graph包含nodes与edges，节点可为ACTIVE/MISSING，边和贡献标记研究演示范围。stages只包含实际执行阶段；BLOCKED可以只执行DATA_FUSION。阶段耗时是实际计算时间，界面不应按固定百分比替代。

合成评分源自作者原演示适配器的明确函数闭包，未复制其科研服务入口。deepagent_contract.py与原合成编排字节一致，docs/source_provenance.json记录来源SHA。此独立入口不读取data、论文、科研聚合或本地Java目录。

Java业务目标接口另见contracts/java-business.target.openapi.json：22个操作是待后端同学实现的目标。患者结果必须选PatientAssessmentSummary并在后端过滤；仅隐藏前端按钮不足以防止接口泄露。成功Envelope和PythonAgentResult格式不同，不能把data.data错当原Agent对象。

报告导出支持HTML/PDF/JSON是课程要求，目标OpenAPI当前冻结JSON资源。新增媒体类型、下载头、错误DTO或状态时先做契约PR，随后补Java客户端和前端验收。
