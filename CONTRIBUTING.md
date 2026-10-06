# 团队协作与PR

后端和前端负责人各自完整实现自己的模块，并承担测试与交付。Python负责人维护python_service、fixtures和共同算法契约。修改共同契约先提交单独PR，说明字段、兼容影响、请求/响应样例与新增测试；不直接改别人模块以回避讨论。

共享仓库为https://github.com/yanxiao07/AD-CausalRisk。仓库拥有者添加团队成员写权限；每人从main创建功能分支，通过PR进入main。成员真实GitHub账号与权限由仓库所有者设置，本次源码上传不代设成员权限。

```bash
git clone https://github.com/yanxiao07/AD-CausalRisk.git
cd AD-CausalRisk
git switch -c backend/auth-and-subjects
# 在backend中实现、运行自己模块的测试
git add backend
git commit -m "Add authentication and subject management"
git push -u origin backend/auth-and-subjects
```

然后在GitHub创建PR，使用模板写明需求编号、实现范围、实际测试和截图。前端可用frontend/bootstrap或frontend/assessment-result等分支。评审通过后合并，再同步main。不要把整个科研工作区、真实数据、病例包、虚拟环境、target或node_modules提交进来。

仓库所有者在远端配置main评审和必要CI检查；私有仓库分支保护的可用性取决于GitHub账户/方案。缺少强制保护时仍遵循PR评审流程，不宣称保护已经配置。参考[GitHub共享仓库协作](https://docs.github.com/en/pull-requests/reference/pull-requests#shared-repository-model)与[分支保护说明](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)。

Python基底CI绿灯只代表已提供的算法子服务与契约通过。backend/frontend开始加入代码后，各负责人须把构建、数据库测试、组件/页面测试接入CI；完整项目验收按Course_SRS_V3_2进行，不能把两个目录README计作完成开发。

自动化交付提交署名为Codex local baseline，以标明整理来源；同学后续提交使用本人身份。仓库当前公开，但尚未指定开放源码许可证；公开可查看不自动授予再分发或商用许可，后续许可证由权利人确认。
