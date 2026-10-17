# AD-CausalRisk Java 后端

本目录是 Java 业务后端：数据库与迁移、登录与权限、业务任务、调用 Python 子服务、结果复核、随访、报告与审计。浏览器只调用 Java；Java 通过 `AgentClient` 调用 Python 子服务（`127.0.0.1:8091`）。业务逻辑放 Service，Controller 处理 DTO 与 HTTP 映射，Repository 处理数据访问。

## 模块任务说明（原始）

> 在此目录创建Spring Boot工程，负责数据库、权限、业务任务与审计；Python调用封装在AgentClient中。
>
> 先阅读[需求](../docs/Course_SRS_V3_2.md)、[设计参考](../docs/Design_Guide.md)、[目标接口](../contracts/java-business.target.openapi.json)及[AI协作指南](../docs/AI_Collaboration.md)。首次PR建议完成工程配置、数据库迁移与档案接口；后续依次完成登录与对象权限、访视/特征、导入、质控、持久化幂等任务、AgentClient、结果复核、随访、报告和审计。
>
> 交付包含Maven/Gradle锁定构建方式、数据库建表/迁移、合成账号与密码哈希初始化、HTTP接口测试、Mock Python超时/版本错/409/BLOCKED测试、重启历史查询和患者字段过滤。添加自己的CI；未经实现与验收的模块保持待开发。
>
> 在模块README补充Java/数据库版本、环境变量示例、迁移、启动、测试命令及接口地址。参数通过配置读取，密码使用环境变量。API沿用共同契约，业务逻辑放Service，Controller处理DTO与HTTP映射，Repository处理数据访问。

---

## 1. 已实现范围

共同契约共 **22 个操作**，目前已实现 **7 个**：

### 第一组：认证（auth）
| 操作 | 路径 | 说明 |
| --- | --- | --- |
| `postApiAuthLogin` | `POST /api/auth/login` | JSON 登录，建立 `JSESSIONID` 会话 |
| `postApiAuthLogout` | `POST /api/auth/logout` | 使会话失效 |
| `getApiMe` | `GET /api/me` | 返回当前用户与角色菜单 |

### 第二组：档案管理（subject）
| 操作 | 路径 | 说明 |
| --- | --- | --- |
| `getApiSubjects` | `GET /api/subjects` | 分页查询档案列表，先按权限过滤再分页 |
| `postApiSubjects` | `POST /api/subjects` | 创建档案（ADMIN / RESEARCHER） |
| `getApiSubjectssubjectId` | `GET /api/subjects/{subjectId}` | 查询档案详情；`visits` 来自 `visit` 表，`features` 仍为空数组 |

### 第三组：访视（visit）
| 操作 | 路径 | 说明 |
| --- | --- | --- |
| `postApiSubjectsVisitssubjectId` | `POST /api/subjects/{subjectId}/visits` | 创建访视（ADMIN / RESEARCHER） |

访视**没有独立的查询接口**：契约在该路径上只声明了 `POST`，因此访视通过档案详情的 `visits` 字段读取（`GET /api/subjects/{subjectId}`）。新增一个 `GET` 属于共同契约变更，需先走契约 PR。

权限规则：ADMIN / RESEARCHER 全量可见；DOCTOR / PATIENT 仅可见 `subject_permission` 表中有授权的档案。创建档案仅限 ADMIN / RESEARCHER；创建访视沿用**同一条** `SubjectAccessPolicy.canCreate` 规则，因此单档案的 `MANAGE` 授权并不足以创建访视。

访视的分数缺失时按 `null` 保存并返回，**绝不补 0**；分数只接受 `DECIMAL(4,1)` 能精确表示的取值，范围 `mmse`/`moca` 0–30、`cdrSb` 0–18。

另外交付：15 张表的 Flyway 基线迁移、四角色与合成账号种子、统一 `Envelope` 响应与错误映射、`X-Trace-Id` 追踪、会话安全配置、后端 CI。

