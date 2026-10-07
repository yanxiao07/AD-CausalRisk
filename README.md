# AD-CausalRisk

AD-CausalRisk 是一个课程协作项目，将多源资料整理、质量检查、条件图谱、排序解释和随访管理连接成完整的 Web 应用。仓库提供可运行的 Python 子服务、接口契约和演示数据，后端与前端成员分别完成 Java 业务系统和浏览器应用。

首次参与按以下顺序开始：克隆仓库 → 运行 Python 示例 → 阅读需求和接口 → 选择一个功能 → 创建分支 → 实现与测试 → 提交 PR。与 AI 一起开发时，可使用下文的任务模板。

## 1. 项目结构与分工

```text
AD-CausalRisk/
├── python_service/             Python 四阶段流程与 HTTP 服务
├── backend/                    Java 后端工程
├── frontend/                   前端工程
├── contracts/                  Python 接口、Java 业务目标接口与 JSON 示例
├── fixtures/                   演示档案、访视及特征数据
├── tests/                      Python 与契约测试
├── docs/                       需求、业务设计、联调和 AI 协作指南
├── .github/                    CI 与 PR 模板
├── AGENTS.md                   AI 编码助手的项目规则
└── CONTRIBUTING.md             团队分支、提交与评审规范
```

| 模块 | 开发内容 | 当前状态 |
| --- | --- | --- |
| Python | 数据融合、条件图谱、排序、解释、输入校验、质量阻断、版本校验 | 可运行 |
| Java 后端 | 数据库、登录与权限、档案、导入、质控、持久化任务、Python 客户端、复核、随访、报告、审计 | 按 `backend/` 的任务说明实现 |
| 前端 | 路由与会话、四角色菜单、业务页面、任务状态、图谱/解释、随访、报告、响应式与交互测试 | 按 `frontend/` 的任务说明实现 |

后端和前端成员各自负责完整工程、测试、部署与说明文档。Python 负责人维护算法接口和示例数据；共同接口的变更需要团队评审。

```text
浏览器前端（frontend）
        ↓ Java 业务 API / 登录会话
Java 后端（backend） ←→ MySQL / 任务 / 审计
        ↓ AgentClient HTTP
Python 子服务（127.0.0.1:8091）
        ↓ DATA_FUSION → CAUSAL_GRAPH → WARNING_MODEL → EXPLANATION
质量状态 / 条件图谱 / 排序 / 解释
```

## 2. 开发前先读什么

| 文档 | 需要确认的内容 |
| --- | --- |
| [课程需求](docs/Course_SRS_V3_2.md) | 功能编号、模块责任、必做项与验收场景 |
| [业务设计参考](docs/Design_Guide.md) | 角色权限、数据库实体、页面和开发顺序 |
| [Python 接口](contracts/python-agent.openapi.json) | 三个已实现的 HTTP 操作与请求/响应结构 |
| [Java 业务接口目标](contracts/java-business.target.openapi.json) | 22个待实现操作、DTO、认证及错误结构 |
| [联调约定](docs/Integration_Contract.md) | 状态、幂等、阻断、版本与失败处理 |
| [AI 协作指南](docs/AI_Collaboration.md) | 任务拆分、上下文、提示模板、验证和接续工作 |
| [协作规范](CONTRIBUTING.md) | 分支命名、提交、PR、评审与同步 |

Java 目标接口是开发约定，不表示对应后端已经存在。开始一个功能前，确认输入、输出、权限、持久化和失败场景；不要只按照页面效果猜接口字段。

## 3. 克隆仓库与准备环境

需要 Git 和 Python 3.11 或 3.12。Python 服务使用标准库，契约测试需要研发依赖。Java、数据库和前端环境由对应成员在模块说明中维护，建议 Java 21、Spring Boot、MySQL 8和 Vue 3。

```bash
git clone https://github.com/yanxiao07/AD-CausalRisk.git
cd AD-CausalRisk
```

下面的命令都在仓库根目录运行。Windows 可先用 `py --list` 检查 Python 版本；若只有3.12，把创建环境命令中的3.11改成3.12。

### Windows PowerShell

```powershell
py -3.11 -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements.txt
.venv/Scripts/python.exe -m python_service.server --port 8091
```

### Linux / macOS

```bash
python3 --version
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m python_service.server --port 8091
```

启动后保持该终端运行。服务监听 `http://127.0.0.1:8091`；另开终端执行下面的检查。无需激活虚拟环境也能使用这些命令。

## 4. 先完成一次 Python 调用

### Windows PowerShell

