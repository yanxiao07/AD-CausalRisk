CREATE TABLE patient_consent_record (
    id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    subject_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    research_use BOOLEAN NOT NULL,
    follow_up_contact BOOLEAN NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_consent_subject FOREIGN KEY (subject_id) REFERENCES subject(id) ON DELETE CASCADE,
    CONSTRAINT fk_consent_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE
);

CREATE INDEX idx_consent_subject_user_time ON patient_consent_record (subject_id, user_id, recorded_at DESC);
