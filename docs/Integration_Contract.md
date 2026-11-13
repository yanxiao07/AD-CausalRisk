# Java与Python联调约定

独立表格研究通道提供 Python 内存适配、语义校验和环回合成宿主。Java 的研究任务接口接收已登记的人工冻结快照，调用独立宿主并保存双原因 CIF。以下描述当前演示接口。[研究契约](Research_Table_Agent_Contract.md) · [Java 研究任务](../backend/README.md#表格研究评估适配)

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

合法认知/MRI的number字段在runId身份比较时按精确数值处理：25与25.0、0与-0.0可以重放；不做数值舍入或容差合并。APOE计数/窗口仍执行原严格整数规则；真实值、日期、数据版本、数组顺序及缺字段/null差异仍冲突。Python保留首个请求、原内容摘要和实际执行耗时，不将此内存身份规则当Java持久业务幂等。

graph包含nodes与edges，节点可为ACTIVE/MISSING，边和贡献标记研究演示范围。stages只包含实际执行阶段；BLOCKED可以只执行DATA_FUSION。阶段耗时是实际计算时间，界面不应按固定百分比替代。

python_service/deepagent_contract.py负责输入校验和四阶段编排，demo_scoring.py负责演示排序。调整规则、请求字段或返回结构时，同步示例与契约测试。

### 图谱与评分的实际关系

四阶段使用同一份输入。融合选择索引日之前每项特征最新的非空观测，同日相同值归并；图中的ACTIVE节点及对应ACTIVE输入边共同决定可传给评分器的特征。缺失的评分入口视为内部执行失败，不生成替代分数。日期必须为YYYY-MM-DD，不能使用紧凑日期或ISO周日期进行字符串比较。

图箭头表示研究先验和输入资格，不表示评分器计算了APOE→认知→结局的生物中介机制。节点ACTIVE表示观测可用，不能据此认定该观测必然贡献分数：两侧海马均可用时，现有规则优先左侧；左侧缺失时才用右侧。因此右侧可显示为上下文节点，但不应被解释成已计算的额外MRI贡献。

### 业务日期边界

课程请求及三表CSV中的“不能晚于今天”统一使用现代上海业务日（UTC+08:00），与后端`ClockConfig`的当前`Asia/Shanghai`日期一致，不随Python宿主机时区变化。Python使用标准库固定偏移，无需Windows额外安装IANA时区数据；该时钟只确定当前校验边界，不把历史观测日期转换成时间戳或推定其时区/可获得日。

每次请求校验、每次CSV行校验在开始时固定一个业务日，避免逐字段遇到午夜后产生混合边界。例如`2026-10-23T16:30:00Z`已是上海10月24日，日期`2026-10-24`合法、`2026-10-25`仍拒绝。此处有意修正旧宿主日期的接受范围：UTC机器在16:00Z后不再误拒上海当天，日期已领先上海的宿主也不能放行上海明天。原严格日期格式、真实日历、错误码、版本/字段、公式、窗口及BLOCKED保持。协议错误/审计时间戳继续使用UTC。

`python-demo-logistic-v1`是保留的旧接口标识；实际算法是加性演示规则加上下界截断，不是拟合的逻辑回归。贡献使用四位小数，基准为0.18，最终分数截断至[0.05,0.95]；解释贡献总和应结合基准、截断和舍入理解。`deepagent-causal-warning-v1`也不表示因果效应已识别。

本地修订强化图到输入的计算连接、归并重复观测及严格日期校验，保持deepagent-v1字段、现有模型标识和正常输入的分数。原课程发布记录仍绑定其原提交；本地修改需要另外交付后才会出现在远端，同学不能依据旧CI记录推断新代码已经发布。

`course-python-2026.10.11.1`提供完整源码ZIP及相对`fb4a09d243bf15170b478d484963312d490da83d`的定向补丁。已有Java/前端工程优先应用补丁：先检查`git status`保留自己的工作，再运行`git apply --check <补丁路径>`；检查成功后运行`git apply <补丁路径>`并执行本仓库测试。检查失败时按实际差异处理冲突，不能用完整ZIP覆盖已有工程。完整ZIP用于独立新目录启动，不替代业务数据库或任务数据。

Java业务接口另见contracts/java-business.target.openapi.json：当前59个操作已实现并经合成本地验证，完整后端验收边界见[后端验收](Backend_Requirements_Acceptance.md)。患者结果必须选PatientAssessmentSummary并在后端过滤；仅隐藏前端按钮不足以防止接口泄露。成功Envelope和PythonAgentResult格式不同，不能把data.data错当原Agent对象。

报告导出支持HTML/PDF/JSON是课程要求，当前保留JSON资源并新增download附件入口，媒体类型与下载参数已记录于同份OpenAPI。后续变更媒体类型、下载头、错误DTO或状态时继续同步契约、调用方和测试。

## Java业务扩展（2026-11-08）

Java OpenAPI已记录59个实际操作。浏览器使用异步submit入口，Java固定输入/质量绑定后后台调用仍为同步的Python；仅能取消Java排队任务。应用重启关闭遗留任务，不自动重放算法。原同步POST和报告JSON资源保留。raw数值参与请求；非法原始值不能经显示舍入变合法。来源、缺失、版本、授权、模板及未完成条件见[业务状态](Business_Feature_Status.md)。
