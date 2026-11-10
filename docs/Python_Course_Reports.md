# 合成输入报告的离线导出

`course_report.py`接收原`AgentRequest`与对应`AgentResult`，导出已经校验的合成结果。内部模块名和机器范围标识保持兼容；此工具不调用真实研究模型，也不把合成评分转换成临床风险概率。

## CSV格式

`render_course_csv(request, result, audience='research')`与HTML/JSON复用同一个报告校验和裁剪入口。CSV使用UTF-8、CRLF、固定两列`field,valueJson`，每个报告字段一行，按字段名排序。值是严格JSON字面量，嵌套结构保留在一个单元格；可用`json.loads(valueJson)`重建整个报告。`null`、零、`false`和空列表保持区别，原始精度、版本、来源和限制均保留。

字符串保留JSON引号，换行被JSON转义，不会作为以`= + - @`或控制符开头的电子表格公式单元格输出。表格软件打开后不应手工移除这些引号并将来源文本作为公式运行。简版仍省略原输入、图和内部轨迹，不是患者权限边界。非法输入先拒绝，不生成部分报告。

```powershell
python -B -m python_service.course_report --request contracts/examples/deepagent-v1.request.example.json --result contracts/examples/deepagent-v1.response.example.json --format csv --output report.csv
```

这只增加显式指定新文件的离线CSV导出；已有HTML/JSON输出、静态示例HTTP格式和Java附件下载接口不变。真实研究报告和完整FR-22仍需分别验收。

```python
from python_service.course_report import build_course_report, render_course_html

report = build_course_report(request, result, audience='research')
html = render_course_html(request, result, audience='teaching')
```

生成前复用原字节固定Schema与语义检查：请求/响应runId、版本、合成范围、质量阻断、图来源、解释重构和执行阶段须匹配。非法/未完成/串对象或研究DTO拒绝；BLOCKED可以生成明确的质量阻断记录，但riskScore保持null、等级UNAVAILABLE，没有编造解释或阶段。合法输入加结果的紧凑UTF-8 JSON合计最多256KiB，属于报告资源上限，不改变原Agent接口。

研究版保留原合成输入值、日期、单位、图路径、规则贡献和所提供响应中的阶段/耗时；不舍入原输入，不补零，不重新评分。只挑选已知字段，不复制任意阶段summary元数据。教学版省略原输入、图结构、数字贡献和内部执行阶段，但保留演示结果、版本和限制；它不是患者权限摘要，不能代替Java授权过滤。

内容hash绑定请求、结果和原Schema，用于核对内容，不认证原记录真实性或服务身份。报告始终声明sourceAuthenticityVerified/clinicalValidityEstablished/causalEffectIdentified为false，reviewRequired为true。输入中的合成声明与DEMO编号不独立认证来源真实性。生成报告不调用模型、网络、科研运行时或业务数据库。

## 离线文件工具

从仓库根目录运行，只有显式指定的全新输出文件会被创建，已有文件拒绝覆盖：

```powershell
python -B -m python_service.course_report --request contracts/examples/deepagent-v1.request.example.json --result contracts/examples/deepagent-v1.response.example.json --output course-report.html
python -B -m python_service.course_report --request contracts/examples/deepagent-v1.request.example.json --result contracts/examples/deepagent-v1.response.example.json --audience teaching --format json --output course-teaching-report.json
```

HTML包含自有打印样式，支持浏览器打印另存PDF；没有新增服务器原生PDF引擎。示例中的durationMs不是本次报告运行耗时。失败时只输出稳定错误码，不打印输入内容或任意异常路径。

## 维护方演示入口

既有8084公开页面新增课程报告示例链接。同一服务新增只读GET `/api/system/reports/course-example`，参数`audience=research|teaching`及`format=html|json`；JSON带`reportOrigin=PINNED_CONTRACT_EXAMPLE_NOT_LIVE_RUN`、`liveServiceProbed=false`。

此资源仅读取原字节固定请求/响应示例，没有taskId参数，不接受提交正文、真实记录或任意文件路径。它不连接8091、不创建任务、不计算模型，也不把静态示例当当前任务。示例文件被改动/缺失时503拒绝；非法查询400，POST405，原Host/Origin检查继续执行。HTML使用现有同源CSS，不放宽CSP。

原8091课程三个操作、8092研究接口、Java共同契约和评分数值均未改变。课程业务报告API、授权导出、任务关联/持久化与审计仍由Java完成；该演示资源不替代这些业务功能。

## 验证

`python -B -m unittest discover -s tests -v`。本模块验证原100份合成请求结果、BLOCKED、身份/版本、解释重构、数值保留、HTML转义、固定示例hash、教学裁剪和CLI不覆盖。维护方另外执行当前8084实际HTTP、独立Schema及浏览器打印验证。所有这些都是工程验收，不构成独立临床效果验证。
