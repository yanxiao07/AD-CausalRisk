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
        <span v-if="previewMode && previewCards.length > 0" class="cards-count">共 {{ previewCards.length }} 张</span>
        <span v-else-if="!previewMode && cards.length > 0" class="cards-count">共 {{ cards.length }} 张</span>
      </div>

      <div v-if="cardsState === 'loading'" class="hcard__note">正在读取卡片…</div>
      <div v-else-if="previewMode" class="review-preview">
        <p class="hcard__value">新卡片已生成，请先确认内容</p>
        <p class="hcard__note">
          确认无误后再开始复习；如果有错，可以重新生成卡片。确认前旧卡片和复习进度都保持不变。
        </p>
        <ul class="card-list">
          <li v-for="(card, index) in previewCards" :key="index" class="card-list-item">
            <div class="card-list-front">{{ card.frontText }}</div>
            <div class="card-list-back">{{ card.backText }}</div>
            <div v-if="card.sourceRef" class="card-list-source">原文：{{ card.sourceRef }}</div>
          </li>
        </ul>
        <div class="inline-actions">
          <el-button size="large" plain :loading="generating" @click="generateCards">重新生成卡片</el-button>
          <el-button type="primary" size="large" :loading="confirming" @click="confirmPreview">
            确认卡片，开始复习
          </el-button>
        </div>
      </div>
      <div v-else-if="cards.length === 0">
        <p class="hcard__note">还没有复习卡片。生成卡片后，可以像翻单词卡一样复习。</p>
        <el-button type="primary" size="large" :loading="generating" @click="generateCards">
          生成记忆卡片
        </el-button>
      </div>
      <template v-else>
        <div v-if="reviewIndex >= 0 && reviewIndex < reviewQueue.length" class="review-stage">
          <p class="review-progress">第 {{ reviewIndex + 1 }} / {{ reviewQueue.length }} 张</p>

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
            <el-button size="large" type="danger" :disabled="reviewingCardId !== null" @click="markRetry">
              再看一次
            </el-button>
            <el-button size="large" type="primary" :disabled="reviewingCardId !== null" @click="markGotIt">
              记住了
            </el-button>
            <el-button size="large" @click="nextCard">下一张</el-button>
          </div>

          <details class="view-original">
            <summary>查看备忘原文</summary>
            <p class="original-text">{{ memo.content }}</p>
          </details>
        </div>
        <div v-else class="review-done">
          <p class="hcard__value">今天没有需要复习的卡片</p>
          <p class="hcard__note">记住的卡片会按 1 / 3 / 7 天的间隔再次出现，到时间再来复习。</p>
          <p v-if="nextDueLabel" class="hcard__note">这 {{ cards.length }} 张卡片的下次复习时间：{{ nextDueLabel }}</p>
          <el-button type="primary" size="large" @click="startReview">检查有没有到期卡片</el-button>
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
  draftCards as apiDraftCards,
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

const reviewQueue = ref([])
const previewMode = ref(false)
const previewCards = ref([])
const previewVersion = ref('')
const previewId = ref('')
const confirming = ref(false)
const reviewingCardId = ref(null)

const currentCard = computed(() => reviewQueue.value[reviewIndex.value] || null)

const nextDueLabel = computed(() => {
  const cutoff = Date.now() + 5000
  const upcoming = cards.value
    .filter(c => c.nextReviewAt && new Date(c.nextReviewAt).getTime() > cutoff)
    .map(c => new Date(c.nextReviewAt).getTime())
  if (upcoming.length === 0) return ''
  const earliest = new Date(Math.min(...upcoming))
  return `${earliest.getMonth() + 1} 月 ${earliest.getDate()} 日`
})

function dueCardsNow() {
  const cutoff = Date.now() + 5000
  return cards.value.filter(c => c.nextReviewAt && new Date(c.nextReviewAt).getTime() <= cutoff)
}

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
    await refreshMemo()
    viewState.value = memo.value ? 'ready' : 'degraded'
  } catch {
    viewState.value = 'degraded'
  }
  await loadCards()
}

/** 只刷新备忘原文，不动卡片和复习队列。 */
async function refreshMemo() {
  const memos = await getMyMemos()
  memo.value = memos.find(m => m.id === memoId.value) || null
}

async function loadCards() {
  cardsState.value = 'loading'
  try {
    cards.value = await getCardsForMemo(memoId.value)
    cardsState.value = 'ready'
  } catch {
    cardsState.value = 'degraded'
  }
  if (!previewMode.value) startReview()
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
    const previous = memo.value?.content
    memo.value = await updateMemo(memoId.value, text)
    editingContent.value = false
    if (previous !== memo.value.content) {
      previewMode.value = false
      previewCards.value = []
      previewVersion.value = ''
      previewId.value = ''
      await loadCards()
      ElMessage.success('已保存。原文变了，旧卡片已暂停，请重新生成卡片后再复习')
    } else {
      ElMessage.success('已保存')
    }
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
    const draft = await loadPreview()
    ElMessage.success(`生成了 ${draft.drafts.length} 张卡片，确认后再开始复习`)
  } catch {
  } finally {
    generating.value = false
  }
}

