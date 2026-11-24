<template>
  <div class="memos-page">
    <section class="hcard hcard--memo" aria-labelledby="memos-title">
      <div class="hcard__heading">
        <button class="back-link" type="button" @click="router.push('/patient')">← 返回首页</button>
      </div>
      <h1 id="memos-title" class="hcard__label">我的备忘</h1>
      <p class="hcard__note">记下想记住的事，随时可以再看。</p>

      <el-button class="new-memo-btn" type="primary" size="large" :icon="Plus" @click="openNewMemo">
        记一件事
      </el-button>

      <div v-if="dueCount > 0" class="review-tip" role="status">
        <p class="review-tip__text">今天有 {{ dueCount }} 张卡片该复习了。</p>
        <el-button type="primary" size="large" @click="openMemoById(dueMemoId)">去复习</el-button>
      </div>

      <div v-if="loadState === 'loading'" class="hcard__note" role="status">正在读取备忘…</div>
      <div v-else-if="loadState === 'degraded'" class="hcard__note" role="alert">
        暂时无法读取备忘。
        <el-button text class="hcard__retry" @click="loadMemos">重新读取</el-button>
      </div>
      <div v-else-if="memos.length === 0" class="empty-tip">
        <p>还没有备忘。</p>
        <p class="hcard__note">点上面的「记一件事」，把想记住的事写下来。</p>
      </div>
      <ul v-else class="memo-list">
        <li v-for="memo in memos" :key="memo.id" class="memo-item">
          <button type="button" class="memo-content" @click="openMemo(memo)">
            <p class="memo-text">{{ memo.content }}</p>
            <p class="memo-time">{{ formatTime(memo.createdAt) }}</p>
          </button>
          <div class="memo-actions">
            <el-button size="large" text @click="openMemo(memo)">查看 / 生成卡片</el-button>
          </div>
        </li>
      </ul>
    </section>

    <el-dialog v-model="newMemoOpen" title="记一件事" width="90%" :close-on-click-modal="false"
      class="memo-dialog" append-to-body>
      <template #default>
        <el-input v-model="newMemoContent" type="textarea" :rows="6" maxlength="1000"
          placeholder="写下想记住的事，比如：每周三下午和小李在社区活动室下棋。" />
        <p class="dialog-hint">{{ newMemoContent.length }} / 1000</p>
      </template>
      <template #footer>
        <el-button size="large" @click="newMemoOpen = false">取消</el-button>
        <el-button type="primary" size="large" :loading="saving" @click="saveNewMemo">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { getMyMemos, createMemo, getDueCards } from '@/api/patients.js'

const router = useRouter()
const memos = ref([])
const loadState = ref('loading')
const newMemoOpen = ref(false)
const newMemoContent = ref('')
const saving = ref(false)
const dueCount = ref(0)
const dueMemoId = ref(null)

function formatTime(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function loadMemos() {
  loadState.value = 'loading'
  try {
    const [list, due] = await Promise.all([
      getMyMemos(),
      getDueCards().catch(() => [])
    ])
    memos.value = list
    dueCount.value = due.length
    dueMemoId.value = due.length > 0 ? due[0].memoId : null
    loadState.value = 'ready'
  } catch {
    loadState.value = 'degraded'
  }
}

function openNewMemo() {
  newMemoContent.value = ''
  newMemoOpen.value = true
}

async function saveNewMemo() {
  const text = newMemoContent.value.trim()
  if (!text) {
    ElMessage.warning('请写下想记住的事')
    return
  }
  saving.value = true
  try {
    const memo = await createMemo(text)
    newMemoOpen.value = false
    newMemoContent.value = ''
    memos.value.unshift(memo)
    ElMessage.success('已保存')
  } catch {
  } finally {
    saving.value = false
  }
}

function openMemo(memo) {
  router.push({ name: 'patient-memo-detail', params: { memoId: memo.id } })
}

function openMemoById(memoId) {
  if (!memoId) return
  router.push({ name: 'patient-memo-detail', params: { memoId } })
}

onMounted(loadMemos)
</script>

<style scoped>
.memos-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.hcard {
  padding: 28px 24px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-card);
}

.hcard--memo {
  background: linear-gradient(150deg, #ffffff 0%, #f0fdf4 100%);
  border-color: rgba(16, 185, 129, 0.22);
}

.hcard__heading {
  margin-bottom: 12px;
}

.back-link {
  background: none;
  border: none;
  font-size: 1rem;
  color: var(--brand-700);
  cursor: pointer;
  padding: 0;
}

.back-link:hover {
  text-decoration: underline;
}

.hcard__label {
  margin: 0 0 12px;
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--ink);
}

.hcard__note {
  margin: 12px 0;
  font-size: 1.125rem;
  color: var(--muted);
}

.new-memo-btn {
  min-height: 56px;
  margin: 16px 0;
}

.review-tip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 20px 24px;
  background: linear-gradient(150deg, #ffffff 0%, #ecfdf5 100%);
  border: 1px solid rgba(16, 185, 129, 0.35);
  border-radius: var(--r-xl);
}

.review-tip__text {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--ink);
}

.empty-tip {
  text-align: center;
  padding: 40px 0;
}

.memo-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.memo-item {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 12px;
  overflow: hidden;
}

.memo-content {
  display: block;
  width: 100%;
  padding: 16px;
  text-align: left;
  background: none;
  border: none;
  cursor: pointer;
}

.memo-content:hover {
  background: rgba(16, 185, 129, 0.05);
}

.memo-text {
  margin: 0 0 8px;
  font-size: 1.25rem;
  line-height: 1.6;
  color: var(--ink);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.memo-time {
  margin: 0;
  font-size: 0.9rem;
  color: var(--muted);
}

.memo-actions {
  padding: 8px 16px 16px;
  display: flex;
  justify-content: flex-end;
}

.dialog-hint {
  text-align: right;
  font-size: 0.9rem;
  color: var(--muted);
  margin: 8px 0 0;
}

:deep(.memo-dialog .el-textarea__inner) {
  font-size: 1.25rem;
}
</style>