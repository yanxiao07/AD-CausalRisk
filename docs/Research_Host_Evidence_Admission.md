# 真实模型宿主证据：限定交付元数据核对

2026-11-10限定运行绑定更新：[源码/锁与安装记录核对](Research_Runtime_Binding.md)已核8源码原字节、12版本及6274带哈希安装成员。锁LF/CRLF两个字节对象和3875无哈希成员明确分列，模块导入受阻及仅解析位置通过也分别记录；未签发可信字段或完整上下文，完整宿主NO_GO保持。此更新不扩大下文各历史阶段执行范围。

本项是第1→3→4环节有界工程支撑，承接[P2来源映射](Research_Model_Identity_Map.md)与[P3人工预测/解释绑定](Prediction_Explanation_Binding_Candidate.md)。实际问题是已有受控模型交付材料能否满足完整宿主上下文、状态和解释参考要求。只核现有工作台固定的两份Model_Manifest及各自计划，不加载参数、解析临床输入或运行已关闭研究。

结论为 **PARTIAL_RELEASE_METADATA_VERIFIED**：限定清单/计划的绑定和六个目标模型声明已核对；完整宿主准入仍因证据不全NO_GO。这是本次四份材料的结论，不是模型无效或全工作区没有所需材料。

## 实际核过的材料

沿用受控主机 `config/research_model_review.json` 的既有路径和摘要，不从请求生成可信清单。材料在本地受控数据目录，未随Git提交：

| 材料组 | 目录与计划 | 本次核对 |
| --- | --- | --- |
| 七变量 | core_final_training_2026-10-24；Training_Plan.json | Model_Manifest原字节摘要与既有配置一致；计划原字节摘要与manifest.plan_sha256一致 |
| 多模态 | core_multimodal_comparison_2026-10-24；Comparison_Plan.json | 同上；仅取multimodal_values的三个最终声明，不将modality_availability接入现有内存准入 |

两组目录都位于受控主机 `data/adni/processed`。七变量manifest摘要为46ad244fe612306cc95a2a457c44ac234361db5ab70184c2ee2226527883a511，计划为47ae229df74070f56165ece9b828a1aebcbf1b316b2005439e3f76fa89b0d54e；多模态manifest为816fd7ca48e3d6e2d5b6c63d624167f9eb8536fa304193811016e38bfa2323f1，计划为3a80287d66d955af89a77ec342dbbac6c7cd1ce6a468ada46cad0a5228ce02c8。它们表示既有配置与文件一致，不额外证明配置发布者身份、临床或独立来源真实性。

三算法logistic/random_forest/xgboost在七变量各有唯一最终文件名/摘要及raw策略声明，在multimodal_values各有唯一最终角色/文件名/摘要声明，共六项；可获得性对照的三个最终声明另列并排除。清单中的历史roundtrip误差是原声明，本轮没有重新运行、认证或以它代替参数字节检查。七变量清单声明training_complete=true、clinical_use=false、system_default_changed=false；多模态清单的clinical_use/default_model_changed/calibrated均false，保留原边界。

两计划的特征顺序、三算法与软件声明均与对应清单一致。七变量计划记录26个历史scripts源码成员，多模态28个；这些成员名不包含当前research_runtime发布源码。本轮没有重读或重新散列这26/28个源码，也没有重算任何临床输入、私有折或计划中的个人级指纹。源码字典的存在不等于完整当前执行路径的单一runtimeSourceSha256已核验。

对现有研究Python3.11.9环境仅用importlib.metadata读取版本，清单所需七项运行依赖均匹配：NumPy2.4.6、pandas3.0.5、scikit-learn1.9.0、SciPy1.17.1、joblib1.6.0、threadpoolctl3.6.0、XGBoost3.2.0。没有导入这些模型包或打开参数。版本匹配不等于锁字节、wheel来源或完整执行环境已认证。

## 可复用部分与剩余缺口

