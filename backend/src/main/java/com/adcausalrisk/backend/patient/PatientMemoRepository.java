package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.patient.entity.PatientMemo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PatientMemoRepository extends JpaRepository<PatientMemo, Long> {

    List<PatientMemo> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<PatientMemo> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);
}
