ALTER TABLE research_table_snapshot
    ADD COLUMN context_json LONGTEXT NULL AFTER context_sha256;
