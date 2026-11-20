# MySQL 8 专用测试库验收

本页服务历史需求 V3.2 的数据库和历史恢复验收。仓库以 MySQL 8 为目标，默认 CI 使用 H2。下列步骤只面向专用 MySQL 8 测试实例和公开构造数据；**V1–V22 fresh 迁移、V12→V22 升级、备份恢复与租约恢复演练均已在隔离实例执行**。生产部署、真实评估中途杀进程与生产备份策略仍未验收。不要使用现有业务库或将测试库地址提交进 Git。

## 近期增量（2026-11-20）

V29 在新的隔离 MySQL 8.0.46 从 V22 经 V27 升级，连续应用 V28 和 V29。旧人工预约原样保留。实际浏览器经已训练 Python 模型完成一次构造资料流程。Java 正常重启后，复读了跨版本预约回执、责任医生绑定、完成评估和医生说明的发布与撤回记录。现有数据库切换与正式部署独立处理。

独立 MySQL 8.0.46 容器已完成 V22→V27→V28。Flyway 历史完整，V27/V28 Hibernate 校验通过，升级前的人工中文预约原样保留。医生与患者通过 Edge 验证改期，Java 正常重启后按预约版本读回旧改期申请和新确认回执。现用入口仍使用原 V27 与 H2，本次隔离验收结束后已清理容器。

## 0. 历史执行结果（2026-11-18）

- 隔离实例：MySQL **8.0.46**，临时 datadir，回环端口 13306；未连接现有 MySQL80 服务或业务库。
- Fresh：`adcr_v22_fresh` 启动当前 jar，Flyway 成功应用 **V1–V22**，`flyway_schema_history` 全部 success，schema 版本 **v22**，Hibernate `validate` 通过，应用日志出现 `Started BackendApplication`。
- Upgrade：`adcr_v22_upgrade` 先以 `SPRING_FLYWAY_TARGET=12`、`SPRING_JPA_HIBERNATE_DDL_AUTO=none`、`RESEARCH_TASK_RECOVERY_ENABLED=false` 应用到 **V1–V12**；再以当前 jar 正常启动，应用 **V13–V22（10 个迁移）**，schema 到 **v22**，Hibernate `validate` 通过，应用成功启动。
- Schema 核对：`research_table_task.executor_id varchar(80) NULL`、`lease_expires_at datetime NULL`；`visit.version_no`、`is_current`、`replaces_id`；`model_release` 表均存在，`flyway_schema_history` 全部 success。
- 备份恢复：`mysqldump` 备份后 DROP/重建库并导入，subject、COMPLETED research task、`model_release` 均回读一致，应用接口与 Hibernate `validate` 通过。步骤和证据见 [MySQL 备份恢复与任务耐久性演练](MySQL_Backup_Restore_Drill.md)。
- 租约恢复：插入过期无主 RUNNING 任务与另一实例有效租约任务，重启后过期任务置 `OUTCOME_UNKNOWN`/`RESEARCH_EXECUTION_INTERRUPTED`，有效租约保持 RUNNING。该演练使用“停止实例留下的过期行”模拟，不是真实评估中途杀进程。
- **V22 修复记录**：首次 fresh 在真实 MySQL 报 `Cannot drop index 'uq_visit_subject_code': needed in a foreign key constraint`（1553）。V22 已改为先建 `idx_visit_subject_current(subject_id,is_current)` 再删旧唯一约束；H2 不暴露该索引依赖，因此必须保留真实 MySQL 验收。
- **范围限制**：V1–V22 已在隔离 MySQL 8 执行；生产部署、生产备份策略、在线恢复和真实进程中断演练仍未执行。

## 1. 核对实例，准备隔离库

用 MySQL 客户端的本地登录配置连接专用实例。三个库名均用于本次验收，`CREATE DATABASE` 不加 `IF NOT EXISTS`；若名字已存在就停止并另取名字，不覆盖已有库。使用 MySQL 8 服务器，记录 `SELECT VERSION()` 输出；MySQL 5.7 不代替本项验收。

