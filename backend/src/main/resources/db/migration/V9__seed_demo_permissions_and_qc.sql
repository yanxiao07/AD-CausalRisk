-- ============================================================================
-- V9 — seed demo permissions and pending QC records
--
-- Why: the demo accounts from V2 had no subject_permission rows at all
-- (V2 comment: "No subject and no subject_permission rows are seeded ...
-- grants have no admin API yet"). Consequences observed in the live run:
--   * doctor  GET /api/subjects          -> total 0   (no grant => not visible)
--   * doctor  GET /api/assessments/{id}  -> 403       (canReadRawScores denied)
--   * patient GET /api/subjects          -> total 0   (bound-subject flow dead)
--
-- This migration only seeds *demo* data: it never touches non-DEMO-* subjects,
-- and every INSERT is guarded with NOT EXISTS so re-running against a database
-- that already has the rows is a no-op (Flyway itself runs each version once,
-- but a restored/partially-seeded database should not fail here).
--
-- The two QC rows give the "数据质控" page something to approve/reject during a
-- demo; both are PENDING and reference the researcher as the data owner.
-- ============================================================================

-- 1) doctor: VIEW + MANAGE on every DEMO-* subject -----------------------------
INSERT INTO subject_permission (subject_id, user_id, permission_type)
SELECT s.id, u.id, 'VIEW'
FROM subject s
JOIN sys_user u ON u.username = 'doctor'
WHERE s.subject_code LIKE 'DEMO-%'
  AND NOT EXISTS (
    SELECT 1 FROM subject_permission p
    WHERE p.subject_id = s.id AND p.user_id = u.id AND p.permission_type = 'VIEW'
  );

INSERT INTO subject_permission (subject_id, user_id, permission_type)
SELECT s.id, u.id, 'MANAGE'
FROM subject s
JOIN sys_user u ON u.username = 'doctor'
WHERE s.subject_code LIKE 'DEMO-%'
  AND NOT EXISTS (
    SELECT 1 FROM subject_permission p
    WHERE p.subject_id = s.id AND p.user_id = u.id AND p.permission_type = 'MANAGE'
  );

-- 2) patient: bind DEMO-001 (VIEW for subject/follow-up reads, SUMMARY_READ
--    reserved for the future patient summary endpoint) -------------------------
INSERT INTO subject_permission (subject_id, user_id, permission_type)
SELECT s.id, u.id, 'VIEW'
FROM subject s
JOIN sys_user u ON u.username = 'patient'
WHERE s.subject_code = 'DEMO-001'
  AND NOT EXISTS (
    SELECT 1 FROM subject_permission p
    WHERE p.subject_id = s.id AND p.user_id = u.id AND p.permission_type = 'VIEW'
  );

INSERT INTO subject_permission (subject_id, user_id, permission_type)
SELECT s.id, u.id, 'SUMMARY_READ'
FROM subject s
JOIN sys_user u ON u.username = 'patient'
WHERE s.subject_code = 'DEMO-001'
  AND NOT EXISTS (
    SELECT 1 FROM subject_permission p
    WHERE p.subject_id = s.id AND p.user_id = u.id AND p.permission_type = 'SUMMARY_READ'
  );

-- 3) two PENDING QC records so the audit page has rows to act on ---------------
INSERT INTO qc_audit_record (
  subject_id, visit_id, target_type, source_type, status,
  abnormal_items, audit_comment, auditor_id, audited_at, quality_check_id,
  created_at, updated_at
)
SELECT
  s.id,
  (SELECT MIN(v.id) FROM visit v WHERE v.subject_id = s.id),
  'VISIT',
  'MANUAL_REVIEW',
  'PENDING',
  '["MMSE 超出 0-30 范围"]',
  NULL,
  u.id,
  NULL,
  NULL,
  CURRENT_TIMESTAMP,
  CURRENT_TIMESTAMP
FROM subject s
JOIN sys_user u ON u.username = 'researcher'
WHERE s.subject_code = 'DEMO-002'
  AND NOT EXISTS (
    SELECT 1 FROM qc_audit_record q
    WHERE q.subject_id = s.id AND q.status = 'PENDING'
  );

INSERT INTO qc_audit_record (
  subject_id, visit_id, target_type, source_type, status,
  abnormal_items, audit_comment, auditor_id, audited_at, quality_check_id,
  created_at, updated_at
)
SELECT
  s.id,
  (SELECT MIN(v.id) FROM visit v WHERE v.subject_id = s.id),
  'VISIT',
  'MANUAL_REVIEW',
  'PENDING',
  '["CDR-SB 与上次访视差异过大"]',
  NULL,
  u.id,
  NULL,
  NULL,
  CURRENT_TIMESTAMP,
  CURRENT_TIMESTAMP
FROM subject s
JOIN sys_user u ON u.username = 'researcher'
WHERE s.subject_code = 'DEMO-003'
  AND NOT EXISTS (
    SELECT 1 FROM qc_audit_record q
    WHERE q.subject_id = s.id AND q.status = 'PENDING'
  );
