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

## 风险评估接入

按[后端说明](../backend/README.md#本地研究任务联调)启动 Java 和训练模型宿主。把你本地前端开发代理的 `/api` 目标改为 `handoff.json` 的 `javaBaseUrl`，保留原会话配置。用其中的 `owner` 登录，打开 `DEMO-RESEARCH-TABLE` 档案。页面实现由前端同学维护。

1. 用当前档案 `subjectId` 请求 `GET /api/research/table-assessment-tasks/snapshots?subjectId={subjectId}`。每条返回 `snapshotId` 和可空的 `taskId`。
2. 没有任务时，`POST /api/research/table-assessment-tasks`，请求体为 `{"snapshotId": 1}`（替换为列表实际值），并传入 `Idempotency-Key`。同一提交沿用原键。
3. 有 `taskId` 时，通过 `GET /api/research/table-assessment-tasks/{taskId}` 读取。网络异常后先重查快照与原任务，按原键恢复未确认的提交。
4. `COMPLETED` 时展示 `result.model.artifactSha256`（可截短显示）、`result.prediction.adCif24m`、`result.prediction.otherDementiaCif24m` 和 `result.explanation`。标注已训练的七变量逻辑回归、24 个月及人工样例。分别展示 `BLOCKED`、`FAILED` 和 `OUTCOME_UNKNOWN`。

旧 `POST /api/assessment-tasks` 和 `/submit` 已退出新评估。历史报告按原版本读取，训练模型的结果在原快照上回读。请求与响应字段以 [Java OpenAPI](../contracts/java-business.target.openapi.json) 为准。

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

角色、任务渲染和图谱交互脚本使用构造接口数据；`scripts/e2e-live.mjs` 保留原规则评估的历史回归场景。当前模型演示走上面的评估页流程。
