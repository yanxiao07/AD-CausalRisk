# 真实研究模型接口候选与Java映射

第1→3→4环节有界工程支撑。此候选连接已完成的`TableAssessment`与业务调用方，复用原输入准入及数值实现，不训练、不读取临床/参数、不更换现有服务。

当前状态：**Python内存请求适配和返回语义校验已实现；HTTP监听、Java接入、真实数据准入未完成**。[OpenAPI草案](contracts/research-table-agent.draft.openapi.json)位于文档目录，`DRAFT_FOR_BACKEND_REVIEW`，不是活动`contracts/`或已部署路由。PR27已由远端合并至main `3774929`，本轮未执行合并；本候选仍是后续本地工作。

## 接口边界

候选操作为`POST /api/research-table/v1/assessments`，版本`research-table-agent-v1`。没有当前监听地址，不复用`deepagent-v1`或旧V8研究路径。`LocalBearer`只是将来服务间认证的设计，当前内存适配器不实现HTTP认证。

请求仅有9项：`contractVersion/runId/modelKey/subjectCode/dataVersion/indexDate/availabilityPolicy/predictionWindowMonths/features`。窗口严格整数24，特征为七变量或十变量精确集合，元数据必须显式存在；未知值保留null/UNKNOWN，不补0。输入UTF-8严格JSON最多128KiB，拒绝重复键、非有限值和额外字段。

模型路径、参数、摘要身份、`trusted_context`、授权开关、计算预算和来源范围均不能由请求提供。宿主预先独立绑定模型别名、受试者、冻结快照/时间策略及证据；错别名409、错受试者403、错版本/索引/时间策略409。读取真实来源、模型与授权仍由有权宿主承担，适配器不创建注册数据或可信上下文。

```python
from research_runtime.research_table_agent import ResearchTableAgent

# 仅在独立数据资格、参数绑定和执行权限已核验后，由宿主构建。
agent = ResearchTableAgent(model_key=approved_model_key, subject_code=approved_subject,
    assessment=verified_assessment, trusted_context=independent_context,
    data_scope='CONTROLLED_RESEARCH')
result = agent.handle_json(java_request_bytes, authorization_granted=True,
    prediction_authorization_ref=prediction_permission_ref,
    explanation_authorization_ref=explanation_permission_ref, max_coalitions=512)
```

授权参数是宿主断言，不能从请求JSON读取；`data_scope`是用途声明，不证明数据真实。公开[示例](contracts/examples/research-table-agent/request-seven.json)只使用`CONSTRUCTED_VALIDATION`人工状态，不能复制成真实上下文。未知QC/完成等仍正常BLOCKED，零计算、预测/解释/binding均null；非法格式返回稳定错误，绝不返回部分数值。

## 返回与消费校验

成功保留`prediction.adCif24m`和`otherDementiaCif24m`及同次SHAP，不生成旧riskScore/riskLevel、临床阈值或因果结论。返回含原准入、模型身份、输入/context/参考绑定、计算次数和false边界。个人结果与摘要只交有权调用方内存，不应公开或直接传患者端。

消费者同时检查Schema和语义。标准库入口：

```python
from research_runtime.research_table_protocol import validate_response

checked = validate_response(response, expected_request=original_frozen_request,
    expected_model=independently_pinned_model_identity,
    expected_data_scope=approved_scope,
    expected_context_sha256=independently_pinned_context_digest)  # 可选的额外pin
```

expected对象不可从收到的model/binding复制。该函数检查身份/请求摘要、预期模型、来源范围、单例分母/缺失、状态与调用次数、null、false边界、双原因CIF和、解释端点/贡献重构及参考/输入绑定。它不认证上下文来源；未提供独立context摘要时，只核格式与其他绑定，不能声称已比较该摘要的独立pin。

JSON Schema将1.0视为integer，不能据此放行APOE；适配器和语义校验仍要求严格整数token。Schema也无法表达两个概率之和及Shapley等式，因此不能只检查JSON形状就保存为可信成功。异常仅输出固定码，不返回内部URL、原始值或异常正文。

`runId`仅关联调用，**不提供缓存/重放或持久化幂等**。相同请求再次执行会新消费宿主许可；Java须持久化自己的任务、冻结输入与幂等键。超时结局未知时不得自动重复POST；不能声称可GET找回、取消Python运行或实时推送阶段。

## Java字段与责任映射

下表对照现有`AgentRequest`，不等于全库没有这些材料。不得仅将DEMO编号改名为真实来源，不能把旧请求塞入新模型。

| 新输入 | 现有DTO可复用内容 | 必须补齐/禁止替代 |
| --- | --- | --- |
| subjectCode/dataVersion | 原关联字段的概念 | 由授权档案映射到独立冻结的研究别名/版本；不拼接前缀冒充真实 |
| indexDate/availabilityPolicy | 旧DTO未携带 | 冻结索引与来源证明；不能因可用日未知自动把AS_OF降为回顾性 |
| age_years / education_years | 旧DTO未携带 | 独立来源，年龄按索引正确派生，单位years，不从别名/默认值推断 |
| mmse / cdr_sb | 旧visits有mmse/cdrSb原值 | 绑定该索引的选中观察、日期/来源/QC/完成，不直接取任意最新访视 |
| cdr_global | 旧DTO未携带 | 独立CDR-global，不用CDR-SB推算 |
| adas__total13 | 旧DTO未携带 | 明确ADAS13，不拿TOTSCORE或MoCA代替 |
| faq__faqtotal | 旧DTO未携带 | 明确FAQ总分，不补默认值 |
| apoe4_count | 原APOE字段概念/原始计数 | JSON整数0/1/2；BigDecimal不得舍入后强转 |
| 双侧海马体积 | 原左右MRI字段概念 | 正数mm3、同scanId/日期/处理版本；normalized不能直接复用/猜换算 |
| 每项观察的元数据 | V11来源/原值记录可供追踪 | 完整sourceRef、evidenceRef、QC、完成、日期依据、缺失原因；旧APPROVED不能自动等于原始量表COMPLETE |
| modelKey | 现有modelVersion概念 | 独立批准的表格模型别名，绑定完整模型/来源摘要；不接受任意路径 |

Java继续做对象权限、用户会话、持久任务/幂等、原始输入快照、复核和审计。研究结果应以独立版本/JSON结构保存，不能覆盖历史agentResult或把AD CIF写入原演示排序列后丢弃另一原因。审计不记录完整输入/权限引用；患者摘要仍由后端白名单处理。

本轮不修改Java实体、DTO、数据库迁移、前端或共享活动契约。Java负责人需评审字段来源、授权/快照注册方式、新结果存储与超时语义，再确定HTTP宿主及双方适配；当前没有独立真实上下文，不得为联调造一份。

## 已验证与下一步

15项适配检查、13项统一入口检查（合计28）通过；另12项OpenAPI/Schema/语义检查通过，校验模块在`-S`下不导入NumPy/pandas/sklearn/joblib/Torch。七份公开样例含两规格完成、QC阻断和403；全部显式人工，不含真实数据/参数。

本工程达到可调用内存适配、可校验候选协议及Java映射终点。下一唯一工程为Java负责人对本候选与字段/快照责任给出具体确认后，实施独立研究HTTP宿主的有限联调；真实来源完成/QC/语言版本等资格继续独立等待，手机溢出后置。不为本任务重训或重读已关闭模型/研究。