/** 拉取草稿预览：只更新内存里的草稿列表、原文版本号和预览标识，不动旧卡片与复习进度。 */
async function loadPreview() {
  const draft = await apiDraftCards(memoId.value)
  previewCards.value = draft.drafts
  previewVersion.value = draft.memoVersion
  previewId.value = draft.previewId
  previewMode.value = true
  reviewIndex.value = -1
  showAnswer.value = false
  return draft
}

async function confirmPreview() {
  if (confirming.value) return
  confirming.value = true
  try {
    cards.value = await apiGenerateCards(
      memoId.value,
      previewId.value,
      previewVersion.value,
      previewCards.value.map((card) => ({
        frontText: card.frontText,
        backText: card.backText,
        sourceRef: card.sourceRef
      }))
    )
    previewMode.value = false
    previewCards.value = []
    previewVersion.value = ''
    previewId.value = ''
    startReview()
    ElMessage.success('已开始复习')
  } catch (err) {
    if (err?.errorCode === 'MEMO_VERSION_MISMATCH') {
      // 预览之后原文被改（例如另一个窗口改了备忘）：此时按新原文发卡，患者就没看过这些卡片。
      // 清掉旧草稿，同步最新原文后重新预览，让患者重新确认一次。
      previewMode.value = false
      previewCards.value = []
      previewVersion.value = ''
      previewId.value = ''
      try {
        await refreshMemo()
      } catch {
      }
      try {
        await loadPreview()
        ElMessage.warning('原文已修改，已按最新内容重新预览，请确认后再开始复习')
      } catch {
      }
    } else {
      ElMessage.error(err?.message || '生成卡片失败')
    }
  } finally {
    confirming.value = false
  }
}

function startReview() {
  reviewQueue.value = dueCardsNow()
  reviewIndex.value = reviewQueue.value.length > 0 ? 0 : -1
  showAnswer.value = false
}

function nextCard() {
  if (reviewQueue.value.length === 0) return
  showAnswer.value = false
  reviewIndex.value = (reviewIndex.value + 1) % reviewQueue.value.length
}

function prevCard() {
  if (reviewQueue.value.length === 0) return
  showAnswer.value = false
  reviewIndex.value = (reviewIndex.value - 1 + reviewQueue.value.length) % reviewQueue.value.length
}

function jumpTo(cardId) {
  const idx = reviewQueue.value.findIndex(c => c.id === cardId)
  if (idx >= 0) {
    reviewIndex.value = idx
    showAnswer.value = false
  } else {
    ElMessage.info('这张卡片还没到复习时间')
  }
}

async function markGotIt() {
  if (!currentCard.value || reviewingCardId.value) return
  const cardId = currentCard.value.id
  reviewingCardId.value = cardId
  try {
    const updated = await reviewCard(memoId.value, cardId, 'GOT_IT')
    const idx = cards.value.findIndex(c => c.id === updated.id)
    if (idx >= 0) cards.value[idx] = updated
    removeFromQueue(cardId)
    showAnswer.value = false
    ElMessage.success('记住了！这张卡片已安排到下次复习')
  } catch {
  } finally {
    reviewingCardId.value = null
  }
}

async function markRetry() {
  if (!currentCard.value || reviewingCardId.value) return
  const cardId = currentCard.value.id
  reviewingCardId.value = cardId
  try {
    const updated = await reviewCard(memoId.value, cardId, 'AGAIN')
    const idx = cards.value.findIndex(c => c.id === updated.id)
    if (idx >= 0) cards.value[idx] = updated
    ElMessage.info('再看一次！这张会优先出现')
    showAnswer.value = false
  } catch {
  } finally {
    reviewingCardId.value = null
  }
}

/**
 * 按提交时的卡片 ID 出队。请求返回时患者可能已经切到别的卡片，所以不能再用
 * 当前的 reviewIndex 定位，否则会把没提交的那张移出本轮队列。
 */
function removeFromQueue(cardId) {
  const removedAt = reviewQueue.value.findIndex(c => c.id === cardId)
  if (removedAt < 0) return
  reviewQueue.value.splice(removedAt, 1)
  if (reviewQueue.value.length === 0) {
    reviewIndex.value = -1
    return
  }
  if (removedAt < reviewIndex.value) {
    reviewIndex.value -= 1
  }
  if (reviewIndex.value >= reviewQueue.value.length) {
    reviewIndex.value = 0
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

.card-list-source {
  margin-top: 4px;
  color: var(--muted);
  font-size: 0.82rem;
  opacity: 0.8;
}
</style>