| 证据层 | 本次状态 | 下一接入所需的具体内容 |
| --- | --- | --- |
| 清单与计划 | 原字节摘要链已核对；六项最终声明可定位 | 保留这些绑定，不能用新训练、角色改名或人工摘要替换 |
| artifactSha256与bundle身份 | 只核清单内声明及文件名/摘要格式；参数未读、未hash、未反序列化 | 在另行限定的受控加载范围核实际字节、bundle角色/特征/策略与完整管道 |
| runtimeSourceSha256/softwareLockSha256 | 历史源码字典与软件版本声明存在；当前完整发布范围未建立 | 明确发布成员、字节对象、组合规则及与实际执行环境的适用性，不随手散列当前文件代填 |
| 完整state_token | 未核验 | 覆盖实际参数、预处理和相关顺序/策略的状态；不能用网络摘要或固定值代替 |
| 解释参考 | 本轮四份JSON未提供可直接用于table_reference的管道内保存统计；参数未读 | 从已核管道中取得保存的原量纲训练参考，绑定同一模型，不重拟合参考或用人工值代填 |
| sourceMappings与逐条evidence | 本轮材料不是完整可信上下文 | 对具体冻结快照独立提供原8字段映射、单位/日期/QC/缺失及逐条观测证据；不能由来件反向复制 |
| 执行权限与业务消费 | 实际宿主的执行许可来源未核，Java对象权限/保存不在本轮 | 明确实际宿主及用途/许可来源；Java继续负责业务权限、任务与持久化 |

这些缺口属于不同证据层，ADNI回复不是本轮工程准入的统一前置条件，四份材料核对也不消除ADNI/因果识别、真实监控双批或独立/临床依赖。

## 后续本地查找已定位的输入材料

后续按本地查找范围沿训练脚本和计划定位，已有资料可供下一次有界核验。下表路径均相对受控主机根目录，原文件不进入Git；本次只读非临床映射/授权元数据与CSV表头，不读逐人内容：

| 本地材料 | 已核实内容与限制 |
| --- | --- |
| data/adni/processed/v2_phase_aware_candidate/mapping_report.json | 原字节SHA256为7b2d4f0d733c51d23830ca13f63a0a7d9e509e3577fe1927a3f9ac737dbaa432，与多模态Comparison_Plan中原输入绑定一致；状态仍candidate_unadjudicated。有UCSFFSX/ST29SV、ST88SV对应双侧海马mm3的字典证据；这份完整报告的文件摘要不是新8字段sourceMappings的规范摘要 |
| data/adni/processed/core_input_coverage_2026-10-24/Scale_Sources.private.csv | 表头包含subject_id、feature_name、source_visit、measured、score、qc_status、date_semantics、available_at等，可定位逐人量表来源；具体记录/覆盖/值/日期/QC尚未读取核验 |
| data/adni/processed/core_multimodal_comparison_2026-10-24/Selected_Sources.private.csv | 表头包含subject_id、source_visit、measured、source_table、APOE/双侧MRI数值列、processed，可定位遗传/MRI选中来源；不因文件名或生成器过滤规则就认证每条观测 |
| data/adni/processed/core_input_coverage_2026-10-24/Input_Candidate.private.csv | 表头提供原快照身份/索引与数值，并包含event_type；不能整体复制为预测请求，更不能把结局作为输入 |
| data/adni/processed/provenance.csv及v2_phase_aware_candidate/provenance.csv | 表头均是source/source_version/file_name/row_count/transform_version/checksum/license，属于文件级来源目录，不能直接当逐条观测evidence |
| data/adni/processed/core_algorithm_training_2026-10-24/User_Authorization.json | 真实存在的历史授权，decision限定两原表DXSUM/PTDEMOG及六次开发训练，绑定另一训练计划；它不是当前研究宿主的通用许可，不重新启用历史训练 |