```powershell
# 查看健康状态
Invoke-RestMethod http://127.0.0.1:8091/api/health

# 提交一个档案；示例的 runId 为 run-demo-001
$request = Get-Content contracts/examples/deepagent-v1.request.example.json -Raw -Encoding UTF8
$body = [Text.Encoding]::UTF8.GetBytes($request)
Invoke-RestMethod http://127.0.0.1:8091/api/agent/v1/runs -Method Post -ContentType application/json -Body $body

# 查询同一次运行
Invoke-RestMethod http://127.0.0.1:8091/api/agent/v1/runs/run-demo-001
```

### Linux / macOS

```bash
curl http://127.0.0.1:8091/api/health
curl -H 'Content-Type: application/json' --data-binary @contracts/examples/deepagent-v1.request.example.json http://127.0.0.1:8091/api/agent/v1/runs
curl http://127.0.0.1:8091/api/agent/v1/runs/run-demo-001
```

完整示例返回 `status=COMPLETED`、`qualityStatus=PASS`、`dataSource=SYNTHETIC_DEMO` 和四阶段轨迹。当前示例的 `riskScore` 为 `0.8304`；阶段耗时随运行变化，不应写死在前端。

| 字段 | 开发时如何使用 |
| --- | --- |
| `runId` | 关联 Java 任务和 Python 调用；同一请求重试沿用原 ID |
| `status` | 根据 `COMPLETED` 或 `BLOCKED` 更新任务 |
| `qualityStatus` | 展示 `PASS/WARN/BLOCKED` 及原因 |
| `riskScore` / `riskLevel` | 展示排序结果；阻断时分数必须为 `null` |
| `graph` | 渲染节点、边、路径、激活/缺失状态与版本 |
| `explanations` | 展示贡献、方向、说明与限制 |
| `stages` | 展示实际执行阶段和耗时 |
| `dataVersion` / `modelVersion` / `workflowVersion` | 保存结果版本，用于追溯 |

当前排序用于课程演示，不作为诊断。请求层接受6–60个月整数，出分规则只支持24个月；其他窗口返回 `BLOCKED`。没有核心认知输入、最近访视不唯一或同日特征值冲突时也会阻断。**不能把 `null` 替换成0或补一个随机分数。**

## 5. 使用演示数据

| 文件 | 内容 | 主要用途 |
| --- | --- | --- |
| `fixtures/synthetic_subjects_100.json` | 100个档案的完整 Python 请求 | 每次取一个 `subjects` 元素提交，进行批量联调 |
| `fixtures/subjects.synthetic.csv` | 100个档案 | 测试档案导入、分页与详情 |
| `fixtures/visits.synthetic.csv` | 200条访视 | 测试历史记录、时间排序与量表展示 |
| `fixtures/features.synthetic.csv` | 300条特征 | 测试单位、来源日期与缺失提示 |
| `contracts/examples/` | 单次请求与完整响应 | 理解字段、编写适配器和页面 Mock |

数据采用 `DEMO-*` 编号和 `demo-*` 版本。Java 可以使用数字数据库主键，向 Python 仍传 `subjectCode`。完整100档案 JSON 不是单档案接口的请求体，不能一次直接 POST 给该接口。

## 6. 后端和前端如何接入

### 后端成员

在 `backend/` 建立 Spring Boot 工程，先实现数据库迁移、登录/权限、档案和访视，再实现导入、质控及任务。`AgentClient` 调用 Python，并把任务和返回结果保存到数据库。

- Python同步计算后返回结果，没有实时阶段推送。执行中可显示 `RUNNING`，完成后展示实际阶段轨迹。
- Python缓存只在进程内存，重启清空；业务任务和历史结果由 Java 持久化。
- Java任务的 `Idempotency-Key` 和 Python的 `runId` 是两层机制，不能互相代替。
- 版本错、超时、409冲突或服务不可用时，保留失败原因与审计。
- 角色和对象权限在后端执行；患者摘要过滤数值排序、图权重及内部轨迹。

### 前端成员

在 `frontend/` 建立应用，按 Java 目标接口实现请求类型、路由、会话和页面。Java未完成时使用明确标记的 Mock；该功能联调前关闭 Mock，调用实际 Java接口。

前端通过 Java访问业务，Python由后端调用。每页覆盖加载、空数据、失败、无权限状态；图谱区分激活和缺失，阻断结果展示原因。使用返回的阶段和耗时，避免按定时器虚构进度。

## 7. 与 AI 一起开发

在 AI编码工具中打开**克隆后的整个仓库目录**，让它先阅读 `AGENTS.md`、本文和对应模块说明，再提供具体任务。仅粘贴一张页面图不足以确定接口、权限和数据规则。

