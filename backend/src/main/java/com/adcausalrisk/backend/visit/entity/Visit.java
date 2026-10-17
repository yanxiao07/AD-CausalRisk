package com.adcausalrisk.backend.visit.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A single visit from the {@code visit} table.
 *
 * <p>The three scores are {@link BigDecimal} on purpose: the DDL declares
 * {@code DECIMAL(4,1)} and {@code ddl-auto: validate} compares the mapped type against the
 * column type, so a {@code Double} field would abort startup validation.
 *
 * <p>There is no {@code @ManyToOne} to {@code Subject}. A visit row never needs to navigate
 * to the subject, and the subject module models its own foreign keys the same way
 * ({@code SubjectPermission} holds a bare {@code Long subjectId}). The database still
 * enforces {@code fk_visit_subject}, and the service checks the subject exists before
 * inserting.
 *
 * <p>A score that was not recorded stays {@code null}: missing values are preserved, never
 * replaced by 0 (the DDL CHECKs are written as {@code mmse IS NULL OR (...)} for this
 * reason).
 */
@Entity
@Table(name = "visit")
public class Visit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "visit_code", nullable = false, length = 50)
    private String visitCode;

    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    /** {@code precision}/{@code scale} mirror the DDL so this entity documents the schema. */
    @Column(name = "mmse", precision = 4, scale = 1)
    private BigDecimal mmse;

    @Column(name = "moca", precision = 4, scale = 1)
    private BigDecimal moca;

    @Column(name = "cdr_sb", precision = 4, scale = 1)
    private BigDecimal cdrSb;

    protected Visit() {
        // for JPA
    }

    public Visit(Long subjectId, String visitCode, LocalDate visitDate,
                 BigDecimal mmse, BigDecimal moca, BigDecimal cdrSb) {
        this.subjectId = subjectId;
        this.visitCode = visitCode;
        this.visitDate = visitDate;
        this.mmse = mmse;
        this.moca = moca;
        this.cdrSb = cdrSb;
    }

    public Long getId() {
        return id;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public String getVisitCode() {
        return visitCode;
    }

    public LocalDate getVisitDate() {
        return visitDate;
    }

    public BigDecimal getMmse() {
        return mmse;
    }

    public BigDecimal getMoca() {
        return moca;
    }

    public BigDecimal getCdrSb() {
        return cdrSb;
    }
}
