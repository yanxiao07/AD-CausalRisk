# Python研究接口调用与阶段验收

本阶段完成历史需求FR-AGENT-01/02的Python支持，并核对FR-QUALITY、FR-WARN-02和FR-ASSESS-02中的阻断及真实阶段语义。交付包含原HTTP边界修复、标准库客户端、固定契约/语义校验和可重复的实际HTTP验收。Java的AgentClient、权限、持久化、业务幂等与前端接入仍由对应成员完成。

| Python验收项 | 已实现 |
| --- | --- |
| 固定请求/响应契约 | 读取原OpenAPI三个Schema，以固定SHA-256检测未经协调的变动；请求还复用原输入校验 |
| 成功结果 | 核对runId、数据/模型/工作流版本、合成模式、图节点/边/路径、时间和来源、解释、四阶段及真实非负耗时 |
| 质量阻断 | 无核心认知、索引日歧义、同日特征冲突、非24月窗口均BLOCKED/null/UNAVAILABLE，无图或解释补值 |
| 幂等/冲突/缓存 | 同runId同请求返回原结果及原耗时；不同请求409；重启后404，不自动重算或使用Mock |
| HTTP错误 | 400/408/409/413/415/500/503维持AgentError结构、稳定代码、traceId和带时区时间戳 |
| 传输及异常 | 提前拒绝先完整发错误，再有界丢弃未读字节；歧义报头拒绝；未预期计算异常安全返回500，失败不缓存 |
| 不确定结果 | POST断线/超时或成功响应不可信时明确标记结果未知；不自动换runId或重试 |

## 沿用接口与范围

原端口8091、GET `/api/health`、POST `/api/agent/v1/runs`、GET `/api/agent/v1/runs/{runId}`，请求/结果/错误字段、模型与工作流标识、评分公式均不变。原 `contracts/python-agent.openapi.json` 的SHA-256为 `90384cccce76dd35c8d31dbcc182d8c2f0ff32031948dc694ca7520cdf13eb6c`，没有重写共同契约或开发人员Java/前端。

研究仍只接收DEMO-*、demo-*及SYNTHETIC_DEMO结果，使用原加性规则引擎；不从研究服务取结果来冒充研究成功，也不把验证验收当临床精度提高。研究的数值范围、空值和JSON整数请求约定继续沿用，不悄悄替换为另一个研究评分门槛。

修复的是异常/歧义路径：多个Content-Length、Transfer-Encoding或多重Content-Type明确拒绝；与声明长度不符的短请求体不进入任务。未预期计算ValueError与JSON解析错误分别处理，前者返回已声明的500/ASSESSMENT_FAILED，不能误报输入400；错误不带内部异常细节。合法请求的路径、字段和算法不变。

错误发出并flush后半关闭写端，最多用0.1秒/256KiB丢弃被拒绝的剩余字节，不解析、不存储、不进入RunStore。预算避免为错误请求无限等待；不承诺任意大持续发送或断连的对端总能收到响应。原请求读取超时10秒保持，实际408已走真实等待路径核对。

## 标准库客户端

```python
import json
from pathlib import Path
from python_service.course_client import CourseClient, CourseClientError

request = json.loads(Path('contracts/examples/deepagent-v1.request.example.json').read_text(encoding='utf-8'))
client = CourseClient('http://127.0.0.1:8091', timeout=15)
client.health()
try:
    result = client.create(request)
    saved = client.result(request['runId'])
except CourseClientError as error:
    code = error.code
    http_status = error.http_status
    outcome_unknown = error.request_outcome_unknown
    # 由调用方明确处理，不生成新runId掩盖失败。
```

执行 `python -B -m python_service.course_client --url http://127.0.0.1:8091` 仅探测能力并输出合成模式/版本/缓存范围，不提交档案或启动服务。服务未运行时非零退出，不回退到演示结果。

客户端拒绝外部URL、URL凭证、查询令牌、重定向、重复或非有限JSON、错误媒体/长度/trace及不一致的结果；先核对服务类型再发送输入，不使用系统代理。错误只暴露安全代码、HTTP状态和 `request_outcome_unknown`，不拼接服务端任意正文。

单客户端实例最多保留1000个已发runId与请求副本的绑定，供同一调用方使用，不用于多线程共享。原请求之后的修改不影响已保留副本；新输入应创建新runId，不能改写原业务任务。新客户端读取已有结果需提供 `expected_request`；未知runId不能绕过绑定检查。

结果没有subjectCode字段，客户端不能从响应独立认证对象身份；Java仍需在对象权限范围内保存subject/run关联。本地runId/请求绑定不是用户权限或Java任务Idempotency-Key。服务器缓存仍在重启后清空，404时查询Java已保存历史；没有自动重提、取消、SSE或伪造阶段进度。

## 校验边界

`course_protocol.py`只实现当前字节固定Schema用到的关键词，不是通用JSON Schema库；未知关键词或契约hash改变时拒绝。研发验收另用独立Draft202012Validator和FormatChecker核对实际请求/响应/错误，运行环境不新增第三方依赖。

Schema之外还检查来源激活、索引时间、图依赖、阶段顺序、BLOCKED无结果及解释重构。原贡献与分数按四位小数输出，重构考虑每项最大0.00005的舍入误差并保留[0.05,0.95]截断；等级边界也考虑最终舍入。校验不拟合模型，不证明临床或个体因果效果。

## 可重复验收

跨语言固定案例、独立本机故障工具、只读runId核对和实际进程重启验收见[故障与恢复说明](Python_Interop_Recovery.md)。这些工具不增加原研究操作，也不替代Java持久化/授权验收。

```powershell
python -B -m unittest discover -s tests -v
python -B -m python_service.course_acceptance --self-test
```

self-test在临时本机端口启动原AgentServer，复用100份既有合成档案。核对请求/响应、与原直接工作流非耗时内容相同、100份GET缓存结果、幂等重放、409冲突、四类BLOCKED、未来特征排除及错误DTO。32次提前拒绝收到完整JSON且不执行任务，500/503和真实408只在自有临时服务验收；结束后关闭服务，不启动另一套页面。

对已运行的合成验证服务可显式使用 `--url http://127.0.0.1:8091`。它会创建105个独立新runId的合成记录，不清理旧任务；不修改现有服务容量或注入执行故障，因此少于self-test的全部22项。这是主动验收命令，不是健康检查，需由维护者明确执行。

维护方原8084演示可选显示只读验收记录：源码匹配且22项/100档案/32拒绝及独立Schema证据齐全才为VERIFIED；源码改变为STALE，缺失/无效记录为NOT_RUN/INVALID。`重新读取记录`只读取，不启动8091、不跑测试、不声称Java接入或临床有效。宿主新增元数据GET `/api/system/course-contract-check`，原研究操作不变。

用同一原工作流/评分器比较HTTP和直接结果只证明传输与契约一致性，不是独立算法或临床验证。共同契约变更仍需协调调用方；每个完成阶段验收后提交本地Git，不自动推送。
