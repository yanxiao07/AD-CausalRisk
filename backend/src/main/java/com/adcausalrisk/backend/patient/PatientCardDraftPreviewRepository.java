package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.patient.entity.PatientCardDraftPreview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PatientCardDraftPreviewRepository extends JpaRepository<PatientCardDraftPreview, String> {

    Optional<PatientCardDraftPreview> findByPreviewIdAndUserIdAndMemoId(String previewId, Long userId, Long memoId);

    /**
     * 立刻执行的批量删除：同一备忘重新预览时会先删旧批次再插新批次，
     * 而 (user_id, memo_id) 上有唯一索引。若走「查询后逐条删除」的派生删除，
     * DELETE 会被推迟到 flush，可能排在 INSERT 之后并撞上唯一约束。
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from PatientCardDraftPreview p where p.memoId = :memoId")
    void deleteByMemoId(@Param("memoId") Long memoId);
}