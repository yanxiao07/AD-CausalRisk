ALTER TABLE subject DROP CONSTRAINT ck_subject_data_version;
ALTER TABLE subject ADD CONSTRAINT ck_subject_data_version CHECK (
    data_version LIKE 'demo-%' OR
    (subject_code LIKE 'ADNI-REVIEW-%' AND data_version LIKE 'adni-%') OR
    (subject_code NOT LIKE 'DEMO-%' AND subject_code NOT LIKE 'ADNI-REVIEW-%' AND data_version LIKE 'care-%')
);

CREATE TABLE care_assessment_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    subject_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    quality_check_id BIGINT NOT NULL,
    requested_at DATETIME NOT NULL,
    CONSTRAINT uq_care_request_quality UNIQUE (subject_id, quality_check_id),
    CONSTRAINT fk_care_request_subject FOREIGN KEY (subject_id) REFERENCES subject(id),
    CONSTRAINT fk_care_request_doctor FOREIGN KEY (requested_by) REFERENCES sys_user(id),
    CONSTRAINT fk_care_request_quality FOREIGN KEY (quality_check_id) REFERENCES quality_check(id)
);
