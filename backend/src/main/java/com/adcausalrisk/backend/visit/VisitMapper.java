package com.adcausalrisk.backend.visit;

import java.math.BigDecimal;

import com.adcausalrisk.backend.subject.dto.VisitResultView;
import com.adcausalrisk.backend.visit.dto.VisitCreateRequest;
import com.adcausalrisk.backend.visit.entity.Visit;

/**
 * Hand-written Entity ↔ DTO converters for the visit module, following the same style as
 * {@link com.adcausalrisk.backend.subject.SubjectMapper}: a plain static utility rather than
 * a Spring bean, and no mapping library anywhere in this project.
 */
public final class VisitMapper {

    private VisitMapper() {
        // utility class - no instances
    }

    /**
     * Builds a fresh entity for one subject. {@code id} is owned by the database, and
     * {@code subjectId} comes from the request path rather than the body.
     */
    public static Visit toEntity(Long subjectId, VisitCreateRequest request) {
        return new Visit(
                subjectId,
                request.visitCode(),
                request.visitDate(),
                request.mmse(),
                request.moca(),
                request.cdrSb());
    }

    /**
     * Maps a {@link Visit} to the contract's {@code VisitResult} shape, which is also the
     * element type of {@code SubjectDetail.visits}.
     *
     * <p>The entity keeps the scores as {@link BigDecimal} (required by
     * {@code ddl-auto: validate} against {@code DECIMAL(4,1)}) while
     * {@link VisitResultView} exposes them as {@code Double}; the conversion happens here.
     * A {@code NULL} score stays JSON null - it is never reported as 0.
     */
    public static VisitResultView toResultView(Visit visit) {
        return new VisitResultView(
                visit.getId(),
                visit.getSubjectId(),
                visit.getVisitCode(),
                visit.getVisitDate(),
                toDouble(visit.getMmse()),
                toDouble(visit.getMoca()),
                toDouble(visit.getCdrSb()));
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
