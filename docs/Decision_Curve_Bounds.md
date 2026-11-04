# 固定24月决策曲线与未知结局补全界限

2026-11-01补充FR-13：现有计算升级为`fixed-horizon-decision-curve-bounds-v2`，每模型/阈值增加分类指标，旧净获益字段及输入不变。23项标准库检查通过，其中9项为新增分类验收；原课程评价44项回归另验。没有真实评价、模型调用或拟合。

对应总体需求FR-14，服务第3环节评价及第4环节解释。本模块完成纯内存算术与构造验收，尚未执行真实研究评价。课程合成评分不是患病概率，不可直接传入。

既有`python_service.prediction_comparison.decision_curve`只处理完整构造二分类标签，保持原行为。新增`research_runtime.decision_curve`处理调用方显式提供的24月AD首次事件CIF、同一队列结局及预先声明的阈值，无依赖安装、文件读取、参数加载、CIF调用或拟合。

```python
from research_runtime.decision_curve import (
    CurveProtocol, HorizonObservation, evaluate_decision_curves,
)

protocol = CurveProtocol('synthetic-v1', 'synthetic-first-AD-24m', (.2, .5))
observations = (
    HorizonObservation('SYNTHETIC-A', 1, 10),  # 已观察AD首次事件
    HorizonObservation('SYNTHETIC-B', 2, 8),   # 其他痴呆首次事件：AD标签0
    HorizonObservation('SYNTHETIC-C', 0, 24),  # 到24月无首次事件：AD标签0
    HorizonObservation('SYNTHETIC-D', 0, 12),  # 早截尾：AD标签未知
)
panels = {
    'synthetic-model-A': dict(zip(('SYNTHETIC-A', 'SYNTHETIC-B', 'SYNTHETIC-C', 'SYNTHETIC-D'), (.8, .3, .1, .6))),
    'synthetic-model-B': dict(zip(('SYNTHETIC-D', 'SYNTHETIC-C', 'SYNTHETIC-B', 'SYNTHETIC-A'), (.6, .1, .3, .8))),
}
result = evaluate_decision_curves(protocol, observations, panels)
# 两模型相同，只是键顺序不同；所有配对差界限为[0, 0]。
```

`event_type`仅允许严格整数0/1/2，`observed_month`为0—24整数。0月保留当前月度表示边界；精确事件日/索引先后由调用方先验收，本模块不读取日期或重构标签。原cause2是其他病因痴呆，不能替换为死亡。这里的结局是AD作为首次竞争事件，不能用于“其后一生是否出现AD”。超24月记录须由有版本的上游终点流程处理，不在本模块截断或改写。

净获益在每个预声明阈值`t`按`TP/N - FP/N * t/(1-t)`计算；采用`p >= t`，与原合成工具一致。公式依据[Vickers与Elkin原始论文](https://pmc.ncbi.nlm.nih.gov/articles/PMC2577036/)。阈值仅表达假定的错误代价权衡，未由本模块选择或推荐。

以下补全界限是本项目对线性算术的直接推导，不是原论文已验证的删失估计方法。令`a_i`为是否选中，已知结局的贡献为`a_i[y_i-(1-y_i)t/(1-t)]/N`；每个未知`y_i`在0和1之间补全，取贡献的最小/最大并求和。每模型、全选、不选及同人策略差都使用完整原队列N。配对模型差先计算`a_right-a_left`，再对同一未知结局取极值；不把两个边际界限机械相减。每个阈值的上下界均可由相应补全达到，但不承诺一套补全能同时达到全曲线所有端点。

输出包含原分母、已知/未知/已知AD/已知非AD人数、每阈值选中人数及其中未知数、各模型界限、相对全选/不选和模型间配对差。缺失不补0、不删除早截尾者、不改变原折或事件定义；单类和全未知也能返回有限算术界限。输出不含人员编号或个人概率，版本与端点只是调用方声明。

FR-13的`classification`包含已知TP/FP/TN/FN、选中/未选中未知人数及各混淆计数范围，和灵敏度、特异度、阳性/阴性预测值、准确率的补全范围。已知真阳性a、已知假阴性b、选中未知u、未选中未知v时，灵敏度的通常端点为`a/(a+b+v)`与`(a+u)/(a+b+u)`。如果某个端点分母为0，不能直接把整个指标判为0或null：只在具有正分母的补全集上求界，并显式报告`denominatorMayBeZero`及`denominatorAlwaysZero`。

例如，两人结局全未知且全部选中：在“至少一人确为AD”的补全中，灵敏度恒为1；在“全为非AD”的补全中，灵敏度未定义。因此输出界限[1,1]且`denominatorMayBeZero=true`，不表示实际灵敏度已知为1。如果完整结局没有任何AD，则灵敏度始终未定义，上下界为null且`denominatorAlwaysZero=true`。全不选者的阳性预测值始终未定义，不能用0代替。

每项界限为该指标各自可达的边际范围，不承诺所有端点能来自同一套补全。TP与FP的上界也不能拼成一个同时成立的混淆矩阵；`jointRangePolicy`对此明确说明。计数加上未知组应重构完整原队列，灵敏度/特异度使用各补全下相应真实类别分母，准确率始终使用完整N。它们不是完整病例分析或IPCW估计。

界限不是置信区间，不包含抽样/拟合/校准/来源误差；未识别的删失机制、历史可获得性、真实训练成员、OOF/端点真实性和临床有效性不由此认证。本模块没有独立删失/IPCW、治疗收益、图增益或临床阈值结论。FR-14的真实研究展示与临床解释仍未验收。

从仓库根目录运行标准库构造检查：

```powershell
<Python3.11> -B -S -m unittest research_runtime.tests.test_decision_curve -v
```

验收包含81种四人已知/未知结局模式的所有二进制补全，以独立有理数TP/FP枚举核对模型、全选/不选及配对差；另核对原完整标签DCA、键序、cause2、月0/24、单类、全未知、极端阈值和非法输入。此处是软件数学验收，不能用于声称1160人的真实净获益或新临床实验已经完成。

分类验收进一步组合81种结局模式与16种选中模式，共1296种情况，逐一枚举所有未知结局补全，直接用集合交集构造完整混淆矩阵和Fraction比率；对五项指标核对可达上下界、部分补全未定义和始终未定义。这是算术真值核对，不是1296个受试者或研究样本。
