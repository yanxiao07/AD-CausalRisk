-- Runtime system console settings (research host, assessment channel, retention).
-- Updated_by is nullable so a deployment can seed a value without a human actor.
CREATE TABLE system_setting (
  setting_key VARCHAR(100) NOT NULL,
  value_json  LONGTEXT     NOT NULL,
  updated_by  BIGINT       NULL,
  updated_at  DATETIME     NOT NULL,
  CONSTRAINT pk_system_setting PRIMARY KEY (setting_key)
);
