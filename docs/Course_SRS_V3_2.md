# AD-CausalRisk 课程协作需求基线

版本V3.2，2026年10月6日。基于[历史V3.1](reference/Course_SRS_V3_1_Historical.md)保留业务要求，补充可运行Python交付和完整模块责任。本文件及contracts目录对新增课程工程的接口与验收具有优先级；历史需求中未被调整的业务目标仍适用，历史实现不能代替同学的开发成果。

## 系统与人员责任

用户承担Python、合成数据、算法契约及独立科研路线。已有Python提供四段合成流程：DATA_FUSION、CAUSAL_GRAPH、WARNING_MODEL、EXPLANATION。图的箭头、演示排序和贡献不证明疾病因果机制，不使用真实个体研究模型。

后端同学完整负责新Java工程：Spring Boot、Controller/Service/Repository分层、MySQL表和迁移、登录权限、对象级数据范围、导入、质控、任务、Python客户端、结果、人工业务复核、随访、报告、审计、部署与接口测试。

前端同学完整负责新前端工程：项目结构、路由、认证会话、角色菜单、受试者和访视、CSV预览、质量页、任务状态、图谱/解释、医生/患者视图、随访、报告、错误/空/加载/无权限状态、响应式、交互测试与部署。

Python现成服务不会代替Java业务后端。作者已有Java工程和研究页面不作为同学接手的现成系统；backend与frontend从任务说明开始。同学独立完成整个模块，依照共同契约通过PR协作。

## 核心需求与验收归属

| 需求 | 内容与完成条件 | 负责人 | 初始状态 |
| --- | --- | --- | --- |
| FR-AUTH-01/02/03 | 登录/退出/当前用户；四角色与对象范围；密码哈希、统一失败提示 | 后端+前端 | 待开发 |
| FR-DATA-01/02 | DEMO档案、访视、临床/认知/APOE/MRI字段、单位、来源时间和版本 | 后端+前端 | 待开发 |
| FR-DATA-03/04/05 | CSV预览、字段校验、错误行、确认入库、幂等导入与数据版本 | 后端+前端 | 待开发；合成CSV已提供 |
| FR-QUALITY-01/02/03 | 保存质量检查及规则版本；PASS/WARN/BLOCKED；阻断不出分 | 后端+前端 | 待开发；Python结果规则已提供 |
| FR-ASSESS-01 | 权限校验、持久化任务、Idempotency-Key与唯一runId | 后端 | 待开发 |
| FR-ASSESS-02 | 显示Java任务状态，完成后展示Python四阶段轨迹及实际耗时 | 后端+前端 | 待开发 |
| FR-GRAPH-01/FR-WARN-01/FR-EXPLAIN-01 | 调用并保存图、演示排序与贡献、缺失、版本、限制 | 后端+前端 | Python返回结构已提供，业务保存和展示待开发 |
| FR-WARN-02 | BLOCKED的riskScore必须为null，图表和导出均不得补0或伪造数值 | 全组 | Python已实现，Java/前端待验收 |
| FR-EXPLAIN-02 | 患者只收到通俗摘要与随访信息；数值排序、图权重和内部轨迹在后端过滤 | 后端+前端 | 待开发 |
| FR-FOLLOW-01/02 | 医生创建、修改、完成、取消随访，展示逾期/近期计划 | 后端+前端 | 待开发 |
| FR-REPORT-01 | 授权导出HTML/PDF/JSON研究报告，带版本、来源和限制 | 后端+前端 | 待开发 |
| FR-AUDIT-01 | 登录、权限拒绝、导入、质控、评估、复核、导出审计 | 后端+前端 | 待开发 |
| FR-AGENT-01/02 | HTTP适配器、超时、重试、版本与BLOCKED校验、调用追踪 | 后端 | 可联调，业务实现待开发 |

## 冻结的集成语义

Python端口8091，契约deepagent-v1、模型deepagent-causal-warning-v1，仅接收DEMO-*和demo-*。公开操作为GET /api/health、POST /api/agent/v1/runs、GET /api/agent/v1/runs/{runId}。请求与响应直接是Agent结构，不套Java业务Envelope。

Python同步计算，尚无持久化任务队列、取消运行或实时阶段推送。执行中Java可以显示RUNNING；返回后保存COMPLETED或BLOCKED及完整真实阶段轨迹。不要根据轮询次数虚构Python阶段进度。若之后添加流式进度或后台队列，先提出契约变更PR并扩展测试。

Java任务至少支持PENDING、RUNNING、COMPLETED、BLOCKED、FAILED。CANCELLED用于尚未调用Python的排队任务或随访业务；运行中的HTTP调用无法取消时，返回409并说明当前不支持，不能标成已取消仍写入成功结果。进度增强不删除原任务生命周期和复核要求。

Java任务创建的Idempotency-Key与Python runId是两层不同机制。Java应把用户/操作范围、键和请求摘要一起持久化；相同键同请求返回原任务，不同请求返回409。重试同一个Python请求沿用原runId。Python缓存重启清空；Java数据库仍应能查看已保存的任务和结果。

Java成功Envelope固定code=0、errorCode=null；失败code为非零整数，errorCode为稳定字符串。统一message、data、traceId、timestamp。HTTP状态也必须正确，拒绝访问不能以HTTP200伪装成功。Python错误使用独立AgentError；AgentClient负责映射。

当前用户路径/api/me；导入/api/import-jobs及/{jobId}/commit；质量POST /api/subjects/{subjectId}/quality-checks；随访POST /api/follow-ups；报告/api/reports/{assessmentId}。不要套用作者旧Java工程中的不同路径。

## 数据与研究事实

本基底提供完全合成的100个档案、200条访视、300条特征。数据字典和CSV字段来自共同契约。没有真实姓名、患者标识、电话、住址、ADNI/NACC参与者记录或论文病例包。Java可将subjectCode映射为自己的数字主键，向Python仍传DEMO编号。

旧需求将NACC列为等待获取已经过时；现有研究工作区已获得授权并完成固定模型的NACC映射终点迁移评估，但个体评分没有开放。课程不依赖这些受控材料，演示算法的规则不能冒充已训练模型或外部验证成果。课程人工业务复核也不同于论文独立终点审阅。

## 阶段验收

1. 后端PR交付可运行新工程、数据库迁移、登录/角色与档案接口、接口测试；前端PR交付新工程、路由、会话、列表/详情和状态界面。
2. 完成CSV预览入库、质控、持久化任务与AgentClient；界面展示四阶段返回结果和条件图、缺失及限制。
3. 完成医生/患者权限、业务复核、随访、报告、审计，断开Python和重启Java仍能看历史结果。
4. 全组录制无科研数据的端到端演示。模拟400/409/BLOCKED/超时/503、重复提交、越权和患者字段过滤；提交可重现启动与验收记录。

建议Java 21、Spring Boot、MySQL 8以及Vue 3；同学自行建立工程与锁定依赖。选择替代前端技术不改变角色边界及验收要求。当前初始CI只证明Python基底和共同契约通过；完整课程系统开发尚未完成。
