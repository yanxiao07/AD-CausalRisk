package com.adcausalrisk.backend.visit;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

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

    /** Latest effective versions only, ordered deterministically for SubjectDetail. */
    List<Visit> findBySubjectIdAndCurrentTrueOrderByVisitDateAscIdAsc(Long subjectId);

    /** One effective version, scoped to its subject. */
    Optional<Visit> findByIdAndSubjectIdAndCurrentTrue(Long id, Long subjectId);

    /** Row lock for an append-only correction; serialises concurrent corrections. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Visit v where v.id = :id and v.subjectId = :subjectId and v.current = true")
    Optional<Visit> findCurrentForUpdate(@Param("id") Long id, @Param("subjectId") Long subjectId);

    /** Full append-only chain, oldest first. */
    List<Visit> findBySubjectIdAndVisitCodeOrderByVersionNoAsc(Long subjectId, String visitCode);

    /** Duplicate check scoped to one subject; versions keep the same logical code. */
    boolean existsBySubjectIdAndVisitCode(Long subjectId, String visitCode);
}
