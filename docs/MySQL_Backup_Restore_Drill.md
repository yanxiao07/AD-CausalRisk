# MySQL 8 备份恢复与任务耐久性演练

本页记录 2026-11-18 在隔离 MySQL 8.0.46 实例上完成的备份恢复和租约恢复演练，作为 [MySQL 8 专用测试库验收](MySQL8_Acceptance.md) 的补充。演练只使用临时库和公开构造数据，不连接现有业务库。

## 1. 环境

- MySQL 8.0.46，回环地址 `127.0.0.1:13306`，临时 datadir，验收后停止并释放端口。
- 数据库：`adcr_v22_fresh`，Flyway `V1–V22`，Hibernate `validate` 通过。
- 备份文件：`.tmp/mysql-v22/backup/adcr_v22_fresh.sql`（36,985 bytes），使用 `--no-tablespaces`。
- 专用账号只授予验收库权限；下列命令用环境变量传入，不把口令写入仓库。

## 2. 备份与恢复步骤

备份使用 `mysqldump` 的 `--single-transaction` 和 `--no-tablespaces`。后者避免专用账号缺少 `PROCESS` 权限时导出 tablespaces 失败。

```powershell
$dump = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$env:ADCR_DB_HOST = '127.0.0.1'
$env:ADCR_DB_PORT = '13306'
$env:ADCR_DB_USERNAME = 'adcr_accept'
$env:ADCR_DB_PASSWORD = '<acceptance-password>'
$env:ADCR_ACCEPT_DB = 'adcr_v22_fresh'

& $dump -h $env:ADCR_DB_HOST -P $env:ADCR_DB_PORT -u $env:ADCR_DB_USERNAME `
  "-p$env:ADCR_DB_PASSWORD" --single-transaction --routines --triggers `
  --no-tablespaces --databases $env:ADCR_ACCEPT_DB `
  --result-file=adcr_v22_fresh.sql
if ($LASTEXITCODE -ne 0) { throw 'mysqldump failed' }
```

恢复前必须停止应用，确认没有连接，再重建目标库。以下 `DROP DATABASE` 只允许用于临时验收库；生产恢复应先确认备份、保留回滚窗口，并按变更审批执行。

```powershell
& $mysql -h $env:ADCR_DB_HOST -P $env:ADCR_DB_PORT -u root --skip-password `
  --execute="DROP DATABASE IF EXISTS $env:ADCR_ACCEPT_DB; CREATE DATABASE $env:ADCR_ACCEPT_DB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
Get-Content adcr_v22_fresh.sql | & $mysql -h $env:ADCR_DB_HOST -P $env:ADCR_DB_PORT -u root --skip-password
if ($LASTEXITCODE -ne 0) { throw 'mysql restore failed' }
```

恢复后以 `local` profile 和原数据库地址重启后端；Flyway 应报告 schema 已是 v22，Hibernate `validate` 应通过。用 SQL 和应用接口各核对一次：

```sql
SELECT COUNT(*) FROM subject;
SELECT COUNT(*) FROM research_table_task;
SELECT COUNT(*) FROM model_release;
SELECT subject_code, alias FROM subject;
SELECT model_version, artifact_sha256 FROM model_release;
```

本次演练恢复前后均为：`subject=1`、`research_table_task=1`（COMPLETED）、`model_release=1`；应用 `GET /api/subjects`、`GET /api/research/table-assessment-tasks/1`、`GET /api/system/models` 均回读到原记录，Hibernate `validate` 通过。

## 3. 任务租约恢复演练

目标：模拟进程在任务执行中被杀，验证启动/定时恢复只回收无主或过期租约，不回收另一实例仍持有的有效租约。

1. 停止应用，插入两条 `RUNNING` 任务：
   - 任务 A：`executor_id='killed-instance'`，租约已过期；
   - 任务 B：`executor_id='other-instance'`，租约仍有效。
2. 启动应用，使用 `research.task.recovery-enabled=true`（默认）和较短的 `research.task.recovery-interval-ms`。
3. 观察任务 A 变为 `OUTCOME_UNKNOWN`、`error_code=RESEARCH_EXECUTION_INTERRUPTED`，执行者/租约/执行 ID 清空；任务 B 保持 `RUNNING` 和原执行者。

本次结果：

```text
id  run_id         status           executor_id      error_code
1   run-backup-1   COMPLETED        NULL             NULL
2   run-killed-1   OUTCOME_UNKNOWN  NULL             RESEARCH_EXECUTION_INTERRUPTED
3   run-active-1   RUNNING          other-instance   NULL
```

**时间戳注意**：应用的 `research_table_task.lease_expires_at` 通过 JDBC 写入，连接参数为 `connectionTimeZone=UTC`，因此应用看到的是 UTC 墙钟时间。手工构造过期租约时必须使用 `UTC_TIMESTAMP()`，不要用 MySQL 服务器本地 `NOW()`：

```sql
UPDATE research_table_task
SET lease_expires_at = DATE_SUB(UTC_TIMESTAMP(), INTERVAL 5 MINUTE)
WHERE id = 2;
```

本次演练是通过“已停止实例留下的过期 RUNNING 行”模拟进程被杀，不是在一个真实评估请求执行到一半时发送 `Stop-Process`。真实生产演练应在一个可延迟的宿主请求上重复本步骤，并记录进程终止时间、租约到期时间和恢复任务 ID。

## 4. 未覆盖与边界

- 本备份只包含 MySQL 数据；`runtime/model-repository/` 下的 Artifact 文件不在 `mysqldump` 内，生产备份必须同时备份该目录（或对象存储版本）。
- 未执行生产反向代理、TLS、最小权限账号、备份加密和异地保留。
- 恢复时应用必须停止；在线恢复需要额外的主从/快照方案。
- 本次使用临时验收库，不构成生产备份恢复演练。