## 2. 固定版本

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Java | 21 | `java.version`；开发机实测 21.0.10 LTS，CI 使用 temurin 21 |
| Maven | 3.9.16 | 固化在 `.mvn/wrapper/maven-wrapper.properties` 的 `distributionUrl`，并用 `distributionSha256Sum` 校验 |
| Maven Wrapper | 3.3.4 | `only-script` 模式，**不提交 `maven-wrapper.jar`** |
| Spring Boot | 3.5.16 | `spring-boot-starter-parent`；该 Spring Boot 线使用 **Jackson 2**，故未选会切到 Jackson 3 的 4.x |
| 目标数据库 | MySQL 8 | 见[需求](../docs/Course_SRS_V3_2.md)；本机无 MySQL，迁移**只在 H2 上验证过** |
| 测试数据库 | H2 2.3.232 | `MODE=MySQL`；不使用 Docker / Testcontainers |

以下版本由 Spring Boot BOM 管理，未手写（括号内为实测解析结果）：Spring Framework（6.2.19）、Spring Security（6.5.11）、Hibernate ORM（6.6.53.Final）、Flyway（11.7.2）、Jackson（2.21.4）、H2（2.3.232）、MySQL Connector/J（9.7.0）。

仅测试范围：`com.networknt:json-schema-validator` **1.5.9**（对着契约文件校验响应）、`org.mockito:mockito-core` **5.17.0**（以 `-javaagent` 加载，原因见 `pom.xml` 注释）。

## 3. 环境变量

参数全部通过配置读取，密码只从环境变量取，**仓库内不存在任何明文凭据**（`V2` 里只有 BCrypt 哈希）。

| 变量 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | 是 | 无 | `local`（MySQL）或 `test`（H2） |
| `ADCR_DB_URL` | 否 | `jdbc:mysql://127.0.0.1:3306/ad_causalrisk?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC` | MySQL 连接串 |
| `ADCR_DB_USERNAME` | 是 | 无 | 未设置时启动即失败，不会静默回退 |
| `ADCR_DB_PASSWORD` | 是 | 无 | 同上 |

PowerShell 示例（占位值，请替换为本机实际值）：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:ADCR_DB_URL      = 'jdbc:mysql://127.0.0.1:3306/ad_causalrisk?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC'
$env:ADCR_DB_USERNAME = '<your-db-user>'
$env:ADCR_DB_PASSWORD = '<your-db-password>'
```

本目录没有 `.env.example`：Spring Boot 不会读取 `.env` 文件，放一个不会被加载的示例文件只会误导，变量清单以上表为准。`.gitignore` 已忽略 `.env` 与 `.env.*`（保留 `.env.example`），如确需该文件可直接添加。

`application.yml` **刻意不配置 datasource**：必须显式指定 profile。不带 profile 启动会因缺少数据源而立即报错，而不是静默启用内存库。

## 4. 数据库迁移

- 迁移脚本位于 `src/main/resources/db/migration/`：`V1__baseline_schema.sql`（15 张表、唯一约束、外键、索引、枚举 CHECK），`V2__seed_synthetic_accounts.sql`（四角色 + 合成账号）。
- **迁移在应用启动时由 Flyway 自动执行**；测试时在 H2 上由测试上下文自动执行。未配置 `flyway-maven-plugin`，因此**没有独立的迁移命令行**。
- 表结构的唯一事实来源是迁移脚本。JPA 配置为 `ddl-auto: validate`：实体与 DDL 不一致时启动即失败，**不允许改成 `update`**，也不靠启动重建表。
- **不要修改已经应用过的 `V1` / `V2`**：Flyway 会因校验和不匹配而拒绝启动。新增变更请添加 `V3__*.sql`。
- `V1` 顶部写明了"可移植性契约"：同一份 SQL 必须同时跑在 MySQL 8 与 H2 `MODE=MySQL`，因此不使用 `ENGINE=`、`DEFAULT CHARSET`、`COLLATE`、`COMMENT`、`UNSIGNED`、`ON UPDATE CURRENT_TIMESTAMP`、`JSON` 列类型。

## 5. 启动

需要 JDK 21；无需预装 Maven（wrapper 会下载并校验）。

```powershell
# Windows
cd backend
$env:SPRING_PROFILES_ACTIVE = 'local'   # 另需第 3 节的三个变量
.\mvnw.cmd spring-boot:run
```

```bash
# Linux / macOS
cd backend
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

或运行已构建的产物：

```bash
java -jar target/backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

服务监听 `http://127.0.0.1:8080`。**上述启动命令尚未在本机实际执行过**（本机无 MySQL），见第 13 节。

## 6. 构建与测试

