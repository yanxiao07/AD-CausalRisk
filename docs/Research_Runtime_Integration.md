# 可选研究服务联调

Python负责人已在授权研究工作区保存六种固定规格的V8复现模型，并提供独立本机研究服务。Java、数据库、权限、前端和业务结果保存由开发人员实现；此文及新契约不表示Java端已经接通。

默认服务仍为8091、deepagent-v1及规则引擎评分。可选研究服务为8092、adni-v8-http-v1，读取本地受控估计器，首轮只接受声明为SYNTHETIC_TEST的来源图。两套接口、输入和模型版本不同，禁止以同一模型ID切换或把失败静默回退成演示分数。

## 接口与启动

完整定义：[research-runtime.openapi.json](../contracts/research-runtime.openapi.json)；请求：[合成例子](../contracts/examples/research-runtime.request.example.json)。只实现两个同步操作：

- GET /api/research-runtime/v1/status：版本、六个结构、运行状态及限制。
- POST /api/research-runtime/v1/predictions：研究请求信封及完整四环节结果。

Python负责人使用已具备授权数据/模型和科学依赖的环境运行；本仓库不分发模型参数、研究记录、受控路径或整套科研工作区。`research_gateway.py`是显式启动入口，`research_gateway/server.py`是HTTP服务源码；两者不下载或自动配置研究运行环境。模型计算提供者来自显式指定的授权工作区，公开服务源码不包含训练参数。

```powershell
# 在同一终端为专用本机服务生成令牌，不把它写入Git或聊天。
$env:ADNI_V8_RUNTIME_TOKEN = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
# 使用研究环境的Python；下方路径由Python负责人替换。
<研究Python路径> -B research_gateway.py --research-workspace <授权研究工作区路径> --port 8092
```

令牌需24–128个英文字母、数字、下划线或短横线。服务只绑定127.0.0.1；Java调用方应在同一受控主机或经另行配置的连接路径访问，不把此监听地址当成跨机器已部署服务。

调用方需通过本机受控配置取得同一令牌；新终端不会自动继承前一个终端的环境变量。不要在文档、日志或截图中输出令牌：

```powershell
$headers = @{Authorization = "Bearer $env:ADNI_V8_RUNTIME_TOKEN"}
Invoke-RestMethod http://127.0.0.1:8092/api/research-runtime/v1/status -Headers $headers
$body = [Text.Encoding]::UTF8.GetBytes((Get-Content contracts/examples/research-runtime.request.example.json -Raw -Encoding UTF8))
Invoke-RestMethod http://127.0.0.1:8092/api/research-runtime/v1/predictions -Method Post -Headers $headers -ContentType application/json -Body $body
```

浏览器不能直接调用Python，服务拒绝Origin头；前端调用Java，Java提供会话/角色/对象授权并代传专用本机令牌。建议研究通道先限管理员/研究人员，不自动进入患者摘要或诊疗流程。

## 调用语义

信封含httpContractVersion、requestId、inputMode和runtimeRequest。内部请求固定contractVersion=adni-v8-research-v1、modelVersion=adni-v8-final-materialized-2026-10-11，明确选六结构之一；caseCode使用LOCAL-SYNTHETIC-*，所有节点source_visit使用DEMO-*。标签和声明不能证明输入真为合成或满足MCI纳入资格，响应明确eligibilityVerified=false、inputDeclarationIndependentlyVerified=false。

相同requestId/相同内容返回原缓存结果；改变内容返回409。缓存只在内存且上限64条，Java若要保存历史结果必须自己持久化。同步请求返回后才显示四环节，不能根据轮询次数编造实时阶段进度。没有SSE、取消或持久任务接口。

输入超256KiB为413；非JSON为415；版本/请求冲突409；访问令牌不足401；输入模式/非合成标签/浏览器直连403；图或日期错误400；模型或来源文件变更及缓存满503。失败响应只有稳定code，没有分数；不能把失败转换为成功或补0。

## 返回结果如何校验

校验HTTP状态、Content-Type、httpContractVersion、requestId以及内部contract/model/variant/caseCode对应。AD和其他痴呆累计发生率应为有限[0,1]数值且和不超过1。四阶段顺序为SOURCE_INTEGRATION、PARTICIPANT_GRAPH、RISK_PREDICTION、PREDICTION_EXPLANATION。

解释参考为source_withdrawal_with_derived_features_and_masks_recomputed；baseline加五来源贡献重构同次AD预测，误差应不超过1e-12。数值重构不证明唯一解释、个体病因或干预效果。clinicalUseEstablished和causalEffectIdentified必须为false；PASS/WARN表示输入来源/缺失检查，不等于临床可用。

本次交付包含Python服务契约、入口和合成请求，不包含Java实现。已有研究接口和前端任务继续按共同需求开发。独立科研模型、病人数据及伦理/数据使用事项不能由研究联调通过替代。

本服务使用全部451名ADNI参与者拟合的最终模型。对训练对象的计算不是折外预测；既有NACC评价也不自动证明其他人群、时点或诊疗用途的有效性。当前验收只说明合成请求能够经过已保存研究模型完成计算。
