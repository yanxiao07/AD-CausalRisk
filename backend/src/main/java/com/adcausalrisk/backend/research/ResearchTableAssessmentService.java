package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.audit.AuditService;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.importjob.IdempotencyRecordRepository;
import com.adcausalrisk.backend.importjob.entity.IdempotencyRecord;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectAccessPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
class ResearchTableAssessmentService {
    static final String TRAINED_MODEL = "trained-seven-logistic";
    private static final String OPERATION = "RESEARCH_TABLE_ASSESSMENT";
    private static final String OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN";
    private static final String TASK_QUERY = """
            SELECT t.id,t.snapshot_id,t.run_id,t.status,t.error_code,t.result_json,t.created_at,t.updated_at,
                   s.subject_id,s.authorized_user_id
            FROM research_table_task t JOIN research_table_snapshot s ON s.id=t.snapshot_id
            """;

    record Task(long taskId, long snapshotId, long subjectId, String runId, String status,
                String errorCode, JsonNode result, Instant createdAt, Instant updatedAt) {}
    record SnapshotOption(long snapshotId, Long taskId) {}

    private record Snapshot(byte[] requestBytes, JsonNode request, JsonNode model, String requestDigest,
                            String inputDigest, String contextDigest) {}
    private record Submission(long taskId, Snapshot snapshot) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final IdempotencyRecordRepository idempotency;
    private final SubjectAccessPolicy access;
    private final ResearchTableClient client;
    private final ResearchTableProtocol protocol;
    private final AuditService audit;
    private final Clock clock;
    @Value("${research.table.test-fixtures-enabled:false}")
    private boolean testFixturesEnabled;

