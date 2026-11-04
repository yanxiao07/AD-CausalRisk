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

## 验收终点

20项标准库元数据核对通过，另用既有环境版本探针；没有新增计算模块、通用读取器/身份生成器或HTTP契约。此为文档和实际材料核对，不把它写成模型推理回归；未重跑P1/P2/P3、733课程回归或已关闭研究。0临床/私有折/模型参数读取、模型加载、原CIF和拟合；原673/36及旧图阴性/结构不可估/CSF NO_GO/阈值SUPPRESSED保持。

本次四材料准入子任务按有限结论关闭，不再重复扫描同一批或追加包装层。下一唯一工程接入为**指定宿主的独立快照映射、逐条证据与执行权限来源准入**，待具体材料/调用反馈；本轮不自动扩读记录或参数。随后实际参数/完整状态/保存参考只能在明确范围内另验，当前不伪称通过。P1—P3仍为已关闭的本地候选；继续本地积累，远端发布按用户明确指令执行。
