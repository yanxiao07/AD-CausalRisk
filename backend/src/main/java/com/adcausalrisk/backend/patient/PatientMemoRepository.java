package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.patient.entity.PatientMemo;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PatientMemoRepository extends JpaRepository<PatientMemo, Long> {

    List<PatientMemo> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<PatientMemo> findByIdAndUserId(Long id, Long userId);

    /**
     * Same lookup as {@link #findByIdAndUserId}, but holding the row lock for the rest of the
     * transaction.
     *
     * <p>编辑与确认都读写同一条备忘的原文和卡片集合，中间的「校验版本 → 删旧卡 → 写新卡」不是
     * 一个原子步骤：不加锁时，确认请求可以在版本校验通过后停顿，让另一笔编辑先提交，再按已经
     * 过期的原文写卡，结果患者看到的卡片和原文对不上。加锁后两笔请求串行，版本校验和写卡落在
     * 同一把锁的保护范围内。
     *
     * <p>Requires an active transaction; every caller here is {@code @Transactional}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from PatientMemo m where m.id = :id and m.userId = :userId")
    Optional<PatientMemo> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    long countByUserId(Long userId);
}