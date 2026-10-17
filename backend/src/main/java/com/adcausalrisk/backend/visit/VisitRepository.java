package com.adcausalrisk.backend.visit;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.adcausalrisk.backend.visit.entity.Visit;

/**
 * Data access for the {@code visit} table.
 *
 * <p>{@code visit_code} is unique only <i>within one subject</i> (the DDL constraint is
 * {@code uq_visit_subject_code UNIQUE (subject_id, visit_code)}), so every lookup below is
 * scoped by {@code subjectId} - a global {@code existsByVisitCode} would be wrong.
 */
@Repository
public interface VisitRepository extends JpaRepository<Visit, Long> {

    /**
     * Every visit of one subject, ordered by visit date and then by id so that
     * {@code SubjectDetail.visits} is deterministic. The frozen contract does not
     * prescribe an order for that array.
     */
    List<Visit> findBySubjectIdOrderByVisitDateAscIdAsc(Long subjectId);

    /** Duplicate check scoped to one subject, matching the DDL unique constraint. */
    boolean existsBySubjectIdAndVisitCode(Long subjectId, String visitCode);
}
