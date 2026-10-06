# Java后端负责人任务

此目录由后端同学独立创建完整Spring Boot工程。用户已有Java工程没有复制到这里，Python服务不会代替数据库、权限、业务任务或审计。

先阅读../docs/Course_SRS_V3_2.md与../contracts/java-business.target.openapi.json，再提交工程初始化和数据库迁移PR。完整负责登录、四角色与对象范围、档案/访视/特征、导入预览确认、质控、持久化幂等任务、AgentClient、结果和业务复核、随访、报告、审计、失败恢复、部署及测试。

交付包含Maven/Gradle锁定构建方式、数据库建表/迁移、合成账号与密码哈希初始化、HTTP接口测试、Mock Python超时/版本错/409/BLOCKED测试、重启历史查询和患者字段过滤。添加自己的CI；未经实现与验收的模块保持待开发。
