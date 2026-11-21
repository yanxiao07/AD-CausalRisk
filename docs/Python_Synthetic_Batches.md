# 合成三表到研究结果报告的批处理

`course_batch.py`把现有三表预检、原验证客户端、结果校验和报告工具接成显式操作的Python流程，对应FR-DATA/QUALITY/AGENT、FR-WARN-02和FR-REPORT-01的合成联调辅助。它只使用原合成验证数据与加性规则，不调用研究模型、不导入Java数据库、不管理用户或业务任务。

## 准备、执行、检查

从仓库根目录运行，Python标准库即可。示例包含一个完整成功和一个无认知阻断案例：

```powershell
python -B -m python_service.course_batch prepare --input python_service/examples/course-batch.synthetic.json --output course-batch-plan.json
```

prepare仅解析/核对原三表和质量门槛，不启动服务、不发HTTP、不评分。`previewInput`沿用原三表输入；`runIds`必须精确覆盖本批档案，且每个runId合法、唯一，不能使用CSV预检占位值。真实调用时由调用方分配并保存业务runId；示例固定runId只用于受控合成联调，不能被当作通用编号生成器。

在另一个终端启动原合成验证服务，然后显式执行计划：

```powershell
python -B -m python_service.server --port 8091
python -B -m python_service.course_batch execute --plan course-batch-plan.json --url http://127.0.0.1:8091 --output course-batch-run
python -B -m python_service.course_batch inspect --directory course-batch-run
```

execute会主动向指定本机合成验证服务逐个POST合成请求；不替代Java业务授权。inspect只读本机文件，不发HTTP、不提交。准备文件和执行目录均必须全新；已有文件/目录拒绝覆盖，不能通过相同输出路径静默续跑。退出码0=计划准备完成或整批结果/报告已就绪，2=HALTED/INCOMPLETE，1=输入/文件错误，130=人工中断。

## 执行与停止规则

整批计划在任何网络请求前验证：固定契约hash、请求Schema/语义、对象与runId唯一绑定、统一数据版本/窗口、质量摘要及计划内容digest。原值、空值、零值、单位、日期和模型/工作流版本保持。计划不包含档案alias，也不认证输入来源；重新计算hash不是获得授权。

服务健康类型匹配后逐个提交。每个POST之前写入并flush/fsync一条INTENT，包含原runId、请求摘要和本次traceId；已校验结果先单独保存，再生成研究版JSON/HTML报告。BLOCKED是合法返回，保留null/UNAVAILABLE，完成相应报告后继续下一条，不补0分或伪造图/解释。

网络/版本/身份/结果校验失败立即停止，后续条目NOT_ATTEMPTED；不自动重试、更换runId、核对后续任务或补算。POST超时、断线或成功正文不可信时记录UNKNOWN，不能用FAILED或缺失结果推断上游没有执行。报告生成失败保留已经验证的原结果，停止后续提交；报告失败不表示模型需要重算。

## 本机输出与中断

| 文件 | 内容 |
| --- | --- |
| plan.json | 原合成请求、质量摘要及内容绑定；不复制原CSV别名 |
| events.jsonl | 顺序执行意图、结果/报告文件摘要、错误和结束记录 |
| 001.result.json等 | 通过原Schema及语义校验的AgentResult |
| 001.report.json / .html等 | 对应原请求/结果的研究版报告；HTML可浏览器打印 |
| summary.json | 当前执行结束时的摘要；inspect不依赖此文件判定状态 |

inspect从计划、顺序记录和对应文件重新核对结果Schema/语义及文件摘要。重复/乱序/不符绑定的事件或修改后的产物拒绝；只有没有换行的最后一段视为未完成写入，不能用它确认结果。INTENT之后没有有效结果的条目为UNKNOWN；有结果而报告尚未完成时保留原COMPLETED/BLOCKED，报告状态MISSING。计数不会把后续NOT_ATTEMPTED当失败或成功。

本机日志与hash是合成联调的内容记录，不是签名、业务审计库或数据库事务，也不承诺断电恢复。中断/写失败后保留原目录，先inspect，再按[只读核对说明](Python_Interop_Recovery.md)查询原runId和Java已持久历史；没有resume命令。主动另建目录重新execute仍是一次新的显式提交，不能由日志或计划hash自动授权。

## 边界与资源

CSV仍合计最多30000 UTF-8字节、最多200个档案及原每档案100访视/500特征限制；计划文件最多256KiB，单请求最多128KiB。日志最多512KiB且事件数有界，结果/报告文件按客户端与报告上限读取；工具不扩充原服务1000条易失缓存容量。产物按顺序数字命名，不将输入别名/runId拼成文件路径。直接文件符号链接拒绝。

这些条目状态只描述本机合成执行记录，不新增Python HTTP状态或Java业务DTO。整批FINISHED只表示本批已校验结果及报告齐全，BLOCKED仍然没有预测。计划与检查摘要均声明businessPersisted/sourceAuthenticityVerified/clinicalValidityAssessed/automaticResumeSupported为false；检查摘要还明确businessHistoryRequired=true、resubmissionAuthorized=false。

原8091/8092、共同契约、工作流/评分器和既有fixtures均不变，没有新增宿主页面或API。Java仍负责导入确认、对象权限、业务幂等、持久化任务/结果、报告授权及审计；真实报告和临床用途没有由这份合成批处理开放。
