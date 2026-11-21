# P2：加载器身份与准入上下文的来源映射

2026-11-10限定运行绑定更新：[源码/锁与安装记录核对](Research_Runtime_Binding.md)已核8源码原字节、12版本及6274带哈希安装成员。锁LF/CRLF两个字节对象和3875无哈希成员明确分列，模块导入受阻及仅解析位置通过也分别记录；未签发可信字段或完整上下文，完整宿主NO_GO保持。此更新不扩大下文各历史阶段执行范围。

2026-11-10后续范围更新：[实际参数组件及人工数值绑定](Model_Artifact_Admission.md)已核6个目标表格模型，并同进程完成9人工案例69次CIF的预测/解释一致性，保存参考和逐实例状态保持。该后续任务不改变本文原阶段的人工/未读取范围，不生成完整独立上下文或执行许可；真实研究模型业务接入和完整宿主NO_GO仍开放。

本项为第1→3→4环节有界工程支撑，承接[接入候选](Research_Model_Integration_Candidate.md)。P2原任务只核既有源码和非临床规格，没有打开真实清单、参数、临床输入或私有折，也没有调用模型/CIF/训练。后续实际四份交付元数据的限定核对另见[宿主证据准入](Research_Host_Evidence_Admission.md)，不改变本页P2原执行范围。

结论：**旧加载器身份返回不能直接作为完整model；已有准入/单次调度API可以复用，但真实宿主绑定证据未由本次建立。** 没有因字段缺失新增生产适配器或自动信任生成器。

[静态字段清单](examples/research_model_identity_map.json)覆盖全部12个model字段和7个trusted_context字段。它是文档索引，不是可直接提交给推理API的新Envelope或运行配置。

## 十二个模型字段各来自哪里

原`load_final_model`返回`(bundle, identity)`；identity只有manifestSha256、artifactSha256、trainingPlanSha256、artifact。后者artifact是文件名，不属于准入MODEL_KEYS，不能将identity原样塞入model，也不能仅删去artifact后使用。

| 分类 | 字段 | 可复用条件 |
| --- | --- | --- |
| 3项加载器返回 | manifestSha256、artifactSha256、trainingPlanSha256 | 只有原严格加载器已经成功执行，并有独立可信的清单摘要，才能沿用；本次未执行加载器 |
| 5项已核模型/选择规则 | family、specification、role、features、predictionPolicy | 从宿主已选且通过原manifest/bundle核验的模型解释；不是从未经核验的来件复制 |
| 3项宿主独立证据 | runtimeSourceSha256、softwareLockSha256、sourceMappingSha256 | 需独立固定摘要作用域和与此模型的适用性；字段填满不等于来源已核 |
| 1项表格常量 | graphProtocolSha256=null | 仅限本次七/十变量LR/RF/XGBoost，不借此接入图模型 |

直接返回项也有有限保证：trainingPlanSha256在旧加载器里来自manifest.plan_sha256，并与bundle字段一致；加载器没有另行打开Training_Plan并认证其科学内容。完整保证不能由一个摘要字段名推断。

五项解释规则的依据见[原加载器](../research_runtime/inference.py)与[准入规格](Real_Input_Admission.md)：

- family同时匹配选择和bundle；specification由选定路径、特征以及多模态arm限定。仅seven和multimodal_values进入本次映射，modality_availability仍不准入。
- role必须保留原FULL_COHORT_SEVEN_VARIABLE_RESEARCH_MODEL或MULTIMODAL_FULL_COHORT_RESEARCH；features保留manifest/bundle核过的顺序。
- 原始策略在七变量bundle由recommended_research_policy=raw限定，在多模态bundle由prediction_policy=raw_uncalibrated_24month_CIF及其他边界限定。CLI输出短字串raw不能原样用于准入predictionPolicy，后续映射须明确核验前提。

没有“只对旧加载器返回补几个默认值”这一可靠接法；应先保证宿主拥有上述独立证据，再直接调用现有接口。

## 三类摘要的验证层次不同

| 摘要 | 现有准入实际检查 | 本次没有证明的内容 |
| --- | --- | --- |
| runtimeSourceSha256 | 64位小写十六进制格式及请求/上下文身份一致 | 未打开或验证模型发布源码；没有规定一个随手散列的当前文件即可替代受控发布来源 |
| softwareLockSha256 | 同上 | 旧加载器比较已安装依赖版本，不核软件锁字节摘要，也不认证wheel或环境来源 |
| sourceMappingSha256 | 同上，且与mapping_digest(sourceMappings)实际重算值相等 | 映射适用于指定模型、逐人记录真实、语言/处理版本合格，仍需外部依据 |

