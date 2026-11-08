-- Preserve the existing synthetic demo accounts' full-cohort policy explicitly.
-- New research accounts default to ASSIGNED in the user service.
ALTER TABLE sys_user ADD COLUMN data_scope VARCHAR(20) NOT NULL DEFAULT 'ALL';
ALTER TABLE sys_user ADD CONSTRAINT ck_user_data_scope CHECK (data_scope IN ('ALL','ASSIGNED'));
