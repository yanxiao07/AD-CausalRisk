ALTER TABLE research_table_task ADD COLUMN execution_id VARCHAR(36) NULL;

ALTER TABLE research_table_task DROP CONSTRAINT ck_rt_task_status;

ALTER TABLE research_table_task ADD CONSTRAINT ck_rt_task_status
  CHECK (status IN ('RUNNING','OUTCOME_UNKNOWN','COMPLETED','BLOCKED','FAILED'));
