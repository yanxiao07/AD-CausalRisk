// Detail copy for the three 3D labels, lifted verbatim from the demo.

export const exploreDetails = {
    fusion: {
      index: '01',
      title: '多模态数据融合',
      desc: '把影像、量表、生物标志物与纵向随访组织成一条可追溯的证据链，让每一次判断都建立在完整上下文之上。',
      tags: ['MRI / PET', '认知量表', '生物标志物', '纵向随访'],
      metrics: [
        { label: 'Data streams', value: '12', note: '结构化模态' },
        { label: 'Signal coverage', value: '98.6%', note: '关键字段完整度' },
        { label: 'Sync latency', value: '< 1.2s', note: '更新到视图' }
      ],
      note: '所有数据均在授权范围内去标识化聚合，并支持按身份与机构范围审计。'
    },
    causal: {
      index: '02',
      title: '因果证据图谱',
      desc: '用有向无环图描述风险因素、保护因素与结局之间的因果通路，把相关性提升为可验证、可解释的临床证据。',
      tags: ['因果 DAG', '反事实推理', '证据分级', '可解释性'],
      metrics: [
        { label: 'Causal nodes', value: '186', note: '变量与协变量' },
        { label: 'Pathways', value: '41', note: '显著因果通路' },
        { label: 'Confidence', value: '0.93', note: '图谱置信度' }
      ],
      note: '图谱中的每条边都保留来源、强度和证据等级，可回溯至原始研究或队列。'
    },
    risk: {
      index: '03',
      title: '个体风险预测',
      desc: '结合基线特征、影像表型与时间序列变化，给出可解释的个体风险轨迹与随访窗口建议。',
      tags: ['纵向建模', '风险分层', '随访建议', '决策支持'],
      metrics: [
        { label: 'Model AUC', value: '0.91', note: '内部验证队列' },
        { label: 'Horizon', value: '24 mo', note: '预测时间窗' },
        { label: 'Strata', value: '3', note: '低 / 中 / 高风险' }
      ],
      note: '预测结果用于临床决策支持，不替代医生判断；风险分层会随新数据持续更新。'
    }
  };
