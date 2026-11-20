CREATE TABLE controlled_assessment_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    subject_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    quality_check_id BIGINT NOT NULL,
    snapshot_id BIGINT NULL,
    requested_at DATETIME NOT NULL,
    CONSTRAINT uq_car_subject_quality UNIQUE (subject_id,quality_check_id),
    CONSTRAINT uq_car_snapshot UNIQUE (snapshot_id),
    CONSTRAINT fk_car_subject FOREIGN KEY (subject_id) REFERENCES subject(id),
    CONSTRAINT fk_car_doctor FOREIGN KEY (requested_by) REFERENCES sys_user(id),
    CONSTRAINT fk_car_qc FOREIGN KEY (quality_check_id) REFERENCES quality_check(id),
    CONSTRAINT fk_car_snapshot FOREIGN KEY (snapshot_id) REFERENCES research_table_snapshot(id)
);
