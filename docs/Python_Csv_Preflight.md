# Python三表预检

本模块完成FR-DATA-03/04及FR-QUALITY-01/02/03的Python辅助部分：解析三表、定位错误行、检查关联与原质量门槛。Java仍负责导入任务、确认入库、幂等持久化、权限、质量记录保存及业务版本；当前模块没有实现这些业务API。它不读取真实研究数据，不运行预测或训练。

## 输入与使用

标准库接口如下；从仓库根目录调用。`preview_csv`仅返回安全摘要；需要接入原AgentRequest时，`prepare_csv`还返回内存中的类型化请求。必须先检查无错误，调用方随后自行分配并保存业务runId，不能直接拿预检中的固定占位runId重复创建评估。

```python
from pathlib import Path
from python_service.course_csv import preview_csv, prepare_csv

payload = {
    "input_mode": "SYNTHETIC_TEST",
    "dataVersion": "demo-csv-check",
    "predictionWindowMonths": 24,
    "tables": {
        name: Path(f"fixtures/{name}.synthetic.csv").read_text(encoding="utf-8")
        for name in ("subjects", "visits", "features")
    },
}
report = preview_csv(payload)
report, typed_requests = prepare_csv(payload)
```

三张表必须同时提供，字段及顺序使用原fixtures表头，不猜列名、不自动截断空格或修正数值。只接受UTF-8文本，可有一个表首BOM；CSV解析遵循Python标准库strict reader，支持带引号逗号/换行。报告中的行号是CSV记录起始物理行，不是按对象分组后的序号。

| 表 | 精确表头 | 核查 |
| --- | --- | --- |
| subjects | subjectCode,alias,status | DEMO编号；别名非空且不超过100字符；ACTIVE/ARCHIVED；编号唯一 |
| visits | subjectCode,visitCode,visitDate,mmse,moca,cdrSb | 档案必须在本批subjects中；编号在档案内唯一；有效非未来日期；认知范围按原研究契约 |
| features | subjectCode,featureName,featureValue,recordedAt,unit | 本批档案关联；三种原白名单特征；APOE整数计数0/1/2、单位count；MRI在[0,1]、单位normalized |

数值空单元格表示None，字符串null/NaN/Infinity/布尔不是缺失值。0保持为0。研究原接口的MMSE/MoCA范围允许小数、CDR-SB范围为[0,18]；本模块保持它们，不把另一套研究接口的整数/离散门槛套到这里。APOE CSV非空计数必须写0、1或2，不将1.0/1e0静默改成整数。

三表合计最多30000 UTF-8字节；subjects/visits/features最多200/1000/2000条，每档案仍受原AgentRequest最多100访视/500特征限制。问题最多显示200条，`errorCount`/`issueCount`保留完整计数，`issuesTruncated`明确是否截断。字节/行上限按独立条件共同执行，不承诺所有行数上限能在字节上限内装下。

## 结果与质量含义

字段、类型或关联错误返回status=INVALID，附table/row/field/code/severity；整批不产生类型化请求和档案质量结果，不将部分合法行当作已导入。请求外层错误抛CsvPreviewError，仅带稳定代码，不携带原始单元格文字。

结构正确返回status=PREVIEW_ONLY。各档案的PASS/WARN/BLOCKED与原工作流质量行为对齐：最近访视没有任何认知值、索引日多访视、同日特征冲突、非24月窗口阻断；缺失模态/部分认知和索引日后特征被排除产生WARN。相同日同值重复特征另列预检WARN，不修改原工作流PASS/WARN。较晚null特征不擦除较早非空值，日期按原采集日字段，不认证实际历史可获得性。ARCHIVED只按既有档案状态保留，不代替Java是否允许评估的业务判断。

所有预检结果的riskScore均为null，predictionProduced/modelExecuted/persisted/commitSupported/clinicalValidityAssessed均false。PASS也没有预测；BLOCKED并不是格式错误，仍可预览其明确原因。SHA-256摘要绑定精确三表文本、声明版本和窗口，只用于内容核对，不是入库凭证、持久幂等、签名、来源认证或数据授权。返回不包含别名、原始访视/特征数值。

## 现有演示系统的薄适配

可选`demo_csv_adapter.py`把同一纯模块接入现有演示系统；`POST /api/system/course-csv-preview`是演示系统新增的独立路径，不是原8091研究契约或Java业务目标API的变更。它要求唯一同源Origin、JSON正文最多32768字节；GET返回405，非合成模式403，结构错误400，媒体415，正文超限413。有效HTTP200仍须检查status与质量结果。没有commit路由或模型调用。

页面“三表关联预检”提供正常/错误构造例、错误物理行和字段、输入关联链接、质量原因。编辑输入清空旧结果，较早请求不能覆盖后来修改；请求失败不保留成功结果。原研究单表预检保留原路径、输入格式和行为，两个模板分别说明。

## 验证

从仓库根目录执行 `python -m unittest discover -s tests -v`。本模块构造测试覆盖三表原100档案/200访视/300特征、全部原100个JSON档案质量对照、错误表头/关联/重复/单位/日期/范围、物理换行、空值/0、阻断、版本与资源限制。该对照沿用原规则，不是独立科学或临床验证。现有演示系统另执行实际HTTP与独立Schema校验、桌面/手机和错误恢复检查；这些本机证据不复制到公共仓库，也不代表Java端到端完成。
