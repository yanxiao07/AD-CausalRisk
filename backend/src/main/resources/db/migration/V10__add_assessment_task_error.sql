-- ============================================================================
-- V10 — persist the stable failure code and a user-readable reason on a task.
--
-- Why: when the Python agent is unreachable, POST /api/assessment-tasks returns a FAILED task
-- that carries AGENT_TIMEOUT (or another AGENT_* code) plus a message. That pair only lived in
-- the first HTTP response: assessment_task had no columns for it, so a later
-- GET /api/assessment-tasks/{id} (or an Idempotency-Key replay) showed status=FAILED with
-- errorCode=null and no explanation.
--
-- What is stored is deliberately narrow:
--   * error_code    – one of the stable AGENT_* constants (or a Java-side TASK_* code),
--   * error_message – a fixed, user-facing sentence chosen from a whitelist.
-- No stack trace, no exception class name, no upstream body and no host/port is written here.
-- ============================================================================

ALTER TABLE assessment_task ADD COLUMN error_code VARCHAR(60) NULL;
ALTER TABLE assessment_task ADD COLUMN error_message VARCHAR(500) NULL;
