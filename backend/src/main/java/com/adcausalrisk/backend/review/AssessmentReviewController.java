package com.adcausalrisk.backend.review;

import com.adcausalrisk.backend.audit.annotation.AuditLog;
import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.review.entity.AssessmentReview;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * {@code POST /api/assessments/{assessmentId}/review} - the contract's review operation.
 *
 * <p>The path carries the <b>assessment</b> id, not the task id: reviews attach to {@code
 * risk_assessment}, and {@code assessment_review.assessment_id} is its foreign key. {@code GET
 * /api/assessments/{assessmentId}/reviews} is an additive read that the frozen contract does not
 * declare yet.
 */
@RestController
public class AssessmentReviewController {

    private final AssessmentReviewService reviewService;
    private final com.adcausalrisk.backend.common.BusinessQueries queries;

    public AssessmentReviewController(
            AssessmentReviewService reviewService,
            com.adcausalrisk.backend.common.BusinessQueries queries) {
        this.reviewService = reviewService;
        this.queries = queries;
    }

    /** {@code ReviewCreate}: decision + comment. */
    public record ReviewRequest(
            @NotBlank String decision, @NotBlank @Size(max = 2000) String comment) {}

    /** {@code ReviewResult}. */
    public record ReviewResult(
            long id, long assessmentId, String decision, String comment, Instant reviewedAt) {}

    @PostMapping("/api/assessments/{assessmentId}/review")
    @AuditLog(eventType = "ASSESSMENT_REVIEW", summary = "记录人工业务复核")
    public Envelope<ReviewResult> review(
            @PathVariable Long assessmentId,
            @Valid @RequestBody ReviewRequest request,
            Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        AssessmentReview review =
                reviewService.review(
                        assessmentId, request.decision(), request.comment(), principal);
        return Envelope.success(view(review));
    }

    @GetMapping("/api/assessments/{assessmentId}/reviews")
    public Envelope<?> history(@PathVariable Long assessmentId, Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        reviewService.history(assessmentId, principal);
        return Envelope.success(
                queries.rows(
                        "SELECT"
                            + " r.id,r.assessment_id,r.reviewer_id,r.decision,r.comment,r.reviewed_at,u.username"
                            + " reviewer_name FROM assessment_review r JOIN sys_user u ON"
                            + " u.id=r.reviewer_id WHERE r.assessment_id=? ORDER BY r.reviewed_at"
                            + " DESC,r.id DESC",
                        assessmentId));
    }

    private static ReviewResult view(AssessmentReview review) {
        return new ReviewResult(
                review.getId(),
                review.getAssessmentId(),
                review.getDecision().name(),
                review.getComment(),
                review.getReviewedAt());
    }
}
