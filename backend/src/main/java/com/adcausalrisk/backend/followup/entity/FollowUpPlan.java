package com.adcausalrisk.backend.followup.entity;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One planned follow-up visit for a subject (V1 {@code follow_up_plan}, FR-FOLLOW-02). */
@Entity
@Table(name = "follow_up_plan")
public class FollowUpPlan {

    /** {@code ck_follow_up_plan_status}. */
    public enum Status {
        PLANNED,
        COMPLETED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "purpose", nullable = false, length = 1000)
    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "notes", length = 1000)
    private String notes;

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        if (notes != null && notes.length() > 1000)
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "备注不能超过1000字");
        if (notes != null) this.notes = notes.trim();
    }

    protected FollowUpPlan() {
        // for JPA
    }

    public FollowUpPlan(Long subjectId, Instant scheduledAt, String purpose, Long ownerId) {
        this.subjectId = subjectId;
        this.scheduledAt = scheduledAt;
        this.purpose = purpose;
        this.ownerId = ownerId;
        this.status = Status.PLANNED;
    }

    public void reschedule(Instant scheduledAt, String purpose) {
        if (this.status != Status.PLANNED && (scheduledAt != null || purpose != null)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "已完结或取消的随访不可修改");
        }
        if (scheduledAt != null) {
            this.scheduledAt = scheduledAt;
        }
        if (purpose != null && !purpose.isBlank()) {
            this.purpose = purpose;
        }
    }

    public void changeStatus(Status status) {
        if (status == null) {
            return;
        }
        // The CHECK constraint only knows the three values; the transition rules live here so a
        // cancelled plan cannot be revived and a completed one cannot fall back to PLANNED.
        if (this.status == Status.CANCELLED) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "已取消的随访不可再变更");
        }
        if (this.status == Status.COMPLETED && status == Status.PLANNED) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不可退回未执行状态");
        }
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public String getPurpose() {
        return purpose;
    }

    public Status getStatus() {
        return status;
    }

    public Long getOwnerId() {
        return ownerId;
    }
}
