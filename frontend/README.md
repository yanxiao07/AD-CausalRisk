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
- 医生工作台提供患者档案、结果复核和随访。研究员工作台提供导入、质控和评估任务。管理工作台提供患者账号申请、评估服务和审计。患者页通过 `GET /api/patients/me/research-summary` 读取脱敏评估进度。
- 医生的「结果与依据」优先显示访视日期、来源核对、缺项和下一步。完成复核后医生可发布和撤回患者说明。患者首页优先显示下次复诊，也可查看其他安排和近期回执。有可用号码时提供拨号入口。
- 「导入测试资料」提供档案、访视、特征及七变量模型模板。前三类可映射列名并预检入库。七变量模板与同日已质控访视合成测试快照，再单独提交研究评估。授权研究资料继续走宿主来源核验。
- 医生从「结果与依据」给阻断或已复核需补充资料的报告发送补件单。研究员在「评估任务 → 补件待办」领取、核对资料、提交已登记的新快照并关联回传。医生在原报告查看工作状态和新报告链接；测试样例在「查看测试样例」范围内处理。
- 医生在档案的「患者账号开通」查看当前质控、申请、资料更新及绑定状态。管理员在「账号管理 → 患者账号申请」核对后发放，并可为待发放或已发放的申请办理责任医生交接。患者的联系信息从绑定关系读取。
- 医生在「患者档案 → 概览与操作」录入访视量表、补录检验和影像结果，并查看预测评估的办理条件。补录时按报告选择指标、填写结果、日期和报告来源。保存后在「就诊与随访记录」回看，需要继续办理时重新质控。
- 医生的「患者档案 → 新建测试档案」可集中录入基本资料、本次访视和已有 APOE ε4、海马影像报告。先保存基本资料后可留在同一页继续填写。「保存并质控」仅在本次待保存的资料处理完成后执行质控。表单显示每项回执，并保留未保存的输入供更正。查看档案可在原记录里继续补录。测试档案只使用演练资料。
- 有档案管理权限的医生可在「预测评估」看到提交入口。满足条件的受控档案会向有权限的研究员提交申请。测试档案还需匹配且同步的七变量快照，可以提交测试评估。「评估结果 → 查看测试模型快照 → 预填测试访视」只把日期、MMSE、CDR-SB 填入待确认的表单。同日已有访视时转到现有记录核对。
- 研究员的任务、报告和评估贡献图默认使用受控研究范围。「查看测试样例」切到构造资料。图谱先选档案再读取最近一次评估。同日同刻以任务编号排序。阻断和失败保留原状态，完成且有同次解释时绘制模型贡献。
- `src/api/request.js` 是共用请求层，成功业务响应返回 Envelope 的 `data`。二进制附件需按 OpenAPI 的媒体类型处理。状态、提示和视觉样式沿用现有页面与设计令牌。

## 风险评估接入

按[后端说明](../backend/README.md#本地研究任务联调)启动 Java 和训练模型宿主。把你本地前端开发代理的 `/api` 目标改为 `handoff.json` 的 `javaBaseUrl`，保留原会话配置。用其中的 `owner` 登录，打开 `DEMO-RESEARCH-TABLE` 档案。页面实现由前端团队维护。

1. 用当前档案 `subjectId` 请求 `GET /api/research/table-assessment-tasks/snapshots?subjectId={subjectId}`。每条返回 `snapshotId` 和可空的 `taskId`。
2. 没有任务时，`POST /api/research/table-assessment-tasks`，请求体为 `{"snapshotId": 1}`（替换为列表实际值），并传入 `Idempotency-Key`。同一提交沿用原键。
3. 有 `taskId` 时，通过 `GET /api/research/table-assessment-tasks/{taskId}` 读取。网络异常后先重查快照与原任务，按原键恢复未确认的提交。
4. `COMPLETED` 时展示 `result.model.artifactSha256`（可截短显示）、`result.prediction.adCif24m`、`result.prediction.otherDementiaCif24m` 和 `result.explanation`。标注已训练的七变量逻辑回归、24 个月及人工样例。分别展示 `BLOCKED`、`FAILED` 和 `OUTCOME_UNKNOWN`。

旧 `POST /api/assessment-tasks` 和 `/submit` 已退出新评估。已有历史报告链接继续打开原报告，训练模型的结果按冻结快照回读。请求与响应字段以 [Java OpenAPI](../contracts/java-business.target.openapi.json) 为准。

研究证据页的「研究表格评估」使用 `ResearchBatchAssessment`：按同步状态筛选、多选快照、批量提交并持久化任务历史；未同步快照会被阻止并提示。列表或提交返回 503 时提示“当前未配置研究宿主，请联系管理员配置研究宿主。”。旧批量评估和异步提交入口已移除，纯 DOCTOR 账号不再显示研究评估表单。

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
