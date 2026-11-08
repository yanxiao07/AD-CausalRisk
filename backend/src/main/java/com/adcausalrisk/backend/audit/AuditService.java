package com.adcausalrisk.backend.audit;

import com.adcausalrisk.backend.audit.entity.AuditLog;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.common.TraceId;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/** Writes and reads the audit trail. Writes never roll back the caller's business transaction. */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private static final int MAX_EVENT_TYPE_LENGTH = 50;
    private static final int MAX_SUMMARY_LENGTH = 500;

    private final AuditLogRepository auditLogRepository;
    private final java.time.Clock clock;
    private final TransactionTemplate writeTransaction;

    public AuditService(
            AuditLogRepository auditLogRepository,
            java.time.Clock clock,
            PlatformTransactionManager transactionManager) {
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Records one event in its own committed transaction. */
    public AuditLog record(Long actorId, String eventType, String summary) {
        String type = truncate(eventType, MAX_EVENT_TYPE_LENGTH);
        String text = truncate(summary, MAX_SUMMARY_LENGTH);
        return writeTransaction.execute(
                status ->
                        auditLogRepository.saveAndFlush(
                                new AuditLog(
                                        actorId,
                                        type,
                                        TraceId.current(),
                                        text,
                                        Instant.now(clock))));
    }

    /**
     * Records without ever failing the caller: an audit write must not turn a successful business
     * operation into a 500, but the loss is logged loudly.
     */
    public void recordQuietly(Long actorId, String eventType, String summary) {
        try {
            record(actorId, eventType, summary);
        } catch (RuntimeException failure) {
            log.error("审计日志写入失败 eventType={} actorId={}", eventType, actorId, failure);
        }
    }

    /** {@code GET /api/audit-logs}; administrators only. */
    public List<AuditLog> list(AppUserPrincipal principal) {
        if (principal == null
                || principal.roleCodes().contains("PATIENT")
                || !principal.roleCodes().contains("ADMIN")) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "仅管理员可查看审计日志");
        }
        return auditLogRepository.findAllByOrderByOccurredAtDescIdDesc();
    }

    private static String truncate(String value, int max) {
        String safe = value == null ? "" : value.trim();
        return safe.length() <= max ? safe : safe.substring(0, max);
    }
}
