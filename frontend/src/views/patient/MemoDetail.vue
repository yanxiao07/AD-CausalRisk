<template>
  <div class="memo-detail">
    <section class="hcard hcard--memo" aria-labelledby="detail-title">
      <div class="hcard__heading">
        <button class="back-link" type="button" @click="router.push('/patient/memos')">← 返回备忘列表</button>
      </div>

      <div v-if="viewState === 'loading'" class="hcard__note">正在读取…</div>
      <div v-else-if="viewState === 'degraded'" class="hcard__note">
        暂时无法读取。
        <el-button text @click="loadAll">重新读取</el-button>
      </div>
      <template v-else-if="memo">
        <h1 id="detail-title" class="hcard__label">备忘详情</h1>

        <div v-if="!editingContent" class="memo-original">
          <p class="memo-full-text">{{ memo.content }}</p>
          <p class="memo-time">记录于 {{ formatTime(memo.createdAt) }}</p>
          <div class="inline-actions">
            <el-button size="large" @click="startEdit">修改文字</el-button>
            <el-button size="large" type="danger" plain @click="confirmDelete">删除备忘</el-button>
          </div>
        </div>

        <div v-else class="memo-editor">
          <el-input v-model="editContent" type="textarea" :rows="6" maxlength="1000" />
          <p class="dialog-hint">{{ editContent.length }} / 1000</p>
          <div class="inline-actions">
            <el-button size="large" @click="cancelEdit">取消</el-button>
            <el-button size="large" type="primary" :loading="saving" @click="saveEdit">保存</el-button>
          </div>
        </div>
      </template>
    </section>

    <section v-if="memo && !editingContent" class="hcard hcard--cards" aria-labelledby="cards-title">
      <div class="hcard__heading">
        <h2 id="cards-title" class="hcard__label">复习卡片</h2>
        <span v-if="cards.length > 0" class="cards-count">共 {{ cards.length }} 张</span>
      </div>

      <div v-if="cardsState === 'loading'" class="hcard__note">正在读取卡片…</div>
      <div v-else-if="cards.length === 0">
        <p class="hcard__note">还没有复习卡片。生成卡片后，可以像翻单词卡一样复习。</p>
        <el-button type="primary" size="large" :loading="generating" @click="generateCards">
          生成记忆卡片
        </el-button>
      </div>
      <template v-else>
        <div v-if="reviewIndex >= 0 && reviewIndex < cards.length" class="review-stage">
          <p class="review-progress">第 {{ reviewIndex + 1 }} / {{ cards.length }} 张</p>

          <div class="flashcard" :class="{ flipped: showAnswer }" @click="showAnswer = !showAnswer">
            <div class="flashcard-inner">
              <div class="flashcard-face flashcard-face--front">
                <p class="card-label">正面（问题）</p>
                <p class="card-text">{{ currentCard.frontText }}</p>
                <p class="card-hint">点击卡片看答案</p>
              </div>
              <div class="flashcard-face flashcard-face--back">
                <p class="card-label">背面（答案）</p>
                <p class="card-text">{{ currentCard.backText }}</p>
              </div>
            </div>
          </div>

          <div class="card-state-indicator">
            <span class="state-tag" :class="'state-tag--' + currentCard.cardState">{{ stateLabel(currentCard.cardState) }}</span>
            <span v-if="currentCard.reviewCount > 0" class="review-count">已复习 {{ currentCard.reviewCount }} 次</span>
          </div>

          <div class="review-actions">
            <el-button size="large" @click="prevCard">上一张</el-button>
            <el-button size="large" type="danger" @click="markRetry">再看一次</el-button>
            <el-button size="large" type="primary" @click="markGotIt">记住了</el-button>
            <el-button size="large" @click="nextCard">下一张</el-button>
          </div>

          <details class="view-original">
            <summary>查看备忘原文</summary>
            <p class="original-text">{{ memo.content }}</p>
          </details>
        </div>
        <div v-else class="review-done">
          <p class="hcard__value">这一轮看完啦！</p>
          <p class="hcard__note">记住的卡片会安排下次复习，忘记的卡片会优先出现。</p>
          <el-button type="primary" size="large" @click="startReview">从头再来一遍</el-button>
        </div>

        <div class="manage-cards" style="margin-top: 24px;">
          <el-button size="large" plain :loading="generating" @click="generateCards">重新生成卡片</el-button>
          <el-button size="large" plain @click="toggleList = !toggleList">
            {{ toggleList ? '收起卡片列表' : '查看全部卡片' }}
          </el-button>
        </div>

        <ul v-if="toggleList" class="card-list">
          <li v-for="card in cards" :key="card.id" class="card-list-item" @click="jumpTo(card.id)">
            <div class="card-list-front">{{ card.frontText }}</div>
            <div class="card-list-back">{{ card.backText }}</div>
            <span class="state-tag" :class="'state-tag--' + card.cardState">{{ stateLabel(card.cardState) }}</span>
          </li>
        </ul>
      </template>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getMyMemos,
  updateMemo,
  deleteMemo,
  generateCards as apiGenerateCards,
  getCardsForMemo,
  reviewCard,
} from '@/api/patients.js'

