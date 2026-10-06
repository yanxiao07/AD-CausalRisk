# AD-CausalRisk Python系统协作基底

本仓库提供可独立启动的Python合成Agent、共同接口、100个合成档案和课程开发基线。后端同学从backend目录建立完整Java业务系统，前端同学从frontend目录建立完整前端；各自负责设计、实现、测试、部署与验收。两目录目前只有任务说明，尚未实现。

仓库地址：[yanxiao07/AD-CausalRisk](https://github.com/yanxiao07/AD-CausalRisk)。首次参与请按“克隆 → 启动Python → 阅读共同契约 → 创建本人模块分支 → 提交PR”的顺序开始。

此基底只使用SYNTHETIC_DEMO。原ADNI/NACC科研数据、病例复核材料、论文环境及作者已有Java系统都不在交付范围内。这里的评分是确定性的演示规则，没有部署真实个体研究模型；已有科研系统的开发和论文验证分别维护。

## 已提供的能力与团队分工

| 模块 | 当前内容 | 责任与状态 |
| --- | --- | --- |
| Python流程 | 数据融合、条件图谱、演示预警、解释四阶段；质量阻断和版本校验 | 基底已实现，维护共同算法契约 |
| Java后端与数据库 | 22个业务接口目标、DTO和验收要求 | 后端同学完整创建并实现，当前待开发 |
| 前端 | 四角色、业务页面、图谱/解释与交互验收要求 | 前端同学完整创建并实现，当前待开发 |
| 数据与测试 | 100档案、200访视、300特征；27项本地验收测试 | 完全合成，可用于导入和接口联调 |

同学的任务包括完整工程、测试、部署和说明文档；作者自己的既有Java系统与研究页面没有复制到本仓库，也不作为同学已经完成的工作。

```text
浏览器前端（frontend）
        ↓ Java业务API与登录会话
Java后端（backend） ←→ MySQL与业务任务/审计
        ↓ AgentClient HTTP调用
Python子服务（python_service，127.0.0.1:8091）
        ↓ 合成数据的四阶段处理
图谱 / 演示排序 / 解释 / 质量状态
```

## 克隆与启动

```bash
git clone https://github.com/yanxiao07/AD-CausalRisk.git
cd AD-CausalRisk
```

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

Linux/macOS的接口检查：

```bash
curl http://127.0.0.1:8091/api/health
curl -H 'Content-Type: application/json' --data-binary @contracts/examples/deepagent-v1.request.example.json http://127.0.0.1:8091/api/agent/v1/runs
curl http://127.0.0.1:8091/api/agent/v1/runs/run-demo-001
```

完整示例应返回status=COMPLETED、dataSource=SYNTHETIC_DEMO和四阶段轨迹。若出现BLOCKED，请检查核心认知输入、同日冲突和窗口，并保留null分数。

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

Linux/macOS对应命令：

```bash
.venv/bin/python -m pip install -r requirements-dev.lock
.venv/bin/python -m unittest discover -s tests -v
```

运行安装与研发安装分开：服务无需第三方库；契约验收使用固定版本开发依赖。CI检查Python基底与OpenAPI，不把尚未开发的Java/前端标成完成。Java、数据库、前端和最终端到端验收须分别加入他们自己的构建与测试流程。

## 从本人分支提交PR

```bash
git switch -c backend/bootstrap
# 后端同学在backend创建工程；前端同学使用frontend/bootstrap分支
git add backend
git commit -m "Initialize backend module"
git push -u origin backend/bootstrap
```

到仓库Pull requests页面创建PR，目标分支选择main，按模板写明需求编号、变更、实际测试和待完成项。接口变更先修改契约并评审，再同步调用方；不要直接推送main。详见[协作规范](CONTRIBUTING.md)。成员写权限和main保护由仓库拥有者设置，不能把本地分支测试当作已配置远端权限。

## 验收记录与使用边界

本地Windows/Python 3.11.9已通过27项测试、关闭site-packages的隔离启动、100个合成档案调用及本地克隆/分支/合并检查。docs/BASELINE_ACCEPTANCE.json保留2026-10-06初次本地验收快照，其中远端字段描述当时状态，不表示后续GitHub Actions结果。远端最新构建以本仓库Actions页面为准；Linux/macOS说明不等同已执行平台验收。

只提交独立基底及同学新模块。真实ADNI/NACC数据、病例复核包、论文目录、凭据、虚拟环境和构建缓存不得加入本仓库。公开仓库目前没有指定开放源码许可证；团队需要自行确认后续再分发或商用许可。完整JavaWeb与临床验证都不能由Python基底CI通过代替。
