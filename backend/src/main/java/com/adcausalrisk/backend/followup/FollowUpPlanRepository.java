package com.adcausalrisk.backend.followup;

import java.util.List;
import java.util.Optional;

import com.adcausalrisk.backend.followup.entity.FollowUpPlan;
import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface FollowUpPlanRepository extends JpaRepository<FollowUpPlan, Long> {

    List<FollowUpPlan> findBySubjectIdOrderByScheduledAtAscIdAsc(Long subjectId);

    Optional<FollowUpPlan> findByIdAndSubjectId(Long id, Long subjectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select plan from FollowUpPlan plan where plan.id = :id")
    Optional<FollowUpPlan> findForUpdate(Long id);
}
