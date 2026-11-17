-- ============================================================================
-- Append-only visit corrections (FR-DATA / clinical compliance).
--
-- A correction NEVER overwrites the clinical cells of an existing visit row.
-- Instead the superseded row is marked is_current = FALSE and a new row is
-- appended with version_no + 1 and replaces_id pointing at the old row. The
-- original values, reason, operator and time all remain queryable.
--
-- The old uniqueness rule (subject_id, visit_code) would forbid the appended
-- row, so it is replaced by (subject_id, visit_code, version_no). Reads select
-- is_current = TRUE; historical versions are served by the correction-history
-- endpoint only.
-- ============================================================================
ALTER TABLE visit ADD COLUMN version_no INT NOT NULL DEFAULT 1;
ALTER TABLE visit ADD COLUMN is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE visit ADD COLUMN replaces_id BIGINT NULL;
ALTER TABLE visit ADD COLUMN correction_reason VARCHAR(500) NULL;
ALTER TABLE visit ADD COLUMN corrected_by BIGINT NULL;
ALTER TABLE visit ADD COLUMN corrected_at TIMESTAMP NULL;

-- MySQL requires an index for the subject_id FK. The old unique key
-- (subject_id, visit_code) currently serves that purpose, so create the
-- current-row index before dropping it; otherwise MySQL rejects the DROP
-- with error 1553 while H2 happens to accept it.
CREATE INDEX idx_visit_subject_current ON visit (subject_id, is_current);
ALTER TABLE visit DROP CONSTRAINT uq_visit_subject_code;
ALTER TABLE visit ADD CONSTRAINT uq_visit_subject_code_version
  UNIQUE (subject_id, visit_code, version_no);
ALTER TABLE visit ADD CONSTRAINT fk_visit_replaces
  FOREIGN KEY (replaces_id) REFERENCES visit (id) ON DELETE RESTRICT;
ALTER TABLE visit ADD CONSTRAINT fk_visit_corrected_by
  FOREIGN KEY (corrected_by) REFERENCES sys_user (id) ON DELETE SET NULL;
