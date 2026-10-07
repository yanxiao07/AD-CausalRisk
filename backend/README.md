# Java后端负责人任务

在此目录创建Spring Boot工程，负责数据库、权限、业务任务与审计；Python调用封装在AgentClient中。

先阅读[需求](../docs/Course_SRS_V3_2.md)、[设计参考](../docs/Design_Guide.md)、[目标接口](../contracts/java-business.target.openapi.json)及[AI协作指南](../docs/AI_Collaboration.md)。首次PR建议完成工程配置、数据库迁移与档案接口；后续依次完成登录与对象权限、访视/特征、导入、质控、持久化幂等任务、AgentClient、结果复核、随访、报告和审计。

交付包含Maven/Gradle锁定构建方式、数据库建表/迁移、合成账号与密码哈希初始化、HTTP接口测试、Mock Python超时/版本错/409/BLOCKED测试、重启历史查询和患者字段过滤。添加自己的CI；未经实现与验收的模块保持待开发。

在模块README补充Java/数据库版本、环境变量示例、迁移、启动、测试命令及接口地址。参数通过配置读取，密码使用环境变量。API沿用共同契约，业务逻辑放Service，Controller处理DTO与HTTP映射，Repository处理数据访问。
