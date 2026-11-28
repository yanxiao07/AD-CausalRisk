CREATE TABLE patient_card_draft_preview (
    preview_id VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    memo_id BIGINT NOT NULL,
    memo_version VARCHAR(128) NOT NULL,
    drafts_json TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_card_draft_preview_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_card_draft_preview_memo FOREIGN KEY (memo_id) REFERENCES patient_memo(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_card_draft_preview_memo ON patient_card_draft_preview (user_id, memo_id);