两份来源表的表头不直接提供完整instrumentVersion/language/completion/evidenceRef/scanId等新上下文要求；是否能从独立材料补证尚未核验。Scale_Sources生成器曾给date_semantics赋代理标签并将available_at置空，后续scale输入门槛明确保留ASSESSMENT_DATE_OR_REGISTRY_FALLBACK_ROW_BRANCH_UNKNOWN，不能把旧标签升级为逐行实际测量日或首次可用证据。本次没有重新审计日期问题或执行已关闭研究。

旧版data/adni/processed/mapping_report.json也已找到，其状态review_required，不能代替上述绑定的V2材料。定向索引了配置/文档/处理区/runtime文件名，并对.runtime/output/schemas/tmp补充文件名查找；未发现特定命名的完整trusted-context文件不构成全工作区不存在的证明。

本地定位的14项元数据/表头/源码核对通过，5份CSV仅读表头，0临床记录/私有折/参数/模型/CIF/拟合。已将“材料位置未知”细化为具体文件及其尚未验收的字段，不生成新的可信上下文、不将来件复制为证据，也不为此增加通用读取器。后续记录级核验应限定这批已定位来源及缺证据项；当前查找不自动授权扩读其逐人内容。

## 已定位三文件的限定记录级核验

后续按明确的三文件范围完成了一次真实记录核验：Input_Candidate仅投影subject_id/index_date/ADAS13/FAQ四列，Scale_Sources投影十个来源列，Selected_Sources投影八个遗传/MRI来源列。原文件各读一次、同缓冲hash和CSV解析；物理字节中包含未选列，但event_type不保留、不使用。每文件上限8MiB/10000行，30秒单次worker，失败不重读；未读其他临床表、RDA、私有折、参数或运行模型。

结果为**限定连接/数值/日期检查PASS，完整准入NO_GO_MISSING_INDEPENDENT_METADATA**。执行COMPLETED，父进程退出0、无超时、守卫拒绝0；先行8项人工检查通过。公开说明只保留技术结论与缺证据项，不保存逐人值/ID/日期/hash或临床小组计数。

| 范围 | 实际核验结论 | 不能据此推断 |
| --- | --- | --- |
| 快照身份与连接 | 候选subject_id唯一，目标量表和遗传/MRI来源身份可连接；所核来源键无重复或越出候选 | 未与最终模型输入快照/五个基础变量来源/私有折建立完整历史身份认证 |
| ADAS13/FAQ | 所核非缺失候选有对应选中来源，来源值与候选按绝对1e-12比较一致，来源日期/存储lag一致且未晚于候选索引 | 不能将数值相符升级为量表完成、语言/版本、独立QC或实际测量时钟认证；未将ADAS TOTSCORE当ADAS13 |
| 遗传/MRI | 选中APOE为数值0/1/2，MRI同一选中行双侧有限正数、在365天内，模态行互不混入另一类数值 | 不是JSON严格int类型验收，也未与最终十变量模型快照核对APOE/MRI值；同一行不等于已核独立scanId |
| 历史摘要 | Selected_Sources原字节摘要与既有Comparison_Plan.prepared_sha256一致 | 另两文件本轮仅固定当前读取摘要，未重新认证原历史结果清单，不将其写成历史pin通过 |
| 日期可用性 | 实际存在量表available_at未知与MRI处理晚于索引的状态，保持原限制 | 不补测量日为可用日；不升级AS_OF_INDEX或重开旧日期/图/阈值研究 |
| 来源QC/日期文字 | 实际量表来源保留NOT_RECORDED、NO_ERROR_OR_APPROVED及原REGISTRY_VISIT_DATE_PROXY标签 | 复合“无错误或已批准”不等于新准入逐条PASS/COMPLETE，旧标签不推翻后续行日期分支未知的既有结论 |

仍缺完整适用sourceMappings、发布/语言/量表版本、量表完成、独立QC/缺失证据、实际日期分支/首次可用、独立扫描身份、五个基础变量来源、真实最终模型输入/状态/参考与宿主执行许可。本轮不伪造这些字段、不调用validate_snapshot来认证一份代填的真实上下文、不生成许可或推理结果。它是一次有界来源资格审计，不是新模型或临床研究，也不证明四环节已完成。

