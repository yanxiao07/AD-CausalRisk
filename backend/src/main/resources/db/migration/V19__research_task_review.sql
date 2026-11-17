ALTER TABLE research_table_task ADD COLUMN review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE research_table_task ADD COLUMN reviewed_by BIGINT NULL;
ALTER TABLE research_table_task ADD COLUMN reviewed_at DATETIME NULL;
ALTER TABLE research_table_task ADD COLUMN review_comment VARCHAR(2000) NULL;