    ResearchTableAssessmentService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            IdempotencyRecordRepository idempotency, SubjectAccessPolicy access,
            ResearchTableClient client, ResearchTableProtocol protocol, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        this.idempotency = idempotency;
        this.access = access;
        this.client = client;
        this.protocol = protocol;
        this.audit = audit;
        this.clock = clock;
    }

    Task submit(long snapshotId, String key, AppUserPrincipal principal) {
        requireResearchRole(principal);
        if (key.isBlank() || key.length() > 100 || !key.equals(key.trim())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "幂等键须为1至100个字符");
        }
        String digest = snapshotKey(snapshotId);
        Submission submission;
        try {
            submission = transaction.execute(status -> reserve(snapshotId, key, digest, principal));
        } catch (DataIntegrityViolationException race) {
            return replay(key, digest, principal).map(id -> find(id, principal)).orElseThrow(() -> race);
        }
        long taskId = submission.taskId();
        if (submission.snapshot() != null) {
            audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_SUBMIT", "taskId=" + taskId);
            execute(taskId, submission.snapshot());
            Task task = find(taskId, principal);
            audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_RESULT",
                    "taskId=" + taskId + " status=" + task.status());
            return task;
        }
        return find(taskId, principal);
    }

    List<SnapshotOption> snapshots(long subjectId, AppUserPrincipal principal) {
        requireResearchRole(principal);
        if (!access.canReadRawScores(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return jdbc.query("""
                SELECT s.id,s.request_json,t.id AS task_id
                FROM research_table_snapshot s LEFT JOIN research_table_task t ON t.snapshot_id=s.id
                WHERE s.subject_id=? AND s.authorized_user_id=? AND s.data_scope='CONSTRUCTED_VALIDATION'
                ORDER BY s.id
                """, (row, index) -> {
                    try {
                        JsonNode request = protocol.parse(row.getString("request_json").getBytes(StandardCharsets.UTF_8));
                        if (!TRAINED_MODEL.equals(request.path("modelKey").asText())) {
                            return null;
                        }
                        return new SnapshotOption(row.getLong("id"), row.getObject("task_id", Long.class));
                    } catch (IOException | IllegalArgumentException invalid) {
                        throw new BusinessException(ErrorCode.RESEARCH_UNAVAILABLE, "冻结研究快照配置无效");
                    }
                }, subjectId, principal.userId()).stream().filter(java.util.Objects::nonNull).toList();
    }

    private Submission reserve(long snapshotId, String key, String digest, AppUserPrincipal principal) {
        // Serialize this actor's reservations across snapshots and release the lock before HTTP.
        jdbc.queryForObject("SELECT id FROM sys_user WHERE id=? FOR UPDATE", Long.class, principal.userId());
        Snapshot snapshot = jdbc.query("SELECT * FROM research_table_snapshot WHERE id=? FOR UPDATE",
                (row, index) -> readSnapshot(row, principal), snapshotId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
        Optional<Long> existing = replay(key, digest, principal);
        if (existing.isPresent()) {
            return new Submission(existing.get(), null);
        }
        if (!jdbc.queryForList("SELECT id FROM research_table_task WHERE snapshot_id=?", Long.class,
                snapshotId).isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "该冻结快照已有关联任务");
        }
        client.requireConfigured();
        var keys = new GeneratedKeyHolder();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO research_table_task(snapshot_id,run_id,status,error_code,created_at,updated_at)
                    VALUES(?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, snapshotId);
            statement.setString(2, snapshot.request().get("runId").textValue());
            statement.setString(3, OUTCOME_UNKNOWN);
            statement.setString(4, "RESEARCH_OUTCOME_UNKNOWN");
            statement.setTimestamp(5, now);
            statement.setTimestamp(6, now);
            return statement;
        }, keys);
        long id = keys.getKey().longValue();
        idempotency.saveAndFlush(new IdempotencyRecord(principal.userId(), OPERATION, key, digest, id));
        return new Submission(id, snapshot);
    }

    private Optional<Long> replay(String key, String digest, AppUserPrincipal principal) {
        return idempotency.findByUserIdAndOperationAndIdempotencyKey(principal.userId(), OPERATION, key)
                .map(record -> {
                    if (!digest.equals(record.getRequestDigest())) {
                        throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "幂等键已绑定其他冻结快照");
                    }
                    return record.getResultId();
                });
    }

    private Snapshot readSnapshot(ResultSet row, AppUserPrincipal principal) throws SQLException {
        long subject = row.getLong("subject_id");
        long owner = row.getLong("authorized_user_id");
        authorize(principal, subject, owner);
        if (!"CONSTRUCTED_VALIDATION".equals(row.getString("data_scope"))) {
            throw new BusinessException(ErrorCode.RESEARCH_UNAVAILABLE, "当前宿主只具备合成研究快照");
        }
        try {
            byte[] bytes = row.getString("request_json").getBytes(StandardCharsets.UTF_8);
            if (!sha256(bytes).equals(digestColumn(row, "wire_sha256"))) {
                throw new IllegalArgumentException("Frozen snapshot bytes changed");
            }
            JsonNode request = protocol.parse(bytes);
            JsonNode model = protocol.parse(row.getString("model_json").getBytes(StandardCharsets.UTF_8));
            protocol.validateRequest(request, model);
            if (!testFixturesEnabled && (!TRAINED_MODEL.equals(request.path("modelKey").asText())
                    || !"seven".equals(model.path("specification").asText())
                    || !"logistic".equals(model.path("family").asText()))) {
                throw new IllegalArgumentException("Expected the registered trained model");
            }
            return new Snapshot(bytes, request, model,
                    digestColumn(row, "request_sha256"), digestColumn(row, "input_sha256"),
                    digestColumn(row, "context_sha256"));
        } catch (IOException | RuntimeException invalid) {
            throw new BusinessException(ErrorCode.RESEARCH_UNAVAILABLE, "冻结研究快照配置无效");
        }
    }

    private void execute(long taskId, Snapshot snapshot) {
        String status = OUTCOME_UNKNOWN;
        String code = "RESEARCH_OUTCOME_UNKNOWN";
        String result = null;
        try {
            ResearchTableClient.Reply reply = client.assess(snapshot.requestBytes());
            if (reply != null && reply.httpStatus() == 200) {
                status = protocol.validateResponse(reply.body(), snapshot.request(), snapshot.model(),
                        "CONSTRUCTED_VALIDATION", snapshot.requestDigest(),
                        snapshot.inputDigest(), snapshot.contextDigest());
                result = reply.body().toString();
                code = null;
            } else if (reply != null) {
                code = protocol.validateError(reply.httpStatus(), reply.body(),
                        snapshot.request().get("runId").textValue());
                status = "FAILED";
            }
        } catch (IllegalArgumentException invalid) {
            code = "RESEARCH_PROTOCOL_INVALID";
        }
        jdbc.update("""
                UPDATE research_table_task SET status=?,error_code=?,result_json=?,updated_at=? WHERE id=?
                """, status, code, result, Timestamp.from(clock.instant()), taskId);
    }

    Task find(long id, AppUserPrincipal principal) {
        requireResearchRole(principal);
        return jdbc.query(TASK_QUERY + " WHERE t.id=?", (row, index) -> {
            long subjectId = row.getLong("subject_id");
            authorize(principal, subjectId, row.getLong("authorized_user_id"));
            String result = row.getString("result_json");
            try {
                return new Task(id, row.getLong("snapshot_id"), subjectId, row.getString("run_id"),
                        row.getString("status"), row.getString("error_code"),
                        result == null ? null : protocol.parse(result.getBytes(StandardCharsets.UTF_8)),
                        row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
            } catch (IOException invalid) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "研究任务结果不可读取");
            }
        }, id).stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
    }

    private void authorize(AppUserPrincipal principal, long subjectId, long owner) {
        if (principal.userId() != owner || !access.canReadRawScores(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private static void requireResearchRole(AppUserPrincipal principal) {
        if (principal.roleCodes().contains("PATIENT") || (!principal.roleCodes().contains("ADMIN")
                && !principal.roleCodes().contains("RESEARCHER"))) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private static String digestColumn(ResultSet row, String column) throws SQLException {
        String digest = row.getString(column);
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid snapshot digest");
        }
        return digest;
    }

    private static String snapshotKey(long id) {
        return sha256((OPERATION + "/" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