## 独立元数据补证：本地字段定义与原对象位置

后续仅核本地ADAS.Rd、FAQ.Rd、UCSFFSX.Rd三份非临床对象说明及投影生成源码，没有重读前述三CSV或打开RDA。14项元数据/源码核对通过，状态SOURCE_FIELDS_LOCATED_NOT_ROW_CERTIFIED；说明文件中定义字段不代表每条记录有值、语义适用或已获授权。

| 缺项 | 本地已找到的具体依据 | 仍需核验 |
| --- | --- | --- |
| 量表语言 | ADAS/FAQ均定义LANGUAGE_CODE；ADAS文档列e/s，FAQ文档列e | 原选中记录的实际代码及适用性；不能把字段选项当所有人的语言 |
| 版本 | 两对象均定义DD_CRF_VERSION_LABEL | 它是CRF版本标签，不能自动等同量表版本或替新instrumentVersion填默认值 |
| 量表QC | 两文档均把HAS_QC_ERROR=0定义为“无错误或错误已批准”的合并状态，1为有错误 | 合并状态仍不能区分逐条独立QC；旧quality_status对数值0/false给EXPLICIT_NO_ERROR的分支不能作为新PASS依据。旧文件及已关闭结果不改、不重算 |
| 量表完成 | 所核两个对象说明未定义独立DONE/COMPLETE/COMPLETED/COMPLETION字段 | 不用非空总分、正确数值或CRF标签代填COMPLETE；不推断所有本地资料都没有完成证据 |
| MRI扫描/处理/QC | UCSFFSX说明定义IMAGEUID、EXAMDATE、RUNDATE、VERSION、STATUS和OVERALLQC；STATUS含complete/partial，QC含Pass/Fail/Partial | 按原选中行核实际字段，区分处理完整与临床量表完成；不把Partial变Pass，不将处理日当首次可用日 |

ADAS中的TOTAL13与TOTSCORE是不同列；字段解释支持保持原ADAS13映射，不从总分名称重新选择模型输入。UCSFFSX说明的原字节摘要与既有多模态计划的准备阶段绑定一致，保留原4.3范围；没有新增跨处理版本对齐结论。

实际对象位置已核文件存在性（未加载）：

- data/adni/processed/core_input_coverage_2026-10-24/selected_sources/extracted/ADAS.rda
- data/adni/processed/core_input_coverage_2026-10-24/selected_sources/extracted/FAQ.rda
- data/adni/incoming/unpacked/ADNIMERGE2/data/UCSFFSX.rda

旧投影生成器选择字段时没有保留LANGUAGE_CODE/DD_CRF_VERSION_LABEL，模态选中投影没有保留IMAGEUID/STATUS/OVERALLQC；这是本次可定位的具体信息缺口。本轮不重新解包原归档、加载这些对象或认证选中行语言/版本/质量；源对象实际字段/行绑定、未知与冲突需要另一次明确范围的元数据投影，不能由字典直接生成可信上下文。

## 已选中来源的原始元数据定向补取结果

后续已按固定范围执行一次补取：两份已选来源表只投影连接键；原ADAS、FAQ、UCSFFSX各原字节读取一次，同缓冲hash后使用既有read_rda解析器。RDA整对象解码后立即投影9/9/10个身份/元数据列，未用分数、结局、QC或语言重新选择来源。每输入8MiB、原对象20000行/500列、60秒/2GiB父监督；临时同缓冲解析副本在结束时清除，未重新解包归档、读取其他表或参数。

执行COMPLETED，五个原输入各读一次、三个原对象各解析一次，选中键均为唯一匹配；没有因多匹配而按分数消歧或换行。16项人工检查（11桥接/5语言标签）和1个原解析器人工RDA检查通过，守卫拒绝0，父进程无停止。原MRI对象摘要与历史计划匹配，两份连接键表与前次快照匹配；ADAS/FAQ原对象只固定本轮字节摘要，不伪称其历史发布pin已认证。

