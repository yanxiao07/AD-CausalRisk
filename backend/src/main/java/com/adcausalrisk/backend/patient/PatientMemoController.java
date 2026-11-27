package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.patient.entity.PatientFlashCard;
import com.adcausalrisk.backend.patient.entity.PatientMemo;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
public class PatientMemoController {

    private final PatientMemoService memoService;

    public PatientMemoController(PatientMemoService memoService) {
        this.memoService = memoService;
    }

    public record MemoView(long id, Long subjectId, String content, Instant createdAt, Instant updatedAt) {}
    public record CardView(long id, long memoId, String frontText, String backText,
                           String cardState, int reviewCount, Instant lastReviewedAt,
                           Instant nextReviewAt, Instant createdAt, Instant updatedAt) {}

    public record MemoCreateRequest(@Size(max = 1000) String content) {}
    public record MemoUpdateRequest(@Size(max = 1000) String content) {}
    public record CardEditRequest(@Size(max = 500) String frontText, @Size(max = 500) String backText) {}
    public record CardReviewRequest(String action) {}
    public record CardDraftView(String frontText, String backText, String sourceRef) {}
    public record CardDraftResponse(String previewId, String memoVersion, List<CardDraftView> drafts) {}
    /**
     * 确认请求。{@code previewId} 指向 card-drafts 返回的那批服务端草稿，确认只发布这一批；
     * {@code drafts} 可选，带上时必须与预览页展示的一致，否则拒绝。
     * 两者都不带表示调用方没有预览步骤，服务层按当前原文抽取（旧调用方行为）。
     */
    public record CardGenerateRequest(@Size(max = 64) String previewId,
                                      @Size(max = 128) String memoVersion,
                                      @Size(max = 20) List<CardDraftView> drafts) {}

    private MemoView view(PatientMemo m) {
        return new MemoView(m.getId(), m.getSubjectId(), m.getContent(), m.getCreatedAt(), m.getUpdatedAt());
    }

    private CardView view(PatientFlashCard c) {
        return new CardView(c.getId(), c.getMemoId(), c.getFrontText(), c.getBackText(),
                c.getCardState().name(), c.getReviewCount(), c.getLastReviewedAt(),
                c.getNextReviewAt(), c.getCreatedAt(), c.getUpdatedAt());
    }

    @GetMapping("/api/patients/me/memos")
    public Envelope<List<MemoView>> listMemos(Authentication auth) {
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        return Envelope.success(memoService.listMyMemos(principal).stream().map(this::view).toList());
    }

    @GetMapping("/api/patients/me/memos/summary")
    public Envelope<Map<String, Object>> memoSummary(Authentication auth) {
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        return Envelope.success(Map.of("totalMemos", memoService.countMyMemos(principal)));
    }

    @PostMapping("/api/patients/me/memos")
    public Envelope<MemoView> createMemo(@Valid @RequestBody MemoCreateRequest request, Authentication auth) {
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        if (request.content() == null || request.content().isBlank()) {
            return Envelope.failure(com.adcausalrisk.backend.common.ErrorCode.VALIDATION_ERROR, "备忘内容不能为空");
        }
        return Envelope.success(view(memoService.createMemo(request.content(), principal)));
    }

    @PatchMapping("/api/patients/me/memos/{memoId}")
    public Envelope<MemoView> editMemo(@PathVariable long memoId,
                                        @Valid @RequestBody MemoUpdateRequest request,
                                        Authentication auth) {
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        if (request.content() == null || request.content().isBlank()) {
            return Envelope.failure(com.adcausalrisk.backend.common.ErrorCode.VALIDATION_ERROR, "备忘内容不能为空");
        }
        return Envelope.success(view(memoService.editMemo(memoId, request.content(), principal)));
    }

    @DeleteMapping("/api/patients/me/memos/{memoId}")
    public Envelope<Void> deleteMemo(@PathVariable long memoId, Authentication auth) {
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        memoService.deleteMemo(memoId, principal);
        return Envelope.success(null);
    }