`mapping_digest`明确为sort_keys=True、separators=(',',':')、ensure_ascii=False、allow_nan=False的UTF-8 JSON。它散列非临床字段映射，不是原始临床表或个人输入摘要；没有必要重新发明映射哈希函数。

源码和软件锁的预期摘要需由受控模型发布材料明确对象/成员/字节范围。原始文件字节、Git clean对象、归档字节和多文件集合摘要不是同一个对象，不能静默归一化以消除不匹配。Windows text=auto检出可能改变换行，当前工作树文件名和版本相同并不足以认证原字节。

[Source_Provenance.json](../research_runtime/Source_Provenance.json)记录历史代码来源、原模块摘要或函数AST保持范围，适合作出处依据；它不是当前完整模型发布代码的单一runtimeSourceSha256证明。`training.py`生成的训练计划记录四个训练/评价源码的摘要字典，也不能未定义成员/组合方法就当单一运行时摘要，更不因该源码存在就证明真实训练计划已经核对。

## 七个上下文字段与宿主责任

| 字段 | 来源/责任 |
| --- | --- |
| schemaVersion | 原CONTEXT_SCHEMA常量research-input-context-v1 |
| dataVersion | 宿主独立控制的冻结快照版本 |
| indexDate | 适用人群/索引规则及冻结快照；不能随来件后移 |
| availabilityPolicy | 独立固定RETROSPECTIVE_SNAPSHOT或AS_OF_INDEX；不能随来件降级 |
| model | 上述全部12字段及相应核验依据 |
| sourceMappings | 原8字段sourceId/dataset/table/field/release/language/instrumentVersion/processingVersion；单位在观测observation.unit中核对，不往映射另加unit键 |
| evidence | 绑定subjectCode、feature和完整observation的独立记录证据，含缺失声明 |

原加载器不提供快照、来源映射或逐条证据。把整个来件复制为trusted_context，即使通过一致性检查，也不能证明上下文独立；人工样例通过不认证临床来源。

上下文之外，宿主还需独立处理实际模型验证、有效state_token与执行许可。`run_in_memory`无许可时即使元数据相容也返回PERMISSION_REQUIRED、predictions=null，零预测回调。Java负责对象权限、任务与持久化，Python内存permit不代替这些业务能力。

## 状态指纹和其他已存在格式不能混用

`BoundPredictor`检查state_token为64位摘要形式并保持前后相同，但无法证明宿主提供的指纹覆盖完整状态。固定假摘要可通过格式检查，不能证明实际模型不变。

源码检查发现两个不能直接代用的既有产物：

1. `graph_native.state_digest(network)`只遍历network.state_dict的名字和数值；它没有接收完整预处理管道、特征顺序或调用策略。不能据此宣称已具备表格六模型或完整图计算路径的通用状态指纹。本次没有导入Torch或执行该函数。
2. `training.save_artifact`中的base_state_joblib_hash是来源状态记录；原注释已说明序列化可能改变对象内存哈希。它不是参数文件SHA256或完整上下文发布身份。新训练的PORTABLE_FINAL格式也与原加载器ROLES不同，现有文档已分开，不能改角色混装。

因此本次没有添加“通用模型哈希器”、重新序列化、重训或更改原加载器；状态指纹的实际实现与验收仍须绑定明确受控模型类型和作用域。

## 有限人工核对与结论边界

对静态清单逐项核既有源码字段、返回键、角色/策略和上下文键；只将新映射相关的人工元数据送入原API。重点包括四键/删文件名后的三键均拒绝、完整人工声明仍无执行许可、短raw拒绝、源码/锁格式一致不等于来源认证、映射摘要实际不匹配拒绝及请求/上下文错绑阻断。

这些核对没有执行load_final_model、bind_existing_model、predict_cif、图状态函数或训练器；不把“代码路径存在”写成模型已加载、真实上下文已可用。P1固定回调/旧测试矩阵也不重跑。

| 项目 | 收口结论 |
| --- | --- |
| 12字段/7上下文字段映射 | 已明确来源、复用条件和缺证据责任 |
| 直接拿旧identity作完整model | NO_GO，仅指这条直接复用途径字段不足 |
| 真实宿主证据及六模型实际绑定 | 本次未核验，未建立；不能推断全工作区没有这些材料 |
| 新生产适配器/读取器 | 没有依据新增；现有API参数位置足够表达这些信息 |
| 真实研究/临床/个人因果 | 未因工程映射而成立，原阴性/不可估/NO_GO/SUPPRESSED不变 |

P2到来源映射及限定结论为止。P3可以先用已有人工案例和归因接口核预测/解释的同源绑定规格；真实模型路径仍需上述独立证据，不能靠扩写报告绕过。后续开发继续在本地分支提交，远端发布按用户明确指令执行。
