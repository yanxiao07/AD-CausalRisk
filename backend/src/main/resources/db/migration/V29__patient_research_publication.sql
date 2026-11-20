CREATE TABLE patient_research_publication (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    action VARCHAR(12) NOT NULL,
    message VARCHAR(1000) NULL,
    recorded_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_prp_task FOREIGN KEY (task_id) REFERENCES research_table_task(id),
    CONSTRAINT fk_prp_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id),
    CONSTRAINT ck_prp_action CHECK (action IN ('PUBLISHED','WITHDRAWN'))
);

CREATE INDEX idx_prp_task_revision ON patient_research_publication(task_id,id);
