# 七/十变量模型统一评估入口

本项是第1→3→4环节的有界业务接入工程。原`run_in_memory`可调用已验证模型，`table_explanation`可解释保存的模型，但此前同次结果拼合只存在于构造示例。`research_runtime.table_assessment.TableAssessment`连接两者，不读取模型或临床文件、不训练、不新增HTTP，也不替换8091。

## 宿主调用

```python
from research_runtime.inmemory_inference import authorize_once
from research_runtime.table_assessment import TableAssessment

# 所有对象由有权宿主从独立、已核验的来源准备。
# 不从来件复制context，也不把真实记录标为constructed来取得许可。
assessment = TableAssessment(verified_loaded_model, verified_identity=identity,
                             reference_kind='median')
permit = authorize_once(request, trusted_context=context,
                        authorization_ref=prediction_authorization_ref,
                        authorization_granted=True)
result = assessment.run(request, trusted_context=context, permit=permit,
                        explanation_authorization_ref=explanation_authorization_ref,
                        explanation_authorized=True, max_coalitions=512)
```

两种授权是可信宿主对独立权限的断言，不是身份认证服务，不能取自请求JSON。模型身份含原manifest/artifact/plan/source/lock/mapping绑定，本模块不认证发布者。Java继续负责用户、对象授权、任务和保存。

入口目前一次接受一个已冻结索引的受试者，支持七/十变量LR/RF/XGBoost表格角色，拒绝图模型和多记录。原来源、QC、完成、单位、日期、缺失和独立证据门槛保持，缺项时无预测或解释。没有预测许可返回`PERMISSION_REQUIRED`；有预测许可但无解释权限，在计算前拒绝。

同一assessment实例以同一把锁覆盖预测、保存参考选择与SHAP。宿主必须独占模型，不在其他线程绕过该实例访问模型；内存状态摘要不认证任意对手篡改。参考仅取模型保存的中位数/原量纲均值，原缺失掩码保持，不接受来件自填参考。

直接预测仍消耗原单次许可，解释另需明确预算（1–512次CIF上限）。解释失败、模型变动、端点错配或贡献不重构时抛固定码，不返回部分分数。预测许可已消耗后不因解释失败恢复；许可成功只表示直接预测子调用成功，不代表统一评估完成。无自动重试。

## 结果与边界

返回`research-table-assessment-v1`，包含原准入/预测及同次解释。`basis`绑定同一请求、独立context、模型状态、保存参考、特征/原因顺序与24月原始CIF策略。预测保留`ad_cif_24m`、`other_dementia_cif_24m`，不转换旧`riskScore/riskLevel`或添加临床阈值。

SHAP是固定参考下的预测归因，不解释缺失机制、病因或干预效果。不计算LIME或执行局部拟合；临床使用、个人因果与默认模型切换均false。个人预测及输入/context摘要仅在有权宿主内存中返回，不写公开日志/Git。

本模块不生成可信上下文。6模型既有核验保持复用；真实来源尚缺的完成/独立QC、语言/量表版本、基础来源及日期资格见[宿主证据](Research_Host_Evidence_Admission.md)，不填默认值。当前只用人工赋值管道/输入验收，没有加载真实参数或解析临床记录，不能称真实数据推理已接通。

下一任务是评审独立真实研究请求/结果契约和Java映射：旧请求缺age、education、cdr_global、ADAS13、FAQ等，旧MRI normalized不能当mm3。新通道须保留准入、双原因CIF和同次解释，不能静默更换`deepagent-v1`或重标DEMO结果。此文档不修改共享契约或代改Java。

## 本地验收

```text
python -B -m unittest research_runtime.tests.test_table_assessment -v
```

原研究环境13项构造检查覆盖两规格、保存中位数/均值、同次重构、未知QC、两种权限、预算、失败无部分结果/许可不可复用、状态变化、输入复制、并发序列化及无加载/拟合。关联`inmemory_inference`、`inmemory_cif_bridge`、`table_explanation`合计53项通过；原数值原语的独立真值复用，不重新训练。
