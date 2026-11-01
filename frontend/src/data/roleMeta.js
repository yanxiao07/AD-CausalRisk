// Per-role copy (labels, CTA, help text) lifted from the reviewed demo.

export const roleMeta = {
    doctor: {
      label: '医生',
      title: '查看个体风险分层、因果解释与随访建议。',
      cta: '进入医生工作台',
      formTitle: '登录医生工作台',
      accountPlaceholder: '工号或机构邮箱',
      detail: '医生 · 临床风险与随访',
      note: '身份与机构权限绑定，仅展示授权范围内的数据。',
      help: '账号由所属机构统一开通。如需重置密码，请联系所在科室的信息联络员或平台管理员。'
    },
    researcher: {
      label: '研究员',
      title: '进入队列研究空间，追溯可解释的因果证据。',
      cta: '进入研究工作台',
      formTitle: '登录研究工作台',
      accountPlaceholder: '研究账号或机构邮箱',
      detail: '研究员 · 队列与因果分析',
      note: '身份与机构权限绑定，仅展示授权范围内的数据。',
      help: '研究账号由项目负责人开通。如需重置密码或调整权限，请联系项目管理员。'
    },
    admin: {
      label: '管理员',
      title: '管理成员权限与数据质量，维护研究规范。',
      cta: '进入管理工作台',
      formTitle: '登录管理工作台',
      accountPlaceholder: '管理员账号或工号',
      detail: '管理员 · 权限与数据质控',
      note: '身份与机构权限绑定，仅展示授权范围内的数据。',
      help: '管理员账号由平台统一维护。如需重置密码，请联系系统负责人。'
    },
    patient: {
      label: '患者',
      title: '查看个人风险档案、复诊提醒与健康教育。',
      cta: '进入患者档案',
      formTitle: '登录患者档案',
      accountPlaceholder: '就诊卡号或手机号',
      detail: '患者 · 个人风险档案',
      note: '仅展示本人的风险档案与随访信息。',
      help: '患者账号由建档医生或随访护士发放。如需找回，请联系为您建档的医生或随访护士。'
    }
  };
