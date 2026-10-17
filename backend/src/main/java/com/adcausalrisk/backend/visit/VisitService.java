package com.adcausalrisk.backend.visit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectAccessPolicy;
import com.adcausalrisk.backend.subject.SubjectRepository;
import com.adcausalrisk.backend.subject.dto.VisitResultView;
import com.adcausalrisk.backend.visit.dto.VisitCreateRequest;
import com.adcausalrisk.backend.visit.entity.Visit;

/**
 * Business logic for the single visit operation of the frozen contract,
 * {@code POST /api/subjects/{subjectId}/visits}.
 *
 * <p>Ordering of the checks matters: permission first, then subject existence, then the
 * duplicate visit code, and only then the insert.
 *
 * <p>There is deliberately no read method here. The contract exposes no visit collection
 * endpoint; visits are read through {@code SubjectDetail.visits}, which the subject module
 * assembles in {@code SubjectService.getSubjectDetail}.
 */
@Service
public class VisitService {

    private static final Logger log = LoggerFactory.getLogger(VisitService.class);

    private final VisitRepository visitRepository;

    private final SubjectRepository subjectRepository;

    private final SubjectAccessPolicy accessPolicy;

    public VisitService(VisitRepository visitRepository,
                        SubjectRepository subjectRepository,
                        SubjectAccessPolicy accessPolicy) {
        this.visitRepository = visitRepository;
        this.subjectRepository = subjectRepository;
        this.accessPolicy = accessPolicy;
    }

    /**
     * Creates one visit for one subject.
     *
     * <p>{@code ADMIN} and {@code RESEARCHER} may create visits; everyone else receives
     * {@code ACCESS_DENIED} (403). This reuses {@link SubjectAccessPolicy#canCreate}, the same
     * rule that guards subject creation.
     *
     * <p>No further per-subject check is needed here <i>today</i>: {@code canCreate} currently
     * means "role is ADMIN or RESEARCHER", and both of those roles are also
     * {@code FULL_VIEW_ROLES} in {@link SubjectAccessPolicy}, so anyone allowed to create can
     * already see every subject. That coupling is intentional but implicit - if
     * {@code canCreate} is ever narrowed to a per-subject {@code MANAGE} rule, a
     * {@code canView}/grant check must be added at this point.
     *
     * <p>A missing subject is reported as 400 {@code VALIDATION_ERROR} rather than 404,
     * matching the convention the subject module established (the frozen contract declares no
     * 404 for business resources). Checking it up front also keeps the
     * {@code fk_visit_subject} constraint from turning into a 500.
     */
    @Transactional
    public VisitResultView createVisit(Long subjectId, VisitCreateRequest request,
                                       AppUserPrincipal principal) {
        if (!accessPolicy.canCreate(principal)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权创建访视");
        }

        if (!subjectRepository.existsById(subjectId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "档案不存在: " + subjectId);
        }

        if (visitRepository.existsBySubjectIdAndVisitCode(subjectId, request.visitCode())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "visitCode 已存在: " + request.visitCode());
        }

        try {
            Visit saved = visitRepository.save(VisitMapper.toEntity(subjectId, request));
            return VisitMapper.toResultView(saved);
        } catch (DataIntegrityViolationException exception) {
            // Backstop for the window between the duplicate check above and the insert, and
            // for engine-specific key comparison (MySQL's default collation is
            // case-insensitive, so 'BL' and 'bl' are the same key there). The DDL constraint
            // uq_visit_subject_code still holds; this only decides the HTTP answer the caller
            // gets - 400, never a 500.
            log.debug("visit insert rejected by a database constraint", exception);
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "visitCode 已存在: " + request.visitCode());
        }
    }
}
