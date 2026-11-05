// Detail copy for the four 3D labels on the login page.
//
// The four labels mirror the system's four core stages (数据融合 → 因果推断 → 风险预测 → 个体解释).
// Each entry is a small "exhibit":
//   capability  — one sentence on the business value
//   highlights  — 2–3 concrete mechanisms (icon key + title + explanation)
//   tags        — short keywords rendered as chips
//   metrics     — verified product facts (template count, window range, evidence levels...)
//
// `metrics` deliberately spells out *implemented behaviour* rather than demo numbers: the login
// drawer is customer-facing, and an invented "98.6%" would read like a real statistic.

export const exploreDetails = {
  fusion: {
    index: '01',
    title: '数据融合',
    en: 'Data Fusion',
    desc: '接入多源临床队列与组学数据，完成自动化的质控与缺失值处理，构建高质量的研究基底。',
    capability: '把多源、多格式的临床数据收敛成一份可信、可追溯的研究基底。',
    highlights: [
      {
        icon: 'layers',
        title: '三类模板，逐行校验',
        desc: 'subjects / visits / features 三类 CSV 模板，错误精确定位到行号与字段。'
      },
      {
        icon: 'bolt',
        title: '幂等导入，不重复入库',
        desc: '每次上传携带 Idempotency-Key，重复提交只会命中同一批次。'
      },
      {
        icon: 'shield',
        title: '授权即边界',
        desc: '按角色与档案授权过滤数据，展示层全程去标识化。'
      }
    ],
    tags: ['多源队列', '自动质控', '缺失值处理', '研究基底'],
    metrics: [
      { label: '导入模板', value: '3', unit: '类', note: 'subjects / visits / features' },
      { label: '校验精度', value: '100', unit: '%', note: '非法行一律不入库' },
      { label: '错误追溯', value: '行级', unit: '', note: '行号 + 字段 + 原因' }
    ],
    note: '所有数据在授权范围内去标识化聚合；预检不通过的行不会入库，可回溯到具体行号。'
  },
  causal: {
    index: '02',
    title: '因果推断',
    en: 'Causal Inference',
    desc: '基于先验医学知识构建个体条件因果图谱，识别风险因素并生成可验证的因果通路。',
    capability: '用先验医学知识约束个体因果结构，让"相关"变成可以检查的通路。',
    highlights: [
      {
        icon: 'route',
        title: '一人一图',
        desc: '每位受试者生成独立条件因果图，节点区分已观测与缺失两种状态。'
      },
      {
        icon: 'medal',
        title: '证据分级',
        desc: '每条边标注 PRIOR（先验）或 MODEL（模型），来源不混淆。'
      },
      {
        icon: 'shield',
        title: '语义不越界',
        desc: '方向固定为 CONDITIONAL，界面明确"条件性关联，非病因判断"。'
      }
    ],
    tags: ['先验知识', '个体条件图', '风险因素', '因果通路'],
    metrics: [
      { label: '证据等级', value: '2', unit: '级', note: 'PRIOR 先验 / MODEL 模型' },
      { label: '关联方向', value: '条件', unit: '性', note: 'direction = CONDITIONAL' },
      { label: '缺失处理', value: '显式', unit: '标注', note: 'MISSING 节点 + 待补边' }
    ],
    note: '图谱中的连线表示条件性关联而非病因证明；每条边保留来源与证据等级，可回溯。'
  },
  risk: {
    index: '03',
    title: '风险预测',
    en: 'Risk Prediction',
    desc: '调用 DeepAgent 算法，结合个体特征与图谱关系，输出可解释的阿尔茨海默病风险评分与预警等级。',
    capability: '把个体特征与因果结构一起送进模型，输出可解释的风险概率与预警等级。',
    highlights: [
      {
        icon: 'bolt',
        title: '四阶段流水线',
        desc: '数据融合 → 因果图谱 → 预警模型 → 个体解释，每阶段耗时可见。'
      },
      {
        icon: 'shield',
        title: '阻断即不可用',
        desc: '数据不足返回 BLOCKED，riskScore 为 null，绝不以 0 或插值代替。'
      },
      {
        icon: 'layers',
        title: '全链路可追溯',
        desc: '结果携带 modelVersion / workflowVersion / runId，可回溯到具体运行。'
      }
    ],
    tags: ['DeepAgent', '个体特征', '风险评分', '预警等级'],
    metrics: [
      { label: '模型版本', value: 'v1', unit: '', note: 'deepagent-causal-warning-v1' },
      { label: '预测窗口', value: '6–60', unit: '个月', note: '默认 24 个月' },
      { label: '风险分层', value: '3+1', unit: '档', note: '低 / 中 / 高 + BLOCKED' }
    ],
    note: '风险评分为 0–1 的概率；被阻断时返回「不可用」而不是 0，也不做插值补分。'
  },
  explain: {
    index: '04',
    title: '个体解释',
    en: 'Individual Explanation',
    desc: '计算各项风险因子的边际贡献与群体归因，为医生生成结构化、符合医学伦理的诊断依据。',
    capability: '把模型判断拆成医生读得懂、还能复核的证据链。',
    highlights: [
      {
        icon: 'chart',
        title: '边际贡献可读',
        desc: '逐特征给出方向（提升 / 降低 / 中性）与贡献度，不打包成一个黑盒分数。'
      },
      {
        icon: 'medal',
        title: '归因来源可辨',
        desc: '解释标注 PREDICTIVE_DEMO，与真实因果效应保持区分。'
      },
      {
        icon: 'shield',
        title: '医学伦理边界',
        desc: '结果仅作决策支持，最终结论以医生人工复核为准。'
      }
    ],
    tags: ['边际贡献', '群体归因', '结构化依据', '医学伦理'],
    metrics: [
      { label: '归因来源', value: '预测性', unit: '', note: 'PREDICTIVE_DEMO 归因' },
      { label: '解释字段', value: '结构化', unit: '', note: '特征 / 方向 / 解释文本' },
      { label: '使用边界', value: '辅助', unit: '', note: '不构成诊断，需医生复核' }
    ],
    note: '解释用于临床决策支持，不替代医生判断；最终结论以人工复核意见为准。'
  }
}
