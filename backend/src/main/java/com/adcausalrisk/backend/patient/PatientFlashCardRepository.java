package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.patient.entity.PatientFlashCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PatientFlashCardRepository extends JpaRepository<PatientFlashCard, Long> {

    List<PatientFlashCard> findByMemoIdOrderByCreatedAtAsc(Long memoId);

    Optional<PatientFlashCard> findByIdAndMemoId(Long id, Long memoId);

    void deleteByMemoId(Long memoId);

    long countByMemoId(Long memoId);
}
