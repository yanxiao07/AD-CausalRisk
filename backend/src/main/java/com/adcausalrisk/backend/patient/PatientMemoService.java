package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.patient.entity.PatientCardDraftPreview;
import com.adcausalrisk.backend.patient.entity.PatientFlashCard;
import com.adcausalrisk.backend.patient.entity.PatientMemo;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class PatientMemoService {

    private final PatientMemoRepository memoRepository;
    private final PatientFlashCardRepository cardRepository;
    private final PatientCardDraftPreviewRepository previewRepository;
    private final PatientSummaryService summary;
    private final MemoCardExtractor extractor;
    private final ObjectMapper json;

    public PatientMemoService(PatientMemoRepository memoRepository,
                              PatientFlashCardRepository cardRepository,
                              PatientCardDraftPreviewRepository previewRepository,
                              PatientSummaryService summary,
                              MemoCardExtractor extractor,
                              ObjectMapper json) {
        this.memoRepository = memoRepository;
        this.cardRepository = cardRepository;
        this.previewRepository = previewRepository;
        this.summary = summary;
        this.extractor = extractor;
        this.json = json;
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
            // 原文一改，绑定的那批预览草稿就失效了：出处与版本都对不上，
            // 患者必须重新预览，确认接口也不该再按旧批次发布。
            previewRepository.deleteByMemoId(memoId);
        }
        return saved;
    }

    @Transactional
    public void deleteMemo(Long memoId, AppUserPrincipal principal) {
        requirePatient(principal);
        PatientMemo memo = lockOwnedMemo(memoId, principal);
        cardRepository.deleteByMemoId(memoId);
        previewRepository.deleteByMemoId(memoId);
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
        return generateCardsForMemo(memoId, principal, null, expectedVersion, null);
    }

    /**
     * 确认写卡。{@code previewId} 指向预览时服务端绑定下来的那批草稿，确认只发布那一批。
     *
     * <p>不能相信客户端提交的卡片文字：只校验 {@code sourceRef} 在原文里找得到，保留版本和
     * 出处、把预览答案从「周五」换成「下周一去公园」照样能存进去——患者看到过的卡片被悄悄
     * 改掉，复习队列里就多了一条他自己没确认过的事实。所以草稿由服务端在预览时落库绑定，
     * 确认只按 {@code previewId} 取回并原样发布。
     *
     * <p>{@code previewed} 只是可选的核对：调用方带上它时，必须与服务端绑定的批次逐条一致，
     * 不一致直接拒绝（400），不会用它覆盖服务端的那批。
     *
     * <p>不带 {@code previewId} 也不带草稿时，沿用原有行为：按当前原文抽取。
     */
    @Transactional
    public List<PatientFlashCard> generateCardsForMemo(Long memoId, AppUserPrincipal principal,
                                                       String previewId, String expectedVersion,
                                                       List<MemoCardExtractor.Draft> previewed) {
        requirePatient(principal);
        PatientMemo memo = lockOwnedMemo(memoId, principal);
        if (expectedVersion != null && !expectedVersion.isBlank()
                && !memoVersion(memo.getContent()).equals(expectedVersion)) {
            throw new BusinessException(ErrorCode.MEMO_VERSION_MISMATCH,
                    "备忘原文已修改，请重新预览卡片后再确认");
        }
        List<MemoCardExtractor.Draft> facts;
        if (previewId != null && !previewId.isBlank()) {
            facts = boundPreview(previewId, memoId, principal, memo.getContent(), previewed);
        } else if (previewed != null && !previewed.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "确认卡片必须带上预览标识 previewId，请重新预览后再确认");
        } else {
            facts = extractor.extract(memo.getContent());
        }
        cardRepository.deleteByMemoId(memoId);

        List<PatientFlashCard> created = new ArrayList<>();
        for (MemoCardExtractor.Draft draft : facts) {
            created.add(cardRepository.saveAndFlush(
                    new PatientFlashCard(memoId, draft.frontText(), draft.backText())));
        }
        if (created.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "未能从这段备忘中提取出可复习的内容，请改写后再试");
        }
        return created;
    }

    /**
     * 取回预览时绑定下来的草稿批次，并核对它仍对得上「这位患者、这条备忘、这份原文」。
     *
     * <p>三步都不能省：previewId 必须属于当前患者和当前备忘（否则别人可以拿自己的预览
     * 往这条备忘里发卡）；绑定时的原文版本必须仍是当前版本（否则出处已经不成立）；
     * 调用方若附带了草稿，必须与绑定的那批逐条一致。
     */
    private List<MemoCardExtractor.Draft> boundPreview(String previewId, Long memoId,
                                                       AppUserPrincipal principal, String content,
                                                       List<MemoCardExtractor.Draft> submitted) {
        PatientCardDraftPreview bound = previewRepository
                .findByPreviewIdAndUserIdAndMemoId(previewId, principal.userId(), memoId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "预览草稿已失效，请重新预览后再确认"));
        if (!memoVersion(content).equals(bound.getMemoVersion())) {
            throw new BusinessException(ErrorCode.MEMO_VERSION_MISMATCH,
                    "备忘原文已修改，请重新预览卡片后再确认");
        }
        List<MemoCardExtractor.Draft> drafts = readBoundDrafts(bound.getDraftsJson());
        if (drafts.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "未能从这段备忘中提取出可复习的内容，请改写后再试");
        }
        if (submitted != null && !submitted.isEmpty() && !sameDrafts(submitted, drafts)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "确认的卡片与预览页展示的不一致，请重新预览后再确认");
        }
        return drafts;
    }

    /** 提交的草稿必须与服务端绑定的批次逐条一致：条数、正反面、出处都不能变。 */
    private static boolean sameDrafts(List<MemoCardExtractor.Draft> submitted,
                                      List<MemoCardExtractor.Draft> bound) {
        if (submitted.size() != bound.size()) return false;
        for (int index = 0; index < bound.size(); index++) {
            MemoCardExtractor.Draft left = submitted.get(index);
            MemoCardExtractor.Draft right = bound.get(index);
            if (left == null) return false;
            if (!Objects.equals(trimToEmpty(left.frontText()), trimToEmpty(right.frontText()))) return false;
            if (!Objects.equals(trimToEmpty(left.backText()), trimToEmpty(right.backText()))) return false;
            if (!Objects.equals(trimToEmpty(left.sourceRef()), trimToEmpty(right.sourceRef()))) return false;
        }
        return true;
    }

    private static String trimToEmpty(String text) {
        return text == null ? "" : text.trim();
    }

    /** 绑定批次以 JSON 存放：一个「正面、背面、出处」三元组数组。 */
    private List<MemoCardExtractor.Draft> readBoundDrafts(String draftsJson) {
        try {
            List<Map<String, String>> rows = json.readValue(draftsJson,
                    new TypeReference<List<Map<String, String>>>() {});
            List<MemoCardExtractor.Draft> drafts = new ArrayList<>();
            for (Map<String, String> row : rows) {
                if (row == null) continue;
                drafts.add(new MemoCardExtractor.Draft(
                        row.get("frontText"), row.get("backText"), row.get("sourceRef")));
            }
            return drafts;
        } catch (Exception unreadable) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "预览草稿已失效，请重新预览后再确认");
        }
    }

    private String writeDrafts(List<MemoCardExtractor.Draft> drafts) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (MemoCardExtractor.Draft draft : drafts) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("frontText", draft.frontText());
            row.put("backText", draft.backText());
            row.put("sourceRef", draft.sourceRef());
            rows.add(row);
        }
        try {
            return json.writeValueAsString(rows);
        } catch (Exception unwritable) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "卡片内容暂时无法保存，请稍后重试");
        }
    }

    /**
     * 预览结果连同原文版本号和预览标识，供确认时核对「患者看到的是不是当前原文」。
     * cards 里每条都带 sourceRef，预览页据此展示这张卡来自哪句原文。
     */
    public record CardDrafts(String previewId, String memoVersion, List<MemoCardExtractor.Draft> cards) {}

    /**
     * 只做预览：按当前原文算出候选卡片，不动已有卡片和复习进度。
     *
     * <p>唯一写入的是这张「预览批次」记录：确认接口只认它，所以预览结果必须由服务端留下
     * 凭据，否则确认时只能相信客户端提交的文字，改一个字就能把患者看过的答案换掉。
     * 同一备忘重新预览会替换旧批次，旧 previewId 随即失效。
     */
    @Transactional
    public CardDrafts draftCardsForMemo(Long memoId, AppUserPrincipal principal) {
        requirePatient(principal);
        PatientMemo memo = memoRepository.findByIdAndUserId(memoId, principal.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "备忘不存在"));
        List<MemoCardExtractor.Draft> facts = extractor.extract(memo.getContent());
        if (facts.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "未能从这段备忘中提取出可复习的内容，请改写后再试");
        }
        String version = memoVersion(memo.getContent());
        String previewId = UUID.randomUUID().toString();
        previewRepository.deleteByMemoId(memoId);
        previewRepository.saveAndFlush(new PatientCardDraftPreview(
                previewId, principal.userId(), memoId, version, writeDrafts(facts)));
        return new CardDrafts(previewId, version, facts);
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