    @PostMapping("/api/patients/me/memos/{memoId}/card-drafts")
    public Envelope<CardDraftResponse> draftCards(@PathVariable long memoId, Authentication auth) {
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        PatientMemoService.CardDrafts drafts = memoService.draftCardsForMemo(memoId, principal);
        return Envelope.success(new CardDraftResponse(drafts.previewId(), drafts.memoVersion(),
                drafts.cards().stream()
                        .map(draft -> new CardDraftView(
                                draft.frontText(), draft.backText(), draft.sourceRef()))
                        .toList()));
    }

    /**
     * 确认生成卡片。请求体可省略（保持旧调用方可用）；带 {@code previewId} 时，服务层只发布
     * 预览时绑定的那批草稿，并核对原文版本——原文在预览之后被改过就拒绝（400 MEMO_VERSION_MISMATCH）。
     * 带 {@code drafts} 却不带 {@code previewId} 会被拒绝：确认不接受客户端自带的卡片文字。
     */
    @PostMapping("/api/patients/me/memos/{memoId}/generate-cards")
    public Envelope<List<CardView>> generateCards(@PathVariable long memoId,
                                                  @Valid @RequestBody(required = false) CardGenerateRequest request,
                                                  Authentication auth) {
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        String previewId = request == null ? null : request.previewId();
        String expectedVersion = request == null ? null : request.memoVersion();
        List<MemoCardExtractor.Draft> previewed = request == null || request.drafts() == null
                ? null
                : request.drafts().stream()
                        .map(draft -> draft == null ? null : new MemoCardExtractor.Draft(
                                draft.frontText(), draft.backText(), draft.sourceRef()))
                        .toList();
        return Envelope.success(
                memoService.generateCardsForMemo(memoId, principal, previewId, expectedVersion, previewed).stream()
                        .map(this::view).toList());
    }

    @GetMapping("/api/patients/me/memos/{memoId}/cards")
    public Envelope<List<CardView>> listCards(@PathVariable long memoId, Authentication auth) {
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        return Envelope.success(memoService.listCardsForMemo(memoId, principal).stream().map(this::view).toList());
    }

    @GetMapping("/api/patients/me/flash-cards/due")
    public Envelope<List<CardView>> dueCards(Authentication auth) {
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        return Envelope.success(memoService.dueCardsForPatient(principal).stream().map(this::view).toList());
    }

    @PatchMapping("/api/patients/me/memos/{memoId}/cards/{cardId}")
    public Envelope<CardView> editCard(@PathVariable long memoId, @PathVariable long cardId,
                                        @Valid @RequestBody CardEditRequest request,
                                        Authentication auth) {
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        if (request.frontText() == null || request.backText() == null
                || request.frontText().isBlank() || request.backText().isBlank()) {
            return Envelope.failure(com.adcausalrisk.backend.common.ErrorCode.VALIDATION_ERROR, "卡片文字不能为空");
        }
        return Envelope.success(view(
                memoService.editCard(memoId, cardId, request.frontText(), request.backText(), principal)));
    }

    @PostMapping("/api/patients/me/memos/{memoId}/cards/{cardId}/review")
    public Envelope<CardView> reviewCard(@PathVariable long memoId, @PathVariable long cardId,
                                          @Valid @RequestBody CardReviewRequest request,
                                          Authentication auth) {
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        if (request.action() == null || request.action().isBlank()) {
            return Envelope.failure(com.adcausalrisk.backend.common.ErrorCode.VALIDATION_ERROR,
                    "请选择 GOT_IT 或 AGAIN");
        }
        return Envelope.success(view(memoService.reviewCard(memoId, cardId, request.action(), principal)));
    }

    @DeleteMapping("/api/patients/me/memos/{memoId}/cards/{cardId}")
    public Envelope<Void> deleteCard(@PathVariable long memoId, @PathVariable long cardId, Authentication auth) {
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        memoService.deleteCard(memoId, cardId, principal);
        return Envelope.success(null);
    }
}