```sql
SELECT VERSION();
CREATE DATABASE adcr_v22_fresh CHARACTER SET utf8mb4;
CREATE DATABASE adcr_v22_upgrade CHARACTER SET utf8mb4;
CREATE DATABASE adcr_v22_restore CHARACTER SET utf8mb4;
```

仅为这三个库分配测试账号所需权限。按 [后端 README](../backend/README.md#启动) 设置数据库账号、口令和 JDBC 地址。每次启动使用回环地址和临时端口（`SERVER_ADDRESS=127.0.0.1`、`SERVER_PORT=0`），记录 Java 日志里实际端口。历史规则任务的种子由对应旧版本生成，新评估使用独立的表格研究宿主。

## 2. 全新迁移与 V12 升级

从仓库 `backend/` 目录运行，以下示例用 PowerShell。先对 fresh 库启动一次正常版本；等 Flyway 完成 V1–V22 和 Hibernate 验证，记录日志后正常关闭。

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '0'
# 使用专用实例端口替换 13306；账号与口令按后端 README 设置。
$env:ADCR_DB_URL = 'jdbc:mysql://127.0.0.1:13306/adcr_v22_fresh?connectionTimeZone=UTC'
.\mvnw.cmd -B -ntp spring-boot:run
```

把地址切到 `adcr_v22_upgrade`，在隔离检出的 V12 版本启动应用。用公开 fixtures 保存至少一个旧规则任务和一个已完成的结果，记下任务 ID、幂等键及回读内容，再正常关闭 Java。通过以下查询确认已迁移到 V12 且 V13 未应用。新版本仅回读这些历史规则任务。

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT COUNT(*) AS research_tables FROM information_schema.tables
 WHERE table_schema = DATABASE() AND table_name IN ('research_table_snapshot', 'research_table_task');
```

备份并恢复到 `adcr_v22_restore`。以下命令使用 MySQL 8 客户端；`adcr-test` 指向上一步核对过的专用实例，备份文件用新路径。使用 `--result-file` 避免 PowerShell 输出重编码。

```powershell
mysqldump --login-path=adcr-test --single-transaction --no-tablespaces --set-gtid-purged=OFF --result-file=adcr-v12.sql adcr_v22_upgrade
if ($LASTEXITCODE -ne 0) { throw 'MySQL backup failed' }
mysql --login-path=adcr-test adcr_v22_restore --execute="source adcr-v12.sql"
if ($LASTEXITCODE -ne 0) { throw 'MySQL restore failed' }
Remove-Item Env:SPRING_FLYWAY_TARGET
```

分别对原升级库和恢复库正常启动应用，检查 V13–V22 只应用一次、研究表齐全，既有研究序列、任务回读、结果版本、授权和幂等行为与 V12 时一致。正常关闭再重启后重复只读核对。迁移或恢复失败保留首个错误及日志；不编辑历史迁移脚本或清理现有表规避失败。

## 3. 研究任务耐久性与租约恢复

本次使用同一批公开构造样例和独立宿主清单，在专用库准备研究快照，并完成以下回读：

- 恢复后保存的 COMPLETED 研究任务可用原 ID 回读，结果和版本不变；subject 与 `model_release` 记录同样回读。
- 模拟已停止实例留下的过期 RUNNING 任务：重启后回收为 `OUTCOME_UNKNOWN`、`error_code=RESEARCH_EXECUTION_INTERRUPTED`，执行者/租约/执行 ID 清空；另一实例仍持有有效租约的任务保持 RUNNING。
- 手工构造过期租约必须使用 `UTC_TIMESTAMP()`；应用 JDBC 连接使用 `connectionTimeZone=UTC`，用本地 `NOW()` 会得到未来时间而无法触发回收。

真实评估请求执行中途杀进程、生产进程守护、在线恢复和备份加密仍未执行；不得把本次“过期行模拟”写成真实中断演练。完整命令、回读结果和边界见 [MySQL 备份恢复与任务耐久性演练](MySQL_Backup_Restore_Drill.md)。

留存 MySQL 版本、两次迁移清单、备份恢复回执、重启前后响应和 Python 计数。H2、MySQL 5.7 及尚未执行的项目分别记录。