```powershell
# Windows：完整构建 + 测试
cd backend
.\mvnw.cmd -B -ntp verify

# 只跑测试，或只跑某个测试类
.\mvnw.cmd -B -ntp test
.\mvnw.cmd -B -ntp test "-Dtest=AuthApiTest"
```

```bash
# Linux / macOS / CI
./mvnw -B -ntp verify
```

测试使用 `test` profile 的 H2（`MODE=MySQL`），**不需要 MySQL、不需要 Docker**。测试之间用 `@Transactional` 回滚隔离，而不是删表重建。

| 测试类 | 用例数 | 覆盖 |
| --- | --- | --- |
| `SchemaMigrationTest` | 11 | 迁移执行、15 张表精确集合、唯一/外键/CHECK 约束确实生效、种子账号与 BCrypt 口令匹配 |
| `EnvelopeContractTest` | 15 | 六键 `Envelope`、null 不省略、未知字段拒绝、400/401/403/404/405/415/500 映射、traceId 透传、真实安全过滤链 |
| `AuthApiTest` | 12 | 登录 / 登出 / `api/me`、多角色并集与菜单、统一失败提示、会话固定防护、会话失效 |
| `SubjectApiTest` | 22 | 三接口正常流程、分页边界钳位、subjectCode 格式/重复校验、不存在返回 400、DOCTOR/PATIENT 创建返回 403、DOCTOR 只看授权档案、未登录 401、详情 visits/features 为空数组、真实登录建立 session、契约 schema 校验 |
| `VisitApiTest` | 27 | 创建访视正常流程、读取闭环（详情 `visits` 出现新记录）、按 `visitDate`/`id` 排序、同 `visitCode` 跨档案可用、分数上下界、50 字符 `visitCode`、缺失分数存 `null` 不补 0、越界与 2 位小数拒绝、非法日期返回 `MALFORMED_JSON`、未知字段拒绝、重复 `visitCode` 返回 400 且不覆盖、档案不存在返回 400、DOCTOR/PATIENT 与仅有 `MANAGE` 授权者返回 403、未登录 401、契约 schema 校验 |
| `VisitServiceTest` | 1 | 纯 Mockito 单测：数据库唯一约束在预检之后触发时返回 400 `VALIDATION_ERROR` 而不是 500 |

总计 **88 个测试**，全部在 H2（`MODE=MySQL`）上通过。

响应同时被 `contracts/java-business.target.openapi.json` **本身**校验：`ContractSchemas` 读取契约文件、解析 `$ref`、按 JSON Schema 2020-12 并开启 format 断言。契约文件找不到时测试直接失败，不会静默跳过。

## 7. 接口地址

| 服务 | 地址 | 本 PR 是否调用 |
| --- | --- | --- |
| Java 业务 API（浏览器调用） | `http://127.0.0.1:8080` | 是 |
| Python 子服务 | `http://127.0.0.1:8091` | **否**（`AgentClient` 属后续 PR） |

所有响应都是统一 `Envelope`，成功固定 `code=0`、`errorCode=null`：

```json
{
  "code": 0,
  "message": "成功",
  "data": { "id": 1, "username": "admin", "roles": ["ADMIN"], "menus": ["audit", "system", "users"] },
  "errorCode": null,
  "traceId": "3f1c9a2b7d4e4f0a8b6c5d3e2f1a0b9c",
  "timestamp": "2026-10-12T00:20:00.123Z"
}
```

`data` 是 required：无载荷时也必须出现且为 `null`，不得省略。每个响应都带 `X-Trace-Id` 响应头（与 body 内 `traceId` 一致）；调用方可用该请求头透传自己的追踪 ID，格式须匹配 `[A-Za-z0-9._-]{1,64}`，否则会被替换为服务端生成值。

## 8. errorCode

`errorCode` 是稳定字符串，**等于 `ErrorCode` 枚举名，改名属于破坏性契约变更**；`code` 与 HTTP 状态码一致。成功恒为 `code=0` / `errorCode=null`。

