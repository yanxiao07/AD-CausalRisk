# 结构稳定性纯内存原语

2026-11-03，FR-10、第2→3环节。实现[固定设计](Graph_Structure_Stability_Design.md)的统计选择器和共同分母计算，提供原人工入口及明确的固定真实内存入口；两者复用同一数值内核，不进行因果方向学习。原graph_protocol.json、图模型、默认及共享HTTP均不改。

## 接口与输入边界

模块为`research_runtime.graph_structure_stability`，仅依赖现有NumPy。没有文件入口、模型加载、CIF、优化器或图修改。

| 接口 | 行为 |
| --- | --- |
| `average_ranks(values)` | n×10纯数值数组，平均秩处理同值及抽样副本；不插补 |
| `rank_precision_scores(values)` | 固定0.1收缩/精度解，返回11个无向候选分数，数值失败无后备规格 |
| `summarize_selections(full_scores, draws)` | 11个全训练分数、恰好100个分数向量或None；按共同有效分母/符号/全训练一致性汇总 |
| `evaluate_training_folds(folds, data_version=..., constructed=True)` | 固定五折、每折100抽样，最多505统计尝试；当前必须显式constructed=True和synthetic-*版本 |
| `evaluate_research_training_folds(folds, binding=...)` | 固定原928训练/232留出五折；binding必须与只读RESEARCH_BINDING三摘要一致，返回constructed=False及固定研究版本；不读取文件或自行认证来源 |

`TrainingFold`包含fold（严格整数1—5）、subject_ids、values、expected_training_ids、held_out_ids和固定features顺序。所有ID列表为唯一非空字符串tuple；训练ID与预期顺序严格相同，不能含留出ID。五折具有相同人员全集，留出集恰好完整分区。values为n×10内存ndarray，数值dtype；NaN表示缺失，inf/字符串/布尔/复数不是缺失，直接拒绝。没有结局/月数/测试值字段。

共享训练人员的快照必须逐项一致（NaN视作同一缺失状态）。预检复制快照并冻结折容器；实际秩和统计选择只使用该折训练资格集。回调只收到阶段、折/重复序号和计数副本，不能修改内部计数；回调异常或原输入数值被改动即停止，返回不含输入值的错误和操作计数，不返回部分研究结果。

完整观测训练人数以区间输出。少于50或全训练恒定列时不启动该折抽样；抽样中不同原人员少于20或任一恒定列，该次对全部11候选共同不可估。不重抽，不把不可估填为未选中。少于90次有效时整折边统计为null，跨折交集也为null；可估而零选中时频率为0、符号一致率null、保留false。

一个人的多次抽中按原值和次数参与平均秩/协方差，不把副本当新人员。固定PCG64/SeedSequence分折分重复种子，不修改NumPy全局随机状态；没有事件分层或测试表现调参。


真实入口由已核验宿主调用，binding为protocol_sha256、snapshot_sha256及graph_folds_sha256；其值可由`dict(RESEARCH_BINDING)`取得，但复制常量不是核实实际字节，也不是授权。宿主必须另外核验真实输入的同缓冲hash、原CSV行序/图折位置身份桥接、独占尝试、资源限制及输出白名单。API仅检查调用方声明和内存形状，provenanceVerified/historicalRealtimeCertified仍false；不得把真实输入送入synthetic入口。

## 计数、输出和错误

每次五折调用最多5个全训练和500个抽样统计尝试。`score_attempts`包括支持不足的抽样尝试；`numerical_score_calls/returns`另记实际数值函数调用/返回，`score_returns`仅计通过数值返回校验者。不同人员数不足的抽样不会调用数值函数。单元测试可多次独立调用或单独检查数值原语，测试总调用数不是一次研究的505上限。

只返回逐折资格区间、支持状态、有效性计数、11候选统计和描述性交集；不返回个人ID、抽样位置、原值、秩或完整精度矩阵。方向元数据沿用原先验，方向/因果/临床/总体区间/错误率控制/来源真实性/历史实时认证均为false。`constructed=True`是调用约定，不验证来源，也不授权将真实记录改名为人工数据。

`StructureStabilityError`含稳定错误码和独立操作计数，无原始异常文本；数值错误、无效抽样返回或进度错误不重试。定义内的恒定列使用`NotEstimable`，其他异常不能伪装成支持不足。实际数组支持不会自动认证真实输入资格。

## 已运行的人工验收

在已锁定Windows图环境，从本仓库根目录运行：

```powershell
.venv-research-graph/Scripts/python.exe -B -m unittest research_runtime.tests.test_graph_structure_stability -v
```

42项通过。独立全因子人工数组给出零分数、双列同秩0.9、三列同秩9/19及负号的解析真值；核对候选外关系不新增和端点交换对称。覆盖90/89有效分母、符号和阈值等号、全训练冲突、零选中、恒定列、缺失、不同人员数、身份/共享快照、数值与回调故障、计数/元数据篡改、确定性及505上限。改变第一折留出人员在其他训练折中的值，不改变第一折结果。

测试期间启用受保护输入路径和网络audit拒绝，结束后守卫失效并恢复函数，不约束后续测试模块；0拒绝尝试、0临床读取/参数加载/原CIF/优化拟合，且不导入预测或图训练模块。测试计数为人工软件验收，不是研究参与人数或独立实证。没有重跑旧54/150/225/855或已关闭阈值研究，未运行全部课程/平台CI，不宣称其他环境已验收。

原42项人工科学验收已关闭并复用。2026-11-03新增8项接线验收通过，原数值辅助函数及循环经AST核对保持；真实单次505调用/386返回，381有效/119抽样恒定列无效。五折有效91/63/78/88/61，仅第1折可估且无候选满足全部保留条件，第2—5折44个候选统计和跨折交集null。本固定真实问题按不可估收口，选边预测比较NO_GO，不补抽、删变量或放宽90门槛。没有学习方向/个体因果或修改图，新增预测拟合0、累计673/36、旧阴性/CSF NO_GO/阈值SUPPRESSED保持。
