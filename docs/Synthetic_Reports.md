# 合成验证报告与演示系统兼容

本阶段提供标准库报告工具 `python_service/research_report.py`，以及可选演示适配器 `python_service/demo_report_adapter.py`。不修改现有研究或合成研究请求/响应，不实现开发人员负责的Java业务报告接口，不下载数据或加载模型。

## 可测试功能

在Python负责人已经运行的同一演示系统进入 `/public/`，运行合法合成案例，或从任务列表选择已有已完成任务。结果区现在有：

- **研究版预览与打印**：模型输出、五来源贡献、合成输入、执行环节、版本/哈希和限制。
- **验证版报告**：保留合成标签、输出、解释和限制，省略原始输入表及页面技术哈希。
- **导出合成报告**：原有JSON下载保持原路径及返回内容。

预览页使用浏览器“打印”或Ctrl+P，可选择“另存为PDF”。HTML有A4打印样式、重复表头和移动布局；在线预览通过同源 `/api/system/reports/report.css` 加载样式，保留原CSP，不需要外部字体、图像或脚本。直接使用Python生成的独立HTML默认内嵌相同样式。本阶段不是服务器原生PDF导出，也不生成真实患者报告或患者角色摘要。

## Python工具与输出

```python
from python_service.research_report import build_report, render_html

report = build_report(completed_synthetic_task, audience='research')
html = render_html(completed_synthetic_task, audience='teaching')
```

输入是现有合成任务结构，要求任务COMPLETED、input_mode为SYNTHETIC_TEST，内层模式、模型/变体、案例标识和DEMO来源节点均通过检查。还检查有限概率、两分支和、五来源身份、贡献重构及研究边界。未完成/失败/取消任务、真实模式或不匹配结果抛出 `ReportError`，不渲染成功报告。

JSON格式标识为 `adcr-synthetic-report-v1`。结果包含scope、audience、原任务时间、模型/案例hash、预测、解释、输入缺失、执行环节和限制。只选取所需字段，不转存整个请求或任意额外元数据；验证版sources为空。数值沿用保存结果，生成报告不重新调用模型，不补造阶段耗时。

## 同一演示服务的新增只读资源

`GET /api/system/reports/synthetic/{taskId}`

| 参数 | 值 | 默认 |
| --- | --- | --- |
| audience | research / teaching | research |
| format | html / json | html |

taskId只能为既有 `wb2-` 任务编号。页面预览返回text/html，结构化报告返回application/json；400为参数错误，404为任务不存在，422为任务未完成/范围或内容不合格，503为来源绑定/模块不可用。原Host/Origin检查先于适配器执行，原任务存储与来源绑定校验继续使用。没有新增真实报告路径或修改旧下载资源。

适配器只接受应用传来的任务库、来源校验、审计与响应对象，不启动另一套服务。前端扩展检查旧按钮/渲染接入点，若接入点改变则明确失败，避免悄悄缺少报告入口。

## 验收边界

仓库报告/适配器构造测试不依赖病例或估计器，覆盖确定性、六变体、双视图、字段选择、HTML转义、数值重构、错误任务及真实输入拒绝。所有原Python/契约检查继续运行：

```powershell
python -B -m unittest discover -s tests -v
```

完整应用的HTTP/浏览器验证由Python负责人在授权工作区执行。Java开发人员仍按研究契约实现授权、业务映射与报告保存；本功能通过不代表Java已接通、跨机部署或临床有效性已经验证。后续若扩展业务HTTP媒体类型或真实报告，应先明确调用方/权限/字段范围，再调整相应契约。
