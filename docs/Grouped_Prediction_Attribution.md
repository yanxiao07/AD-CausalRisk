# 固定参考的分组Shapley与LIME局部解释

本模块服务于第4环节、FR-15/16尚未完成的SHAP/LIME方法需求。现已提供**精确分组Shapley**与**二元分组邻域上的LIME式局部替代回归**，以及既有图模型两原因CIF接口；这是方法实现与构造验收。真实受试者SHAP/LIME、跨模型重训/参考的解释稳健性和个人因果结论仍未验收。旧正反序解释及其阴性/敏感结论保持，不重算、不替换。

实现为NumPy数值算法，复用既有图模型CIF、个人观测门控、训练人月中位数和九组定义：左右海马联合为一组。没有新增shap/lime软件包依赖，也不改变服务HTTP契约、默认模型或Java业务。

## 明确定义被解释的量

对调用方提供的固定预测器和参考值r，定义v(S)=f(x中S组保留观测，其余组用r替换)。输出为AD与其他竞争原因的24月CIF，在概率尺度计算。缺失位置保持NaN，观测掩码固定；这不是“没有该因素”的临床状态。固定参考不代表条件分布，组合出的状态也未必真实存在。

参考由既有外折训练人月中位数取得；适配器只使用已在内存模型中保存的中位数，不读取样本或重新拟合。调用者仍须认证模型来源、外折身份、输入资格和权限；本模块不能从一个DataFrame证明无泄漏或补齐QC/历史可获得性。

| 方法 | 实际计算 | 必须保留的限制 |
| --- | --- | --- |
| 精确分组Shapley | 对所有子集计算组加入前后的差，按\|S\|!(m-\|S\|-1)!/m!加权；不是只取正/反序。与参考完全相同的组为dummy，可省略后严格补0 | 精确性只针对所定义替换博弈；改变参考/分组会改变归因，不能推断解释在生物学上唯一 |
| LIME式局部替代 | 二元组保留掩码邻域包含参考/全观测端点，其他掩码无放回采样；按exp(-平均Hamming距离/width²)加权，带ridge的线性回归只惩罚斜率 | 采样/核宽/参考/ridge均影响结果；局部替代截距不等于真实参考CIF。返回实际预测残差，不能强制摊入特征或以训练邻域R²当独立验证 |

Shapley满足固定博弈的加和重构；LIME的代理预测可能不能精确重构真实预测，局部线性值也不保证在概率范围内。真实CIF与代理预测分别返回，不截断归因或隐藏残差。两种方法均标记`is_causal_effect=False`。

## API与资源边界

`research_runtime.group_shapley.exact_values(predict, values, reference, groups, max_coalitions=512)`处理最多256行、9组，每组列索引必须完整无重叠地分割输入。每批最多512次预测，超过预算在预测前拒绝；真实研究总调用量仍须由外部编排另行锁定。

`research_runtime.group_lime.local_surrogate(...)`一次只解释一行；默认128个唯一掩码、seed=20261027、kernel_width=0.75、ridge=1e-6，最多512次预测。无变化组返回0；无变化博弈不拟合替代模型。报告掩码摘要、局部回归计数、设计秩、加权RMSE/R²及观测点残差，常数响应的R²为None。

两个模块均提供`explain_graph(model, frame, ...)`，直接复用`graph_explanation.Calls`和既有两原因CIF检查，并绑定模型身份、保持原输入与模型不变。Shapley适配器支持小批量，LIME适配器仅一人。返回数组仅驻留内存，**包含个人归因**；不自动写入日志/文件/网页或公开接口，也没有临床路径CLI。

后续执行准入增加`reference_kind='median'/'mean'`：均使用模型已保存的外折训练人月原量纲统计，缺失掩码保持。LIME增加可选`batch_predictions=True`与`holdout_samples=32`；前者只批量计算同一邻域，不改掩码/权重/回归，后者使用未参与拟合的掩码评价局部代理。没有剩余掩码时明确报告未定义，不将拟合邻域R²当作留出验证。默认值保留旧串行、无留出行为。

固定研究编排由`research_runtime.grouped_xai_study.evaluate`提供，接收已核验的内存队列/冻结折/模型加载回调和原OOF聚合绑定，先核对每种子及三种子均值的两原因OOF和Brier，再执行归因。它返回聚合重要性、同人配对敏感性和局部代理忠实度；不读取数据或参数文件、不保存个人数组、不重训风险模型。具体研究范围见[执行规格](Grouped_XAI_Study_Protocol.md)。

```powershell
# 从仓库根目录，在既有独立图环境中执行；全部为构造数据。
.venv-research-graph/Scripts/python.exe -B -m unittest research_runtime.tests.test_group_shapley research_runtime.tests.test_group_lime -v
```

本轮16项新增检查包括：独立4!排列平均与解析交互真值、两原因、九组全512子集、分组次序不变、缺失/联合海马、参考敏感性；LIME已知线性系数、非线性残差、固定seed重放、变seed/核宽/参考敏感性；预算/非法输出/副作用拒绝与真实CIF代码的常数参数接通。LIME构造回归另计，不计入原风险模型基础/校准拟合。

稳健性需比较事前固定的参考/seed/核宽/模型，报告每种结果及无定义情形，不以选择最一致配置代替稳健性。已有`graph_explanation.local_rank_summary`可用于同一观测组的归因排序比较；本轮敏感性构造反例证明“可以变化”，没有估计真实人群变化率或临床可靠性。

## 后续真实研究准入

先锁定既有模型/外折/结果身份、研究范围、参考方案、总CIF与局部回归预算、个人输出的留存限制及阴性停止条件，再申请明确的旧派生快照读取和既有模型加载范围。此实现不自动授权临床/私有投影读取、模型加载、新拟合或重开旧解释。

方法依据：Lundberg SM, Lee SI. [A Unified Approach to Interpreting Model Predictions](https://papers.nips.cc/paper/7062-a-unified-approach-to-interpreting-model-predictions). NIPS 2017；Ribeiro MT, Singh S, Guestrin C. [Why Should I Trust You? Explaining the Predictions of Any Classifier](https://arxiv.org/abs/1602.04938). 2016。另核对[SHAP精确解释器文档](https://shap.readthedocs.io/en/latest/generated/shap.ExactExplainer.html)。本项目定义的是明确的分组、单参考博弈，不声称与任意现成解释器的默认设置等同。
