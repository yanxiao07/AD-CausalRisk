# 团队协作与PR

后端和前端负责人各自完整实现自己的模块，并承担测试与交付。Python负责人维护python_service、fixtures和共同算法契约。修改共同契约先提交单独PR，说明字段、兼容影响、请求/响应样例与新增测试；不直接改别人模块以回避讨论。

共享仓库为https://github.com/yanxiao07/AD-CausalRisk。每人从最新main创建功能分支，通过PR进入main；仓库维护者分配写权限。没有写权限时，在本人Fork中开发并向本仓库提交PR。

```bash
git clone https://github.com/yanxiao07/AD-CausalRisk.git
cd AD-CausalRisk
git switch -c backend/auth-and-subjects
# 在backend中实现、运行自己模块的测试
git add backend
git commit -m "Add authentication and subject management"
git push -u origin backend/auth-and-subjects
```

然后创建PR，写明需求编号、实现范围、实际测试和截图。前端可用frontend/bootstrap或frontend/assessment-result等分支。评审通过后合并，再同步main。凭据、个人资料、虚拟环境、target和node_modules不提交。

以PR评审和CI检查作为合并条件；维护者在仓库设置中管理main保护。不要强制推送或直接覆盖共享分支。GitHub操作见[创建PR](https://docs.github.com/en/pull-requests/how-tos/create-pull-requests/creating-a-pull-request)。

当前CI覆盖Python与共同契约；backend/frontend增加代码后，对应负责人接入构建、数据库/接口和组件/页面测试。完整项目验收按Course_SRS_V3_2执行。

提交使用本人Git身份，按功能拆分。使用AI时遵循[AI协作指南](docs/AI_Collaboration.md)：先明确任务与文件范围，完成后检查差异、运行测试，再提交。

## 同步、冲突与继续开发

工作区有未提交改动时，先保存到本人功能分支。之后拉取main并合并到本人分支：

```bash
git fetch origin
git switch backend/auth-and-subjects
git merge origin/main
```

若出现冲突，逐文件理解双方意图，结合共同契约解决，再运行测试并提交；不要一律选择ours/theirs。需要暂停时，`git merge --abort`可撤销当前合并，保留合并前的分支状态。请勿用`git reset --hard`清理他人或未保存改动。

PR合并后，在干净工作区切到main，执行`git pull --ff-only origin main`；下一个功能从更新后的main新建分支。PR描述应列出测试的实际结果与未验证项，截图、日志和接口样例不能包含凭据或个人资料。
