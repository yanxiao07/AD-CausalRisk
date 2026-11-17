package com.adcausalrisk.backend.research;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Research tasks are executed synchronously in the Java process. If that process stops while a
 * task is RUNNING, no executor remains. Keep the task for audit but release it as
 * OUTCOME_UNKNOWN so the researcher can retry deliberately; a late executor can no longer write
 * because every terminal update is guarded by status and execution_id.
 */
@Component
final class ResearchTaskRecovery {

    static final String INTERRUPTED_CODE = "RESEARCH_EXECUTION_INTERRUPTED";
    private static final Logger log = LoggerFactory.getLogger(ResearchTaskRecovery.class);

    private final JdbcTemplate jdbc;

    ResearchTaskRecovery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverOrphanedTasks() {
        int recovered = recoverInterrupted();
        if (recovered > 0) {
            log.warn("Recovered {} interrupted research task(s) as {}", recovered, INTERRUPTED_CODE);
        }
    }

    int recoverInterrupted() {
        return jdbc.update("""
                UPDATE research_table_task
                SET status='OUTCOME_UNKNOWN', error_code=?, execution_id=NULL,
                    updated_at=CURRENT_TIMESTAMP
                WHERE status='RUNNING'
                """, INTERRUPTED_CODE);
    }
}
