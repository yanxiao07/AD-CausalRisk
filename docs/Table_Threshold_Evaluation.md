# 九方案原外折预测的只读阈值评价

对应FR-13/14，第3→4环节的预测评价工程。`table_xai_study.evaluate_thresholds`独立于原归因/训练扰动入口；后者默认`SPECIFICATIONS=('seven','multimodal_values')`及原算法保持。新模式已完成构造验收与固定45外折重放；真实1160人、九方案的联合发布门槛返回整表SUPPRESSED，分类/DCA数值不发布，不据此宣称模型无效或临床净获益。

三规格为seven、modality_availability、multimodal_values，三算法为logistic、random_forest、xgboost；每方案原五外折，共45槽位。仅使用原始未校准24月两原因CIF，不使用全量模型、校准输出、阈值调优或模型推荐。availability按原`information_arm`把新增三变量的非缺失值置0并保留缺失；原输入保留，返回矩阵不保存个人预测。

## 调用与绑定

由已独立验证来源和权限的宿主提供内存对象：

```python
from research_runtime.decision_curve import CurveProtocol
from research_runtime.table_xai_study import evaluate_thresholds, THRESHOLDS

result = evaluate_thresholds(
    frame, splits, loader, candidates, expected_AD,
    protocol=CurveProtocol(data_version, endpoint_id, THRESHOLDS),
    expected_input_fingerprint=pinned_cohort_fingerprint,
    original_folds=original_fold_records,
    expected_subjects=1160,
    progress=record_counts,
)
```

这些变量由宿主提供，不是本模块会自动读取的文件。`frame`为一人一行原十变量、subject_id、observed_month、event_type；固定24月首次AD，cause2仍为其他病因痴呆。`splits`的原顺序与原存储位置必须匹配`original_folds`，后者同时包含train_positions/test_positions与train_subjects/test_subjects。仅检查合法五折不能替代原受试者和顺序绑定。输入fingerprint与原九方案期望由可信历史清单固定，不从本次预测重新生成。

`candidates`为45个不同(specification,family,outer_fold)元数据条目，包含原role、plan_sha256、clinical_use=false。`loader(specification,family,fold)`最多每槽调用一次，返回原bundle；宿主须在反序列化前核对授权路径和原字节hash。本模块不读取文件、不加载参数。七变量要求NESTED_OUTER_RESEARCH_VALIDATION，其余要求MULTIMODAL_OUTER_VALIDATION，校验列顺序、算法类型、原fold/plan、arm、raw和回顾性用途。禁止全量模型冒充外折、不同fold共用同一活对象或共享预处理/估计器。

`checked_outer_bundle`保持默认拒绝availability，只有新模式显式`allow_availability=True`。两种十列模型使用相同保存预处理布局，因此只在布局校验时复用multimodal_values的列结构；模型arm身份与输入变换始终为availability，不改名绕过检查。原`groups_for`和归因两规格约束未改。

`expected_AD`须完整含三规格×三算法；每规格包含三family字典及`historical_other_cause_OOF_hash=None`。每family提供原`oof_prediction_sha256`（原顺序float64小端AD数组hash）及`pooled_oof_brier`。五个训练折KM只按原定义重放旧Brier身份门槛，单独记training_KM_replays=5，不是新删失识别或校准研究。九hash和九Brier全部通过之前不调用任何分类/DCA计算；未存在的历史其他原因OOF证据不能自动认证。

## 结果与失败语义

六阈值固定0.05、0.10、0.20、0.30、0.40、0.50，均为探索性错误代价假设。每算法独立输出三方案、三同人比较，合计54模型阈值点与54比较阈值点。主描述比较为values−seven和values−availability，可用性对照为availability−seven，不跨算法选最佳。比率和净获益的含义见[未知结局范围](Decision_Curve_Bounds.md)。

返回`operations`区分加载尝试、loader返回、通过元数据核对的完成数，及CIF尝试/返回/接受数；拒绝的返回不能计作接受。完整矩阵为45/45/45、45/45/45，CIF状态行为9N，24月推理人月为216N。构造回调的槽位计数不是实际原生CIF执行数，真实宿主还须独立核对原函数调用。

输入、模型状态和行序不能被回调改写。加载/CIF/输出回调异常、非法概率/竞争概率和、历史门槛不符、模型复用、耗时或内存超限均停止；抛固定错误，不返回部分科学结果，不自动重试。progress仅含阶段、spec/family/fold及计数，不含分数/身份/个人hash。loader和CIF异常消息不回显。每个模型接受预测后释放本模块强引用；调用方如持有模型仍负责其生命周期。

资源软检查上限1200秒/2GiB，覆盖外部回调前后；不能中断正在阻塞的回调。真实宿主必须另有父进程硬停止、独占启动、同缓冲hash/解析及独立调用账本。本构造验收不等于这些真实执行措施已接通。

## 聚合发布门槛

在所有门槛通过后，按标签状态（已知AD/已知非AD/未知）及全部九方案×六阈值的联合选中向量划分原子组。任何非空组不足5人，则`privacyStatus=SUPPRESSED`、`familyResults=null`，整份分类/DCA及所有配对比较均不输出。完成重放且抑制不等于失败，不能为了得到可见指标换阈值、删人或放宽门槛。

不能仅检查单表细胞：两个阈值分别产生20与19个阳性时，差集只有1人；两个算法各选中15个AD而交集只有1人时，每算法单独抑制仍会遗漏交叉反推。本规则覆盖同次发布的这些联合差集，不输出原子模式/计数。全部非空原子至少5时，每个发布计数、比率和DCA均是这些原子的函数；不承诺抵御任意外部逐人知识，不称差分隐私或全面隐私认证。

结果始终clinical_use=false、causal_effect_identified=false，模型字节来源/真实OOF训练成员尚需宿主独立证据。保留原图阴性、CSF NO_GO、缺失非随机及历史可用性未知，不自动部署或选择临床阈值。

## 构造验收

在固定研究环境运行：

```powershell
<研究Python> -B -m unittest research_runtime.tests.test_table_thresholds -v
```

19项检查覆盖90行人工队列45解析槽位、九门槛先于全部指标、原fold身份/顺序、第三规格及零/缺失、失败计数、无重试/部分结果、输入与模型副作用、弱引用释放、资源限制、跨阈值/跨算法隐私挑战。原CIF在人工直接赋值参数上另作两次算术一致性检查，没有训练/参数文件加载。真实1160人的输入、权重与指标均未在本阶段执行。
