package com.adcausalrisk.backend.assessment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One persisted assessment request, mapped onto the V1 {@code assessment_task} table.
 *
 * <p>The row exists before the Python call and keeps its {@code runId}, so a crash or a timeout
 * still leaves an auditable record that can be reconciled through {@code GET /runs/{runId}}. {@code
 * runId} is UNIQUE: a retry must reuse the original id instead of minting a second run.
 */
@Entity
@Table(name = "assessment_task")
public class AssessmentTask {

    /**
     * {@code ck_assessment_task_status}; FAILED is a Java-side state, Python only returns
     * COMPLETED/BLOCKED.
     */
    public enum Status {
        PENDING,
        RUNNING,
        COMPLETED,
        BLOCKED,
        FAILED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "run_id", length = 80)
    private String runId;

    @Column(name = "request_digest", nullable = false, length = 64)
    private String requestDigest;

    /** Stable failure code (AGENT_TIMEOUT, AGENT_UNAVAILABLE, TASK_PERSIST_FAILED, ...). */
    @Column(name = "error_code", length = 60)
    private String errorCode;

    /** Fixed user-facing reason; never a stack trace, upstream body or host address. */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AssessmentTask() {
        // for JPA
    }

    public AssessmentTask(Long subjectId, String runId, String requestDigest, Instant now) {
        this.subjectId = subjectId;
        this.runId = runId;
        this.requestDigest = requestDigest;
        this.status = Status.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markCompleted(Instant now) {
        this.status = Status.COMPLETED;
        clearError();
        this.updatedAt = now;
    }

    public void markRunning(Instant now) {
        this.status = Status.RUNNING;
        this.updatedAt = now;
    }

    public void markBlocked(Instant now) {
        this.status = Status.BLOCKED;
        clearError();
        this.updatedAt = now;
    }

    public void markFailed(Instant now) {
        markFailed(now, null, null);
    }

    /**
     * Closes the task as FAILED and records why.
     *
     * <p>Both values are meant to be stable and safe to re-read later: {@code errorCode} is one of
     * the published {@code AGENT_*} constants and {@code errorMessage} is a fixed sentence, so a
     * re-fetch (or an idempotent replay) can explain the failure without persisting a stack trace,
     * an upstream response body or a host address.
     */
    public void markFailed(Instant now, String errorCode, String errorMessage) {
        this.status = Status.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.updatedAt = now;
    }

    private void clearError() {
        this.errorCode = null;
        this.errorMessage = null;
    }

    public Long getId() {
        return id;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public Status getStatus() {
        return status;
    }

    public String getRunId() {
        return runId;
    }

    public String getRequestDigest() {
        return requestDigest;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
