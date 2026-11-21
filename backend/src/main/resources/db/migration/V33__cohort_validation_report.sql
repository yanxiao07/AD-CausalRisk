CREATE TABLE validation_cohort_report (
    id CHAR(36) NOT NULL PRIMARY KEY,
    researcher_id BIGINT NOT NULL,
    project_code VARCHAR(50) NOT NULL,
    artifact_sha256 CHAR(64) NOT NULL,
    snapshot_sha256 CHAR(64) NOT NULL,
    enrollment_ids_json LONGTEXT NOT NULL,
    report_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_validation_report UNIQUE (researcher_id, project_code, artifact_sha256, snapshot_sha256),
    CONSTRAINT fk_validation_report_user FOREIGN KEY (researcher_id) REFERENCES sys_user(id)
);
