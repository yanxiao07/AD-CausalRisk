package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.patient.entity.PatientFlashCard;
import com.adcausalrisk.backend.patient.entity.PatientMemo;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PatientMemoService {

    private final PatientMemoRepository memoRepository;
    private final PatientFlashCardRepository cardRepository;
    private final PatientSummaryService summary;

    public PatientMemoService(PatientMemoRepository memoRepository,
                              PatientFlashCardRepository cardRepository,
                              PatientSummaryService summary) {
        this.memoRepository = memoRepository;
        this.cardRepository = cardRepository;
        this.summary = summary;
    }

    /**
     * Every memo and card entry point is patient-only. A doctor or researcher account must never
     * read even an empty list through these paths, so the role is checked once here instead of
     * relying on each caller to remember it.
     */
    private static void requirePatient(AppUserPrincipal principal) {
        if (!principal.roleCodes().contains("PATIENT")) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "仅患者本人可访问我的备忘");
        }
    }

    @Transactional(readOnly = true)
    public List<PatientMemo> listMyMemos(AppUserPrincipal principal) {
        requirePatient(principal);
        return memoRepository.findByUserIdOrderByCreatedAtDesc(principal.userId());
    }

    @Transactional(readOnly = true)
    public long countMyMemos(AppUserPrincipal principal) {
        requirePatient(principal);
        return memoRepository.countByUserId(principal.userId());
    }

    @Transactional
    public PatientMemo createMemo(String content, AppUserPrincipal principal) {
        requirePatient(principal);
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "备忘内容不能为空");
        }
        String trimmed = content.trim();
        if (trimmed.length() > 1000) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "备忘内容不能超过1000字");
        }
        // subjectForPatient resolves the bound archive through subject_permission and rejects any
        // non-patient caller. patient_account_request has no user_id column, so querying it here
        // used to fail the whole request with a 500 for every bound patient.
        Long subjectId = summary.subjectForPatient(principal);
        return memoRepository.saveAndFlush(new PatientMemo(principal.userId(), subjectId, trimmed));
    }

    @Transactional
    public PatientMemo editMemo(Long memoId, String content, AppUserPrincipal principal) {
        requirePatient(principal);
        PatientMemo memo = lockOwnedMemo(memoId, principal);
        String previous = memo.getContent();
        memo.editContent(content);
        PatientMemo saved = memoRepository.saveAndFlush(memo);
        // A card whose answer was extracted from the old wording would keep showing a stale
        // answer, so the old cards are retired as soon as the source text really changed. The
        // patient then previews freshly generated cards before reviewing them again.
        if (!Objects.equals(previous, saved.getContent())) {
            cardRepository.deleteByMemoId(memoId);
        }
        return saved;
    }

    @Transactional
    public void deleteMemo(Long memoId, AppUserPrincipal principal) {
        requirePatient(principal);
        PatientMemo memo = lockOwnedMemo(memoId, principal);
        cardRepository.deleteByMemoId(memoId);
        memoRepository.delete(memo);
    }

    /**
     * Loads the memo this patient owns while taking the row lock, so any two requests that touch
     * the same memo's text or card set run one after another instead of interleaving. Card
     * confirmation uses this to keep "check the version → delete the old cards → write the new
     * ones" inside a single lock; without it a concurrent edit can commit in the middle of that
     * sequence and the confirmation then publishes cards built from text the patient never saw.
     */
    private PatientMemo lockOwnedMemo(Long memoId, AppUserPrincipal principal) {
        return memoRepository.findByIdAndUserIdForUpdate(memoId, principal.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "备忘不存在"));
    }

    @Transactional(readOnly = true)
    public List<PatientFlashCard> listCardsForMemo(Long memoId, AppUserPrincipal principal) {
        requirePatient(principal);
        PatientMemo memo = memoRepository.findByIdAndUserId(memoId, principal.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "备忘不存在"));
        return cardRepository.findByMemoIdOrderByCreatedAtAsc(memo.getId());
    }

    /**
     * The spaced-repetition queue: only cards whose {@code nextReviewAt} has arrived, scoped to the
     * memos this patient owns. The review page consumes this queue instead of walking every card
     * of a memo, so a card answered today does not reappear until its interval elapses.
     */
    @Transactional(readOnly = true)
    public List<PatientFlashCard> dueCardsForPatient(AppUserPrincipal principal) {
        requirePatient(principal);
        List<Long> memoIds = memoRepository.findByUserIdOrderByCreatedAtDesc(principal.userId()).stream()
                .map(PatientMemo::getId)
                .toList();
        if (memoIds.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now();
        return cardRepository.findDueByMemoIds(memoIds, now).stream()
                .sorted((a, b) -> {
                    int stateCmp = stateOrder(a.getCardState()) - stateOrder(b.getCardState());
                    if (stateCmp != 0) return stateCmp;
                    return a.getNextReviewAt().compareTo(b.getNextReviewAt());
                })
                .limit(20)
                .toList();
    }

    private static int stateOrder(PatientFlashCard.CardState s) {
        return switch (s) {
            case NEW -> 0;
            case REVIEW -> 1;
            case MASTERED -> 2;
        };
    }

    @Transactional
    public List<PatientFlashCard> generateCardsForMemo(Long memoId, AppUserPrincipal principal) {
        return generateCardsForMemo(memoId, principal, null);
    }

    /**
     * 确认生成卡片。{@code expectedVersion} 非空时先校验它是否仍是当前原文的版本。
     *
     * <p>预览与确认之间原文可能被另一个窗口改掉：此时按新原文抽卡，患者就会收到自己
     * 从未看过、却已进入复习队列的卡片。所以版本不符必须拒绝。校验放在
     * {@code deleteByMemoId} 之前，拒绝时旧卡片和复习进度原样保留。
     *
     * <p>校验和写卡都在备忘行锁内完成：只比对版本号还不够，请求若在校验之后、写卡之前停顿，
     * 另一笔编辑仍可能插进来提交，确认就会按已过期的原文写卡。行锁让两笔请求串行，见
     * {@link PatientMemoRepository#findByIdAndUserIdForUpdate}。
     *
     * <p>{@code expectedVersion} 为空表示调用方没有预览步骤，沿用原有行为。
     */
    @Transactional
    public List<PatientFlashCard> generateCardsForMemo(Long memoId, AppUserPrincipal principal,
                                                       String expectedVersion) {
        requirePatient(principal);
        PatientMemo memo = lockOwnedMemo(memoId, principal);
        if (expectedVersion != null && !expectedVersion.isBlank()
                && !memoVersion(memo.getContent()).equals(expectedVersion)) {
            throw new BusinessException(ErrorCode.MEMO_VERSION_MISMATCH,
                    "备忘原文已修改，请重新预览卡片后再确认");
        }
        cardRepository.deleteByMemoId(memoId);

        List<String[]> facts = extractFacts(memo.getContent());
        List<PatientFlashCard> created = new ArrayList<>();
        for (String[] pair : facts) {
            if (pair[0].length() > 500 || pair[1].length() > 500) continue;
            created.add(cardRepository.saveAndFlush(new PatientFlashCard(memoId, pair[0], pair[1])));
        }
        if (created.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "未能从这段备忘中提取出可复习的内容，请改写后再试");
        }
        return created;
    }

    /** 预览结果连同原文版本号，供确认时核对「患者看到的是不是当前原文」。 */
    public record CardDrafts(String memoVersion, List<String[]> cards) {}

    /**
     * 只做预览：按当前原文算出候选卡片，但不写库，也不动已有卡片和复习进度。
     * 患者点「确认」后才会调用 {@link #generateCardsForMemo} 真正替换旧卡，
     * 这样生成动作在确认之前不会生效。
     */
    @Transactional(readOnly = true)
    public CardDrafts draftCardsForMemo(Long memoId, AppUserPrincipal principal) {
        requirePatient(principal);
        PatientMemo memo = memoRepository.findByIdAndUserId(memoId, principal.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "备忘不存在"));
        List<String[]> facts = extractFacts(memo.getContent()).stream()
                .filter(pair -> pair[0].length() <= 500 && pair[1].length() <= 500)
                .toList();
        if (facts.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "未能从这段备忘中提取出可复习的内容，请改写后再试");
        }
        return new CardDrafts(memoVersion(memo.getContent()), facts);
    }

    /**
     * 原文版本号：内容摘要的十六进制串。原文一改值就变，且不依赖时间戳精度——
     * 同一秒内两次编辑也能区分开。
     */
    private static String memoVersion(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static final int MAX_FACTS = 6;

    private static List<String[]> extractFacts(String content) {
        List<String[]> results = new ArrayList<>();
        if (content == null || content.isBlank()) return results;

        String[] sentences = content.split("[。！？.!?;；\\n]+");
        for (String raw : sentences) {
            String sent = raw.trim();
            if (sent.length() < 2) continue;

            String[] qa = parseQuestionAnswer(sent);
            if (qa != null) {
                results.add(qa);
                if (results.size() >= MAX_FACTS) break;
                continue;
            }

            // 没有可靠问答结构时整句作为一张「回忆这件事」的卡片。早期实现会把相邻的
            // 词两两配对成「关于 A，我写了什么？→ B」，把互不相关的片段拼成错误答案，
            // 所以这里只做整句兜底：宁可少一张卡，也不生成答非所问的内容。
            if (results.size() < MAX_FACTS && sent.length() <= 120) {
                results.add(new String[] { "我记下的这件事是什么？", sent });
            }
        }

        if (results.isEmpty()) {
            String trimmed = content.trim();
            if (!trimmed.isBlank()) {
                results.add(new String[] { "我记下的这件事是什么？", trimmed });
            }
        }

        return results;
    }

    /**
     * 出现这些疑问词说明句子本身在提问，不能再拿它当「X 在 Y」这类陈述句去拆答案。
     * 「哪」同时覆盖「哪里 / 哪儿 / 哪个 / 哪些」。
     */
    private static final Set<String> QUESTION_WORDS = Set.of("哪", "什么", "谁", "怎么", "多少");

    /**
     * 按可靠度从高到低尝试抽取一组问答：
     * 1. 冒号结构「标题：内容」是最明确的写法，优先于任何句式推断；
     * 2. 「X 在 Y（里/中/上/下/处/旁边）」地点陈述句；
     * 3. 「我 要/需要/… X」计划陈述句。
     * 都不匹配时返回 null，由整句兜底接管，避免拼出答非所问的卡片。
     */
    private static String[] parseQuestionAnswer(String sentence) {
        String[] colon = splitByColon(sentence);
        if (colon != null) return colon;

        String[] place = matchPlaceStatement(sentence);
        if (place != null) return place;

        return matchIntentStatement(sentence);
    }

    /**
     * 「标题：内容」按冒号切分。旧实现在句式正则之后才处理冒号，导致
     * 「棋盒放在哪里：蓝色帆布袋」被地点句式抢走，答案只剩下「哪」。
     */
    private static String[] splitByColon(String sentence) {
        if (!sentence.contains("：") && !sentence.contains(":")) return null;
        String[] parts = sentence.split("[:：]", 2);
        String head = parts[0].trim();
        String tail = parts[1].trim();
        if (head.length() < 2 || tail.isBlank()) return null;
        if (head.length() > 120 || tail.length() > 120) return null;
        // 「16:00」「http://…」这类冒号不是问答结构，切开会得到毫无意义的卡片。
        if (Character.isDigit(head.charAt(head.length() - 1)) && Character.isDigit(tail.charAt(0))) return null;
        if (tail.startsWith("//")) return null;
        String question = head.endsWith("？") || head.endsWith("?") ? head : head + "？";
        return new String[] { question, tail };
    }

    private static final Pattern PLACE_STATEMENT = Pattern.compile(
            "^(.{1,60}?)在(.{1,40}?)(?:里面|里|中|上|下|处|旁边|内)$");

    private static String[] matchPlaceStatement(String sentence) {
        if (containsQuestionWord(sentence)) return null;
        Matcher m = PLACE_STATEMENT.matcher(sentence);
        if (!m.matches()) return null;
        String subject = m.group(1).trim();
        String location = m.group(2).trim();
        if (subject.isBlank() || location.isBlank()) return null;
        if (containsQuestionWord(subject) || containsQuestionWord(location)) return null;
        return new String[] { subject + "在哪里？", location };
    }

    private static final Pattern INTENT_STATEMENT = Pattern.compile(
            "^我(?:要|需要|得|会|打算|计划|想)(.{1,80})$");

    private static String[] matchIntentStatement(String sentence) {
        Matcher m = INTENT_STATEMENT.matcher(sentence);
        if (!m.matches()) return null;
        String action = m.group(1).trim();
        if (action.isBlank() || containsQuestionWord(action)) return null;
        return new String[] { "我接下来要做什么？", action };
    }

    private static boolean containsQuestionWord(String text) {
        for (String word : QUESTION_WORDS) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    @Transactional
    public PatientFlashCard editCard(Long memoId, Long cardId, String frontText, String backText,
                                      AppUserPrincipal principal) {
        requirePatient(principal);
        PatientMemo memo = memoRepository.findByIdAndUserId(memoId, principal.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "备忘不存在"));
        PatientFlashCard card = cardRepository.findByIdAndMemoId(cardId, memo.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "卡片不存在"));
        card.editTexts(frontText, backText);
        return cardRepository.saveAndFlush(card);
    }

    @Transactional
    public PatientFlashCard reviewCard(Long memoId, Long cardId, String action, AppUserPrincipal principal) {
        requirePatient(principal);
        PatientMemo memo = memoRepository.findByIdAndUserId(memoId, principal.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "备忘不存在"));
        PatientFlashCard card = cardRepository.findByIdAndMemoId(cardId, memo.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "卡片不存在"));

        switch (Objects.toString(action, "")) {
            case "GOT_IT", "OK" -> card.recordReview();
            case "AGAIN", "RETRY" -> card.resetForRetry();
            default -> throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "action 仅支持 GOT_IT / AGAIN");
        }
        return cardRepository.saveAndFlush(card);
    }

    @Transactional
    public void deleteCard(Long memoId, Long cardId, AppUserPrincipal principal) {
        requirePatient(principal);
        PatientMemo memo = memoRepository.findByIdAndUserId(memoId, principal.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "备忘不存在"));
        PatientFlashCard card = cardRepository.findByIdAndMemoId(cardId, memo.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "卡片不存在"));
        cardRepository.delete(card);
    }
}