ALTER TABLE quality_check ADD COLUMN checked_by BIGINT NULL;
ALTER TABLE quality_check ADD COLUMN data_version VARCHAR(100) NULL;
ALTER TABLE quality_check ADD COLUMN input_digest VARCHAR(64) NULL;
ALTER TABLE quality_check ADD COLUMN coverage_json TEXT NULL;
ALTER TABLE subject_feature ADD COLUMN source VARCHAR(200) NULL;
ALTER TABLE subject_feature ADD COLUMN missing_reason VARCHAR(500) NULL;
ALTER TABLE subject_feature ADD COLUMN data_version VARCHAR(100) NULL;
ALTER TABLE subject_feature ADD COLUMN import_job_id BIGINT NULL;
ALTER TABLE visit ADD COLUMN source VARCHAR(200) NULL;
ALTER TABLE visit ADD COLUMN missing_reason VARCHAR(500) NULL;
ALTER TABLE visit ADD COLUMN data_version VARCHAR(100) NULL;
ALTER TABLE visit ADD COLUMN import_job_id BIGINT NULL;
ALTER TABLE data_import_job ADD COLUMN original_csv_text LONGTEXT NULL;
ALTER TABLE data_import_job ADD COLUMN column_mapping_json TEXT NULL;
ALTER TABLE data_import_job ADD COLUMN original_digest VARCHAR(64) NULL;
ALTER TABLE follow_up_plan ADD COLUMN notes VARCHAR(1000) NULL;
ALTER TABLE sys_user ADD COLUMN display_name VARCHAR(100) NULL;
ALTER TABLE sys_user ADD COLUMN department VARCHAR(100) NULL;
ALTER TABLE sys_user ADD COLUMN contact_phone VARCHAR(50) NULL;
ALTER TABLE sys_user ADD COLUMN office_hours VARCHAR(200) NULL;
ALTER TABLE sys_user ADD COLUMN auth_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE risk_assessment ADD COLUMN risk_score DECIMAL(12,10) NULL;
ALTER TABLE risk_assessment ADD COLUMN risk_level VARCHAR(20) NULL;
CREATE TABLE task_input_snapshot (
 task_id BIGINT NOT NULL PRIMARY KEY,
 request_json LONGTEXT NOT NULL,
 input_digest VARCHAR(64) NOT NULL,
 data_version VARCHAR(100) NOT NULL,
 captured_at DATETIME NOT NULL,
 quality_check_id BIGINT NULL,
 provenance_json LONGTEXT NULL,
 CONSTRAINT fk_snapshot_task FOREIGN KEY (task_id) REFERENCES assessment_task(id),
 CONSTRAINT fk_snapshot_quality FOREIGN KEY (quality_check_id) REFERENCES quality_check(id)
);
CREATE TABLE user_preferences (
 user_id BIGINT NOT NULL PRIMARY KEY,
 preferences_json TEXT NOT NULL,
 updated_at DATETIME NOT NULL,
 CONSTRAINT fk_preferences_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
);
CREATE TABLE model_configuration (
 model_version VARCHAR(100) NOT NULL PRIMARY KEY,
 enabled BOOLEAN NOT NULL,
 updated_at DATETIME NOT NULL
);
INSERT INTO model_configuration VALUES ('deepagent-causal-warning-v1', TRUE, CURRENT_TIMESTAMP);
CREATE INDEX idx_task_created ON assessment_task(created_at, id);
CREATE INDEX idx_assessment_created ON risk_assessment(created_at, id);
CREATE INDEX idx_follow_up_status_time ON follow_up_plan(status, scheduled_at);
ALTER TABLE risk_assessment MODIFY COLUMN agent_result_json LONGTEXT NOT NULL;
