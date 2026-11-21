CREATE TABLE research_supplement_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    original_task_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    note VARCHAR(1000) NOT NULL,
    issues_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    assigned_to BIGINT NULL,
    replacement_task_id BIGINT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uq_rs_origin UNIQUE (original_task_id),
    CONSTRAINT uq_rs_replacement UNIQUE (replacement_task_id),
    CONSTRAINT fk_rs_origin FOREIGN KEY (original_task_id) REFERENCES research_table_task(id),
    CONSTRAINT fk_rs_replacement FOREIGN KEY (replacement_task_id) REFERENCES research_table_task(id),
    CONSTRAINT fk_rs_requester FOREIGN KEY (requested_by) REFERENCES sys_user(id),
    CONSTRAINT fk_rs_assignee FOREIGN KEY (assigned_to) REFERENCES sys_user(id),
    CONSTRAINT ck_rs_state CHECK (
        (status='OPEN' AND assigned_to IS NULL AND replacement_task_id IS NULL)
        OR (status='CLAIMED' AND assigned_to IS NOT NULL AND replacement_task_id IS NULL)
        OR (status='RETURNED' AND assigned_to IS NOT NULL AND replacement_task_id IS NOT NULL)
    )
);

CREATE INDEX idx_rs_status_id ON research_supplement_request(status, id);
