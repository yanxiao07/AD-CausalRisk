# Java业务服务

Java负责会话、对象授权、资料、导入、质量、持久化任务/结果、复核、随访、报告和审计。浏览器只调用Java，Java调用Python。业务操作见[OpenAPI](../contracts/java-business.target.openapi.json)，功能与研究边界见[业务状态](../docs/Business_Feature_Status.md)。

本次交付仅包含后端、共享业务契约及相关检查/文档；前端候选页面不随本次提交交付。按需求逐项完成状态、验证与尚未验收条件见[后端验收](../docs/Backend_Requirements_Acceptance.md)。

## 接收与升级

原同步评估POST、JSON报告资源及当前用户接口保持兼容。新调用方按OpenAPI使用异步submit/任务查询、服务端分页、输入快照和附件download入口；取消只支持PENDING任务。管理员界面按`/api/me`的menus渲染，纯ADMIN仅audit/system/users，多角色才合并对应业务入口。

已有库先完整备份，再在专用测试库验证 V11–V13。应用升级后 Flyway 执行前向迁移并校验历史脚本。V12 为既有演示研究账号显式保留 ALL，新研究账号默认 ASSIGNED，通过授权接口配置对象范围。

前端应从本次契约接入，不要复制本地候选页面冒充本PR交付。此PR未配置前端专用CI；独立后端验证不依赖未提交前端。

## 构建与验证

需要 JDK21。Windows 运行 `.\mvnw.cmd -B -ntp verify`，Linux/macOS 运行 `./mvnw -B -ntp verify`。测试使用 H2 MySQL 兼容模式，核对 61 个业务操作与实际路由。研究适配测试使用隔离环回宿主、公开构造请求与响应。

## 启动

目标数据库是MySQL8。将 `src/main/resources/application-local.example.yml` 复制为同目录 `application-local.yml`（已被Git忽略），创建空的专用数据库，配置：

```powershell
$env:SPRING_PROFILES_ACTIVE='local'
$env:ADCR_DB_URL='jdbc:mysql://127.0.0.1:3306/ad_causalrisk?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC'
$env:ADCR_DB_USERNAME='<db-user>'
$env:ADCR_DB_PASSWORD='<db-password>'
$env:ADCR_PYTHON_URL='http://127.0.0.1:8091'
.\mvnw.cmd spring-boot:run
```

默认Java8080、Python8091。无数据库配置时启动失败。Flyway在启动时执行V1–V13，Hibernate验证表结构。V11新增输入快照/质量及来源元数据，V12新增研究数据范围，V13新增独立研究快照和任务表。**MySQL实际迁移和部署待验收**。已有库按前向迁移流程升级。

合成种子账号为admin、researcher、doctor、patient、research_doctor，仓库种子口令为 `DemoPass!2026`，保存BCrypt哈希。管理员可通过账号管理重置密码；本地既有账号密码不会在升级时重置。

## 表格研究评估适配

| 操作 | 调用方式 |
| --- | --- |
| 创建或重放 | `POST /api/research/table-assessment-tasks`，携带 `Idempotency-Key` 和唯一请求字段 `snapshotId`（正整数）。首次提交同步调用研究宿主，重放读取 Java 任务。 |
| 读取 | `GET /api/research/table-assessment-tasks/{id}` 从 Java 读取持久化任务及已校验的研究结果。 |

管理员或研究员须为快照登记的授权用户，同时具备当前档案原始数据权限。患者角色优先排除。快照由受控登记流程写入 `research_table_snapshot`，保存原始 UTF-8 请求、原字节 SHA-256、独立固定的请求/输入/上下文摘要、模型身份、档案及授权用户。当前只接收 `CONSTRUCTED_VALIDATION` 快照。测试使用仓库公开的七/十变量与 QC 阻断样例登记人工快照。真实来源映射、快照登记和执行资格继续依赖独立证据。字段、单位、索引时钟与 QC 责任见[研究契约](../docs/Research_Table_Agent_Contract.md)。

默认研究宿主未配置，已授权且已登记的首次请求返回 503。联调时设置 `research.table.base-url`、`research.table.bearer-token` 与可选的 `research.table.read-timeout`（默认 120 秒）。Java 按草案同步 POST 到独立研究宿主。宿主接收原始快照字节并独立绑定来源、模型及预测和解释授权。HTTP 限环回地址，远程地址使用 HTTPS。

在仓库根目录，用已安装 `research_runtime/requirements.lock` 的研究解释器和 JDK21 运行：

```sh
python -m research_runtime.examples.run_research_table_integration ./research-http-receipt
```

选择尚不存在的输出目录。联调命令启动独立 Python 合成宿主，通过 Java MVC 入口与 H2 验证七变量、十变量和 QC 阻断。Python 从既有人工构造器创建模型和可信上下文，每次请求经过实际计算入口。回执包括人工快照清单、Java 测试日志及每次 Python 计算次数。用例检查授权拒绝、错绑、重复提交和历史回读。目标 MySQL 及真实研究来源待验收。

