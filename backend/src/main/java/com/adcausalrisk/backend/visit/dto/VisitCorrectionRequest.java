package com.adcausalrisk.backend.visit.dto;

import java.math.BigDecimal;

import com.adcausalrisk.backend.visit.entity.VisitModelInputs;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/subjects/{subjectId}/visits/{visitId}}.
 *
 * <p>{@code visitCode} is intentionally absent: a correction fixes the observation, not the
 * identity of the visit. A changed code would break the append-only version chain and make
 * {@code GET .../history} ambiguous.
 *
 * <p>The original row is never overwritten. The service appends a new {@code visit} row with
 * {@code version_no + 1}, records the reason/operator/time on that row, and flips the previous
 * version's {@code is_current} flag.
 */
public record VisitCorrectionRequest(
        @NotNull
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "格式须为 yyyy-MM-dd")
        String visitDate,
        @DecimalMin("0") @DecimalMax("30")
        @Digits(integer = 2, fraction = 61) BigDecimal mmse,
        @DecimalMin("0") @DecimalMax("30")
        @Digits(integer = 2, fraction = 61) BigDecimal moca,
        @DecimalMin("0") @DecimalMax("18")
        @Digits(integer = 2, fraction = 61) BigDecimal cdrSb,
        @NotBlank @Size(max = 500) String reason,
        @Valid VisitModelInputs modelInputs) {
}
