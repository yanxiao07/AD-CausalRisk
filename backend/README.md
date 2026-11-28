# Java业务服务

Java负责会话、对象授权、资料、导入、质量、持久化任务/结果、复核、随访、报告和审计。浏览器只调用Java，Java调用Python。业务操作见[OpenAPI](../contracts/java-business.target.openapi.json)，功能与研究边界见[业务状态](../docs/Business_Feature_Status.md)。

按需求逐项完成状态、验证与尚未验收条件见[后端验收](../docs/Backend_Requirements_Acceptance.md)。

## 接收与升级

新评估走表格研究任务。旧评估接口保留历史读取与原幂等键回读，JSON 报告资源及当前用户接口仍可用。管理员界面按 `/api/me` 的菜单渲染，纯 ADMIN 使用 audit/system/users，多角色合并对应入口。

已有库先完整备份，再在专用测试库验证迁移。应用升级后 Flyway 执行前向迁移并校验历史脚本。V12 为既有演示研究账号显式保留 ALL，新研究账号默认 ASSIGNED，通过授权接口配置对象范围。目标库验收步骤见 [MySQL 8 验收](../docs/MySQL8_Acceptance.md)。

评估页接入和运行方式见[前端说明](../frontend/README.md#风险评估接入)。

## 构建与验证

需要 JDK21。Windows 运行 `.\mvnw.cmd -B -ntp verify`，Linux/macOS 运行 `./mvnw -B -ntp verify`。测试使用 H2 MySQL 兼容模式，核对当前 OpenAPI 的139个操作与实际路由。Windows 全量507项通过，包含输入范围、历史非法快照出站阻断及当前验证快照回读的8项新增回归。研究适配测试使用隔离环回宿主、公开构造请求与响应；实际模型调用与模拟客户端分别验收。

Java 有一项跨语言快照检查使用仓库根目录的 `.venv/Scripts/python.exe`。在 Windows 上先按[项目验证步骤](../README.md#验证与升级)创建 `.venv` 并安装锁定依赖，即可运行该项。其他环境可执行同节的公开 HTTP 契约验证。

## 启动

目标数据库是 MySQL 8。仓库内置 `local` 配置。创建专用数据库后在终端设置连接参数，字段可参照 `src/main/resources/application-local.example.yml`。

```powershell
$env:SPRING_PROFILES_ACTIVE='local'
$env:ADCR_DB_URL='jdbc:mysql://127.0.0.1:3306/ad_causalrisk?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC'
$env:ADCR_DB_USERNAME='<db-user>'
$env:ADCR_DB_PASSWORD='<db-password>'
.\mvnw.cmd spring-boot:run
```

Java 默认端口为 8080。Flyway 在启动时执行 V1–V37，Hibernate 验证表结构。V30–V31 支持普通档案、评估申请及访视输入，V32–V33 保存研究授权、随访结局及验证报告，V34–V37 支持患者备忘/卡片、授权表态、预约取消及卡片预览绑定。当前迁移链在本机 H2 验收，MySQL 目标环境的升级及恢复需另行复测。[既有 MySQL 验收](../docs/MySQL8_Acceptance.md)

合成种子账号为admin、researcher、doctor、patient、research_doctor，仓库种子口令为 `DemoPass!2026`，保存BCrypt哈希。管理员可通过账号管理重置密码；本地既有账号密码不会在升级时重置。

## 患者建档与账号开通

医生建立档案并完成访视资料录入，通过档案中的质控入口检查当前版本。以当前访视的 MMSE、CDR-SB 和质控无阻断作为账号申请条件，可带提示继续。Java 同时核对数据版本及原输入摘要。修改资料后，医生应重新质控并更新待发放申请。管理员核对本人身份、档案和责任医生后发放；Java 在同一事务再次核对申请绑定的质控与医生权限，创建患者账号、档案摘要权限和责任医生关系。历史发放记录无责任医生字段时，患者只会看到唯一符合原授权规则的联系人。当前质量闸门用于业务开通流程，受控研究许可由研究宿主独立核对。

访视的 `modelInputs` 保存同次访视的年龄、教育年限、CDR 全局评分、ADAS-Cog 13、FAQ 总分。建档时随访视保存，补录时追加访视版本并使原质控过期。既有客户端更正时省略整个 `modelInputs` 会保留该版本的补充资料。

### 普通档案自动预测的人工验收

`synthetic-care` 用于隔离的人工验收。Java 必须监听 `127.0.0.1`。双击从 `backend` 工作目录打开固定的 `jdbc:h2:file:./../runtime/desktop/adcr_care_sandbox`，持久化档案和结果。下方手工演练使用 `jdbc:h2:mem:adcr_care_sandbox`，临时演练也可使用带下划线和字母数字后缀的指定内存库。库地址或监听地址不匹配会在连接数据库前失败。Python 研究宿主使用独立环回端口、固定摘要的模型清单和专用人工快照目录。研究用途标记随报告保存。

启动器在模型未就绪时传入 `--research.care.prediction-enabled=false`，同一联调库继续保存普通评估申请，既有评估任务与报告仍可读取。批次验证使用独立的 Python 进程计算已保存的概率，不依赖预测宿主持续运行。正常医院环境的普通评估仍待适用模型准入。

从模型保管方取得七变量 `Model_Manifest.json`、同目录 `final__logistic.joblib` 和独立提供的清单 SHA-256。下列命令从仓库根目录开始，使用 Windows PowerShell 7。将示例中的清单路径和摘要替换为收到的内容。三个终端的端口分别为 `16301`、`8080` 和 `5173`。如果已经按快速开始启动了 Java、Vue 或研究宿主，先在原终端按 Ctrl+C 停止，再用新终端启动这套流程。

**终端一启动 Python 研究宿主**

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r research_runtime/requirements.lock
$manifestPath = (Resolve-Path 'C:/controlled-model/Model_Manifest.json').Path
$manifestSha256 = '<模型保管方提供的 SHA-256>'
$env:RESEARCH_TABLE_TEST_TOKEN = Read-Host '设置研究宿主访问令牌' -MaskInput
.\.venv\Scripts\python.exe -B -m research_runtime.dynamic_host `
  --host 127.0.0.1 --port 16301 --manifest $manifestPath `
  --trusted-manifest-sha256 $manifestSha256 --snapshot-dir runtime/synthetic-care-snapshots
```

**终端二启动 Java**

```powershell
$manifestPath = (Resolve-Path 'C:/controlled-model/Model_Manifest.json').Path
$manifestSha256 = '<与终端一相同的 SHA-256>'
$lockSha256 = .\.venv\Scripts\python.exe -B -c 'from research_runtime.lock_digest import lock_sha256; print(lock_sha256())'
$researchToken = Read-Host '输入终端一的访问令牌' -MaskInput
$env:SPRING_APPLICATION_JSON = @{research=@{table=@{'bearer-token'=$researchToken}}} | ConvertTo-Json -Depth 4 -Compress
Set-Location backend
.\mvnw.cmd -B -ntp compile dependency:build-classpath '-DincludeScope=test' '-Dmdep.outputFile=target/h2-demo-classpath.txt'
$demoDependencies = (Get-Content target/h2-demo-classpath.txt -Raw).Trim()
java -cp "target/classes;$demoDependencies" com.adcausalrisk.backend.BackendApplication `
  --spring.profiles.active=synthetic-care --server.address=127.0.0.1 --server.port=8080 `
  '--spring.datasource.url=jdbc:h2:mem:adcr_care_sandbox;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE' `
  --spring.datasource.driver-class-name=org.h2.Driver --spring.datasource.username=sa `
  '--spring.datasource.password=' --research.table.base-url=http://127.0.0.1:16301 `
  "--research.model.manifest-path=$manifestPath" "--research.model.trusted-manifest-sha256=$manifestSha256" `
  "--research.model.software-lock-sha256=$lockSha256"
```

**终端三启动 Vue**

```powershell
Set-Location frontend
npm.cmd ci
npm.cmd run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

用演练医生账号登录。新建普通编号人工档案，在同一次访视填入 MMSE、CDR-SB 和五项补充资料并质控，然后点击「提交预测评估」。Java 读取已保存的当前版本并绑定质控记录，缺项时引导至原访视，齐备时固定输入并调用模型。同一版本重复提交回读原任务。报告沿用档案授权与医生复核流程。关闭 Java 后，这次验收的内存档案即清空。

## 获授权病例验证

管理员登记或撤销项目内的档案授权。研究员仅能读取本人在项目下获授权的病例、模型选项及报告。普通档案取首次成功的预测，失败状态留在诊疗任务记录。随访写入需要当前医生具有档案管理权限，已办理责任交接时按现任医生校验。

报告回读按当前获授权病例、冻结输入、预测概率及结局/观察日期组成的快照匹配，不按报告创建时间选择。恢复授权后重新运行可以复用原报告，刷新仍返回该快照的同一报告；更正结局或改变病例范围后，尚无匹配报告时返回 `data:null`，需要运行验证。历史报告及创建时间保持不变，读取时仍在事务中复核项目授权和患者最新科研使用表态。

在 `synthetic-care` 中，Java 通过本机 Python 计算已保存概率的描述指标。启动器为演练配置 `research.validation.python` 和 `research.validation.workspace`。Java 限制进程并发和输入输出时间，保存结果前复查授权。普通业务配置返回独立协议待补齐的状态。接口示例与状态见[集成契约](../docs/Integration_Contract.md#获授权病例的批次验证)。

### 已训练表格模型的输入范围

Java 在手动快照、七变量 CSV、完整冻结请求及执行前共用数值范围校验。七项已观测值须为有限非负数，年龄不超过120、教育年限40、MMSE30、CDR-SB18、CDR全局3、ADAS-Cog13为85、FAQ30，含上下界。`null` 继续保留为缺失；越界值不截断、不替换为缺失，也不送入预测。CSV 逐行标记 INVALID；登记请求返回400。升级前已冻结的非法输入禁止再次执行，旧任务与报告保留用于追溯。仅供契约算术检查的 constructed 模型使用独立测试规格，不替代已训练模型的准入。

## 研究补件交接

### 医生申请评估

医生在档案概览使用「预测评估」，须拥有该档案的显式管理权限。Java 核对当前质控摘要和最近访视的 MMSE、CDR-SB。测试档案还需保存同一访视日期及分数的七项模型资料，申请后复用原模型、任务租约和幂等机制，报告回到医生待复核。开通患者账号可独立办理。

受控申请保存在 V27 的队列。仅具备显式档案授权的研究员可读，执行时核对当前质控、批准快照、部署方授权、研究宿主地址和摘要。无匹配许可时保留待核验。患者通过原有入口查看经医生确认的本人摘要和随访。

- 受控重试沿用首次发送的档案、质控、冻结内容及指定宿主校验。已失效申请或未获许可的重试在出站之前拒绝。
- 患者账号通过申请发放。管理员可以为待发放或已发放的申请确认另一名拥有该档案管理权限的医生，原申请人可追溯。
- `GET/PUT/DELETE /api/research/table-assessment-tasks/{id}/patient-publication` 提供已确认报告的患者说明。复核更新进度，单独发布才会把说明交给患者。撤回追加一条历史记录。患者摘要禁止根据模型输出自动分档。
- `POST /api/research/table-assessment-tasks/snapshots/batch-csv` 默认只预检，可传 `preview=false` 保存通过的七变量测试快照。使用当前质控访视的两个分数和模板中的五个字段，保存后另行提交评估。已有同版本同内容快照复用原编号。
- 管理员的 `/api/system/research-models` 返回配置宿主报告的已加载模型摘要。仓库启用状态和部署状态分开管理，宿主不支持查询时返回 `UNSUPPORTED`。

V25 新增补件单。医生从 `BLOCKED` 或已复核为 `NEEDS_DATA` 的原任务创建，同一任务只建一张。具备显式档案 VIEW/MANAGE 的研究员可以领取并回传；医生或当前领取人可退回待领取。回传必须关联领取人为同一档案、同资料范围的新快照生成的已核验任务，接受 `COMPLETED` 或仍需补资料的 `BLOCKED`，保留原报告。患者端仅可继续查看本人的脱敏摘要和随访。

医生录入的测试快照交接也要求研究账号有显式档案 VIEW/MANAGE 授权，可在发放患者账号前办理。真实数据证据准入和执行许可分别验收。

具备测试档案显式 MANAGE 授权的医生，可用 `GET /api/research/table-assessment-tasks/snapshots/doctor-intake?subjectId=...` 分页回看已保存的七项输入与同步状态。响应只读取标记为医生录入的构造快照，省略请求结构、来源内部绑定和运行标识。档案质控继续读取持久化访视与特征。

## 表格研究评估适配

| 操作 | 调用方式 |
| --- | --- |
| 可用快照 | `GET /api/research/table-assessment-tasks/snapshots?subjectId={id}`，返回该用户获准的快照及已保存任务编号。 |
| 创建或重放 | `POST /api/research/table-assessment-tasks`，携带 `Idempotency-Key` 和唯一请求字段 `snapshotId`（正整数）。首次提交同步调用研究宿主，重放读取 Java 任务。 |
| 读取 | `GET /api/research/table-assessment-tasks/{id}` 从 Java 读取持久化任务及已校验的研究结果。 |
| 受控登记 | `POST /api/research/table-assessment-tasks/snapshots/register`，请求体 `{subjectId, indexDate?, features?}`（也可传完整的 `{subjectId, request, model}`）；仅 ADMIN/RESEARCHER 且具备该档案原始数据权限，写入冻结快照并返回 `{snapshotId}`。 |

报告、复核、工作台摘要和指标接口可用 `dataScope` 过滤 `CONTROLLED_RESEARCH` 或 `CONSTRUCTED_VALIDATION`。报告列表还支持 `subjectId`，按创建时间及任务编号降序排序，先做对象授权与范围筛选再分页。快照和任务回执的 `dataScope` 来自冻结记录，任务没有模型结果时也会返回。人工登记和批量构造仅适用 `DEMO-` 档案。

管理员或研究员须为快照登记的授权用户，同时具备当前档案原始数据权限。患者角色优先排除。快照保存原始 UTF-8 请求、原字节 SHA-256、独立固定的请求/输入/上下文摘要、模型身份、档案及授权用户。业务评估接收已登记的 `CONSTRUCTED_VALIDATION` 样例，模型固定为 `trained-seven-logistic`。人工快照可由隔离启动器或受控登记接口登记。真实来源映射、快照登记和执行资格继续依赖独立证据。字段、单位、索引时钟与 QC 责任见[研究契约](../docs/Research_Table_Agent_Contract.md)。

默认研究宿主未配置，已授权且已登记的首次请求返回 503。联调时设置 `research.table.base-url`、`research.table.bearer-token` 与可选的 `research.table.read-timeout`（默认 120 秒）。Java 按草案同步 POST 到独立研究宿主。宿主接收原始快照字节并独立绑定来源、模型及预测和解释授权。HTTP 限环回地址，远程地址使用 HTTPS。

Java 先持久化任务、快照引用和幂等键，再发一次 POST。超时、断线或无效响应保留 `OUTCOME_UNKNOWN`，原键重放和 GET 只查本地任务，同一快照换键返回 409。合法拒绝保留错误码及 `FAILED`。通过原候选 Schema、独立身份绑定、双原因 CIF 和解释重构检查后才保存结果。阻断保持预测、解释及绑定为 null，计算次数为零。审计保存任务 ID 和状态。现有患者摘要与演示风险数据沿用各自接口。

### 指定受控回顾性结果入库

`research.table.controlled-admission-enabled=true` 启用受控资料核验，默认只保存 `BLOCKED`。接收指定回顾性快照的 `COMPLETED` 时，部署人员需同时配置以下两项，并独立核对执行及入库范围：

| 启动配置 | 取值 |
| --- | --- |
| `research.table.controlled-completion-base-url` | 预先核对的研究宿主根地址。必须与实际响应的评估请求地址对应。 |
| `research.table.controlled-completion-authorization-file` | 部署方持有的独立授权 JSON 记录。使用 `runId`、`snapshotId`、`subjectId`、`requestSha256`、`inputSha256`、`contextSha256`、`predictionAuthorizationRef`、`explanationAuthorizationRef`。由来源和执行许可持有人确认，保存在服务管理的本机路径。 |

两个值在启动时读取。Java 校验配置、档案编号及档案代码、冻结请求与重算模型输入摘要，然后核对响应的模型、独立证据摘要和输出结构。无法证明绑定的成功响应保留 `OUTCOME_UNKNOWN`，结果为 null。撤回配置后须重启 Java，已持久化报告仍走原查询与对象权限。研究报告继续由医生复核，患者本人只能从患者接口查看复核状态、摘要文字与随访。该接入在独立 H2 和人工回顾性资料上验证。

## 本地研究任务联调

从仓库根目录运行。需要 JDK 21、Python 3.11、`research_runtime/requirements.lock`，以及受控环境的七变量 `Model_Manifest.json`、同目录 `final__logistic.joblib` 和可信清单 SHA-256。普通克隆提供启动器和人工样例。请从有权的模型保管方取得原文件和独立摘要。

```powershell
python -m pip install -r research_runtime/requirements.lock
$manifestPath = 'C:/controlled-model/Model_Manifest.json'
$manifestSha256 = '<由模型保管方提供的 SHA-256>'
python -m research_runtime.examples.run_research_table_integration runtime/trained-ui --serve --trained-manifest $manifestPath --trusted-manifest-sha256 $manifestSha256
```

用尚不存在的输出目录。启动器运行 Java 和 Python，使用新的内存 H2 登记一个人工快照。在开发人员自己的前端开发代理中，把同源 `/api` 的目标设为 `handoff.json` 的 `javaBaseUrl`。以 `researcher` / `DemoPass!2026` 登录，使用 `subjectId` 查获授权的快照。省略 `--serve` 可先运行 Java 会话验收。CI 使用 `--contract-test` 核对公开人工参数的契约。

## 关键行为

- 纯ADMIN的业务菜单保持需求规定的audit/system/users三个入口（审计、系统/模型配置、账号/角色）；研究和医生入口由相应角色提供。菜单不是接口授权，原有管理员管理权限仍在服务端校验。
- PATIENT优先否决原始资料与临床写权限，即使旧账号同时带其他角色。新建账号禁止混合PATIENT与其他角色。
- 新研究账号默认ASSIGNED：VIEW可读/评估，MANAGE可编辑、录入、导入、审核质控；自建或自行导入的新档案获得MANAGE。管理员可显式配置ALL演示队列。旧合成研究账号由迁移明确保留ALL策略。
- 医生需VIEW/MANAGE原始资料授权才能复核和维护随访；只有MANAGE可编辑档案。研究员可读不等于有临床复核/随访写权限。患者仅本人SUMMARY_READ摘要，不返回原始数值、图权重、任意内部限制、输入快照或执行备注。
- 账号停用和密码重置撤销现有会话；角色、数据范围及对象授权每次请求重新核对。
- 访视raw_*保存规范化原始数值文本，DECIMAL列只作派生显示。算法请求优先取raw值，30.004不能舍入成30后参与计算。旧行无法恢复已丢失的历史精度；来源未知保持null。
- 自动质量检查独立保存ID、规则、操作者、版本、输入摘要与覆盖。人工APPROVED不修复全缺失/非法原始资料。算法预测窗口等资格仍按Python实际结果，两个状态不混同。
- 旧 `POST /api/assessment-tasks` 和 `/submit` 对新任务返回 503，原幂等记录仍可重放。旧报告保留原规则版本和分数语义。
- CSV预检与确认入库分开；支持原列名映射到三表模板并保留原文/映射/摘要，幂等身份同时绑定原文与映射。确认入库重新校验授权与预览身份；CSV访视也触发人工QC。错误CSV沿用存储错误上限，汇总行数不截断。
- 报告JSON资源保持兼容；`/api/reports/{assessmentId}/download?format=pdf|html|json&audience=research|doctor`返回附件。PDF内嵌[OFL字体](src/main/resources/fonts/README.md)，不依赖系统中文字体。
- 业务审计记录主体、操作、HTTP结果与traceId，不记录密码、完整请求或内部异常正文；写审计失败会记录错误，不回滚已完成业务。管理员分页接口为 `/api/audit-logs/page`。

会话使用 `JSESSIONID`。默认启用 CSRF 校验。使用 Cookie 会话的写请求先调用 `GET /api/auth/csrf`，保留返回的 Cookie，并按响应指定的 `X-CSRF-TOKEN` 请求头回传令牌。Vue 共用请求层已处理。登录接口豁免该校验。

研究证据接口 `/api/research/evidence` 与 `/{key}` 仅向ADMIN/RESEARCHER开放。Maven按白名单打包十份现有Git文档，Java返回内容摘要与聚合原文；`research.artifacts-root`仅用于核对15个既定模型文件是否存在，不读取权重。真实患者输入准入见业务状态。
