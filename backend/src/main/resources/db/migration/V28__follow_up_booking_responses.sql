ALTER TABLE follow_up_plan ADD COLUMN visit_location VARCHAR(255) NULL;
ALTER TABLE follow_up_plan ADD COLUMN preparation VARCHAR(1000) NULL;
ALTER TABLE follow_up_plan ADD COLUMN appointment_revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE follow_up_response (
    follow_up_id BIGINT NOT NULL,
    appointment_revision BIGINT NOT NULL,
    patient_id BIGINT NOT NULL,
    response_status VARCHAR(24) NOT NULL,
    response_note VARCHAR(280) NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (follow_up_id, appointment_revision),
    CONSTRAINT fk_fur_follow_up FOREIGN KEY (follow_up_id) REFERENCES follow_up_plan(id),
    CONSTRAINT fk_fur_patient FOREIGN KEY (patient_id) REFERENCES sys_user(id),
    CONSTRAINT ck_fur_status CHECK (response_status IN ('CONFIRMED','RESCHEDULE_REQUESTED','RESCHEDULE_DECLINED'))
);
