package com.adcausalrisk.backend.review;

import com.adcausalrisk.backend.assessment.RiskAssessmentRepository;
import com.adcausalrisk.backend.assessment.entity.RiskAssessment;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.review.entity.AssessmentReview;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectAccessPolicy;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Human business review of an assessment: records the decision and mirrors it onto {@code
 * risk_assessment.review_status} so the list/report views see the current state.
 */
@Service
public class AssessmentReviewService {

    private static final int MAX_COMMENT_LENGTH = 2000;

    private final AssessmentReviewRepository reviewRepository;
    private final RiskAssessmentRepository assessmentRepository;
    private final SubjectAccessPolicy accessPolicy;
    private final java.time.Clock clock;
    private final TransactionTemplate writeTransaction;

    public AssessmentReviewService(
            AssessmentReviewRepository reviewRepository,
            RiskAssessmentRepository assessmentRepository,
            SubjectAccessPolicy accessPolicy,
            java.time.Clock clock,
            PlatformTransactionManager transactionManager) {
        this.reviewRepository = reviewRepository;
        this.assessmentRepository = assessmentRepository;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** {@code POST /api/assessments/{assessmentId}/review}. */
    public AssessmentReview review(
            Long assessmentId, String decision, String comment, AppUserPrincipal principal) {
        RiskAssessment assessment =
                assessmentRepository
                        .findById(assessmentId)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.VALIDATION_ERROR, "评估结果不存在"));
        if (!accessPolicy.canWriteClinical(principal, assessment.getSubjectId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权复核该评估");
        }
        AssessmentReview.Decision parsed = parseDecision(decision);
        String text = comment == null ? "" : comment.trim();
        if (text.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "comment 不能为空");
        }
        if (text.length() > MAX_COMMENT_LENGTH) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "comment 长度不能超过 " + MAX_COMMENT_LENGTH);
        }

        Instant now = Instant.now(clock);
        return writeTransaction.execute(
                status -> {
                    // Conditional transition, not a blind overwrite: only a review that still sees
                    // PENDING may move the row. The loser of a race gets 0 rows here and is
                    // refused,
                    // instead of silently replacing the first reviewer's decision.
                    int updated =
                            assessmentRepository.updateReviewStatus(
                                    assessmentId,
                                    reviewStatusOf(parsed),
                                    RiskAssessment.ReviewStatus.PENDING);
                    if (updated == 0) {
                        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "该评估已被他人复核，请刷新");
                    }
                    return reviewRepository.saveAndFlush(
                            new AssessmentReview(
                                    assessmentId, principal.userId(), parsed, text, now));
                });
    }

    /** Review history of one assessment, newest first. Not part of the frozen contract yet. */
    public List<AssessmentReview> history(Long assessmentId, AppUserPrincipal principal) {
        RiskAssessment assessment =
                assessmentRepository
                        .findById(assessmentId)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.VALIDATION_ERROR, "评估结果不存在"));
        if (!accessPolicy.canReadRawScores(principal, assessment.getSubjectId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权查看该评估");
        }
        return reviewRepository.findByAssessmentIdOrderByReviewedAtDescIdDesc(assessmentId);
    }

    private static AssessmentReview.Decision parseDecision(String decision) {
        if (decision == null || decision.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "decision 不能为空");
        }
        try {
            return AssessmentReview.Decision.valueOf(decision.trim());
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "decision 仅支持 ACKNOWLEDGED / NEEDS_DATA / REJECTED");
        }
    }

    private static RiskAssessment.ReviewStatus reviewStatusOf(AssessmentReview.Decision decision) {
        return switch (decision) {
            case ACKNOWLEDGED -> RiskAssessment.ReviewStatus.ACKNOWLEDGED;
            case NEEDS_DATA -> RiskAssessment.ReviewStatus.NEEDS_DATA;
            case REJECTED -> RiskAssessment.ReviewStatus.REJECTED;
        };
    }
}
