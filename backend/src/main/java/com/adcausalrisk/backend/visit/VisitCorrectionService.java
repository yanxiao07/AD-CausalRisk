package com.adcausalrisk.backend.visit;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adcausalrisk.backend.audit.AuditService;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.InputVersions;
import com.adcausalrisk.backend.subject.SubjectAccessPolicy;
import com.adcausalrisk.backend.subject.dto.VisitResultView;
import com.adcausalrisk.backend.visit.dto.VisitCorrectionRequest;
import com.adcausalrisk.backend.visit.dto.VisitVersionView;
import com.adcausalrisk.backend.visit.entity.Visit;

import jakarta.persistence.EntityManager;

/**
 * Appends a new version of a visit instead of overwriting the clinical cells.
 *
 * <p>This is deliberately separate from {@link VisitService}: creating a visit and correcting a
 * visit have different permissions, different history semantics and different audit events.
 */
@Service
public class VisitCorrectionService {

    private final VisitRepository visitRepository;
    private final SubjectAccessPolicy accessPolicy;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final AuditService auditService;
    private final Clock clock;

    public VisitCorrectionService(
            VisitRepository visitRepository,
            SubjectAccessPolicy accessPolicy,
            EntityManager entityManager,
            JdbcTemplate jdbc,
            ApplicationEventPublisher events,
            AuditService auditService,
            Clock clock) {
        this.visitRepository = visitRepository;
        this.accessPolicy = accessPolicy;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
        this.events = events;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public VisitResultView correct(
            Long subjectId, Long visitId, VisitCorrectionRequest request, AppUserPrincipal principal) {
        if (!accessPolicy.canManage(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权更正该档案的访视");
        }

        Visit current = visitRepository
                .findCurrentForUpdate(visitId, subjectId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "访视不存在或已被更正: " + visitId));

        LocalDate visitDate = parseVisitDate(request.visitDate());

        // Flip only the lifecycle flag; every clinical cell on the old row stays untouched.
        current.markSuperseded();
        visitRepository.saveAndFlush(current);

        long correctedBy = principal.userId();
        Visit next = new Visit(
                subjectId,
                current.getVisitCode(),
                visitDate,
                request.mmse(),
                request.moca(),
                request.cdrSb(),
                current.getVersionNo() + 1,
                current.getId(),
                request.reason().trim(),
                correctedBy,
                LocalDateTime.now(clock),
                true);
        Visit saved = visitRepository.saveAndFlush(next);
        entityManager.refresh(saved);

        // Advance the subject's input version after the new row exists, so a snapshot taken after
        // the correction cannot bind the superseded version.
        String version = InputVersions.advance(jdbc, subjectId);
        jdbc.update(
                "UPDATE visit SET data_version=?,source='correction' WHERE id=?",
                version,
                saved.getId());

        auditService.recordQuietly(
                correctedBy,
                "DATA_VISIT_CORRECTED",
                "visitId=" + visitId + " version=" + saved.getVersionNo());

        events.publishEvent(new VisitCreatedEvent(saved.getId(), correctedBy));
        return VisitMapper.toResultView(saved);
    }

    @Transactional(readOnly = true)
    public List<VisitVersionView> history(
            Long subjectId, Long visitId, AppUserPrincipal principal) {
        if (!accessPolicy.canReadRawScores(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权查看该档案的访视更正记录");
        }
        Visit any = visitRepository
                .findById(visitId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "访视不存在: " + visitId));
        if (!subjectId.equals(any.getSubjectId())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "访视不属于该档案");
        }
        return visitRepository
                .findBySubjectIdAndVisitCodeOrderByVersionNoAsc(subjectId, any.getVisitCode())
                .stream()
                .map(VisitCorrectionService::toVersionView)
                .toList();
    }

    private static VisitVersionView toVersionView(Visit visit) {
        return new VisitVersionView(
                visit.getId(),
                visit.getVersionNo(),
                visit.isCurrent(),
                visit.getVisitCode(),
                visit.getVisitDate(),
                exact(visit.getRawMmse(), visit.getMmse()),
                exact(visit.getRawMoca(), visit.getMoca()),
                exact(visit.getRawCdrSb(), visit.getCdrSb()),
                visit.getCorrectionReason(),
                visit.getCorrectedBy(),
                visit.getCorrectedAt());
    }

    private static BigDecimal exact(String raw, BigDecimal derived) {
        return raw != null ? new BigDecimal(raw) : derived;
    }

    private static LocalDate parseVisitDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "visitDate 不是有效日期");
        }
    }
}
