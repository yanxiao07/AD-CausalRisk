package com.adcausalrisk.backend.assessment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.adcausalrisk.backend.assessment.entity.AssessmentTask;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentTaskRepository extends JpaRepository<AssessmentTask, Long> {

    Optional<AssessmentTask> findByRunId(String runId);

    List<AssessmentTask> findBySubjectIdOrderByIdDesc(Long subjectId);

    /** {@code GET /api/assessment-tasks}: only the subjects the caller may see are paged. */
    Page<AssessmentTask> findBySubjectIdIn(Collection<Long> subjectIds, Pageable pageable);
}
