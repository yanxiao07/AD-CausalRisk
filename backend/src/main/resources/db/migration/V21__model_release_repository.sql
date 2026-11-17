-- Model releases uploaded through the administrator console.
-- The artifact bytes live on disk (outside the database); this table retains the
-- immutable identity (SHA-256) and the manifest needed to audit what was installed.
CREATE TABLE model_release (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  model_version     VARCHAR(100) NOT NULL,
  manifest_sha256   CHAR(64) NOT NULL,
  artifact_sha256   CHAR(64) NOT NULL,
  artifact_filename VARCHAR(255) NOT NULL,
  artifact_size     BIGINT NOT NULL,
  manifest_json     LONGTEXT NOT NULL,
  storage_path      VARCHAR(255) NOT NULL,
  uploaded_by       BIGINT NULL,
  uploaded_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  activated_at      TIMESTAMP NULL,
  CONSTRAINT uq_model_release_version UNIQUE (model_version),
  CONSTRAINT fk_model_release_actor FOREIGN KEY (uploaded_by)
    REFERENCES sys_user(id) ON DELETE SET NULL
);

CREATE INDEX idx_model_release_uploaded ON model_release(uploaded_at);
