import { defineStore } from 'pinia'
import { createAssessmentTask, getAssessmentTask } from '@/api/assessments.js'

const POLL_MS = 2000
const MAX_WAIT_MS = 5 * 60 * 1000
const TERMINAL = ['COMPLETED', 'BLOCKED', 'FAILED', 'CANCELLED']

/** 轮询定时器放在模块作用域，避免被 Pinia 的响应式包装。 */
let timer = null

function clearTimer() {
  if (timer) window.clearTimeout(timer)
  timer = null
}

/**
 * 批量发起评估。
 *
 * 后端没有"批量"端点，也没有任务列表端点，所以这里：
 *  - 逐个 POST /api/assessment-tasks（每个都带独立 Idempotency-Key）；
 *  - 逐条轮询 GET /api/assessment-tasks/{taskId} 直到终态；
 *  - 进度面板挂在 AppShell 上，切页面也不会丢。
 */
export const useBatchAssessmentStore = defineStore('batchAssessment', {
  state: () => ({
    running: false,
    visible: false,
    startedAt: 0,
    tasks: [], // { subjectId, taskId, status, errorCode, message }
    error: ''
  }),

  getters: {
    total: (state) => state.tasks.length,
    finished: (state) =>
      state.tasks.filter((task) => TERMINAL.includes(task.status)).length,
    failed: (state) => state.tasks.filter((task) => ['FAILED', 'CANCELLED'].includes(task.status)).length,
    blocked: (state) => state.tasks.filter((task) => task.status === 'BLOCKED').length,
    completed: (state) => state.tasks.filter((task) => task.status === 'COMPLETED').length,
    percent: (state) => {
      if (!state.tasks.length) return 0
      const done = state.tasks.filter((task) => TERMINAL.includes(task.status)).length
      return Math.round((done / state.tasks.length) * 100)
    },
    isAllFinished() {
      return this.tasks.length > 0 && this.finished === this.tasks.length
    },
    failures: (state) =>
      state.tasks.filter((task) => ['FAILED', 'CANCELLED', 'BLOCKED'].includes(task.status))
  },

  actions: {
    /** 发起一批评估；subjectIds 是受试者 id 数组。 */
    async start(subjectIds, predictionWindowMonths = 24) {
      if (this.running || !Array.isArray(subjectIds) || subjectIds.length === 0) return

      clearTimer()
      this.tasks = subjectIds.map((subjectId) => ({
        subjectId,
        taskId: null,
        status: 'PENDING',
        errorCode: '',
        message: ''
      }))
      this.running = true
      this.visible = true
      this.startedAt = Date.now()
      this.error = ''

      // 逐个创建；单个失败不影响其它受试者
      for (const task of this.tasks) {
        try {
          const created = await createAssessmentTask({
            subjectId: task.subjectId,
            predictionWindowMonths
          })
          task.taskId = created?.taskId ?? null
          task.status = created?.status ?? 'PENDING'
        } catch (problem) {
          task.status = 'FAILED'
          task.errorCode = problem?.errorCode || 'CREATE_FAILED'
          task.message = problem?.message || '创建任务失败'
        }
      }

      if (this.tasks.every((task) => TERMINAL.includes(task.status))) {
        this.stop()
        return
      }
      this.schedulePoll()
    },

    schedulePoll() {
      clearTimer()
      timer = window.setTimeout(() => this.poll(), POLL_MS)
    },

    async poll() {
      if (!this.running) return
      if (Date.now() - this.startedAt > MAX_WAIT_MS) {
        this.error = '等待超时：任务仍在后端运行，可稍后在档案抽屉里查看结果。'
        this.stop()
        return
      }

      const pending = this.tasks.filter(
        (task) => task.taskId && !TERMINAL.includes(task.status)
      )
      for (const task of pending) {
        try {
          const latest = await getAssessmentTask(task.taskId)
          task.status = latest?.status ?? task.status
          task.errorCode = latest?.errorCode || ''
          task.message = latest?.message || ''
        } catch (problem) {
          task.errorCode = problem?.errorCode || 'POLL_FAILED'
          task.message = problem?.message || '轮询失败'
        }
      }

      if (this.tasks.every((task) => !task.taskId || TERMINAL.includes(task.status))) {
        this.stop()
        return
      }
      this.schedulePoll()
    },

    stop() {
      clearTimer()
      this.running = false
    },

    dismiss() {
      this.visible = false
      if (!this.running) this.tasks = []
    }
  }
})
