-- Patients only ever read their own summary. The V12 backfill defaulted every
-- existing account to ALL, including the synthetic patient seed; normalize it to
-- ASSIGNED so seeded data reflects the documented role boundary.
UPDATE sys_user
SET data_scope = 'ASSIGNED'
WHERE data_scope = 'ALL'
  AND EXISTS (
    SELECT 1
    FROM user_role ur
    JOIN sys_role r ON r.id = ur.role_id
    WHERE ur.user_id = sys_user.id
      AND r.role_code = 'PATIENT'
  );
