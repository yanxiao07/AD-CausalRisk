package com.adcausalrisk.backend.patient.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "patient_flash_card")
public class PatientFlashCard {

    public enum CardState {
        NEW,
        REVIEW,
        MASTERED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "memo_id", nullable = false)
    private Long memoId;

    @Column(name = "front_text", nullable = false, length = 500)
    private String frontText;

    @Column(name = "back_text", nullable = false, length = 500)
    private String backText;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_state", nullable = false, length = 20)
    private CardState cardState;

    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    @Column(name = "last_reviewed_at")
    private Instant lastReviewedAt;

    @Column(name = "next_review_at")
    private Instant nextReviewAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PatientFlashCard() {
    }

    public PatientFlashCard(Long memoId, String frontText, String backText) {
        this.memoId = memoId;
        this.frontText = frontText;
        this.backText = backText;
        this.cardState = CardState.NEW;
        this.reviewCount = 0;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.nextReviewAt = now;
    }

    public Long getId() { return id; }
    public Long getMemoId() { return memoId; }
    public String getFrontText() { return frontText; }
    public String getBackText() { return backText; }
    public CardState getCardState() { return cardState; }
    public int getReviewCount() { return reviewCount; }
    public Instant getLastReviewedAt() { return lastReviewedAt; }
    public Instant getNextReviewAt() { return nextReviewAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void editTexts(String front, String back) {
        if (front == null || front.isBlank()) {
            throw new com.adcausalrisk.backend.common.BusinessException(
                com.adcausalrisk.backend.common.ErrorCode.VALIDATION_ERROR, "卡片正面不能为空");
        }
        if (back == null || back.isBlank()) {
            throw new com.adcausalrisk.backend.common.BusinessException(
                com.adcausalrisk.backend.common.ErrorCode.VALIDATION_ERROR, "卡片背面不能为空");
        }
        if (front.length() > 500 || back.length() > 500) {
            throw new com.adcausalrisk.backend.common.BusinessException(
                com.adcausalrisk.backend.common.ErrorCode.VALIDATION_ERROR, "卡片文字不能超过500字");
        }
        this.frontText = front.trim();
        this.backText = back.trim();
        this.updatedAt = Instant.now();
    }

    public void recordReview() {
        this.reviewCount++;
        this.lastReviewedAt = Instant.now();
        this.cardState = this.reviewCount >= 3 ? CardState.MASTERED : CardState.REVIEW;
        long delayDays = switch (this.cardState) {
            case NEW -> 1;
            case REVIEW -> 3;
            case MASTERED -> 7;
        };
        this.nextReviewAt = Instant.now().plusSeconds(delayDays * 86400L);
        this.updatedAt = Instant.now();
    }

    public void resetForRetry() {
        this.cardState = CardState.REVIEW;
        this.reviewCount = Math.max(0, this.reviewCount - 1);
        this.nextReviewAt = Instant.now();
        this.updatedAt = Instant.now();
    }
}
