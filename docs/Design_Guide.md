# 业务设计与开发顺序

本指南帮助把需求和接口转换成数据库、后端服务及前端页面。字段与路径以contracts中的接口为准；这里的表结构建议由后端成员落实为迁移脚本。

## 1. 角色与数据范围

| 角色 | 主要功能 | 必须检查的边界 |
| --- | --- | --- |
| ADMIN | 用户/角色、模型配置、系统状态、审计 | 管理操作留审计，不能任意改写已保存评估结果 |
| RESEARCHER | 档案、访视、特征、导入、质控、评估、报告 | 只能操作获授权的数据范围 |
| DOCTOR | 获授权档案、结果、人工复核、随访、报告 | 检查档案授权及复核/随访权限 |
| PATIENT | 本人摘要和随访 | 仅本人档案；不返回排序数值、图权重或内部阶段轨迹 |

菜单与按钮可根据角色显示，但所有权限必须在后端再验证。列表、详情、任务、结果、随访和导出都执行对象级检查。即使修改URL中的数字ID，也不能访问未授权档案。

## 2. 后端分层

```text
Controller：接收DTO、校验输入、返回HTTP与统一Envelope
    ↓
Service：权限范围、业务规则、事务、状态转换、版本与幂等
    ↓
Repository：数据库读写与查询

AgentClient：Python调用、超时/重试、契约与返回值校验
```

不要把数据库Entity直接作为外部响应；用DTO控制字段和角色范围。Python结果完整保存为版本化记录，页面需要的简化摘要由Java生成。

## 3. 数据库实体建议

| 实体/表 | 关键字段 | 约束和用途 |
| --- | --- | --- |
| sys_user | id, username, password_hash, status, created_at | username唯一，密码哈希保存 |
| sys_role / user_role | id, role_code / user_id, role_id | 四种角色，明确多角色处理规则 |
| subject | id, subject_code, alias, status, data_version, created_at | subject_code唯一，DEMO编号映射数字主键 |
| subject_permission | subject_id, user_id, permission_type | 档案与访问范围授权 |
| visit | id, subject_id, visit_code, visit_date, mmse, moca, cdr_sb | 同档案visit_code唯一，保留日期与缺失 |
| subject_feature | id, subject_id, feature_name, feature_value, recorded_at, unit | 保留来源时间、单位和版本，识别重复/冲突 |
| data_import_job | id, created_by, status, data_version, total_rows, valid_rows, invalid_rows | 预览、确认入库与失败分开 |
| quality_check | id, subject_id, quality_status, issues, checked_at, rule_version | 保存检查，不只临时计算页面值 |
| assessment_task | id, subject_id, status, run_id, request_digest, created_at, updated_at | 任务持久化及错误恢复 |
| idempotency_record | user_id, operation, idempotency_key, request_digest, result_id | 用户+操作+键唯一，防止重复创建 |
| risk_assessment | id, subject_id, task_id, agent_result_json, review_status, created_at | 保存返回版本、图、解释和限制 |
| assessment_review | id, assessment_id, reviewer_id, decision, comment, reviewed_at | 复核人、时间和理由可追溯 |
| follow_up_plan | id, subject_id, scheduled_at, purpose, status, owner_id | PLANNED/COMPLETED/CANCELLED |
| audit_log | id, actor_id, event_type, trace_id, summary, timestamp | 可查登录、拒绝、导入、评估、复核、导出等事件 |

迁移脚本带版本号。不要靠每次启动自动删表重建；数据修改要有唯一约束、外键、必要索引和事务。服务重启后任务、历史结果和随访仍应可查询。

## 4. 关键业务流程

### 导入

上传CSV → 解析字段和类型 → 检查重复、缺失、范围和时间 → 返回预览与错误行 → 用户确认 → 合格记录入库并生成dataVersion。不能把错误行先入库再静默删除。

使用fixtures的档案、访视和特征CSV分别验证。错误响应应能定位行号与字段；同一文件/幂等请求不会重复增加记录。

### 评估

检查权限与档案 → 执行/读取质量检查 → 幂等创建PENDING任务 → RUNNING调用Python → 校验版本、runId和结果 → 保存COMPLETED/BLOCKED/FAILED及审计。

Python响应是AgentResult，Java响应是Envelope，两种格式分别处理。BLOCKED保留null分数并显示warnings。网络失败不能保存演示样例冒充成功结果。

### 人工复核与随访

查看授权结果 → 保存复核decision/comment和操作者 → 根据需要创建随访 → 完成或取消计划并保留记录。随访表和复核表关联档案、任务或评估，避免页面重新加载后丢失。

### 报告与审计

报告读取已保存的评估及版本，加入生成时间、用途说明和复核信息。执行导出权限检查，并保存审计事件。日志记录摘要和traceId，不写密码、令牌或完整请求体。

## 5. 页面与交互

| 页面 | 主要内容 | 重点状态 |
| --- | --- | --- |
| 登录与工作台 | 登录、角色菜单、待办和服务提示 | 凭据错误、未登录、网络失败 |
| 档案列表/详情 | 分页、筛选、档案、访视、特征和数据版本 | 空列表、无权限、字段缺失 |
| 导入页 | 选择文件、字段、预览、错误行和确认 | 上传失败、校验失败、重复确认 |
| 质量页 | PASS/WARN/BLOCKED、规则、时间及问题 | 阻断原因可读 |
| 任务页 | Java任务状态、错误、返回后的阶段与耗时 | RUNNING、BLOCKED、FAILED |
| 图谱与解释 | 节点、边、路径、激活/缺失、贡献与版本 | 缺失模态、空图、结果不可用 |
| 复核与随访 | 复核意见、计划、完成/取消和逾期 | 表单错误、重复提交 |
| 患者摘要 | 本人通俗摘要和随访 | 无结果、无计划、无权限 |
| 报告与审计 | 下载、版本、事件和筛选 | 导出失败、授权拒绝 |

颜色之外同时显示状态文字。表单有字段提示与校验；提交中禁用重复操作，失败后允许修正。桌面、手机和键盘操作都应可用。

## 6. 适合拆成PR的开发顺序

1. 工程配置、运行命令、数据库迁移、前端路由与API客户端。
2. 登录/会话、四角色菜单和对象权限。
3. 档案、访视、特征；分页和详情。
4. CSV预览确认、质控、版本与错误行。
5. 持久化任务、AgentClient、失败处理、图谱和解释。
6. 患者摘要过滤、人工复核、随访、报告、审计。
7. 全链路联调、服务断开/重启、重复提交和越权测试。

每个PR列明对应需求和可运行证据。功能未实现时保留待开发状态；使用Mock的页面在联调前切换到实际接口。
