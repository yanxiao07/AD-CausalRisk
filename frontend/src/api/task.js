import request from './request.js'
import { MOCK_SUBJECTS, mockDelay } from '../mock/data.js'

const USE_MOCK = import.meta.env.VITE_USE_MOCK !== 'false'

// 幂等键：每次创建任务生成唯一值
function newIdempotencyKey() {
  return `task-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

// ---- Mock 数据 ----
function mockTask(subjectId, status, creationOffsetMs) {
  const subject = MOCK_SUBJECTS.find(s => s.id === subjectId) || { subjectCode: `SUB-${subjectId}` }
  const now = Date.now()
  return {
    taskId: now + creationOffsetMs,
    subjectId,
    subjectCode: subject.subjectCode,
    status,
    runId: 'run-' + (now + creationOffsetMs),
    assessmentId: status === 'COMPLETED' ? now + creationOffsetMs : null,
    errorCode: null,
    message: null,
    createdAt: new Date(now + creationOffsetMs).toISOString(),
    updatedAt: new Date(now + creationOffsetMs).toISOString(),
    stages: status === 'COMPLETED'
      ? [
          { stage: 'FEATURE_EXTRACTION', status: 'DONE', durationMs: 420, summary: { count: 18 } },
          { stage: 'MODEL_INFERENCE', status: 'DONE', durationMs: 610, summary: { model: 'cox-ad-v2' } },
          { stage: 'RISK_SCORE', status: 'DONE', durationMs: 240, summary: { score: 0.82 } },
          { stage: 'INTERPRETATION', status: 'DONE', durationMs: 380, summary: { stages: 4 } }
        ]
      : []
  }
}

const MOCK_TASKS = [
  mockTask(1, 'COMPLETED', -7200000),
  mockTask(2, 'RUNNING', -540000),
  mockTask(3, 'PENDING', -180000)
]

function mockStageSummary(summary) {
  if (!summary) return ''
  const entries = Object.entries(summary).map(([k, v]) => `${k}: ${v}`)
  return entries.join('，')
}

function buildMockDetail(task) {
  return {
    assessmentId: task.assessmentId,
    subjectId: task.subjectId,
    subjectCode: task.subjectCode,
    taskId: task.taskId,
    riskScore: 0.82,
    reviewStatus: 'PENDING',
    createdAt: task.createdAt,
    agentResult: {
      fourStageExplanation: [
        '受试者携带 APOE E4 基因型，42 个月内发生 AD 的基线风险较高（中位数风险 0.82）。',
        '认知量表得分（MMSE 偏下降）提示轻度认知受损，与高风险相关。',
        '随访轨迹显示视空间执行功能区（CDR-SB）持续进展，强化风险信号。',
        '综合多模态特征，建议进入 AD 高风险专项随访队列并加强年度评估。'
      ],
      stages: task.stages
    }
  }
}

export async function getTaskList(params = {}) {
  const { page = 1, pageSize = 10, status } = params
  // 后端暂未提供 GET /api/assessment-tasks 列表接口（只有 POST 创建、/{taskId}、/assessments/{id}），
  // 此处始终走 mock 兜底避免 405。后端补齐列表接口后再切换为 request.get。
  await mockDelay(400)
  const filtered = status ? MOCK_TASKS.filter(t => t.status === status) : MOCK_TASKS
  const start = (page - 1) * pageSize
  const items = filtered.slice(start, start + pageSize)
  return { items, total: filtered.length, page, pageSize }
}

export async function createAssessmentTask(data) {
  if (USE_MOCK) {
    await mockDelay(500)
    const task = mockTask(data.subjectId, 'RUNNING', 0)
    MOCK_TASKS.unshift(task)
    return task
  }
  return request.post('/assessment-tasks', data, {
    headers: { 'Idempotency-Key': newIdempotencyKey() }
  })
}

export async function getAssessmentDetail(assessmentId) {
  const task = MOCK_TASKS.find(t => t.assessmentId === assessmentId)
  // 列表走 mock 时，详情须跟随 mock，否则会拿 mock 的时间戳 assessmentId 去请求真实后端
  // 导致 400。后端补齐列表后再接入真实 /assessments/{id}。
  await mockDelay(300)
  if (!task) {
    return Promise.reject({ code: 404, errorCode: 'NOT_FOUND', message: '评估结果不存在' })
  }
  return buildMockDetail(task)
}