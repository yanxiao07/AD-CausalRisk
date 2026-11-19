package com.adcausalrisk.backend.research;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;

/**
 * Research tasks are executed synchronously in the Java process. If that process stops while a
 * task is RUNNING, its lease eventually expires and the task is released as OUTCOME_UNKNOWN so the
 * researcher can retry deliberately; a late executor can no longer write because every terminal
 * update is guarded by status and execution_id.
 *
 * <p>A start-up recovery used to reset every RUNNING row. That is unsafe with more than one
 * instance: it would kill work another instance is still performing. Recovery now only touches
 * tasks with no owner or an expired lease. The scheduled pass handles the same condition after a
 * process dies without a restart elsewhere.</p>
 */
@Component
final class ResearchTaskRecovery {

    static final String INTERRUPTED_CODE = "RESEARCH_EXECUTION_INTERRUPTED";
    private static final Logger log = LoggerFactory.getLogger(ResearchTaskRecovery.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final boolean enabled;

    ResearchTaskRecovery(JdbcTemplate jdbc, Clock clock,
            @Value("${research.task.recovery-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverOrphanedTasks() {
        if (!enabled) {
            return;
        }
        int recovered = recoverInterrupted();
        if (recovered > 0) {
            log.warn("Recovered {} interrupted research task(s) as {}", recovered, INTERRUPTED_CODE);
        }
    }

    @Scheduled(fixedDelayString = "${research.task.recovery-interval-ms:60000}")
    void recoverExpiredTasks() {
        if (!enabled) {
            return;
        }
        int recovered = recoverInterrupted();
        if (recovered > 0) {
            log.warn("Recovered {} expired research task lease(s) as {}", recovered, INTERRUPTED_CODE);
        }
    }

    int recoverInterrupted() {
        return jdbc.update("""
                UPDATE research_table_task
                SET status='OUTCOME_UNKNOWN', error_code=?, execution_id=NULL,
                    executor_id=NULL, lease_expires_at=NULL, updated_at=?
                WHERE status='RUNNING'
                  AND (executor_id IS NULL OR lease_expires_at IS NULL OR lease_expires_at < ?)
                """, INTERRUPTED_CODE, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
    }
}
