CREATE TABLE validation_cohort_enrollment (
    id CHAR(36) NOT NULL PRIMARY KEY,
    project_code VARCHAR(50) NOT NULL,
    subject_id BIGINT NOT NULL,
    researcher_id BIGINT NOT NULL,
    authorization_ref VARCHAR(150) NOT NULL,
    granted_by BIGINT NOT NULL,
    granted_at DATETIME NOT NULL,
    CONSTRAINT uq_validation_enrollment UNIQUE (researcher_id, project_code, subject_id),
    CONSTRAINT fk_validation_subject FOREIGN KEY (subject_id) REFERENCES subject(id),
    CONSTRAINT fk_validation_researcher FOREIGN KEY (researcher_id) REFERENCES sys_user(id),
    CONSTRAINT fk_validation_grantor FOREIGN KEY (granted_by) REFERENCES sys_user(id)
);

CREATE TABLE validation_follow_up (
    subject_id BIGINT NOT NULL PRIMARY KEY,
    outcome VARCHAR(20) NOT NULL,
    observed_date DATE NOT NULL,
    source_ref VARCHAR(150) NOT NULL,
    recorded_by BIGINT NOT NULL,
    recorded_at DATETIME NOT NULL,
    CONSTRAINT ck_validation_outcome CHECK (outcome IN ('AD', 'OTHER_DEMENTIA', 'NO_EVENT', 'UNKNOWN')),
    CONSTRAINT fk_validation_follow_up_subject FOREIGN KEY (subject_id) REFERENCES subject(id),
    CONSTRAINT fk_validation_follow_up_doctor FOREIGN KEY (recorded_by) REFERENCES sys_user(id)
);
