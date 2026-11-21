ALTER TABLE subject DROP CONSTRAINT ck_subject_data_version;
ALTER TABLE subject ADD CONSTRAINT ck_subject_data_version CHECK (
    data_version LIKE 'demo-%' OR
    (subject_code LIKE 'ADNI-REVIEW-%' AND data_version LIKE 'adni-%')
);