const route = useRoute()
const router = useRouter()
const memoId = computed(() => Number(route.params.memoId))

const memo = ref(null)
const viewState = ref('loading')
const cards = ref([])
const cardsState = ref('loading')
const editingContent = ref(false)
const editContent = ref('')
const saving = ref(false)
const generating = ref(false)

const reviewIndex = ref(-1)
const showAnswer = ref(false)
const toggleList = ref(false)

const currentCard = computed(() => cards.value[reviewIndex.value] || cards.value[0])

function formatTime(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function stateLabel(s) {
  return { NEW: '新卡片', REVIEW: '复习中', MASTERED: '已掌握' }[s] || s
}

async function loadAll() {
  viewState.value = 'loading'
  try {
    const memos = await getMyMemos()
    memo.value = memos.find(m => m.id === memoId.value) || null
    viewState.value = memo.value ? 'ready' : 'degraded'
  } catch {
    viewState.value = 'degraded'
  }
  await loadCards()
}

async function loadCards() {
  cardsState.value = 'loading'
  try {
    cards.value = await getCardsForMemo(memoId.value)
    cardsState.value = 'ready'
  } catch {
    cardsState.value = 'degraded'
  }
  if (cards.value.length > 0 && reviewIndex.value < 0) {
    startReview()
  }
}

function startEdit() {
  editContent.value = memo.value.content
  editingContent.value = true
}

function cancelEdit() {
  editingContent.value = false
  editContent.value = ''
}

async function saveEdit() {
  const text = editContent.value.trim()
  if (!text) {
    ElMessage.warning('内容不能为空')
    return
  }
  saving.value = true
  try {
    memo.value = await updateMemo(memoId.value, text)
    editingContent.value = false
    ElMessage.success('已保存')
  } catch {
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  try {
    await ElMessageBox.confirm('确定要删除这条备忘吗？卡片也会一起删除。', '删除备忘', {
      confirmButtonText: '删除',
      cancelButtonText: '再想想',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await deleteMemo(memoId.value)
    ElMessage.success('已删除')
    router.push('/patient/memos')
  } catch {
  }
}

async function generateCards() {
  generating.value = true
  try {
    cards.value = await apiGenerateCards(memoId.value)
    reviewIndex.value = 0
    showAnswer.value = false
    ElMessage.success(`生成了 ${cards.value.length} 张卡片`)
  } catch {
  } finally {
    generating.value = false
  }
}

function startReview() {
  reviewIndex.value = 0
  showAnswer.value = false
}

function nextCard() {
  if (cards.value.length === 0) return
  showAnswer.value = false
  reviewIndex.value = (reviewIndex.value + 1) % cards.value.length
}

function prevCard() {
  if (cards.value.length === 0) return
  showAnswer.value = false
  reviewIndex.value = (reviewIndex.value - 1 + cards.value.length) % cards.value.length
}

function jumpTo(cardId) {
  const idx = cards.value.findIndex(c => c.id === cardId)
  if (idx >= 0) {
    reviewIndex.value = idx
    showAnswer.value = false
  }
}

async function markGotIt() {
  if (!currentCard.value) return
  try {
    const updated = await reviewCard(memoId.value, currentCard.value.id, 'GOT_IT')
    const idx = cards.value.findIndex(c => c.id === updated.id)
    if (idx >= 0) cards.value[idx] = updated
    ElMessage.success('记住了！下次可以更久再复习')
    nextCard()
  } catch {
  }
}

async function markRetry() {
  if (!currentCard.value) return
  try {
    const updated = await reviewCard(memoId.value, currentCard.value.id, 'AGAIN')
    const idx = cards.value.findIndex(c => c.id === updated.id)
    if (idx >= 0) cards.value[idx] = updated
    ElMessage.info('再看一次！这张会优先出现')
    showAnswer.value = false
  } catch {
  }
}

onMounted(loadAll)
</script>

<style scoped>
.memo-detail {
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

.hcard--cards {
  background: linear-gradient(150deg, #ffffff 0%, #eff6ff 100%);
  border-color: rgba(59, 130, 246, 0.22);
}

.hcard__heading {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.back-link {
  background: none;
  border: none;
  font-size: 1rem;
  color: var(--brand-700);
  cursor: pointer;
  padding: 0;
}

.hcard__label {
  margin: 0;
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--ink);
}

.hcard__value {
  margin: 0;
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--ink);
}

.hcard__note {
  margin: 12px 0;
  font-size: 1.125rem;
  color: var(--muted);
  line-height: 1.6;
}

.cards-count {
  font-size: 1rem;
  color: var(--muted);
}

.memo-full-text {
  font-size: 1.25rem;
  line-height: 1.7;
  white-space: pre-wrap;
  margin: 0 0 8px;
}

.memo-time {
  font-size: 0.9rem;
  color: var(--muted);
  margin: 0 0 16px;
}

.inline-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 16px;
}

.dialog-hint {
  text-align: right;
  font-size: 0.9rem;
  color: var(--muted);
  margin: 8px 0 0;
}

.review-stage {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 16px;
}

.review-progress {
  font-size: 1.125rem;
  color: var(--muted);
  margin: 0;
}

.flashcard {
  perspective: 1200px;
  cursor: pointer;
  margin: 16px 0;
  min-height: 260px;
}

.flashcard-inner {
  position: relative;
  width: 100%;
  min-height: 260px;
  transition: transform 0.6s;
  transform-style: preserve-3d;
}

.flashcard.flipped .flashcard-inner {
  transform: rotateY(180deg);
}

.flashcard-face {
  position: absolute;
  inset: 0;
  backface-visibility: hidden;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  padding: 32px 24px;
  background: #fff;
  border: 2px solid var(--brand-700);
  border-radius: 16px;
  text-align: center;
  box-shadow: var(--shadow-card);
}

.flashcard-face--back {
  transform: rotateY(180deg);
  background: linear-gradient(150deg, #fff, #f0fdf4);
  border-color: #10b981;
}

.card-label {
  font-size: 0.95rem;
  color: var(--muted);
  margin: 0 0 16px;
  text-transform: uppercase;
  letter-spacing: 1px;
}

.card-text {
  font-size: clamp(1.375rem, 3vw, 1.75rem);
  font-weight: 700;
  line-height: 1.5;
  color: var(--ink);
  margin: 0;
  overflow-wrap: anywhere;
}

.card-hint {
  font-size: 0.95rem;
  color: var(--muted);
  margin: 16px 0 0;
}

.card-state-indicator {
  display: flex;
  align-items: center;
  gap: 12px;
  justify-content: center;
}

.state-tag {
  display: inline-block;
  padding: 4px 12px;
  border-radius: 999px;
  font-size: 0.9rem;
  font-weight: 600;
}

.state-tag--NEW { background: #fef3c7; color: #92400e; }
.state-tag--REVIEW { background: #dbeafe; color: #1e40af; }
.state-tag--MASTERED { background: #d1fae5; color: #065f46; }

.review-count {
  font-size: 0.95rem;
  color: var(--muted);
}

.review-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
  flex-wrap: wrap;
  margin-top: 16px;
}

.review-actions .el-button {
  min-width: 120px;
  min-height: 52px;
  font-size: 1.125rem;
}

.view-original {
  margin-top: 16px;
  padding: 12px;
  background: rgba(0,0,0,0.02);
  border-radius: 8px;
}

.view-original summary {
  cursor: pointer;
  color: var(--brand-700);
  font-weight: 600;
  padding: 4px 0;
}

.original-text {
  margin: 8px 0 0;
  font-size: 1rem;
  line-height: 1.6;
  color: var(--muted);
  white-space: pre-wrap;
}

.review-done {
  text-align: center;
  padding: 40px 0;
}

.card-list {
  list-style: none;
  padding: 0;
  margin: 16px 0 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.card-list-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 16px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 8px;
  cursor: pointer;
}

.card-list-item:hover {
  border-color: var(--brand-700);
}

.card-list-front {
  font-weight: 700;
  color: var(--ink);
}

.card-list-back {
  color: var(--muted);
  font-size: 0.95rem;
}
</style>