# Python研究基底与后端接入验收

本阶段覆盖需求FR-DATA、FR-QUALITY、FR-GRAPH、FR-WARN、FR-EXPLAIN与FR-AGENT中的Python支持：实测访视序列、字段/时间/来源依赖校验、研究结果映射与失败场景。Java业务工程、角色权限、数据库、持久化和前端仍由对应成员实现，本文不宣称它们已验收。

## 可采用的范围

当前离线研究已完成七变量LR/RF/XGBoost的五外折/三内折校准验证、三最终模型及15外折模型交付。三算法校准差条件区间均跨0，研究推荐保留原始输出。模型与输入留在授权环境，未接到业务HTTP或替换原部署模型。

七变量解释诊断复用15个外折模型；ADAS13/FAQ在置换和训练中位数替换中全局前二一致，其他完整排序、局部及跨折并不完全稳定。数值重构或解释一致性不能认证个体病因、因果边或临床精准概率。

遗传/横断MRI的固定回顾性增量研究已完成：原1160人、APOE有效428人/双侧海马276人，两个新方案共30外折和6全量新模型，36次真实基础拟合及保存/加载复现差0。对照区分真实数值和可获得性/缺失模式，九项Brier差条件区间均跨0。XGBoost的AUC点估计0.855640/Brier0.109427不能升级为已确立的算法或临床优越。275/276所选MRI记录RUNDATE晚于起点，仅允许声明的回顾性快照用途；十变量未训练新校准器，不套用七变量校准参数。

同一1160人/五折内，加入ADAS13/FAQ后LR/RF/XGBoost的AUC约0.825/0.824/0.843，三算法输入增量Brier差条件区间均支持改善；算法间优势未确立。原四边交互增量补验差-0.000767、区间[-0.003076,+0.001526]跨0，图增益未成立。与451人后续landmark属于不同任务，不能横比AUC当作模型改进。

该研究推进主线累计190基础拟合、18校准拟合，包含15次必要模型重放；不是190个独立试验。研究完成范围、源码/数据/模型的交付状态及Java调用关系见[研究交付与接入说明](Research_Progress_and_Integration.md)。本仓库提供合成验证服务和受控服务客户端，不分发受限数据、模型参数或机器私有验收记录。新增模型尚未接入现有服务，Java实际AgentClient与业务链仍待联合验收。

既有主图方案的内部折平均IPCW ROC AUC约0.807，内部日历留出约0.757，NACC映射终点约0.733。AUC是区分度，不能写成“准确率80.7%”。原图增益标准没有通过，NACC图减可获得性对照的配对Brier差为+0.004124、条件受试者Bootstrap95%区间约[+0.002970,+0.005198]；Brier越低越好。外部图方案校准斜率约0.388，尚不支持精准概率承诺，且终点不等价。以上是原聚合报告的描述，本阶段没有重新估计或选择新模型。

