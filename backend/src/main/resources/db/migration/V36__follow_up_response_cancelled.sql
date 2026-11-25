ALTER TABLE follow_up_response DROP CONSTRAINT ck_fur_status;
ALTER TABLE follow_up_response ADD CONSTRAINT ck_fur_status
    CHECK (response_status IN ('CONFIRMED','RESCHEDULE_REQUESTED','RESCHEDULE_DECLINED','CANCELLED'));
