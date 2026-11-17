# Java客户端故障验收与只读结果核对

对应研究FR-AGENT-01/02、FR-WARN-02和FR-ASSESS-02的Python辅助部分。提供可导出的固定构造案例、本机HTTP故障工具和只读核对工具；不改变原三个研究操作，不实现Java业务任务、权限、数据库或审计。Java实际调用这些材料后才能记录Java接入验收完成。

## 固定案例包

在仓库根目录执行，Python 3.11/3.12标准库即可：

```powershell
python -B -m python_service.course_interop --export interop-pack.json
```

输出必须是全新文件，已有文件拒绝覆盖；导出不启动服务、不发送网络请求。包包含原契约hash、生成来源hash、六份合成请求及期望核心：完整成功、非24月阻断、无认知阻断、缺失模态WARN、小数认知、合法零值。原示例完整成功分数保持0.8304，小数25.44/22.123456/.55不舍入，零值不当缺失。

Java测试可循环读取`cases`，将`request`作为原始AgentRequest提交。收到响应先按原OpenAPI校验，检查日期/单位、runId与版本、质量阻断、来源图、解释重构及真实非负耗时；仅比较`expectedCore`时移除`stages[*].durationMs`。同一请求重放和GET缓存结果必须保留第一次真实耗时，不能将时间归零后比较重放。

`expectedCore`不是完整AgentResult：它专门省略耗时，不能直接当HTTP响应返回。核心由原研究工作流生成，比较只证明传输及语义一致，不是独立算法验证。包内`pythonClientCode`是参考Python客户端错误码，Java可沿用自己的业务错误映射，但不得将拒绝或故障保存成成功结果。

## 本机故障工具

```powershell
python -B -m python_service.course_interop --serve commit-drop
```

工具只绑定127.0.0.1的系统分配临时端口；终端输出`SYNTHETIC_FAULT_LAB_NOT_PRODUCTION`、场景和URL。将Java测试配置中的Python基址临时指向该URL，结束后Ctrl+C关闭。不要用它代替默认8091；它没有新增宿主页面或业务接口。响应头`X-Course-Fault-Lab: synthetic-test-only`标明测试工具；健康DTO刻意沿用原服务结构以便原客户端执行测试，不是对服务身份的认证。

| `--serve`场景 | 真实HTTP行为 | 调用方应验证 |
| --- | --- | --- |
| ok | 原工作流和易失缓存 | 成功/阻断、相同请求精确重放、不同请求409、GET读取 |
| error500 | 构造执行异常，由原HTTP处理器输出500 | ASSESSMENT_FAILED、安全AgentError、不缓存、不回退Mock |
| capacity503 | 自有测试缓存容量0，由原处理器输出503 | RUN_CAPACITY_REACHED，不得保存成功结果 |
| wrong-run | 已计算结果的runId改为另一合法值 | Schema可以通过，仍须拒绝身份不符 |
| wrong-version | 已计算结果的workflowVersion篡改 | 固定Schema/版本核对拒绝 |
| blocked-zero | 将返回分数伪造为0和LOW | 用blocked-window请求；不得把BLOCKED变成0分成功 |
| malformed-json | HTTP200但正文被截为不完整JSON | 解析失败、结果未确认，不能换runId重试 |
| wrong-trace | POST响应的追踪头改成其他值 | 校验响应trace与本次请求一致 |
| redirect | POST返回302到无效外部地址 | 不跟随、不将合成请求转发到外部 |
| commit-drop | 原结果已缓存，发送响应前断开连接 | 上游结果未知；仅用原runId查询，可找回结果 |
| commit-delay | 原结果已缓存，响应最多等待3秒 | 测试客户端超时设为0.5–1秒；超时后GET可找回，不再次POST |
| wrong-health | 健康响应声明错误服务类型 | 提交档案前拒绝，POST次数应为0 |

故障只在独立LabHandler/LabStore生效，不改原AgentServer、评分器或读取超时。`commit-delay`的3秒是故障工具参数，不是生产服务超时SLA。工具仍只接受原合成声明，不开放浏览器跨域；不要向它发送真实资料。

## 超时或断线后的只读核对

