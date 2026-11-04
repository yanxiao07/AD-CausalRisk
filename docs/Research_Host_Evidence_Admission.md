# 真实模型宿主证据：限定交付元数据核对

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

## 验收终点

20项标准库元数据核对通过，另用既有环境版本探针；没有新增计算模块、通用读取器/身份生成器或HTTP契约。此为文档和实际材料核对，不把它写成模型推理回归；未重跑P1/P2/P3、733课程回归或已关闭研究。0临床/私有折/模型参数读取、模型加载、原CIF和拟合；原673/36及旧图阴性/结构不可估/CSF NO_GO/阈值SUPPRESSED保持。

原四材料准入及后续本地定位子任务均按有限结论关闭，不重复扫描或追加包装层。下一唯一工程接入为**已定位来源对新上下文的记录级资格与缺证据项核验**：范围须明确后只核既有来源及独立元数据，不自动扩读记录或参数、回放旧授权；宿主执行许可仍待适用依据。随后实际参数/完整状态/保存参考也只能在明确范围内另验，当前不伪称通过。P1—P3仍为已关闭的本地候选；继续本地积累，远端发布按用户明确指令执行。
