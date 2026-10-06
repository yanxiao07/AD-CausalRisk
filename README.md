# AD-CausalRisk 课程协作基底

本仓库提供可独立启动的Python合成Agent、共同接口、100个合成档案和课程开发基线。后端同学从backend目录建立完整Java业务系统，前端同学从frontend目录建立完整前端；各自负责设计、实现、测试、部署与验收。两目录目前只有任务说明，尚未实现。

此基底只使用SYNTHETIC_DEMO。原ADNI/NACC科研数据、病例复核材料、论文环境及作者已有Java系统都不在交付范围内。这里的评分是确定性的演示规则，没有部署真实个体研究模型；已有科研系统的开发和论文验证分别维护。

## 开始使用

需要Python 3.11或3.12。运行服务只用标准库，不依赖数据库、科研文件、用户机器路径或外网。

Windows PowerShell，在仓库目录运行：

```powershell
py -3.11 -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements.txt
.venv/Scripts/python.exe -m python_service.server --port 8091
```

Linux/macOS：

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m python_service.server --port 8091
```

另开终端检查服务或提交合成示例：

```powershell
Invoke-RestMethod http://127.0.0.1:8091/api/health
$request = Get-Content contracts/examples/deepagent-v1.request.example.json -Raw
Invoke-RestMethod http://127.0.0.1:8091/api/agent/v1/runs -Method Post -ContentType application/json -Body ([Text.Encoding]::UTF8.GetBytes($request))
Invoke-RestMethod http://127.0.0.1:8091/api/agent/v1/runs/run-demo-001
```

示例的runId重复提交相同请求会返回同一结果；不同请求复用该ID返回409。服务同步完成四阶段后响应，运行记录保存在内存，重启清空。Java同学负责业务任务、持久化、重试、权限和历史结果保存。前端通过Java接口访问，Python仅监听本机127.0.0.1:8091，不提供前端跨域入口。网络部署与完整用户认证应由团队另行实现，不属于本地Agent服务。

当前演示规则只对24个月窗口出分；其他6–60个月的有效请求会得到BLOCKED和原因。BLOCKED必须保留null分数。

## 共同基线

| 文件 | 用途 |
| --- | --- |
| [课程需求V3.2](docs/Course_SRS_V3_2.md) | 当前分工、必做需求和验收；历史V3.1仅作参考 |
| [Python接口](contracts/python-agent.openapi.json) | 已实现的三个HTTP操作、请求/响应结构 |
| [Java业务接口目标](contracts/java-business.target.openapi.json) | 22个操作及DTO目标，等待后端同学实现 |
| [接口语义与版本](docs/Integration_Contract.md) | 状态、幂等、阻断、错误与任务映射 |
| [分工与PR流程](CONTRIBUTING.md) | 克隆、分支、PR、评审和各模块责任 |
| [合成数据](fixtures/) | 100个DEMO档案，200条访视，300条特征；CSV可用于导入联调 |
| [Python验收](tests/) | HTTP、契约、并发与版本/阻断检查 |
| [基底验收记录](docs/BASELINE_ACCEPTANCE.json) | 实际验证范围及尚未验证项，由交付验收生成 |

研发验收：

```powershell
.venv/Scripts/python.exe -m pip install -r requirements-dev.lock
.venv/Scripts/python.exe -m unittest discover -s tests -v
```

运行安装与研发安装分开：服务无需第三方库；契约验收使用固定版本开发依赖。CI检查Python基底与OpenAPI，不把尚未开发的Java/前端标成完成。Java、数据库、前端和最终端到端验收须分别加入他们自己的构建与测试流程。

初次交付仅建立本地Git提交基线。GitHub远端、成员权限、分支保护和远端CI需在实际仓库建立后落实；本地验收不代表已经提交或合并GitHub PR。请只上传此目录，完整科研工作区不作为课程仓库根目录。
