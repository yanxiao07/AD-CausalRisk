package com.adcausalrisk.backend.visit.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One immutable version in the visit correction chain.
 *
 * <p>The first version is the original observation ({@code version=1}, {@code current=false}
 * once corrected, {@code reason=null}). Every correction appends a new row with a higher
 * version, the reason, the operator and the timestamp.
 */
public record VisitVersionView(
        Long id,
        int version,
        boolean current,
        String visitCode,
        LocalDate visitDate,
        BigDecimal mmse,
        BigDecimal moca,
        BigDecimal cdrSb,
        String reason,
        Long correctedBy,
        LocalDateTime correctedAt) {
}
