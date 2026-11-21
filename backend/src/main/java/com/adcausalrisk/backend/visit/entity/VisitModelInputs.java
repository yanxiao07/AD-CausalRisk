package com.adcausalrisk.backend.visit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

import java.math.BigDecimal;

/** Optional measurements recorded alongside the scores of one visit version. */
@Embeddable
public record VisitModelInputs(
        @Column(name = "age_years", precision = 9, scale = 6)
        @DecimalMin("0") @DecimalMax("120") @Digits(integer = 3, fraction = 6) BigDecimal ageYears,
        @Column(name = "education_years", precision = 9, scale = 6)
        @DecimalMin("0") @DecimalMax("40") @Digits(integer = 3, fraction = 6) BigDecimal educationYears,
        @Column(name = "cdr_global", precision = 9, scale = 6)
        @DecimalMin("0") @DecimalMax("3") @Digits(integer = 3, fraction = 6) BigDecimal cdrGlobal,
        @Column(name = "adas_cog13", precision = 9, scale = 6)
        @DecimalMin("0") @DecimalMax("85") @Digits(integer = 3, fraction = 6) BigDecimal adasCog13,
        @Column(name = "faq_total", precision = 9, scale = 6)
        @DecimalMin("0") @DecimalMax("30") @Digits(integer = 3, fraction = 6) BigDecimal faqTotal) {
    public boolean complete() {
        return ageYears != null && educationYears != null && cdrGlobal != null && adasCog13 != null && faqTotal != null;
    }
}
