package com.adcausalrisk.backend.subject.dto;

import java.time.LocalDate;

/**
 * Response item shape for {@code VisitResult}. The frozen contract requires this
 * type as the element of {@code SubjectDetail.visits}, which must always be an
 * array (empty or populated).
 *
 * <p>All seven fields match the contract:
 * <pre>id, subjectId, visitCode, visitDate (date), mmse (number|null),
 * moca (number|null), cdrSb (number|null)</pre>
 *
 * <p>Populated by {@code POST /api/subjects/{subjectId}/visits}, which persists the row,
 * and read back through {@code SubjectDetail.visits}. The scores are {@code Double} here
 * while the {@code Visit} entity stores {@link java.math.BigDecimal} - required by
 * {@code ddl-auto: validate} against the DDL's {@code DECIMAL(4,1)} - so
 * {@code VisitMapper} performs the conversion. A score that was never recorded stays
 * {@code null} and is never reported as 0.
 */
public record VisitResultView(
        Long id,
        Long subjectId,
        String visitCode,
        LocalDate visitDate,
        Double mmse,
        Double moca,
        Double cdrSb
) {
}