结论是**可以继续作为受控研究计算基底接入；临床精度、独立样本外效果、历史可获得性和个体因果用途尚未确立**。这与[TRIPOD+AI说明](https://www.tripod-statement.org/wp-content/uploads/2024/04/TRIPODAI-Supplement.pdf)区分区分度、校准、用途与外部评价的要求一致；报告清单本身不是认证。

## 访视序列工具

`python_service.visit_timeline.build_timeline(visits, metric)`接收内存中的访视列表，metric为mmse/cdr/nwbv。每条记录保留visit、days_from_first_mri及对应实测值。输出包含原单位、0—30/0—3/0—1轴范围、实测点、缺失/异常、首末实测差和相邻观测连接候选。

默认图仍只显示实测点。两次间隔457天的记录只产生两个点；不补零、插值或模拟日曲线。辅助线须显式开启，不连接缺失访视、未知日期或同日歧义。单点/无值/同日歧义时不输出净变化；MR图与访视表保留六位小数显示，Python数值不舍入。图的首末差不是病程速度或预测结果。

在维护方现有8084演示中，新增只读`GET /api/system/public-timelines/{subjectId}?metric=mmse`，只接受已绑定OASIS公开编号；不接受ADNI私有别名或任意输入。原公开/研究/研究路径与字段不变；同一入口，没有另开服务。此公开图不产生OASIS对象的ADNI风险预测。

## 内存研究记录映射

```python
from python_service.research_record import build_research_record

record = build_research_record(
    authenticated_result,
    expected_alias=requested_alias,
    expected_model_sha256=independently_bound_manifest_sha256,
)
# 仅由授权程序在内存使用；不要打印真实结果或自动导出。
```

模型hash必须来自调用方独立绑定的受信清单，不能直接把响应中的hash传回当作校验。此函数不发HTTP、不读取文件或模型、不认证来源真伪；调用前由既有受控服务执行原完整资格/派生值/模型来源核验。没有新增真实导出或映射HTTP接口。Java仍可获取既有受控原JSON，再由开发人员按下表映射专用研究DTO，不能将这个对象直接套入患者结果或原研究AgentResult。

| 映射项 | 语义与校验 |
| --- | --- |
| alias/modelVersion/modelArtifactSha256 | 请求对象和固定研究模型；独立hash匹配，拒绝串对象或替换产物 |
| probabilities | 24个月AD及竞争痴呆CIF，单位proportion；原数值，不输出代替临床风险的riskScore |
| sourceNodes/sourceBindings | 测量日、距索引日、原单位和来源hash；可获得日保持null，拒绝未来测量、非法MMSE和错单位 |
| derivedNodes/currentFeatureNodes/chronologicalEdges | 保留派生来源依赖、当前输入别名和观测顺序；年龄为索引年减出生年的近似年龄，不冒充精确生日年龄 |
| priorInteractions | 预设预测交互及其实际来源节点，不能命名为已识别个人致病因果边 |
| attribution | 五来源撤除贡献、基线和重构残差，单位概率比例；贡献不是治疗收益 |
| stages | 实际四阶段证据；原研究运行时没有记录耗时，durationMs保持null |
| boundaries | 临床用途、历史实时、导出、因果均false；outOfSample和个体预测区间保持null |
| recordSha256 | 对规范化记录内容的稳定校验值，不是数字签名或临床正确性证明 |

校验失败抛出只含稳定代码的ResearchRecordError，不包含受限值、别名或任意服务端正文。原始多余字段不复制到记录，源对象不修改。结果不因工程检查通过而取得新的医学含义。

recordSha256使用Python紧凑JSON、排序键、UTF-8及原数值表示计算，不承诺任意跨语言重新序列化都产生相同hash；Java若不能保证相同表示，应逐字段核对并保留模型/来源hash。它不是通用JSON规范化签名。来源hash检查可以发现字节变更，不能独立认证原记录真实性或提取完整性。

## 供Java开发人员复用的验收资料

`python_service/examples/research_record_acceptance.json`是静态构造资料，包含成功原响应、期望映射及失败变更/错误代码。里面的hash和别名仅用于离线单元测试，不能向活动服务提交，也不代表真实患者、模型精度或接口签名。Java可将相同字段和失败案例用于本地DTO校验测试。

实际接入顺序：确认当前服务/健康与独立模型清单；完成授权会话；从READY目录选对象并保持请求键幂等；按真实任务状态轮询；仅消费对应对象COMPLETED结果；核对模型、概率、来源、研究边界；在授权生命周期内使用并注销。完整调用与超时语义见[受控客户端](Controlled_Research_Client.md)。

Java还需自己验收：用户/对象权限和角色过滤、业务状态与结果持久化、断线/超时/409/401处理、同键重放、Python服务重启后历史结果、患者摘要排除原分数/图权重/内部轨迹。当前本地访问码不等于生产身份体系，真实任务在重启后不会持久保存，exportAllowed=false仍限制自动导出和复制。这些项目未完成时，不能把Python单测通过写成Java端到端通过。

本仓库验证命令为`python -B -m unittest discover -s tests -v`。授权宿主另行核对所有既有投影、公开访视和实际推理；本仓库不包含受限源表、模型或主机私有验收快照。