| errorCode | code / HTTP | 触发场景 |
| --- | --- | --- |
| `VALIDATION_ERROR` | 400 | `@Valid` 校验失败、参数类型不匹配、缺少必需参数 |
| `MALFORMED_JSON` | 400 | 请求体无法解析，或包含契约未定义的字段（`additionalProperties: false`） |
| `AUTH_INVALID_CREDENTIALS` | 401 | 登录被拒：用户名不存在 / 密码错误 / 账号停用，三者提示完全相同 |
| `UNAUTHENTICATED` | 401 | 未登录或会话已失效 |
| `ACCESS_DENIED` | 403 | 已登录但无权：安全链规则拒绝，或服务层对象级拒绝 |
| `NOT_FOUND` | 404 | **仅**表示未匹配任何路由；不用于缺失的业务资源 |
| `METHOD_NOT_ALLOWED` | 405 | 请求方法不被支持 |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 请求体不是 `application/json` |
| `INTERNAL_ERROR` | 500 | 未预期异常；详情只进服务端日志，不回给客户端 |

说明：冻结契约只为在范围内的操作声明了 400/401/403。404/405/415/500 用于框架在进入 Controller 之前就可能产生的拒绝或失败，否则错误体将不是 `Envelope`。**缺失的业务资源不用 404**：契约没有为业务操作声明 404，各模块用 400 + 模块自有错误码（例如档案模块的"档案不存在"）。

## 9. menus 词表

`CurrentUser.menus` 在契约里只是 `array<string>`，**没有枚举**，因此下列 code 是团队约定而非契约保证，**需前端确认**。后端按角色下发，前端负责 code → 路由 / 文案的映射。

| code | 对应区域 | 角色 |
| --- | --- | --- |
| `users` | 用户与角色管理 | ADMIN |
| `system` | 模型配置与系统状态 | ADMIN |
| `audit` | 审计日志 | ADMIN |
| `subjects` | 档案列表与详情 | RESEARCHER、DOCTOR |
| `imports` | CSV 预览与确认入库 | RESEARCHER |
| `quality` | 质量检查 | RESEARCHER |
| `tasks` | 评估任务 | RESEARCHER |
| `graph` | 条件图谱与解释 | RESEARCHER、DOCTOR |
| `reviews` | 人工业务复核 | DOCTOR |
| `followups` | 随访计划 | DOCTOR、PATIENT |
| `reports` | 报告导出 | RESEARCHER、DOCTOR |
| `summary` | 患者摘要 | PATIENT |

多角色账号取各角色菜单的**并集**，去重后按字母序排列；未知角色不贡献菜单，也不会导致登录失败。`roles` 同样按字母序返回。顺序**未定义**，调用方不得依赖。

菜单只是界面可见性提示。所有权限都在服务端再次校验——隐藏前端按钮不构成访问控制。

## 10. 演示账号

以下账号是**合成课程数据**，由 `V2__seed_synthetic_accounts.sql` 写入，密码统一为 `DemoPass!2026`（BCrypt 强度 10，五个账号各自独立的盐，避免从哈希看出密码相同）。

| 用户名 | 角色 |
| --- | --- |
| `admin` | ADMIN |
| `researcher` | RESEARCHER |
| `doctor` | DOCTOR |
| `patient` | PATIENT |
| `research_doctor` | RESEARCHER + DOCTOR（多角色账号） |

⚠️ **这些口令仅为课程演示公开使用，不是真实凭据。任何真实数据环境都不得沿用它们**；上线前必须删除或替换这些账号，并改用环境变量或密钥管理提供凭据。仓库里只保存哈希，明文只出现在本文档中，因为它是刻意公开的演示口令。

当前没有任何 `subject` / `subject_permission` 种子数据：档案由后续的 CSV 导入 PR 写入，授权管理接口也尚未实现。因此 `DOCTOR` / `PATIENT` 在全新库上没有任何档案可见。

## 11. CSRF 取舍（本次 PR 明确记录）

本 PR **关闭了 Spring Security 的 CSRF 防护**（`SecurityConfig` 中的 `csrf().disable()`），并且**没有实现任何 CSRF token 防护**。这是课程范围内取舍，不是已完成的安全能力，不得当作"已防 CSRF"使用。

原因：共同契约 [java-business.target.openapi.json](../contracts/java-business.target.openapi.json) 把会话定义为一个 `JSESSIONID` Cookie（`securitySchemes.sessionCookie`），并且 22 个操作里**都没有声明 CSRF token 的请求字段、响应头或获取端点**。浏览器端无法从契约内取得 token，所以开启 CSRF 防护的结果是前端的每一个写操作（包括登录本身）都返回 403，而不是换来真实防护。

残余风险：会话凭据由浏览器自动随 Cookie 发送，用户已登录时，恶意页面可以伪造写请求（导入、创建档案、复核、随访等）。目前没有任何服务端 token 校验来区分这类请求。

