package com.adcausalrisk.backend.visit.dto;

import java.math.BigDecimal;

import com.adcausalrisk.backend.validation.ContractGroup;
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
 * Body of {@code POST /api/subjects/{subjectId}/visits}, matching the contract's
 * {@code Visit} schema ({@code id} and {@code subjectId} are server-owned and are not part
 * of the request).
 *
 * <p>The constraints below mirror the parts of the database that a request can violate, so that
 * a bad request is answered with 400 {@code VALIDATION_ERROR} in the unified Envelope instead of
 * reaching the DDL, where it would surface as a constraint violation:
 *
 * <ul>
 *   <li>{@code @NotBlank @Size(max = 50)} mirrors {@code visit_code VARCHAR(50) NOT NULL}
 *       and the contract's {@code minLength: 1, maxLength: 50}.</li>
 *   <li>{@code visitDate} is a {@code String} pinned by {@code @Pattern} to the contract's
 *       {@code ^\d{4}-\d{2}-\d{2}$}, not a {@link java.time.LocalDate}. A {@code LocalDate}
 *       field does <i>not</i> enforce that shape: Jackson parses it with ISO-8601, whose year
 *       accepts a sign and a variable number of digits, so {@code +02025-01-01} would silently
 *       become 2025-01-01 and {@code 2025-1-1} would pass as well. The pattern rejects both with
 *       400 {@code VALIDATION_ERROR}, and a missing value fails {@code @NotNull}. The service
 *       then parses the text, so a shape-valid but impossible date ({@code 2025-02-30}) is also
 *       a 400 rather than a 500.</li>
 *   <li>{@code @DecimalMin} is always enforced (default validation group {@code Default}).
 *       {@code @DecimalMax} is gated behind {@link ContractGroup} so callers that explicitly
 *       opt in to QC-tolerant mode (via {@code X-Allow-OOR: true} header) can submit values
 *       beyond the clinical range — they pass validation here and the QC layer catches them
 *       instead of a 400. Python clients that do not send the header keep the contract's
 *       hard ceiling and receive 400 on out-of-range scores.</li>
 *   <li>Precision is not a business rule here. The contract declares a plain {@code number} with
 *       only a min/max range, so a value such as {@code 27.55} must be accepted and stored
 *       exactly as submitted: migration {@code V4} added {@code raw_*} text columns that keep the
 *       original value, while the {@code DECIMAL(5,2)} columns hold a rounded copy for display.
 *       {@code @Digits(integer = 2, fraction = 61)} is therefore a <b>storage-fit</b> guard, not
 *       a precision preference: a value in 0-30 written out with at most 61 decimals fits the
 *       {@code VARCHAR(64)} raw column, and anything longer is rejected with a clear 400 instead
 *       of failing inside the database (where MySQL could even truncate it silently).</li>
 * </ul>
 *
 * <p>The three scores are optional. Omitting one stores {@code NULL} and the response reports
 * JSON {@code null} - a missing score is never turned into 0.
 */
public record VisitCreateRequest(
        @NotBlank @Size(max = 50) String visitCode,
        @NotNull
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "格式须为 yyyy-MM-dd")
        String visitDate,
        @DecimalMin("0") @DecimalMax(value = "30", groups = ContractGroup.class)
        @Digits(integer = 2, fraction = 61) BigDecimal mmse,
        @DecimalMin("0") @DecimalMax(value = "30", groups = ContractGroup.class)
        @Digits(integer = 2, fraction = 61) BigDecimal moca,
        @DecimalMin("0") @DecimalMax(value = "18", groups = ContractGroup.class)
        @Digits(integer = 2, fraction = 61) BigDecimal cdrSb,
        @Valid VisitModelInputs modelInputs) {
    public VisitCreateRequest(String visitCode, String visitDate, BigDecimal mmse, BigDecimal moca, BigDecimal cdrSb) {
        this(visitCode, visitDate, mmse, moca, cdrSb, null);
    }
}
