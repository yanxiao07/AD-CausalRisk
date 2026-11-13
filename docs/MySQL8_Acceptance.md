# MySQL 8 专用测试库验收

本页服务课程需求 V3.2 的数据库和历史恢复验收。仓库以 MySQL 8 为目标，默认 CI 使用 H2。下列步骤只面向专用 MySQL 8 测试实例和公开构造数据，**尚未在目标库执行**。不要使用现有业务库或将测试库地址提交进 Git。

## 1. 核对实例，准备隔离库

用 MySQL 客户端的本地登录配置连接专用实例。三个库名均用于本次验收，`CREATE DATABASE` 不加 `IF NOT EXISTS`；若名字已存在就停止并另取名字，不覆盖已有库。使用 MySQL 8 服务器，记录 `SELECT VERSION()` 输出；MySQL 5.7 不代替本项验收。

```sql
SELECT VERSION();
CREATE DATABASE adcr_accept_fresh CHARACTER SET utf8mb4;
CREATE DATABASE adcr_accept_upgrade CHARACTER SET utf8mb4;
CREATE DATABASE adcr_accept_restore CHARACTER SET utf8mb4;
```

仅为这三个库分配测试账号所需权限。按 [后端 README](../backend/README.md#启动) 设置数据库账号、口令和 JDBC 地址。每次启动使用回环地址和临时端口（`SERVER_ADDRESS=127.0.0.1`、`SERVER_PORT=0`），记录 Java 日志里实际端口。历史规则任务的种子由对应旧版本生成，新评估使用独立的表格研究宿主。

## 2. 全新迁移与 V12 升级

从仓库 `backend/` 目录运行，以下示例用 PowerShell。先对 `adcr_accept_fresh` 启动一次正常版本；等 Flyway 完成 V1–V13 和 Hibernate 验证，记录日志后正常关闭。

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '0'
# 使用专用实例端口替换 13306；账号与口令按后端 README 设置。
$env:ADCR_DB_URL = 'jdbc:mysql://127.0.0.1:13306/adcr_accept_fresh?connectionTimeZone=UTC'
.\mvnw.cmd -B -ntp spring-boot:run
```

把地址切到 `adcr_accept_upgrade`，在隔离检出的 V12 版本启动应用。用公开 fixtures 保存至少一个旧规则任务和一个已完成的结果，记下任务 ID、幂等键及回读内容，再正常关闭 Java。通过以下查询确认已迁移到 V12 且 V13 未应用。新版本仅回读这些历史规则任务。

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT COUNT(*) AS research_tables FROM information_schema.tables
 WHERE table_schema = DATABASE() AND table_name IN ('research_table_snapshot', 'research_table_task');
```

备份并恢复到 `adcr_accept_restore`。以下命令使用 MySQL 8 客户端；`adcr-test` 指向上一步核对过的专用实例，备份文件用新路径。使用 `--result-file` 避免 PowerShell 输出重编码。

```powershell
mysqldump --login-path=adcr-test --single-transaction --no-tablespaces --set-gtid-purged=OFF --result-file=adcr-v12.sql adcr_accept_upgrade
if ($LASTEXITCODE -ne 0) { throw 'MySQL backup failed' }
mysql --login-path=adcr-test adcr_accept_restore --execute="source adcr-v12.sql"
if ($LASTEXITCODE -ne 0) { throw 'MySQL restore failed' }
Remove-Item Env:SPRING_FLYWAY_TARGET
```

分别对原升级库和恢复库正常启动应用，检查 V13 只应用一次、研究表齐全，既有课程序列、任务回读、结果版本、授权和幂等行为与 V12 时一致。正常关闭再重启后重复只读核对。迁移或恢复失败保留首个错误及日志；不编辑历史迁移脚本或清理现有表规避失败。

## 3. 研究任务耐久性

使用同一批公开构造样例和独立宿主清单，在专用库通过受控登记流程准备研究快照。仓库 [前端联调启动器](../backend/README.md#本地研究任务联调) 只支持新建内存 H2；它不能作为 MySQL 样例登记或耐久性通过的证据。目标库登记工具到位前，本节待执行。

- 保存一个完成结果、一个 QC 阻断以及一次上游不可达的 `OUTCOME_UNKNOWN`；记录任务 ID、原幂等键、状态、结果及调用次数。
- 正常关闭 Java，保持同一数据库，再启动并登录。所有任务可用原 ID 回读，结果和版本不变；使用原键重放不新增 Python 调用，换键仍返回 409。确认患者不能读取研究任务。
- 留存 MySQL 版本、两次迁移清单、备份恢复回执、重启前后响应和 Python 计数。H2、MySQL 5.7 及尚未执行的项目分别记录。
