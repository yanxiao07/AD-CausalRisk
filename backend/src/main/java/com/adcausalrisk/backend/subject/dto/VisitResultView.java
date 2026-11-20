package com.adcausalrisk.backend.subject.dto;

import com.adcausalrisk.backend.visit.entity.VisitModelInputs;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
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
 * and read back through {@code SubjectDetail.visits}. The scores are {@link BigDecimal},
 * the same type the entity stores, so no lossy {@code double} conversion happens anywhere on
 * the way out; the JSON shape is unchanged because the contract only says {@code number}.
 * A score that was never recorded stays {@code null} and is never reported as 0.
 */
public record VisitResultView(
        Long id,
        Long subjectId,
        String visitCode,
        LocalDate visitDate,
        BigDecimal mmse,
        BigDecimal moca,
        BigDecimal cdrSb,
        @JsonInclude(JsonInclude.Include.NON_NULL) VisitModelInputs modelInputs
) {
}