每次完成一个可测试功能。复制以下通用提示，替换方括号内容：

```text
请先阅读 AGENTS.md、README.md、docs/Course_SRS_V3_2.md、
docs/Integration_Contract.md 和我负责模块的 README。

我的模块：[backend 或 frontend]
本次需求：[需求编号和具体行为，例如 FR-AUTH-01 登录与当前用户]
验收条件：[正常、参数错误、未登录/无权限、失败等场景]
现有环境：[系统、语言版本、构建命令、数据库或接口地址]

先检查代码和契约，说明实现步骤、需要改的文件及依赖。
然后分步实现，运行相关测试，并根据实际失败修复。
遵守现有路径、字段、版本和状态，不扩大到其他模块。
若需改共同契约，指出原因与调用方影响，先由团队确定方案。
完成后给出改动摘要、实际测试、未验证项和建议PR说明。
不要把未执行的测试写成通过；不要代我推送或合并。
```

首次后端任务可选“工程初始化、数据库迁移和档案接口”；首次前端任务可选“工程初始化、路由、登录页及四种状态”。详细的后端、前端、修复、代码评审和接续工作模板见 [AI协作指南](docs/AI_Collaboration.md)。

AI完成代码后，自己检查 `git diff`、运行命令、看接口返回或页面，再继续下一段。AI帮助实现和检查，代码含义、测试证据及最终提交由开发成员负责。

## 8. 安装研发依赖与运行测试

Windows：

```powershell
.venv/Scripts/python.exe -m pip install -r requirements-dev.lock
.venv/Scripts/python.exe -m unittest discover -s tests -v
```

Linux/macOS：

```bash
.venv/bin/python -m pip install -r requirements-dev.lock
.venv/bin/python -m unittest discover -s tests -v
```

测试覆盖 HTTP、OpenAPI、示例响应、阻断、输入校验、并发幂等、缓存、数据文件与患者摘要字段。GitHub [Actions](https://github.com/yanxiao07/AD-CausalRisk/actions)运行 Python 3.11/3.12检查。

CI目前覆盖 Python和共同契约。各成员应添加自己的构建、数据库/接口测试及组件/页面测试，并在模块说明记录命令。提交前运行自己模块的检查及受改动影响的共同测试。

## 9. 分支、提交与 PR

开始前确认工作区干净并同步 `main`：

```bash
git status
git switch main
git pull --ff-only origin main
git switch -c backend/auth-and-subjects
# 前端可使用 frontend/login-and-routing
```

完成一个功能后：

```bash
git diff
git diff --check
# 先运行模块测试，再提交相关文件
git add backend
git commit -m "Add authentication and subject management"
git push -u origin backend/auth-and-subjects
```

在 GitHub创建 PR，`base` 选 `main`，`compare` 选本人分支；填写需求编号、实现、测试、截图或接口示例及未完成项。CI通过后交由团队评审合并。没有写权限时，先 Fork 到本人账号，在 Fork 中开发，再向本仓库提 PR。

合并后，确认工作区没有未提交改动，再切回 `main` 并执行 `git pull --ff-only origin main`。有未提交文件时先提交到功能分支或按需保留，不要强制覆盖。更多冲突处理步骤见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 10. 常见问题

| 问题 | 检查与处理 |
| --- | --- |
| `No module named python_service` | 回到仓库根目录，使用虚拟环境执行 `-m python_service.server` |
| 缺少 `jsonschema` / `openapi_spec_validator` | 在同一虚拟环境安装 `requirements-dev.lock` |
| 8091端口占用 | 确认已有服务是否可用；改用 `--port 8092` 时同步 Java配置与调用地址 |
| 请求400/415 | 检查 UTF-8 JSON、Content-Type、必需字段、单位、日期和版本 |
| 请求409 | 检查契约/模型版本，以及是否用同一 `runId` 提交了不同内容 |
| 返回 `BLOCKED` | 检查24个月窗口、核心认知输入、最近访视和同日冲突，展示 `warnings` |
| Python重启后查询404 | 内存缓存已清空；历史结果从 Java数据库读取 |
| 浏览器不能直接访问8091 | 由 Java后端调用 Python，前端访问 Java业务接口 |
| 找不到 Java/前端工程 | 两个工程在各自模块目录创建，Python测试不会生成它们 |
| `git push` 被拒绝 | 检查本人分支、权限和认证，不要强制覆盖共享分支 |

只提交源码、契约和必要示例。数据库密码、访问令牌、虚拟环境、运行日志、构建缓存和个人资料不应进入仓库。
