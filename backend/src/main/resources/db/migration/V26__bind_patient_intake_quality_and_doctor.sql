ALTER TABLE patient_account_request ADD COLUMN quality_check_id BIGINT NULL;
ALTER TABLE patient_account_request ADD COLUMN responsible_doctor_id BIGINT NULL;
ALTER TABLE patient_account_request ADD CONSTRAINT fk_patient_request_quality
    FOREIGN KEY (quality_check_id) REFERENCES quality_check(id);
ALTER TABLE patient_account_request ADD CONSTRAINT fk_patient_request_responsible
    FOREIGN KEY (responsible_doctor_id) REFERENCES sys_user(id);
