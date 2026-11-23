-- ============================================================================
-- Widen the three visit score columns so that a submitted value is stored exactly.
--
-- V1 declared DECIMAL(4,1), which silently rounded 27.55 to 27.6 on insert, so the
-- original observation could never be read back. The scores are clinical scale values
-- (MMSE/MoCA 0-30, CDR-SB 0-18) that a client may submit with two decimals, so the
-- columns now keep two decimals.
--
-- PORTABILITY
-- MODIFY COLUMN is accepted by both MySQL 8 and H2 2.x in MODE=MySQL (verified on H2
-- 2.3.232). ALTER COLUMN ... SET DATA TYPE would work on H2 but is not MySQL syntax.
-- MySQL drops attributes that MODIFY COLUMN does not restate, so NULL is restated
-- explicitly; these columns carry no default and no comment. The ck_visit_* CHECK
-- constraints are table-level and survive the type change.
--
-- KNOWN BOUND
-- Two decimals is still a bound: a value with three or more decimals (27.555) is
-- rounded by the database, because the request layer deliberately does not reject
-- extra precision. Raising the bound means changing the scale here and in Visit's
-- @Column(precision/scale) together.
-- ============================================================================
ALTER TABLE visit MODIFY COLUMN mmse DECIMAL(5,2) NULL;
ALTER TABLE visit MODIFY COLUMN moca DECIMAL(5,2) NULL;
ALTER TABLE visit MODIFY COLUMN cdr_sb DECIMAL(5,2) NULL;
