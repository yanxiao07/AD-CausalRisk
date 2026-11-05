// Detail copy for the four 3D labels on the login page.
//
// The four labels mirror the system's four stages (数据融合 → 因果推断 → 风险预测 → 个体解释).
// Each entry is a small "exhibit": capability / highlights / tags / metrics / note.
//
// HONESTY RULES for this file (customer-facing, and easy to overclaim):
//   * 6–60 months is the *accepted request range*; the synthetic rule set only scores 24 months.
//   * riskScore is a synthetic demo score, not a calibrated clinical probability.
//   * the graph is ONE shared prior graph plus per-subject observed/missing flags — not an
//     independent individual causal graph.
//   * explanations are PREDICTIVE_DEMO attributions, not population-level causal attribution.
//   * nothing here is a diagnostic basis: all of it is decision support pending doctor review.

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
      { label: '非法行拦截', value: '100', unit: '%', note: '预检不通过的行不入库' },
      { label: '错误追溯', value: '行级', unit: '', note: '行号 + 字段 + 原因' }
    ],
    note: '所有数据在授权范围内去标识化聚合；预检不通过的行不会入库，可回溯到具体行号。'
  },
  causal: {
    index: '02',
    title: '因果推断',
    en: 'Causal Inference',
    desc: '在共享的先验医学图谱上叠加个体观测，标出已观测与缺失节点，给出可检查的条件关联通路。',
    capability: '把共享先验知识与个体观测对齐，让"相关"变成可以逐条检查的条件通路。',
    highlights: [
      {
        icon: 'route',
        title: '共享先验图 + 个体标注',
        desc: '同一份先验图谱服务所有受试者；个体结果只改变节点的观测状态（ACTIVE / MISSING），不生成独立因果图。'
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
    tags: ['共享先验', '个体观测', '风险因素', '条件通路'],
    metrics: [
      { label: '证据等级', value: '2', unit: '级', note: 'PRIOR 先验 / MODEL 模型' },
      { label: '关联方向', value: '条件', unit: '性', note: 'direction = CONDITIONAL' },
      { label: '缺失处理', value: '显式', unit: '标注', note: 'MISSING 节点 + 待补边' }
    ],
    note: '图谱是共享先验结构，个体差异体现在节点观测状态上；连线表示条件性关联而非病因证明。'
  },
  risk: {
    index: '03',
    title: '风险预测',
    en: 'Risk Prediction',
    desc: '调用 DeepAgent 合成规则，结合个体特征与图谱证据，输出可解释的风险分层与预警等级（演示用途）。',
    capability: '在合成演示数据上跑通四阶段流程，给出可复核的风险分层，而不是一个临床可用的概率。',
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
    tags: ['DeepAgent', '合成演示', '风险分层', '预警等级'],
    metrics: [
      { label: '模型版本', value: 'v1', unit: '', note: 'deepagent-causal-warning-v1' },
      { label: '出分窗口', value: '24', unit: '个月', note: '合成规则只对该窗口出分' },
      { label: '风险分层', value: '3+1', unit: '档', note: '低 / 中 / 高 + BLOCKED' }
    ],
    note: '接口接受 6–60 个月的请求，但当前合成规则只对 24 个月给出评分；分数是演示用途的合成值，不是校准后的临床概率。'
  },
  explain: {
    index: '04',
    title: '个体解释',
    en: 'Individual Explanation',
    desc: '展示各特征对本次预测的贡献方向与幅度，为医生复核提供结构化依据，不构成诊断结论。',
    capability: '把模型判断拆成医生读得懂、还能复核的证据链。',
    highlights: [
      {
        icon: 'chart',
        title: '预测贡献，不是因果归因',
        desc: '逐特征给出方向（提升 / 降低 / 中性）与贡献度，属于预测性归因（PREDICTIVE_DEMO），不是群体因果效应。'
      },
      {
        icon: 'medal',
        title: '来源可辨',
        desc: '解释明确标注 PREDICTIVE_DEMO，与先验图谱里的 PRIOR 证据分开呈现。'
      },
      {
        icon: 'shield',
        title: '医学伦理边界',
        desc: '结果仅作决策辅助，不构成诊断；最终结论以医生人工复核为准。'
      }
    ],
    tags: ['预测贡献', '预测性归因', '结构化依据', '医学伦理'],
    metrics: [
      { label: '归因来源', value: '预测性', unit: '', note: 'PREDICTIVE_DEMO，非群体归因' },
      { label: '解释字段', value: '结构化', unit: '', note: '特征 / 方向 / 解释文本' },
      { label: '使用边界', value: '辅助', unit: '', note: '不构成诊断，需医生复核' }
    ],
    note: '解释来自合成演示模型的预测性归因，不代表真实群体归因，也不能作为诊断依据。'
  }
}
