package com.adcausalrisk.backend.visit.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/subjects/{subjectId}/visits}, matching the contract's
 * {@code Visit} schema ({@code id} and {@code subjectId} are server-owned and are not part
 * of the request).
 *
 * <p>Every constraint below is a deliberate mirror of the database so that a bad request is
 * answered with 400 {@code VALIDATION_ERROR} in the unified Envelope instead of reaching the
 * DDL, where it would surface as a constraint violation:
 *
 * <ul>
 *   <li>{@code @NotBlank @Size(max = 50)} mirrors {@code visit_code VARCHAR(50) NOT NULL}
 *       and the contract's {@code minLength: 1, maxLength: 50}.</li>
 *   <li>{@code @NotNull} on {@code visitDate} mirrors {@code visit_date DATE NOT NULL}. The
 *       contract's {@code format: date} and {@code ^\d{4}-\d{2}-\d{2}$} pattern come for free
 *       from {@link LocalDate} plus Jackson's ISO-8601 handling: a malformed date string fails
 *       during deserialization, i.e. 400 {@code MALFORMED_JSON}, while a missing date fails
 *       {@code @NotNull}, i.e. 400 {@code VALIDATION_ERROR}.</li>
 *   <li>{@code @DecimalMin}/{@code @DecimalMax} mirror the {@code ck_visit_*} CHECK
 *       constraints. {@code @Min}/{@code @Max} are not used because they do not support
 *       {@link BigDecimal}.</li>
 *   <li>{@code @Digits(integer = 3, fraction = 1)} mirrors {@code DECIMAL(4,1)}. Accepting a
 *       second decimal place would let MySQL round the submitted value silently, which would
 *       change recorded source data; rejecting it keeps client input and stored data equal.</li>
 * </ul>
 *
 * <p>The three scores are optional. Omitting one stores {@code NULL} and the response reports
 * JSON {@code null} - a missing score is never turned into 0.
 */
public record VisitCreateRequest(
        @NotBlank @Size(max = 50) String visitCode,
        @NotNull LocalDate visitDate,
        @DecimalMin("0") @DecimalMax("30") @Digits(integer = 3, fraction = 1) BigDecimal mmse,
        @DecimalMin("0") @DecimalMax("30") @Digits(integer = 3, fraction = 1) BigDecimal moca,
        @DecimalMin("0") @DecimalMax("18") @Digits(integer = 3, fraction = 1) BigDecimal cdrSb) {
}
