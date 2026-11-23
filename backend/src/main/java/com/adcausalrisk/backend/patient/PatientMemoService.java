package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.patient.entity.PatientFlashCard;
import com.adcausalrisk.backend.patient.entity.PatientMemo;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        PatientMemo memo = memoRepository.findByIdAndUserId(memoId, principal.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "备忘不存在"));
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
        PatientMemo memo = memoRepository.findByIdAndUserId(memoId, principal.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "备忘不存在"));
        cardRepository.deleteByMemoId(memoId);
        memoRepository.delete(memo);
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
        requirePatient(principal);
        PatientMemo memo = memoRepository.findByIdAndUserId(memoId, principal.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "备忘不存在"));
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
                if (results.size() >= 6) break;
                continue;
            }

            String[] chunks = sent.split("[，,、\\s]+");
            if (chunks.length >= 2) {
                for (int i = 0; i < chunks.length - 1 && results.size() < 6; i++) {
                    String a = chunks[i].trim();
                    String b = chunks[i + 1].trim();
                    if (a.length() >= 1 && b.length() >= 1
                            && a.length() <= 80 && b.length() <= 80) {
                        results.add(new String[] { "关于「" + a + "」，我写了什么？", b });
                    }
                }
            } else if (results.isEmpty() && sent.length() >= 2) {
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

    private static String[] parseQuestionAnswer(String sentence) {
        String[] questionPatterns = {
            "^我(.+)在(.+?)(?:里|中|上|下|处|旁边)",
            "^我(.+?)(?:要|需要|得|会|打算|计划|想)",
            "^(.+?)在(.+?)(?:里|中|上|下)",
        };

        for (String pattern : questionPatterns) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher m = p.matcher(sentence);
            if (m.find() && m.groupCount() >= 2) {
                String part1 = m.group(1).trim();
                String part2 = m.group(2).trim();
                if (!part1.isBlank() && !part2.isBlank()
                        && part1.length() <= 120 && part2.length() <= 120) {
                    String question = "我" + part1 + "在哪里？";
                    String answer = part2;
                    return new String[] { question, answer };
                }
            }
        }

        if (sentence.contains("和") || sentence.contains("与") || sentence.contains("跟")) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("(.+?)[和与跟](.+?)在(.+?)(?:里|中|上|下)");
            java.util.regex.Matcher m = p.matcher(sentence);
            if (m.find()) {
                String who = m.group(1).trim();
                String location = m.group(3).trim();
                if (!who.isBlank() && !location.isBlank()
                        && who.length() <= 120 && location.length() <= 120) {
                    return new String[] { who + "在哪里？", location };
                }
            }
        }

        if (sentence.contains("：") || sentence.contains(":")) {
            String[] parts = sentence.split("[:：]", 2);
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                return new String[] { parts[0].trim() + "？", parts[1].trim() };
            }
        }

        if (sentence.contains("，") || sentence.contains(",")) {
            String[] parts = sentence.split("[，,]", 2);
            if (parts.length == 2 && parts[0].length() >= 4 && parts[1].length() >= 2) {
                return new String[] { parts[0].trim() + "？", parts[1].trim() };
            }
        }

        return null;
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