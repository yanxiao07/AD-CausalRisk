package com.adcausalrisk.backend.assessment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.adcausalrisk.backend.assessment.entity.RiskAssessment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, Long> {

    Optional<RiskAssessment> findByTaskId(Long taskId);

    Optional<RiskAssessment> findByTaskIdAndSubjectId(Long taskId, Long subjectId);

    /** {@code GET /api/assessments}: only the subjects the caller may see are paged. */
    Page<RiskAssessment> findBySubjectIdIn(Collection<Long> subjectIds, Pageable pageable);

    /** Used by the task list to map {@code taskId -> assessmentId} in one query instead of N+1. */
    List<RiskAssessment> findByTaskIdIn(Collection<Long> taskIds);

    /** The patient summary always describes the most recent result for the bound subject. */
    Optional<RiskAssessment> findFirstBySubjectIdOrderByIdDesc(Long subjectId);

    /**
     * Conditional review-status transition: only the caller that observes {@code current} may
     * write {@code next}. Two reviewers race on the same row, the database serialises them and
     * exactly one sees {@code 1}; the other gets {@code 0} and is refused instead of silently
     * overwriting the first decision.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RiskAssessment r set r.reviewStatus = :next"
            + " where r.id = :id and r.reviewStatus = :current")
    int updateReviewStatus(
            @Param("id") Long id,
            @Param("next") RiskAssessment.ReviewStatus next,
            @Param("current") RiskAssessment.ReviewStatus current);
}
