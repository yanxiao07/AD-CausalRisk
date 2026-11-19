ALTER TABLE research_table_task ADD COLUMN executor_id VARCHAR(80) NULL;

ALTER TABLE research_table_task ADD COLUMN lease_expires_at DATETIME NULL;

CREATE INDEX idx_rt_task_lease ON research_table_task(status, lease_expires_at);
