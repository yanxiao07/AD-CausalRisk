# 受控研究系统 Python 客户端

`python_service/workbench_client.py` 用于在授权主机上调用已经运行的研究系统。它只使用标准库，不启动服务、不读取临床源表、不加载模型，也不把研究工作区复制进本仓库。现有研究8091接口与可选合成研究8092接口继续保持原契约。

## 准备与能力检查

由Python负责人先在授权工作区运行既有 `启动系统.ps1`。当前统一系统入口为 `http://127.0.0.1:8084/`；客户端只接受显式的本机IPv4地址及端口，拒绝外部地址、URL凭证、查询令牌和HTTP重定向，不使用系统代理。

在本仓库根目录执行：

```powershell
python -B -m python_service.workbench_client --url http://127.0.0.1:8084
```

该命令核对服务类型及健康检查，只输出状态、契约、模式和研究限制。要确认受控目录可访问，可再加 `--access-code-file '<本机访问码文件路径>'`。访问码文件由Python负责人从同一授权工作区提供，不把其内容作为命令行参数或写入Git。带认证的检查只输出目录总数与可计算数，不输出别名、测量值、概率、Cookie或来源图；退出前注销该次会话。

服务未启动时返回 `LOCAL_TRANSPORT_FAILED` 并以非零状态退出，不切换到模型验证分数。能力检查成功不等于个体输入具有临床适用性。

## 在受控程序中调用

```python
from python_service.workbench_client import WorkbenchClient

with WorkbenchClient('http://127.0.0.1:8084', timeout=15) as client:
    client.status()
    client.health()
    client.login_file(access_code_file)  # 调用方提供本机私有路径
    page = client.cases(state='READY', page=1, page_size=20)
    if page['items']:
        selected = [page['items'][0]['alias']]
        submitted = client.submit(selected, request_key=business_request_key)
        job_id = submitted['job']['id']
        job = client.wait(job_id, timeout=90)
        for item in job['cases']:
            if item['status'] == 'COMPLETED':
                result = client.result(job_id, item['alias'])
                # 仅在授权程序内存中消费；不要print、写日志或自动落盘。
```

示例中的 `access_code_file` 与 `business_request_key` 由调用方提供。请求键为8—80位字母、数字、下划线或连字符，每个业务请求固定一个键。每批最多10名且不能重复，只接受已有受控别名，不接受原ID、任意字段、CSV或患者自填分数。只有目录状态READY的记录才可能通过服务端资格检查，目录可浏览不等于可预测。

调用前应保留 `job_id`。`wait` 返回真实终态COMPLETED/PARTIAL/FAILED/CANCELLED；只读取逐对象状态COMPLETED的结果。可用 `job(job_id)` 继续查询，`cancel(job_id)` 显式取消。客户端不推断或伪造实时阶段进度。

## 复用的既有接口

客户端在同一服务复用 `/api/workbench/v4/` 下的 `status`、`health`、`session`、`real/cases`、`jobs`、`jobs/{id}`、`jobs/{id}/cancel`、`jobs/{id}/results/{alias}` 和 `logout`。没有新增HTTP路径、修改旧DTO或替换旧模型标识。

会话使用原HttpOnly Cookie机制；POST设置服务要求的精确同源Origin。客户端的本机能力不表示允许浏览器绕过Java业务权限：研究浏览器仍调用Java，由开发人员实现角色、对象范围、复核与业务持久化。访问码也不是多用户或机器身份授权方案；每个客户端独占自己的研究会话，单实例不用于并发线程。此阶段没有交付Java适配器或跨机服务。

## 错误与重试

`ClientError` 只提供安全的 `code`、`http_status` 和 `request_outcome_unknown`，不会带回服务端任意错误正文。格式/输入错误在调用前或服务端拒绝；401清空本地会话和已知任务，409冲突不更换键重跑，429/503不自动重试。

POST期间断线或网络超时，`request_outcome_unknown=True` 表示请求可能已到达服务端。仍处同一有效会话时，以相同请求键和相同别名列表重试提交，让原服务处理幂等；不能自动生成新键。`wait`超时不代表任务已取消，也不自动再次提交，可继续查询或显式取消。会话过期/服务重启后不能承诺跨会话幂等或恢复原真实任务。

正常退出上下文时确认注销；如果注销本身失败而没有其他异常，客户端抛出错误并清除本地凭证。已有异常时保留原异常，仍尝试注销，但不承诺服务端会话已关闭。注销不会擦除调用方已经持有的Python结果对象，也不承诺擦除服务进程的共享计算缓存。

## 结果校验及边界

客户端核对批量/计算契约、对象与模型身份、24个月窗口、任务进度一致性、两类累计发生率的有限性和范围、五来源贡献及重构误差。重构容差为1e-12；重复/非有限JSON和身份不匹配结果拒绝交付调用方。

当前模式仅 `REAL_RETROSPECTIVE_RESEARCH`，现有目录5008名、既有受控投影50名。历史可获得日期与样本外身份仍未知，个体临床适用性、临床概率准确性和因果效应没有认证。客户端要求这些原有边界保持，不从数值校验推定科学有效性。真实结果不提供导出，真实任务在重启或会话退出后失效，不改变这些限制。

## 本阶段验收与后续

本仓库完整Python/契约回归命令：

```powershell
python -B -m unittest discover -s tests -v
```

新增构造HTTP测试覆盖正常会话、幂等冲突、401、超时、取消、错误终态、重定向、异常响应、身份/概率/贡献重构和研究边界；不依赖患者数据或保存模型。实际研究系统调用须另在授权主机完成，不能把构造测试算作真实模型或Java联调验收。

按阶段继续完善：先完成本客户端及既有投影实际调用；后续整理供Java开发人员实现的调用验收场景、结果映射与报告字段，再按需求讨论数据接入和模型比较的缺项。共同HTTP契约变更要先协调调用方；不以扩展研究模式的名义放开合成验证接口。每阶段通过检查后提交本地Git，推送远端另按用户明确指令执行。