```python
from python_service.course_client import CourseClient
from python_service.course_recovery import reconcile_course_run

report = reconcile_course_run(CourseClient('http://127.0.0.1:8091'), original_request)
```

工具只调用健康GET及原runId的结果GET，检查原请求绑定及结果Schema/语义，不调用create、不自动重提或更换runId。请求副本与返回结果独立保存，阻断结果仍为null/UNAVAILABLE。

参考客户端在网络调用前固定请求快照和发送字节；调用者在health或等待HTTP响应期间改写原对象，不会改变已登记的runId绑定或返回校验基准。无效trace、序列化及请求字节限制在健康探测和绑定登记前拒绝。发送已开始的失败仍保留原快照及不确定性，供原runId的GET核对；详情见[核心校验说明](Python_Core_Validation.md#调用端请求绑定)。Python本地绑定不代替Java持久历史、身份或对象权限。

Java调用方应发送实际UTF-8 JSON，Content-Type唯一且charset省略或单个UTF-8；Content-Encoding省略或单个identity。原Python服务现拒绝相互冲突或重复的charset、内容编码及不支持字符集。参考客户端同样拒绝不合规响应及伪装成UTF-8的UTF-16/32正文；即使原结果已经缓存，也不会将该响应视为成功，只按原runId作GET核对。详情见[HTTP正文表示](Python_Core_Validation.md#http正文表示)。

| lookupStatus | 含义与下一步 |
| --- | --- |
| FOUND | 找到与原请求相符的DTO；Java仍须检查对象权限、任务关系、已有保存记录和审计后处理，不能据此认证真实执行或业务提交成功 |
| CACHE_MISS | 只有404/RUN_NOT_FOUND才为缓存缺失；可能是重启/从未运行/其他进程，执行是否发生仍未知，查询Java持久历史 |
| LOOKUP_FAILED | 传输、绑定、契约、解析或其他HTTP失败；不是缓存缺失，也不是成功，保留错误并人工核对 |

客户端按原实现将错误码与HTTP状态及操作一起校验。健康GET收到404/RUN_NOT_FOUND属于错误契约，不是原run缓存缺失；恢复结果为LOOKUP_FAILED且不继续结果GET或POST。POST的错误码若与状态不符，或者出现未支持的新错误码，则为ERROR_CONTRACT并保留结果未知/原绑定，仅按原runId GET核对。正常原状态/错误码组合保持，详见[调用端请求绑定](Python_Core_Validation.md#调用端请求绑定)。这些检查不认证上游执行真实性。

所有结果都保持`businessHistoryRequired=true`、`executionOccurred=null`、`resubmissionAuthorized=false`，核对不作新的提交授权。Java沿用原任务状态；可在FAILED及审计中保留“上游结果未确认”，不需要为此改共同DTO或新增一个Python状态。错误不携带任意服务正文；缺失/失败的报告没有输入量表或特征值。

文件工具：

```powershell
python -B -m python_service.course_recovery --url http://127.0.0.1:8091 --request original-request.json --output recovery.json
```

只创建全新输出，不覆盖请求或已有记录。退出码0=FOUND、2=CACHE_MISS、1=LOOKUP_FAILED/本地拒绝。完整结果仅写指定JSON；控制台只打印状态/是否提交。请求须符合原合成模式且文件不超过128KiB；本工具不读取Java数据库或认证患者来源。

## 可重复实际验收

```powershell
python -B -m unittest discover -s tests -v
python -B -m python_service.course_interop --self-test
```

self-test覆盖六案例的实际HTTP与精确重放、409后原结果保留、11种故障与不自动重试；`commit-drop`/`commit-delay`确认仅一次POST后GET找回。另启动原`python_service.server`子进程，提交后结束自有进程，在同一端口启动新原服务，核对两次GET均404而没有自动重算。只结束自身创建的临时服务，不连接/重启既有8091/8092/8084。

Java集成测试后续应额外记录：以上场景的业务FAILED/BLOCKED映射、原请求/版本和trace保留、数据库结果唯一性、重复业务Idempotency-Key、越权拒绝、患者字段裁剪，以及Python断开或Java重启后历史仍可查。Python本轮自验不证明这些Java步骤已经通过。
