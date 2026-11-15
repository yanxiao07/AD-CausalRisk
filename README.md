# AD-CausalRisk

面向阿尔茨海默病研究的医疗科研协作平台。系统以受试者研究队列为核心，覆盖资料采集、质量控制、特征与访视管理、研究模型评估、任务追踪、复核随访与报告审计。

## 当前能力

- **受试者研究队列**：档案、访视、特征、观测与数据来源追踪。
- **研究模型评估**：Java 任务调度 + Python 动态研究宿主，使用已训练的七变量逻辑回归输出 24 个月双原因 CIF 与同次分组归因。
- **受控快照登记**：ADMIN/RESEARCHER 可录入七变量数据，缺失项按 `NOT_COLLECTED` 记录；登记后自动同步到研究宿主，失败可在列表重试。
- **批量评估与任务历史**：按同步状态筛选快照，批量提交并持久化任务历史；失败/结果未知任务可重试。
- **角色化协作**：ADMIN、RESEARCHER、DOCTOR、PATIENT 分权视图；患者摘要不含原始分数、图权重和内部轨迹。
- **系统控制台**：研究宿主、评估通道、审计保留策略与模型开关。

## 架构

```text
Vue 3 (5173)
   │  /api
   ▼
Java Spring Boot (8080) ─── MySQL 8 (3306)
   │  HTTP + Bearer
   ▼
Python dynamic research host (16301)
```

浏览器只调用 Java；Java 负责会话、授权、持久化、审计与任务编排；Python 宿主负责模型推理与快照注册。Python 执行是同步的，任务状态以 Java 持久化记录为准。

## 快速开始

### 0. 环境

- JDK 21
- Node.js 22.12+
- Python 3.11/3.12
- MySQL 8（本地库 `ad_causalrisk`，Flyway 自动迁移 V1–V16）

### 1. 启动 Java 后端（8080）

```powershell
cd backend
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:ADCR_DB_URL = 'jdbc:mysql://127.0.0.1:3306/ad_causalrisk?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC'
$env:ADCR_DB_USERNAME = 'root'
$env:ADCR_DB_PASSWORD = '<你的密码>'
.\mvnw.cmd spring-boot:run
```

### 2. 启动 Python 研究宿主（16301）

首次使用先安装研究依赖：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r research_runtime\requirements.lock
```

启动动态宿主（模型文件由模型保管方提供，不进入 Git）：

```powershell
.\.venv\Scripts\python.exe -m research_runtime.dynamic_host `
  --port 16301 `
  --manifest "C:\controlled-model\Model_Manifest.json" `
  --artifact "C:\controlled-model\final__logistic.joblib" `
  --snapshot-dir runtime\dynamic-snapshots `
  --token adcr-research-token
```

### 3. 启动前端（5173）

```powershell
cd frontend
npm ci
npm run dev -- --port 5173
```

Vite 的 `/api` 代理指向 `http://localhost:8080`。

### 4. 配置研究宿主

使用管理员登录后，在「系统控制台 → 研究宿主」填写：

- 地址：`http://127.0.0.1:16301`
- Bearer Token：`adcr-research-token`
- 读取超时：`120` 秒
- 启用：开

保存后即可在「研究证据 → 研究表格评估」中登记快照并提交评估。

### 5. 演示账号

| 角色 | 账号 | 口令 |
| --- | --- | --- |
| 研究员 | `researcher` | `DemoPass!2026` |
| 医生 | `doctor` | `DemoPass!2026` |
| 管理员 | `admin` | `DemoPass!2026` |
| 患者 | `patient` | `DemoPass!2026` |

## 验证

```powershell
# 后端（H2 MySQL 模式，326 测试）
cd backend
.\mvnw.cmd -B -ntp clean verify

# 前端
cd frontend
npm run build

# Python
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

## 数据与证据边界

- 当前研究评估使用 `CONSTRUCTED_VALIDATION` 受控快照，结果标注为人工样例，不构成临床诊断或个人因果效应。
- 缺失变量按 `NOT_COLLECTED` 记录，不补 0、不插均值；缺失过多时模型可返回 `BLOCKED`。
- `BLOCKED` 时 `riskScore=null`、`riskLevel=UNAVAILABLE`，不生成替代分数。
- 真实患者数据接入、临床验证与生产部署仍需单独验收。

## 文档

- [产品化规划](docs/Frontend_Productization_Plan.md)
- [P1 验收报告](docs/P1_Acceptance_Report.md)
- [P2 数据验证报告](docs/P2_Data_Validation_Report.md)
- [P2 数据模板](docs/W2_203_Data_Template.md)
- [发布说明](docs/Release_Notes_v1.0.md)
- [集成契约](docs/Integration_Contract.md)
- [研究原则](docs/Research_Core_Principles.md)

## 目录

```text
backend/          Java 业务 API、权限、持久化、任务与审计
frontend/         Vue 3 管理端、医生端、研究员端与患者端
python_service/   合成验证服务（历史 Agent 兼容）
research_runtime/ 研究运行时、动态宿主与推理协议
contracts/        OpenAPI 契约
docs/             设计、验收、发布与协作文档
```
