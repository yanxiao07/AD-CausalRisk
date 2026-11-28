package com.adcausalrisk.backend.patient.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 服务端为一次预览绑定下来的草稿批次。
 *
 * <p>确认接口不再相信客户端提交的卡片文字：预览时把整批草稿连同原文版本写进这张表，
 * 确认时只按 {@code previewId} 取回这一批并原样发布。患者看到的和存进复习队列的因此是
 * 同一批内容——即使有人把预览答案从「周五」改成「下周一去公园」，服务端也只认自己存下的那批。
 */
@Entity
@Table(name = "patient_card_draft_preview")
public class PatientCardDraftPreview {

    @Id
    @Column(name = "preview_id", nullable = false, length = 64)
    private String previewId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "memo_id", nullable = false)
    private Long memoId;

    @Column(name = "memo_version", nullable = false, length = 128)
    private String memoVersion;

    @Column(name = "drafts_json", nullable = false, columnDefinition = "TEXT")
    private String draftsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PatientCardDraftPreview() {
    }

    public PatientCardDraftPreview(String previewId, Long userId, Long memoId,
                                   String memoVersion, String draftsJson) {
        this.previewId = previewId;
        this.userId = userId;
        this.memoId = memoId;
        this.memoVersion = memoVersion;
        this.draftsJson = draftsJson;
        this.createdAt = Instant.now();
    }

    public String getPreviewId() { return previewId; }
    public Long getUserId() { return userId; }
    public Long getMemoId() { return memoId; }
    public String getMemoVersion() { return memoVersion; }
    public String getDraftsJson() { return draftsJson; }
    public Instant getCreatedAt() { return createdAt; }
}