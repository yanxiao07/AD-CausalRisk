package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.patient.entity.PatientFlashCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PatientFlashCardRepository extends JpaRepository<PatientFlashCard, Long> {

    List<PatientFlashCard> findByMemoIdOrderByCreatedAtAsc(Long memoId);

    Optional<PatientFlashCard> findByIdAndMemoId(Long id, Long memoId);

    void deleteByMemoId(Long memoId);

    long countByMemoId(Long memoId);

    /**
     * Due cards for the given memos only. Scoping by memo ids (which the caller derives from the
     * patient's own rows) keeps another patient's cards out of the queue at the query level
     * instead of loading every card and filtering in memory.
     */
    @Query("""
            SELECT c FROM PatientFlashCard c
            WHERE c.memoId IN :memoIds
              AND c.nextReviewAt IS NOT NULL
              AND c.nextReviewAt <= :now
            ORDER BY c.nextReviewAt ASC
            """)
    List<PatientFlashCard> findDueByMemoIds(@Param("memoIds") Collection<Long> memoIds,
                                            @Param("now") Instant now);
}