Java 先持久化任务、快照引用和幂等键，再发一次 POST。超时、断线或无效响应保留 `OUTCOME_UNKNOWN`，原键重放和 GET 只查本地任务，同一快照换键返回 409。合法拒绝保留错误码及 `FAILED`。通过原候选 Schema、独立身份绑定、双原因 CIF 和解释重构检查后才保存结果。阻断保持预测、解释及绑定为 null，计算次数为零。审计保存任务 ID 和状态。现有患者摘要与演示风险数据沿用各自接口。

## 关键行为

- 纯ADMIN的业务菜单保持需求规定的audit/system/users三个入口（审计、系统/模型配置、账号/角色）；研究和医生入口由相应角色提供。菜单不是接口授权，原有管理员管理权限仍在服务端校验。
- PATIENT优先否决原始资料与临床写权限，即使旧账号同时带其他角色。新建账号禁止混合PATIENT与其他角色。
- 新研究账号默认ASSIGNED：VIEW可读/评估，MANAGE可编辑、录入、导入、审核质控；自建或自行导入的新档案获得MANAGE。管理员可显式配置ALL演示队列。旧合成研究账号由迁移明确保留ALL策略。
- 医生需VIEW/MANAGE原始资料授权才能复核和维护随访；只有MANAGE可编辑档案。研究员可读不等于有临床复核/随访写权限。患者仅本人SUMMARY_READ摘要，不返回原始数值、图权重、任意内部限制、输入快照或执行备注。
- 账号停用和密码重置撤销现有会话；角色、数据范围及对象授权每次请求重新核对。
- 访视raw_*保存规范化原始数值文本，DECIMAL列只作派生显示。算法请求优先取raw值，30.004不能舍入成30后参与计算。旧行无法恢复已丢失的历史精度；来源未知保持null。
- 自动质量检查独立保存ID、规则、操作者、版本、输入摘要与覆盖。人工APPROVED不修复全缺失/非法原始资料。算法预测窗口等资格仍按Python实际结果，两个状态不混同。
- `POST /api/assessment-tasks/submit`先持久化PENDING及固定请求/来源/质量绑定，再由有界双线程队列转RUNNING。原同步POST保留。相同幂等意图不重复调用算法；后续数据变更不能覆盖旧快照。
- 仅PENDING任务可取消；RUNNING返回409，不冒充Python支持取消。启动时将遗留PENDING/RUNNING关闭为FAILED/TASK_INTERRUPTED，不自动重算。**当前按单应用实例独占任务库设计，不是分布式任务队列。**
- Python连接/读取超时默认2s/120s，不自动重复POST；健康检查单独采用2s超时。FAILED持久化稳定白名单错误，首次响应、GET与重放不泄露上游URL或异常正文。
- BLOCKED始终riskScore=null/riskLevel=UNAVAILABLE；结果带SYNTHETIC_DEMO，不解释为临床患病概率。
- CSV预检与确认入库分开；支持原列名映射到三表模板并保留原文/映射/摘要，幂等身份同时绑定原文与映射。确认入库重新校验授权与预览身份；CSV访视也触发人工QC。错误CSV沿用存储错误上限，汇总行数不截断。
- 报告JSON资源保持兼容；`/api/reports/{assessmentId}/download?format=pdf|html|json&audience=research|doctor`返回附件。PDF内嵌[OFL字体](src/main/resources/fonts/README.md)，不依赖系统中文字体。
- 业务审计记录主体、操作、HTTP结果与traceId，不记录密码、完整请求或内部异常正文；写审计失败会记录错误，不回滚已完成业务。管理员分页接口为 `/api/audit-logs/page`。

## 本地合成联调

先在仓库根目录启动Python：`python -B -m python_service.server --port 8091`。需要可丢弃H2实例时，可用测试classpath显式启动，不能指向临床库：

```powershell
.\mvnw.cmd test-compile dependency:build-classpath "-Dmdep.outputFile=target/runtime-classpath.txt" "-Dmdep.includeScope=test"
$testClasspath='target/test-classes;target/classes;'+(Get-Content target/runtime-classpath.txt -Raw).Trim()
java -cp $testClasspath com.adcausalrisk.backend.BackendApplication --spring.profiles.active=test
```

此示例采用测试profile的内存库，重启不会保留数据；需要文件库耐久性验证时另行显式配置新文件库。不能把H2通过等同MySQL、分布式部署、研究模型或临床验收。

会话采用JSESSIONID；现有契约未提供CSRF令牌流程，当前CSRF关闭。对公网部署的认证/CSRF/审计可靠性与目标库验收须在部署任务中处理，本地运行不代表生产准入。

研究证据接口 `/api/research/evidence` 与 `/{key}` 仅向ADMIN/RESEARCHER开放。Maven按白名单打包十份现有Git文档，Java返回内容摘要与聚合原文；`research.artifacts-root`仅用于核对15个既定模型文件是否存在，不读取权重。真实推理准入保持独立，见业务状态。