| 补取范围 | 实际取得的元数据 | 未关闭的限制 |
| --- | --- | --- |
| 原选中ADAS13/FAQ来源 | 能唯一连接原记录；部分记录取得语言、CRF标签及QC字段，仍有缺失 | 不把缺失默认成英语/默认版本；不把CRF标签等同量表版本；有值的QC仍是无错误/已批准错误的合并状态，未认证逐条独立PASS/COMPLETE |
| 原选中MRI来源 | 能唯一连接原记录，取得IMAGEUID、complete处理状态及Pass源QC标签 | 只关闭这些原字段“未投影/未定位”问题，不认证临床有效性、首次可用、最终模型输入/完整上下文或因果效应 |
| 语言解码 | 原解析器可返回文档字母码对应的完整语言标签 | 初始仅检查e/s字母码而误标已解码标签，已保留诊断并仅对存储聚合按文档同义关系修正；未重读对象、补造缺失或改变资格结论 |

状态METADATA_PROJECTED_WITH_QUALIFICATION_LIMITS；完整宿主准入仍NO_GO_INDEPENDENT_EVIDENCE_INCOMPLETE。没有生成逐人新报告、trusted_context、permit或推理结果；个人身份/日期/分数/图像ID仅在进程内，退出后不保留。公开说明只记录技术结论，具体分母和元数据分布留在受控内部，不将跨表来源行数当独立人数。

## 分阶段验收终点

最初四份交付元数据阶段的20项标准库核对和环境版本探针已关闭；该阶段没有临床/私有折/参数读取。后续本地定位、真实三CSV核验及本轮三份对象说明核对的范围与结果分别列在上文，不能把最初“0临床读取”外推到实际三CSV阶段。各阶段均未执行模型/CIF/拟合，没有新增生产计算模块、通用读取器/身份生成器或HTTP契约；原673/36及旧图阴性/结构不可估/CSF NO_GO/阈值SUPPRESSED保持。

四材料准入、本地定位、三CSV核验、字典字段定义及原始元数据补取均按有限终点关闭，不重复读取或追加包装层。下一唯一工程接入为**剩余独立资格证据准入**：仅在新增适用来源或具体宿主权限依据到位后核量表完成/独立QC、缺失语言/版本及CRF到量表版本的适用解释、实际日期分支/首次可用、五基础来源等缺项。不因MRI原字段已补回而放宽量表门槛，不用默认值或旧授权救准入；实际模型参数/状态/参考仍另验。P1—P3及固定科学研究继续保持关闭，后续具体新问题据实调整，本地积累和远端发布遵守用户指令。

## 2026-11-10参数组件核验首轮

后续已启动[模型参数/状态/训练参考核验](Model_Artifact_Admission.md)。实际只读/加载2份参数，LR组件检查通过，RF在审计脚本额外的跨深拷贝摘要断言处停止；余4份未读，真实核验未重试，同次预测/解释未运行。人工反例说明跨对象摘要相等不具通用保证，不能据此判定RF无效。上文“参数未读”适用于原阶段，当前不能外推为6份参数均已核验。完整独立上下文、QC/首次可用、许可和完整宿主NO_GO仍保持。

## 2026-11-10续轮组件与人工计算收口

[模型组件续轮](Model_Artifact_Admission.md)已通过：6份实际参数各一次读取/加载，30逐实例状态变动及保存参考/管道检查通过；同进程复用完成9人工案例69次原CIF，预测端点差和贡献重构最大误差0。首轮停止历史保留，不能再将实际参数/保存训练参考整体描述为尚未核过。此结论只限数值组件，不提供独立来源/QC/首次可用、权限或运行源码/锁适用资格，未生成完整trusted_context或permit；完整宿主NO_GO仍保持。下一唯一接入任务为具体适用新材料/反馈到位后的剩余独立资格补证，不重跑已关闭组件或旧研究。
