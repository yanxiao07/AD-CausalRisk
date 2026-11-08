package com.adcausalrisk.backend.followup;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.followup.entity.FollowUpPlan;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectAccessPolicy;
import com.adcausalrisk.backend.subject.SubjectRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/** Creates, reschedules and closes follow-up plans, scoped to a subject the caller can see. */
@Service
public class FollowUpService {

    private static final int MAX_PURPOSE_LENGTH = 1000;

    private final FollowUpPlanRepository followUpRepository;
    private final SubjectRepository subjectRepository;
    private final SubjectAccessPolicy accessPolicy;
    private final TransactionTemplate writeTransaction;

    public FollowUpService(
            FollowUpPlanRepository followUpRepository,
            SubjectRepository subjectRepository,
            SubjectAccessPolicy accessPolicy,
            PlatformTransactionManager transactionManager) {
        this.followUpRepository = followUpRepository;
        this.subjectRepository = subjectRepository;
        this.accessPolicy = accessPolicy;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** {@code POST /api/follow-ups}. */
    public FollowUpPlan create(
            Long subjectId, Instant scheduledAt, String purpose, AppUserPrincipal principal) {
        if (scheduledAt == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "scheduledAt 不能为空");
        }
        String text = purpose == null ? "" : purpose.trim();
        if (text.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "purpose 不能为空");
        }
        if (text.length() > MAX_PURPOSE_LENGTH) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "purpose 长度不能超过 " + MAX_PURPOSE_LENGTH);
        }
        if (!subjectRepository.existsById(subjectId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "档案不存在");
        }
        requireWriteAccess(principal, subjectId);

        return writeTransaction.execute(
                status ->
                        followUpRepository.saveAndFlush(
                                new FollowUpPlan(
                                        subjectId, scheduledAt, text, principal.userId())));
    }

    /** {@code PATCH /api/follow-ups/{followUpId}} - reschedule, complete or cancel. */
    public FollowUpPlan update(
            Long followUpId,
            String status,
            Instant scheduledAt,
            String purpose,
            AppUserPrincipal principal) {
        return update(followUpId, status, scheduledAt, purpose, null, principal);
    }

    public FollowUpPlan update(
            Long followUpId,
            String status,
            Instant scheduledAt,
            String purpose,
            String notes,
            AppUserPrincipal principal) {
        FollowUpPlan plan =
                followUpRepository
                        .findById(followUpId)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.VALIDATION_ERROR, "随访计划不存在"));
        requireWriteAccess(principal, plan.getSubjectId());

        FollowUpPlan.Status parsed = parseStatus(status);
        if (purpose != null && purpose.trim().length() > MAX_PURPOSE_LENGTH) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "purpose 长度不能超过 " + MAX_PURPOSE_LENGTH);
        }
        return writeTransaction.execute(
                transaction -> {
                    FollowUpPlan managed = followUpRepository.findById(followUpId).orElseThrow();
                    managed.reschedule(scheduledAt, purpose);
                    managed.setNotes(notes);
                    if (parsed != null) {
                        managed.changeStatus(parsed);
                    }
                    return followUpRepository.saveAndFlush(managed);
                });
    }

    /** {@code GET /api/subjects/{subjectId}/follow-ups}. */
    public List<FollowUpPlan> list(Long subjectId, AppUserPrincipal principal) {
        if (!subjectRepository.existsById(subjectId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "档案不存在");
        }
        requireSubjectAccess(principal, subjectId);
        return followUpRepository.findBySubjectIdOrderByScheduledAtAscIdAsc(subjectId);
    }

    private void requireSubjectAccess(AppUserPrincipal principal, Long subjectId) {
        if (!accessPolicy.canView(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权访问该档案");
        }
    }

    private void requireWriteAccess(AppUserPrincipal principal, Long subjectId) {
        if (!accessPolicy.canWriteClinical(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "仅授权医生或管理员可维护随访");
        }
    }

    private static FollowUpPlan.Status parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return FollowUpPlan.Status.valueOf(status.trim());
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "status 仅支持 PLANNED / COMPLETED / CANCELLED");
        }
    }
}
