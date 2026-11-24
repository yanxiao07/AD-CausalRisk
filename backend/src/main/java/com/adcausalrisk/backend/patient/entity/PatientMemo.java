package com.adcausalrisk.backend.patient.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "patient_memo")
public class PatientMemo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "subject_id")
    private Long subjectId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PatientMemo() {
    }

    public PatientMemo(Long userId, Long subjectId, String content) {
        this.userId = userId;
        this.subjectId = subjectId;
        this.content = content;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getSubjectId() { return subjectId; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void editContent(String newContent) {
        if (newContent == null || newContent.isBlank()) {
            throw new com.adcausalrisk.backend.common.BusinessException(
                com.adcausalrisk.backend.common.ErrorCode.VALIDATION_ERROR, "备忘内容不能为空");
        }
        if (newContent.length() > 1000) {
            throw new com.adcausalrisk.backend.common.BusinessException(
                com.adcausalrisk.backend.common.ErrorCode.VALIDATION_ERROR, "备忘内容不能超过1000字");
        }
        this.content = newContent.trim();
        this.updatedAt = Instant.now();
    }
}
