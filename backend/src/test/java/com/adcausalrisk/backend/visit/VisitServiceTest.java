package com.adcausalrisk.backend.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectAccessPolicy;
import com.adcausalrisk.backend.subject.SubjectPermissionRepository;
import com.adcausalrisk.backend.subject.SubjectRepository;
import com.adcausalrisk.backend.visit.dto.VisitCreateRequest;
import com.adcausalrisk.backend.visit.entity.Visit;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;

/**
 * Pure Mockito unit tests (no Spring context, no database) for the one branch of {@link
 * VisitService} that cannot be reached deterministically through the API: the database unique
 * constraint firing after the duplicate pre-check has already passed.
 *
 * <p>That window exists because the service checks for a duplicate and then inserts. A request that
 * loses the race has to be answered with 400 {@code VALIDATION_ERROR} like any other duplicate -
 * never with a 500 - so this branch needs its own coverage rather than being assumed.
 *
 * <p>It is deliberately kept out of {@link VisitApiTest}: making the integration suite depend on a
 * mocked repository would either need a stubbed Spring context (an extra cached context) or produce
 * a non-deterministic concurrency test.
 */
class VisitServiceTest {

    private static final long SUBJECT_ID = 7L;

    private static final String VISIT_CODE = "BL";

    private static final AppUserPrincipal ADMIN =
            new AppUserPrincipal(1L, "admin", "", List.of("ADMIN"), true);

    private static final VisitCreateRequest REQUEST =
            new VisitCreateRequest(
                    VISIT_CODE, "2025-01-01", new BigDecimal("28.0"), null, new BigDecimal("0.5"));

    @Test
    void duplicateThatSlipsPastThePreCheckBecomesAValidationErrorAndNotAServerError() {
        VisitRepository visitRepository = mock(VisitRepository.class);
        SubjectRepository subjectRepository = mock(SubjectRepository.class);
        // The real policy: canCreate only inspects the principal's role codes.
        SubjectAccessPolicy accessPolicy =
                new SubjectAccessPolicy(mock(SubjectPermissionRepository.class));

        given(subjectRepository.existsById(SUBJECT_ID)).willReturn(true);
        given(visitRepository.existsBySubjectIdAndVisitCode(SUBJECT_ID, VISIT_CODE))
                .willReturn(false);
        given(visitRepository.saveAndFlush(any(Visit.class)))
                .willThrow(new DataIntegrityViolationException("uq_visit_subject_code"));

        VisitService service =
                new VisitService(
                        visitRepository,
                        subjectRepository,
                        accessPolicy,
                        mock(EntityManager.class),
                        mock(ApplicationEventPublisher.class),
                        mock(org.springframework.jdbc.core.JdbcTemplate.class));

        assertThatThrownBy(() -> service.createVisit(SUBJECT_ID, REQUEST, ADMIN))
                .isInstanceOf(BusinessException.class)
                // The message is deliberately neutral: any integrity violation lands here, not
                // only a duplicate visit code.
                .hasMessageContaining("无法保存访视")
                .satisfies(
                        thrown -> {
                            ErrorCode errorCode = ((BusinessException) thrown).errorCode();
                            assertThat(errorCode).isEqualTo(ErrorCode.VALIDATION_ERROR);
                            // The whole point of the branch: 400, not 500.
                            assertThat(errorCode.status().value()).isEqualTo(400);
                        });
    }

    /**
     * {@code @Pattern} only guarantees the shape, so a shape-valid but impossible date reaches the
     * service. It must be a business failure, not a {@code DateTimeParseException} (500).
     */
    @Test
    void impossibleCalendarDateBecomesAValidationError() {
        VisitRepository visitRepository = mock(VisitRepository.class);
        SubjectRepository subjectRepository = mock(SubjectRepository.class);
        SubjectAccessPolicy accessPolicy =
                new SubjectAccessPolicy(mock(SubjectPermissionRepository.class));
        VisitCreateRequest impossible =
                new VisitCreateRequest(
                        VISIT_CODE, "2025-02-30", new BigDecimal("28.0"), null, null);

        VisitService service =
                new VisitService(
                        visitRepository,
                        subjectRepository,
                        accessPolicy,
                        mock(EntityManager.class),
                        mock(ApplicationEventPublisher.class),
                        mock(org.springframework.jdbc.core.JdbcTemplate.class));

        assertThatThrownBy(() -> service.createVisit(SUBJECT_ID, impossible, ADMIN))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("visitDate")
                .satisfies(
                        thrown ->
                                assertThat(((BusinessException) thrown).errorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
