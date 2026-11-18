package com.adcausalrisk.backend.research;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * A research task is executed synchronously in the Java process. The executor column and lease
 * make that ownership visible to other instances. A task is claimable only while it is RUNNING,
 * still carries the expected execution id, and has no live owner. The conditional UPDATE is the
 * only operation that can move a task from "unowned/expired" to "owned", so two instances cannot
 * both start the same task even when a start-up recovery and a retry race.
 */
@Component
final class ResearchTaskLease {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String executorId;
    private final Duration leaseDuration;

    ResearchTaskLease(JdbcTemplate jdbc, Clock clock,
            @Value("${research.task.executor-id:local}") String executorId,
            @Value("${research.task.lease-seconds:900}") long leaseSeconds) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.executorId = executorId;
        this.leaseDuration = Duration.ofSeconds(Math.max(1, leaseSeconds));
    }

    String executorId() {
        return executorId;
    }

    Timestamp expiresAt() {
        return Timestamp.from(clock.instant().plus(leaseDuration));
    }

    boolean claim(long taskId, String executionId) {
        return claim(taskId, executionId, executorId);
    }

    /**
     * Claim one RUNNING attempt atomically. The executor column is deliberately part of the
     * predicate: a live owner, including one on another instance, must lose the race. Tests pass
     * two explicit executor ids through this overload.
     */
    boolean claim(long taskId, String executionId, String claimant) {
        Instant now = clock.instant();
        Timestamp until = Timestamp.from(now.plus(leaseDuration));
        int updated = jdbc.update("""
                UPDATE research_table_task
                SET executor_id=?, lease_expires_at=?, updated_at=?
                WHERE id=? AND status='RUNNING' AND execution_id=?
                  AND (executor_id IS NULL OR lease_expires_at IS NULL OR lease_expires_at < ?)
                """, claimant, until, Timestamp.from(now), taskId, executionId, Timestamp.from(now));
        return updated == 1;
    }
}
