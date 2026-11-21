ALTER TABLE research_table_snapshot ADD COLUMN bundle_json LONGTEXT NULL AFTER context_json;

ALTER TABLE research_table_snapshot ADD COLUMN host_sync_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER bundle_json;

ALTER TABLE research_table_snapshot ADD COLUMN host_synced_at DATETIME NULL AFTER host_sync_status;
