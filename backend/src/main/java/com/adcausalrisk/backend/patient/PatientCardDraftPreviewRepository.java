package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.patient.entity.PatientCardDraftPreview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PatientCardDraftPreviewRepository extends JpaRepository<PatientCardDraftPreview, String> {

    Optional<PatientCardDraftPreview> findByPreviewIdAndUserIdAndMemoId(String previewId, Long userId, Long memoId);

    void deleteByMemoId(Long memoId);
}