要真正开启，需要先走共同契约变更 PR：新增 token 下发方式（例如 `XSRF-TOKEN` Cookie 或 `GET /api/csrf`），前端改为回传请求头，然后 Java 侧启用 `CookieCsrfTokenRepository`。改完之前不要把这里的配置描述为已具备 CSRF 防护。

另外，本 PR **没有**显式设置会话 Cookie 的 `SameSite` 属性，沿用容器默认值；这同样是待办，不是已完成的缓解措施。

## 12. 未完成项

- 契约 22 个操作中的 **15 个未实现**：特征、导入、质控、评估任务、`AgentClient`、结果查询、人工复核、随访、报告、审计。
- **档案/访视模块剩余**：授权管理接口（`subject_permission` 写操作）未实现；特征写入未实现（详情接口的 `features` 目前恒为空数组）；访视只支持创建，**没有修改或删除**（契约也未声明）。
- **访视分数的精度限制**：`visit` 表的三个分数列是 `DECIMAL(4,1)`，只能精确保存 1 位小数。请求层用 `@Digits(integer = 3, fraction = 1)` **拒绝**第二位小数（例如 `27.55` 返回 400），以免 MySQL 静默舍入、导致入库值与提交值不一致。契约写的是无标度 `number`，若要支持更多小数位需要新的迁移脚本。
- **FR-AUDIT-01 完全未做**：登录成功与失败只写日志，**没有写入 `audit_log` 表**（表已建）。日志不等于审计。
- **FR-AUTH-02/03 部分覆盖**：角色管理接口未实现；档案对象级权限已在 `SubjectAccessPolicy` 中实现但授权管理端点缺失。
- **`Idempotency-Key` 处理未做**（`idempotency_record` 表已建）。
- **患者字段过滤未做**（FR-EXPLAIN-02）：不返回排序数值、图权重与内部轨迹的要求尚未落实。
- **Mock Python 超时 / 版本错 / 409 / BLOCKED 测试未做**；重启历史查询未做。
- **CSRF token 防护未做、`SameSite` 未显式配置**（见第 11 节）。
- 后端 CI 已添加（`.github/workflows/backend-build.yml`），但**从未在 GitHub 上实际跑过**。

## 13. 未验证项

1. **MySQL 8 上的迁移与运行**：本机无 MySQL 服务、无 Docker，`V1` / `V2` 与全部测试都只在 H2 2.3.232（`MODE=MySQL`）上验证过，MySQL 分支从未执行。
2. **`application-local.yml` 的连接串**：从未连接过真实 MySQL。
3. **第 5 节的启动命令**：`spring-boot:run` 与 `java -jar` 均未实际执行过。
4. **`ddl-auto: validate` 只在 H2 上生效过**：它现在真实检查已映射实体与 DDL 的一致性（`sys_user`、`sys_role`、`user_role`、`subject`、`subject_permission`、`visit`），但结论不能外推到 MySQL 的类型名。这一点对 `visit` 尤其重要：三个分数列是 `DECIMAL(4,1)`，实体必须映射为 `BigDecimal`，映射成 `Double` 会在启动时校验失败。
5. **`visit_code` 的唯一性在 MySQL 上与 H2 行为不同**：MySQL 8 默认排序规则 `utf8mb4_0900_ai_ci` 大小写不敏感，`'BL'` 与 `'bl'` 在 MySQL 上是同一个键；H2 的 `DATABASE_TO_LOWER` 只影响标识符、不影响值，因此两者在测试中是不同的键。所以"大小写变体算不算重复"**无法在 H2 上验证**（`subject_code` 存在同一问题）。为此 `VisitService` 会捕获 `DataIntegrityViolationException` 并返回 400，而不是 500。
6. **CI 工作流未运行过**：`actions/setup-java` 的 Maven 缓存与 `chmod +x mvnw` 步骤仅经人工核对。
7. **`mvnw` 的可执行位**：本仓库在 Windows 上添加，`core.filemode=false`，因此 `mvnw` 会以 `100644` 提交，Linux 上 `./mvnw` 会报 Permission denied。CI 用 `chmod +x mvnw` 规避；也可以 `git add --chmod=+x backend/mvnw` 从根上解决。
8. **浏览器实机联调未做**：Cookie 属性、跨域、前端拦截 401 等均未在真实浏览器验证。