# AD-CausalRisk 前端

Vue 3、Vite 和 Element Plus 应用。浏览器通过同源 `/api` 调用 Java；Python 计算由 Java 调用。

## 本地运行

使用 Node.js 22.12 或更新的 22.x，在 `frontend/` 执行：

```sh
npm ci
npm run dev
npm run build
```

默认开发地址为 `http://localhost:5173`，Vite 把 `/api` 转发到 Java 的 `http://localhost:8080`。Java 启动及数据库配置见[后端 README](../backend/README.md)。后端不可用时显示请求错误。

## 当前界面与接入规则

- 登录和当前用户信息来自 Java。菜单按 `/api/me` 返回的角色和菜单生成；对象权限由 Java 校验。
- 现有页面包括档案、导入、质控、任务、图谱、复核、随访、报告和管理。患者页使用 `GET /api/patients/me/summary`，不读取内部评估或原始风险分数。
- `src/api/request.js` 是共用请求层，成功业务响应返回 Envelope 的 `data`。二进制附件需按 OpenAPI 的媒体类型处理。状态、提示和视觉样式沿用现有页面与设计令牌。

## 待接入接口

| 用途 | Java 入口与验收重点 |
| --- | --- |
| 课程异步评估 | `POST /api/assessment-tasks/submit` 后查询原任务；仅展示实际返回的阶段与耗时，阻断分数为 `null`。现有页面仍可使用兼容的同步入口。 |
| 授权报告下载 | `GET /api/reports/{assessmentId}/download` 按契约处理 PDF、HTML、JSON 附件和授权失败。 |
| 表格研究评估 | `POST /api/research/table-assessment-tasks` 接收 `snapshotId` 和 `Idempotency-Key`；`GET /api/research/table-assessment-tasks/{id}` 回读原任务。分别展示完成、阻断、失败、结局未知；同一意图使用原键，不因超时创建新任务。 |

表格研究联调从[本地研究任务启动器](../backend/README.md#本地研究任务联调)取得 `handoff.json`，将本地 Vite 代理指向其中的 `javaBaseUrl`，使用指定研究员和三个人工快照。完成结果分别展示 AD 和其他痴呆的 24 月 CIF 及同次解释；阻断不补值，患者界面不展示研究任务。接口以 [Java OpenAPI](../contracts/java-business.target.openapi.json) 为准。

## 浏览器检查

先在 5199 启动 Vite，再按需要运行以下检查。角色和任务脚本默认使用本地 Chrome，图谱脚本默认使用 Edge；可用 `E2E_CHROME` 指定浏览器。

```sh
npm run dev -- --port 5199
```

```sh
node scripts/e2e-roles.mjs
npm run test:task-page
```

图谱检查默认访问 5204；若沿用 5199，设置 `E2E_BASE=http://127.0.0.1:5199` 后运行 `node scripts/e2e-graph-interaction.mjs`。

角色、任务渲染和图谱交互脚本使用构造接口数据；真实服务测试另见 `scripts/e2e-live.mjs`。构造浏览器检查不替代新接口的端到端验收。
