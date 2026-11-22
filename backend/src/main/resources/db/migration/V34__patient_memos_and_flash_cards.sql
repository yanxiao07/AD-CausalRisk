CREATE TABLE patient_memo (
    id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    subject_id BIGINT,
    content TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_memo_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_memo_subject FOREIGN KEY (subject_id) REFERENCES subject(id) ON DELETE SET NULL
);

CREATE INDEX idx_memo_user_created ON patient_memo (user_id, created_at DESC);

CREATE TABLE patient_flash_card (
    id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    memo_id BIGINT NOT NULL,
    front_text VARCHAR(500) NOT NULL,
    back_text VARCHAR(500) NOT NULL,
    card_state VARCHAR(20) NOT NULL DEFAULT 'NEW',
    review_count INT NOT NULL DEFAULT 0,
    last_reviewed_at DATETIME(6),
    next_review_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_flashcard_memo FOREIGN KEY (memo_id) REFERENCES patient_memo(id) ON DELETE CASCADE,
    CONSTRAINT ck_flashcard_state CHECK (card_state IN ('NEW', 'REVIEW', 'MASTERED'))
);

CREATE INDEX idx_flashcard_memo ON patient_flash_card (memo_id);
CREATE INDEX idx_flashcard_state_next ON patient_flash_card (card_state, next_review_at);
