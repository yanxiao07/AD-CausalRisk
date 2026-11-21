CREATE TABLE research_table_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  subject_id BIGINT NOT NULL,
  authorized_user_id BIGINT NOT NULL,
  request_json LONGTEXT NOT NULL,
  wire_sha256 CHAR(64) NOT NULL,
  request_sha256 CHAR(64) NOT NULL,
  model_json TEXT NOT NULL,
  data_scope VARCHAR(30) NOT NULL,
  input_sha256 CHAR(64) NOT NULL,
  context_sha256 CHAR(64) NOT NULL,
  CONSTRAINT fk_rt_snapshot_subject FOREIGN KEY (subject_id) REFERENCES subject(id),
  CONSTRAINT fk_rt_snapshot_user FOREIGN KEY (authorized_user_id) REFERENCES sys_user(id),
  CONSTRAINT ck_rt_snapshot_scope CHECK (data_scope IN ('CONSTRUCTED_VALIDATION','CONTROLLED_RESEARCH'))
);

CREATE TABLE research_table_task (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  snapshot_id BIGINT NOT NULL,
  run_id VARCHAR(80) NOT NULL,
  status VARCHAR(20) NOT NULL,
  error_code VARCHAR(60) NULL,
  result_json LONGTEXT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  CONSTRAINT fk_rt_task_snapshot FOREIGN KEY (snapshot_id) REFERENCES research_table_snapshot(id),
  CONSTRAINT uq_rt_task_snapshot UNIQUE (snapshot_id),
  CONSTRAINT ck_rt_task_status CHECK (status IN ('OUTCOME_UNKNOWN','COMPLETED','BLOCKED','FAILED'))
);
