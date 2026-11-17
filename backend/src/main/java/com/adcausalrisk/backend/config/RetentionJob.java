package com.adcausalrisk.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Deletes audit rows older than the retention window configured in the system console.
 *
 * <p>Only the audit trail is pruned. Clinical assessments, reports and follow-up records are
 * evidence and are never deleted by this job.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final JdbcTemplate jdbc;
    private final SystemSettingService settings;

    public RetentionJob(JdbcTemplate jdbc, SystemSettingService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void pruneAuditLog() {
        prune(Instant.now());
    }

    int prune(Instant now) {
        int days = settings.auditRetentionDays();
        Timestamp cutoff = Timestamp.from(now.minus(days, ChronoUnit.DAYS));
        int deleted = jdbc.update("DELETE FROM audit_log WHERE occurred_at < ?", cutoff);
        if (deleted > 0) {
            log.info("按保留策略清理审计日志 {} 条（保留 {} 天）", deleted, days);
        }
        return deleted;
    }
}
