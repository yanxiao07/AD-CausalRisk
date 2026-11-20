CREATE TABLE patient_account_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    subject_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    issued_user_id BIGINT NULL,
    issued_at TIMESTAMP NULL,
    CONSTRAINT uq_patient_account_subject UNIQUE (subject_id),
    CONSTRAINT fk_patient_request_subject FOREIGN KEY (subject_id) REFERENCES subject(id),
    CONSTRAINT fk_patient_request_doctor FOREIGN KEY (requested_by) REFERENCES sys_user(id),
    CONSTRAINT fk_patient_request_account FOREIGN KEY (issued_user_id) REFERENCES sys_user(id)
);
ALTER TABLE research_table_snapshot ADD COLUMN doctor_handoff BOOLEAN NOT NULL DEFAULT FALSE;
