package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.audit.AuditService;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.importjob.IdempotencyRecordRepository;
import com.adcausalrisk.backend.importjob.entity.IdempotencyRecord;
import com.adcausalrisk.backend.qualitycheck.QualityCheckService;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectAccessPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
class ResearchTableAssessmentService {
    static final String TRAINED_MODEL = "trained-seven-logistic";
    private static final String OPERATION = "RESEARCH_TABLE_ASSESSMENT";
    private static final String OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN";
    private static final List<String> MANUAL_FEATURES = List.of(
            "age_years", "education_years", "mmse", "cdr_sb", "cdr_global", "adas__total13", "faq__faqtotal");
    private static final String TASK_QUERY = """
            SELECT t.id,t.snapshot_id,t.run_id,t.status,t.error_code,t.result_json,t.created_at,t.updated_at,
                   t.review_status,t.reviewed_by,t.reviewed_at,t.review_comment,
                   s.subject_id,s.authorized_user_id,sub.subject_code,sub.alias AS subject_alias
            FROM research_table_task t
            JOIN research_table_snapshot s ON s.id=t.snapshot_id
            JOIN subject sub ON sub.id=s.subject_id
            """;

    record Task(long taskId, long snapshotId, long subjectId, String subjectCode, String subjectAlias,
                String runId, String status, String errorCode, JsonNode result,
                Instant createdAt, Instant updatedAt,
                String reviewStatus, Long reviewedBy, Instant reviewedAt, String reviewComment) {}

    record ReviewQueue(List<Task> items, long total, int page, int pageSize) {}
    record SnapshotOption(long snapshotId, Long taskId, String hostSyncStatus,
                          boolean compatible, String compatibilityMessage) {}
    record Registration(long snapshotId, String hostSyncStatus) {}
    record TaskSummary(long tasksToday, long completedToday, long failedToday, long averageSeconds,
                       List<Task> recent) {}

    record Metrics(long tasksToday, long completed, long blocked, long failed, long running,
                   long pendingReview, long syncedSnapshots, long averageSeconds) {}
    record BatchItem(long subjectId, Long snapshotId, String hostSyncStatus, Long taskId,
                     String taskStatus, String message) {}
    record BatchResult(List<BatchItem> items, int succeeded, int failed) {}

    private record BatchVisit(
            String visitDate,
            BigDecimal mmse,
            BigDecimal cdrSb,
            String rawMmse,
            String rawCdrSb,
            String dataVersion,
            String qualityStatus,
            String qualityDigest,
            String qualityDataVersion) {}

    record MigrationResult(int migrated, int failed, int skipped, List<Long> failedSnapshotIds,
                           List<Long> skippedSnapshotIds) {}

    private record Snapshot(byte[] requestBytes, JsonNode request, JsonNode model, String requestDigest,
                            String inputDigest, String contextDigest) {}
    private record Submission(long taskId, Snapshot snapshot, String executionId) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final IdempotencyRecordRepository idempotency;
    private final SubjectAccessPolicy access;
    private final ResearchTableClient client;
    private final ResearchTableProtocol protocol;
    private final ResearchModelIdentity modelIdentity;
    private final ResearchTaskLease lease;
    private final QualityCheckService qualityChecks;
    private final AuditService audit;
    private final Clock clock;
    @Value("${research.table.test-fixtures-enabled:false}")
    private boolean testFixturesEnabled;

    ResearchTableAssessmentService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            IdempotencyRecordRepository idempotency, SubjectAccessPolicy access,
            ResearchTableClient client, ResearchTableProtocol protocol, ResearchModelIdentity modelIdentity,
            ResearchTaskLease lease, QualityCheckService qualityChecks, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        this.idempotency = idempotency;
        this.access = access;
        this.client = client;
        this.protocol = protocol;
        this.modelIdentity = modelIdentity;
        this.lease = lease;
        this.qualityChecks = qualityChecks;
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
            audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_SUBMIT",
                    "taskId=" + taskId + " executionId=" + submission.executionId());
            execute(taskId, submission.snapshot(), submission.executionId());
            Task task = find(taskId, principal);
            audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_RESULT",
                    "taskId=" + taskId + " status=" + task.status());
            return task;
        }
        return find(taskId, principal);
    }

    long register(long subjectId, JsonNode request, AppUserPrincipal principal) {
        requireResearchRole(principal);
        if (!access.canReadRawScores(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        String subjectCode = jdbc.query("SELECT subject_code FROM subject WHERE id=?", (row, index) -> row.getString(1),
                subjectId).stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
        ObjectNode model = modelIdentity.identity();
        ObjectNode normalized = normalizedRequest(request, subjectCode);
        if (!TRAINED_MODEL.equals(normalized.path("modelKey").asText())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "受控登记只接受已训练的七变量逻辑回归身份");
        }
        try {
            protocol.validateRequest(normalized, model);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "研究请求或模型身份不符合契约");
        }
        byte[] requestBytes = protocol.canonicalBytes(normalized);
        String requestDigest = protocol.digest(normalized);
        String inputDigest = protocol.digest(protocol.admissionCore(normalized, model));
        JsonNode context = protocol.registrationContext(normalized, model);
        String contextDigest = protocol.digest(context);
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO research_table_snapshot(subject_id,authorized_user_id,request_json,wire_sha256,
                        request_sha256,model_json,data_scope,input_sha256,context_sha256,context_json)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, subjectId);
            statement.setLong(2, principal.userId());
            statement.setString(3, new String(requestBytes, StandardCharsets.UTF_8));
            statement.setString(4, sha256(requestBytes));
            statement.setString(5, requestDigest);
            statement.setString(6, model.toString());
            statement.setString(7, "CONSTRUCTED_VALIDATION");
            statement.setString(8, inputDigest);
            statement.setString(9, contextDigest);
            statement.setString(10, context.toString());
            return statement;
        }, keys);
        long id = keys.getKey().longValue();
        audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_SNAPSHOT_REGISTERED",
                "snapshotId=" + id + " subjectId=" + subjectId);
        return id;
    }

    Registration registerAndSync(long subjectId, JsonNode request, AppUserPrincipal principal) {
        long id = register(subjectId, request, principal);
        return new Registration(id, syncToHost(id, principal));
    }

    /**
     * Creates a new, current-model snapshot from an old snapshot's frozen request. The old row is
     * never rewritten: its clinical values, model identity and digest stay auditable. The only
     * mutation is a fresh {@code runId} so the host can register the new image independently.
     */
    Registration reRegister(long snapshotId, AppUserPrincipal principal) {
        requireResearchRole(principal);
        var source = jdbc.query("""
                SELECT subject_id,request_json,model_json
                FROM research_table_snapshot
                WHERE id=? AND data_scope='CONSTRUCTED_VALIDATION'
                  AND (authorized_user_id=? OR ?=TRUE)
                """, (row, index) -> new Object[] {
                        row.getLong("subject_id"), row.getString("request_json"), row.getString("model_json")},
                snapshotId, principal.userId(), principal.roleCodes().contains("ADMIN")).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
        long subjectId = (Long) source[0];
        if (!access.canReadRawScores(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        ObjectNode request;
        JsonNode storedModel;
        try {
            request = (ObjectNode) protocol.parse(
                    ((String) source[1]).getBytes(StandardCharsets.UTF_8));
            storedModel = protocol.parse(((String) source[2]).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.RESEARCH_UNAVAILABLE, "旧快照配置不可解析");
        }
        if (modelIdentity.identity().equals(storedModel)) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT,
                    "该快照的模型身份仍与研究宿主兼容，无需重新登记");
        }
        request.put("runId", "run-rereg-" + snapshotId + "-"
                + UUID.randomUUID().toString().substring(0, 12));
        Registration registration = registerAndSync(subjectId, request, principal);
        audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_SNAPSHOT_REREGISTERED",
                "oldSnapshotId=" + snapshotId + " newSnapshotId=" + registration.snapshotId());
        return registration;
    }

    Registration registerManualAndSync(long subjectId, JsonNode features, String indexDate, String dataVersion,
            String runId, AppUserPrincipal principal) {
        long id = registerManual(subjectId, features, indexDate, dataVersion, runId, principal);
        return new Registration(id, syncToHost(id, principal));
    }

    /**
     * Turn the latest observed visit of each selected subject into a seven-variable
     * snapshot. Only MMSE and CDR-SB are available in the visit table; every other
     * variable is deliberately recorded as NOT_COLLECTED rather than imputed.
     */
    BatchResult batch(List<Long> requested, boolean evaluate, AppUserPrincipal principal) {
        requireResearchRole(principal);
        if (requested == null || requested.isEmpty() || requested.size() > 50) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择 1-50 个受试者");
        }
        Set<Long> subjectIds = new LinkedHashSet<>();
        for (Long id : requested) {
            if (id != null && id > 0) {
                subjectIds.add(id);
            }
        }
        if (subjectIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择 1-50 个受试者");
        }
        List<BatchItem> items = new ArrayList<>();
        for (long subjectId : subjectIds) {
            try {
                if (!access.canReadRawScores(principal, subjectId)) {
                    items.add(new BatchItem(subjectId, null, null, null, null, "没有该受试者的原始数据权限"));
                    continue;
                }
                BatchVisit visit = jdbc.query("""
                        SELECT v.visit_date,v.mmse,v.cdr_sb,v.raw_mmse,v.raw_cdr_sb,
                               COALESCE(v.data_version,s.data_version) AS data_version,
                               q.quality_status,q.input_digest AS quality_digest,
                               q.data_version AS quality_data_version
                        FROM visit v JOIN subject s ON s.id=v.subject_id
                        LEFT JOIN quality_check q ON q.id=(
                            SELECT q2.id FROM quality_check q2
                             WHERE q2.subject_id=v.subject_id
                             ORDER BY q2.checked_at DESC,q2.id DESC LIMIT 1)
                        WHERE v.subject_id=? AND v.is_current=TRUE
                        ORDER BY v.visit_date DESC,v.id DESC LIMIT 1
                        """, (row, index) -> new BatchVisit(
                        row.getDate("visit_date").toLocalDate().toString(),
                        row.getBigDecimal("mmse"), row.getBigDecimal("cdr_sb"),
                        row.getString("raw_mmse"), row.getString("raw_cdr_sb"),
                        row.getString("data_version"), row.getString("quality_status"),
                        row.getString("quality_digest"), row.getString("quality_data_version")),
                        subjectId).stream().findFirst().orElse(null);
                if (visit == null) {
                    items.add(new BatchItem(subjectId, null, null, null, null, "没有可用于生成快照的访视数据"));
                    continue;
                }
                if (!"PASS".equals(visit.qualityStatus())) {
                    items.add(new BatchItem(subjectId, null, null, null, null,
                            visit.qualityStatus() == null
                                    ? "该受试者尚未完成质控，请先在质控页完成审核"
                                    : "最新质控状态为 " + visit.qualityStatus() + "，请先处理质控异常"));
                    continue;
                }
                if (visit.qualityDigest() == null
                        || !visit.qualityDigest().equals(qualityChecks.inputDigest(subjectId))) {
                    items.add(new BatchItem(subjectId, null, null, null, null,
                            "最新质控未绑定当前数据（已有新访视或数据更正），请重新质控"));
                    continue;
                }
                if (visit.qualityDataVersion() == null
                        || !visit.qualityDataVersion().equals(visit.dataVersion())) {
                    items.add(new BatchItem(subjectId, null, null, null, null,
                            "最新质控的数据版本早于当前访视，请重新质控"));
                    continue;
                }
                BigDecimal mmse;
                BigDecimal cdrSb;
                try {
                    mmse = rawScore(visit.rawMmse(), visit.mmse());
                    cdrSb = rawScore(visit.rawCdrSb(), visit.cdrSb());
                } catch (NumberFormatException invalid) {
                    items.add(new BatchItem(subjectId, null, null, null, null,
                            "原始认知分数不可解析，请先修正访视数据"));
                    continue;
                }
                ObjectNode features = JsonNodeFactory.instance.objectNode();
                for (String feature : MANUAL_FEATURES) {
                    features.putNull(feature);
                }
                putIfPresent(features, "mmse", mmse);
                putIfPresent(features, "cdr_sb", cdrSb);
                Registration registration = registerManualAndSync(subjectId, features, visit.visitDate(),
                        visit.dataVersion(), null, principal);
                Long taskId = null;
                String taskStatus = null;
                String message = null;
                if (evaluate && "SYNCED".equals(registration.hostSyncStatus())) {
                    Task task = submit(registration.snapshotId(), "batch-" + registration.snapshotId(), principal);
                    taskId = task.taskId();
                    taskStatus = task.status();
                } else if (evaluate) {
                    message = "快照已登记，但尚未同步到研究宿主，暂未评估";
                }
                items.add(new BatchItem(subjectId, registration.snapshotId(), registration.hostSyncStatus(),
                        taskId, taskStatus, message));
            } catch (BusinessException problem) {
                items.add(new BatchItem(subjectId, null, null, null, null, problem.getMessage()));
            } catch (RuntimeException problem) {
                items.add(new BatchItem(subjectId, null, null, null, null, "批量生成失败"));
            }
        }
        int succeeded = (int) items.stream().filter(item -> item.snapshotId() != null).count();
        return new BatchResult(items, succeeded, items.size() - succeeded);
    }

    private static BigDecimal rawScore(String raw, BigDecimal derived) {
        if (raw == null || raw.isBlank()) return derived;
        return new BigDecimal(raw.trim());
    }

    private static void putIfPresent(ObjectNode features, String name, BigDecimal value) {
        if (value == null) {
            features.putNull(name);
        } else {
            // Python parses JSON numbers as doubles; normalize JDBC BigDecimal here so a
            // DECIMAL 24.00 and a JSON 24.0 produce the same canonical digest.
            features.put(name, value.doubleValue());
        }
    }

    /** Today's research throughput for the researcher workbench (owner-scoped). */
    TaskSummary summary(AppUserPrincipal principal) {
        requireResearchRole(principal);
        Instant start = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
        List<Task> tasks = jdbc.query(TASK_QUERY + """
                 WHERE s.authorized_user_id=? AND t.created_at>=?
                 ORDER BY t.created_at DESC,t.id DESC
                """, (row, index) -> {
                    try {
                        return mapTask(row, principal);
                    } catch (BusinessException denied) {
                        if (denied.errorCode() == ErrorCode.ACCESS_DENIED) {
                            return null;
                        }
                        throw denied;
                    }
                }, principal.userId(), Timestamp.from(start)).stream().filter(java.util.Objects::nonNull).toList();
        long completed = tasks.stream().filter(task -> "COMPLETED".equals(task.status())).count();
        long failed = tasks.stream().filter(task -> "FAILED".equals(task.status())
                || OUTCOME_UNKNOWN.equals(task.status())).count();
        long average = Math.round(tasks.stream().filter(task -> "COMPLETED".equals(task.status()))
                .mapToLong(task -> Math.max(0, Duration.between(task.createdAt(), task.updatedAt()).toSeconds()))
                .average().orElse(0));
        return new TaskSummary(tasks.size(), completed, failed, average,
                tasks.stream().limit(5).toList());
    }

    Metrics metrics(AppUserPrincipal principal) {
        requireMetricsRole(principal);
        Instant start = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
        String scope;
        List<Object> args = new ArrayList<>();
        if (principal.roleCodes().contains("ADMIN")) {
            scope = "";
        } else if (principal.roleCodes().contains("DOCTOR")) {
            scope = " AND EXISTS (SELECT 1 FROM subject_permission p WHERE p.subject_id=s.subject_id"
                    + " AND p.user_id=? AND p.permission_type IN ('VIEW','MANAGE'))";
            args.add(principal.userId());
        } else {
            scope = " AND s.authorized_user_id=?";
            args.add(principal.userId());
        }
        args.add(Timestamp.from(start));
        List<Object[]> rows = jdbc.query("""
                SELECT t.status,t.review_status,t.created_at,t.updated_at
                FROM research_table_task t
                JOIN research_table_snapshot s ON s.id=t.snapshot_id
                WHERE 1=1
                """ + scope + " AND t.created_at>=?", (row, index) -> new Object[] {
                row.getString(1), row.getString(2), row.getTimestamp(3), row.getTimestamp(4)
        }, args.toArray());

        long completed = 0;
        long blocked = 0;
        long failed = 0;
        long running = 0;
        long pendingReview = 0;
        long durationSeconds = 0;
        long durationCount = 0;
        for (Object[] row : rows) {
            String status = (String) row[0];
            String review = (String) row[1];
            if ("COMPLETED".equals(status)) {
                completed++;
                if ("PENDING".equals(review)) {
                    pendingReview++;
                }
                Timestamp created = (Timestamp) row[2];
                Timestamp updated = (Timestamp) row[3];
                if (created != null && updated != null) {
                    durationSeconds += Math.max(0, Duration.between(created.toInstant(), updated.toInstant()).getSeconds());
                    durationCount++;
                }
            } else if ("BLOCKED".equals(status)) {
                blocked++;
            } else if ("FAILED".equals(status) || OUTCOME_UNKNOWN.equals(status)) {
                failed++;
            } else if ("RUNNING".equals(status)) {
                running++;
            }
        }

        List<Object> syncArgs = args.subList(0, args.size() - 1);
        Long synced = jdbc.queryForObject(
                "SELECT COUNT(*) FROM research_table_snapshot s WHERE s.host_sync_status='SYNCED'" + scope,
                Long.class, syncArgs.toArray());
        long averageSeconds = durationCount == 0 ? 0 : durationSeconds / durationCount;
        return new Metrics(rows.size(), completed, blocked, failed, running, pendingReview,
                synced == null ? 0 : synced, averageSeconds);
    }

    String syncToHost(long snapshotId, AppUserPrincipal principal) {
        requireResearchRole(principal);
        var row = jdbc.query("""
                SELECT subject_id,authorized_user_id,request_json,model_json,context_json,bundle_json
                FROM research_table_snapshot WHERE id=?
                """, (result, index) -> new Object[] {result.getLong(1), result.getLong(2),
                result.getString(3), result.getString(4), result.getString(5), result.getString(6)},
                snapshotId).stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
        long subjectId = (long) row[0];
        if ((long) row[1] != principal.userId() || !access.canReadRawScores(principal, subjectId)
                || row[4] == null) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        try {
            JsonNode request = protocol.parse(((String) row[2]).getBytes(StandardCharsets.UTF_8));
            JsonNode model = protocol.parse(((String) row[3]).getBytes(StandardCharsets.UTF_8));
            JsonNode context = protocol.parse(((String) row[4]).getBytes(StandardCharsets.UTF_8));
            ObjectNode bundle = JsonNodeFactory.instance.objectNode();
            bundle.put("schemaVersion", "research-dynamic-snapshot-v1");
            bundle.put("runId", request.path("runId").asText());
            bundle.set("request", request);
            bundle.set("model", model);
            bundle.set("context", context);
            bundle.put("requestSha256", protocol.digest(request));
            bundle.put("inputSha256", protocol.digest(protocol.admissionCore(request, model)));
            bundle.put("contextSha256", protocol.digest(context));
            bundle.put("dataScope", "CONSTRUCTED_VALIDATION");
            bundle.put("snapshotId", snapshotId);
            bundle.put("subjectId", subjectId);
            byte[] bundleBytes = protocol.canonicalBytes(bundle);
            jdbc.update("UPDATE research_table_snapshot SET bundle_json=? WHERE id=?",
                    new String(bundleBytes, StandardCharsets.UTF_8), snapshotId);
            ResearchTableClient.Reply reply;
            try {
                reply = client.registerSnapshot(bundleBytes);
            } catch (BusinessException unavailable) {
                if (unavailable.errorCode() == ErrorCode.RESEARCH_UNAVAILABLE) {
                    updateSync(snapshotId, "PENDING");
                    return "PENDING";
                }
                throw unavailable;
            } catch (RuntimeException invalid) {
                updateSync(snapshotId, "FAILED");
                audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_SNAPSHOT_SYNC_FAILED",
                        "snapshotId=" + snapshotId + " error=host-protocol");
                return "FAILED";
            }
            if (reply == null || reply.httpStatus() != 200
                    || !"SYNCED".equals(reply.body().path("status").asText())) {
                String hostError = reply == null ? "" : reply.body().path("errorCode").asText("");
                updateSync(snapshotId, "FAILED");
                audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_SNAPSHOT_SYNC_FAILED",
                        "snapshotId=" + snapshotId + " status="
                                + (reply == null ? "unavailable" : reply.httpStatus())
                                + (hostError.isBlank() ? "" : " hostError=" + hostError));
                return "FAILED";
            }
            updateSync(snapshotId, "SYNCED");
            audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_SNAPSHOT_SYNCED",
                    "snapshotId=" + snapshotId + " subjectId=" + subjectId);
            return "SYNCED";
        } catch (IOException invalid) {
            updateSync(snapshotId, "FAILED");
            return "FAILED";
        }
    }

    private void updateSync(long snapshotId, String status) {
        jdbc.update("UPDATE research_table_snapshot SET host_sync_status=?,host_synced_at=? WHERE id=?",
                status, "SYNCED".equals(status) ? Timestamp.from(clock.instant()) : null, snapshotId);
    }

    /**
     * Normalize caller-supplied observations into the trusted seven-variable
     * admission form. Provenance, QC and completion are server-owned: a client may
     * supply values and dates, never a model identity or a pass/fail decision.
     */
    private ObjectNode normalizedRequest(JsonNode source, String subjectCode) {
        if (source == null || !source.isObject()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "研究请求必须为 JSON 对象");
        }
        if (!subjectCode.equals(source.path("subjectCode").asText())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请求 subjectCode 必须与所选受试者一致");
        }
        ObjectNode request = source.deepCopy();
        JsonNode node = request.get("features");
        if (node == null || !node.isObject()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "features 必须为七变量对象");
        }
        ObjectNode features = (ObjectNode) node;
        for (String feature : MANUAL_FEATURES) {
            JsonNode raw = features.get(feature);
            if (raw == null || !raw.isObject()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "缺少特征 " + feature);
            }
            ObjectNode observation = (ObjectNode) raw;
            boolean missing = !observation.has("value") || observation.get("value").isNull();
            boolean demographic = feature.equals("age_years") || feature.equals("education_years");
            observation.put("unit", demographic ? "years" : "score");
            observation.put("sourceRef", "CONSTRUCTED-SRC-" + feature);
            observation.put("evidenceRef", "EV-" + feature);
            if (missing) {
                observation.putNull("value");
                observation.putNull("measuredDate");
                observation.putNull("availableDate");
                observation.put("dateBasis", "UNKNOWN");
                observation.putNull("scanId");
                observation.put("qc", "UNKNOWN");
                observation.put("completion", "INCOMPLETE");
                observation.put("missingReason", "NOT_COLLECTED");
            } else {
                observation.put("qc", demographic ? "NOT_APPLICABLE" : "PASS");
                observation.put("completion", demographic ? "NOT_APPLICABLE" : "COMPLETE");
                observation.putNull("missingReason");
                if (!observation.hasNonNull("dateBasis")) {
                    observation.put("dateBasis", "MEASUREMENT_DATE");
                }
            }
        }
        return request;
    }

    long registerManual(long subjectId, JsonNode features, String indexDate, String dataVersion,
            String runId, AppUserPrincipal principal) {
        requireResearchRole(principal);
        if (!access.canReadRawScores(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        String subjectCode = jdbc.query("SELECT subject_code FROM subject WHERE id=?", (row, index) -> row.getString(1),
                subjectId).stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
        String date = indexDate == null || indexDate.isBlank() ? LocalDate.now(clock).toString() : indexDate;
        try {
            LocalDate.parse(date);
        } catch (DateTimeParseException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "indexDate 须为 YYYY-MM-DD");
        }
        String version = dataVersion == null || dataVersion.isBlank()
                ? "manual-snapshot-" + date
                : dataVersion;
        String run = runId == null || runId.isBlank()
                ? "run-manual-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : runId;
        if (!run.matches("^run-[A-Za-z0-9][A-Za-z0-9-]{0,63}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "runId 须匹配 run-* 格式");
        }
        ObjectNode request = manualRequest(subjectCode, features, date, version, run);
        return register(subjectId, request, principal);
    }

    private ObjectNode manualRequest(String subjectCode, JsonNode features, String date,
            String dataVersion, String runId) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("contractVersion", "research-table-agent-v1");
        request.put("runId", runId);
        request.put("modelKey", TRAINED_MODEL);
        request.put("subjectCode", subjectCode);
        request.put("dataVersion", dataVersion);
        request.put("indexDate", date);
        request.put("availabilityPolicy", "RETROSPECTIVE_SNAPSHOT");
        request.put("predictionWindowMonths", 24);
        ObjectNode observations = JsonNodeFactory.instance.objectNode();
        for (String feature : MANUAL_FEATURES) {
            JsonNode value = features.path(feature);
            if (!value.isNull() && !value.isNumber()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, feature + " 须为数字或 null");
            }
            observations.set(feature, manualObservation(feature, value, date));
        }
        request.set("features", observations);
        return request;
    }

    private ObjectNode manualObservation(String feature, JsonNode value, String date) {
        ObjectNode observation = JsonNodeFactory.instance.objectNode();
        boolean missing = value.isNull() || value.isMissingNode();
        boolean demographic = feature.equals("age_years") || feature.equals("education_years");
        if (missing) {
            observation.putNull("value");
            observation.put("qc", "UNKNOWN");
            observation.put("completion", "INCOMPLETE");
            observation.put("missingReason", "NOT_COLLECTED");
            observation.putNull("measuredDate");
            observation.putNull("availableDate");
            observation.put("dateBasis", "UNKNOWN");
        } else {
            observation.set("value", value);
            observation.put("qc", demographic ? "NOT_APPLICABLE" : "PASS");
            observation.put("completion", demographic ? "NOT_APPLICABLE" : "COMPLETE");
            observation.putNull("missingReason");
            observation.put("measuredDate", date);
            observation.put("availableDate", date);
            observation.put("dateBasis", "MEASUREMENT_DATE");
        }
        observation.put("unit", demographic ? "years" : "score");
        observation.put("sourceRef", "CONSTRUCTED-SRC-" + feature);
        observation.putNull("scanId");
        observation.put("evidenceRef", "EV-" + feature);
        return observation;
    }

    List<SnapshotOption> snapshots(long subjectId, AppUserPrincipal principal) {
        requireResearchRole(principal);
        if (!access.canReadRawScores(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        JsonNode currentModel = modelIdentity.identity();
        return jdbc.query("""
                SELECT s.id,s.request_json,s.model_json,s.host_sync_status,t.id AS task_id
                FROM research_table_snapshot s LEFT JOIN research_table_task t ON t.snapshot_id=s.id
                WHERE s.subject_id=? AND s.authorized_user_id=? AND s.data_scope='CONSTRUCTED_VALIDATION'
                ORDER BY s.id
                """, (row, index) -> {
                    try {
                        JsonNode request = protocol.parse(row.getString("request_json").getBytes(StandardCharsets.UTF_8));
                        if (!TRAINED_MODEL.equals(request.path("modelKey").asText())) {
                            return null;
                        }
                        boolean compatible;
                        try {
                            JsonNode storedModel = protocol.parse(
                                    row.getString("model_json").getBytes(StandardCharsets.UTF_8));
                            compatible = currentModel.equals(storedModel);
                        } catch (IOException | IllegalArgumentException invalid) {
                            compatible = false;
                        }
                        return new SnapshotOption(row.getLong("id"), row.getObject("task_id", Long.class),
                                row.getString("host_sync_status"), compatible,
                                compatible ? null : "旧模型身份或软件锁摘要与新研究宿主不兼容，请重新登记");
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
            return new Submission(existing.get(), null, null);
        }
        String syncStatus = jdbc.queryForObject(
                "SELECT host_sync_status FROM research_table_snapshot WHERE id=?", String.class, snapshotId);
        if (!"SYNCED".equals(syncStatus)) {
            throw new BusinessException(ErrorCode.RESEARCH_UNAVAILABLE,
                    "该快照尚未同步到研究宿主，请等待登记完成或联系管理员");
        }
        if (!jdbc.queryForList("SELECT id FROM research_table_task WHERE snapshot_id=?", Long.class,
                snapshotId).isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "该冻结快照已有关联任务");
        }
        client.requireConfigured();
        var keys = new GeneratedKeyHolder();
        Timestamp now = Timestamp.from(clock.instant());
        String executionId = UUID.randomUUID().toString();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO research_table_task(snapshot_id,run_id,status,error_code,execution_id,
                        created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, snapshotId);
            statement.setString(2, snapshot.request().get("runId").textValue());
            statement.setString(3, "RUNNING");
            statement.setString(4, null);
            statement.setString(5, executionId);
            statement.setTimestamp(6, now);
            statement.setTimestamp(7, now);
            return statement;
        }, keys);
        long id = keys.getKey().longValue();
        idempotency.saveAndFlush(new IdempotencyRecord(principal.userId(), OPERATION, key, digest, id));
        // Claim inside the reservation transaction. An unowned RUNNING row never reaches another
        // instance, while the conditional UPDATE still gives the task a visible lease.
        if (!lease.claim(id, executionId)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "研究任务无法取得执行租约");
        }
        return new Submission(id, snapshot, executionId);
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

    private void execute(long taskId, Snapshot snapshot, String executionId) {
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
        } catch (BusinessException unavailable) {
            if (unavailable.errorCode() == ErrorCode.RESEARCH_UNAVAILABLE) {
                jdbc.update("""
                        UPDATE research_table_task
                        SET status='OUTCOME_UNKNOWN',error_code='RESEARCH_UNAVAILABLE',
                            result_json=NULL,execution_id=NULL,executor_id=NULL,lease_expires_at=NULL,
                            updated_at=?
                        WHERE id=? AND status='RUNNING' AND execution_id=?
                        """, Timestamp.from(clock.instant()), taskId, executionId);
            }
            throw unavailable;
        }
        // Only the execution that still owns the task may write its terminal state. A slow
        // attempt that was overtaken by a retry must not overwrite the newer outcome.
        jdbc.update("""
                UPDATE research_table_task
                SET status=?,error_code=?,result_json=?,executor_id=NULL,lease_expires_at=NULL,updated_at=?
                WHERE id=? AND status='RUNNING' AND execution_id=?
                """, status, code, result, Timestamp.from(clock.instant()), taskId, executionId);
    }

    Task find(long id, AppUserPrincipal principal) {
        requireArchiveRole(principal);
        return jdbc.query(TASK_QUERY + " WHERE t.id=?", (row, index) -> mapTask(row, principal),
                id).stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
    }

    ReviewQueue reviewQueue(String status, int page, int pageSize, AppUserPrincipal principal) {
        requireReviewRole(principal);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        boolean all = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status);
        String reviewFilter = all ? "" : " AND t.review_status=?";
        String permissionFilter =
                " AND EXISTS (SELECT 1 FROM subject_permission p WHERE p.subject_id=s.subject_id"
                        + " AND p.user_id=? AND p.permission_type IN ('VIEW','MANAGE'))";
        String from = """
                FROM research_table_task t
                JOIN research_table_snapshot s ON s.id=t.snapshot_id
                JOIN subject sub ON sub.id=s.subject_id
                WHERE t.status='COMPLETED'
                """ + reviewFilter + permissionFilter;
        List<Object> params = new ArrayList<>();
        if (!all) params.add(status.toUpperCase(Locale.ROOT));
        params.add(principal.userId());

        Long total = jdbc.queryForObject("SELECT COUNT(*) " + from, Long.class, params.toArray());
        String select = """
                SELECT t.id,t.snapshot_id,t.run_id,t.status,t.error_code,t.result_json,t.created_at,t.updated_at,
                       t.review_status,t.reviewed_by,t.reviewed_at,t.review_comment,
                       s.subject_id,s.authorized_user_id,sub.subject_code,sub.alias AS subject_alias
                """ + from + " ORDER BY t.created_at DESC,t.id DESC LIMIT ? OFFSET ?";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(safeSize);
        pageParams.add((long) (safePage - 1) * safeSize);
        List<Task> items = jdbc.query(select, (row, index) -> mapTask(row, principal), pageParams.toArray());
        return new ReviewQueue(items, total == null ? 0 : total, safePage, safeSize);
    }

    ReviewQueue reports(String status, int page, int pageSize, AppUserPrincipal principal) {
        requireReportRole(principal);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        boolean all = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status);
        String statusFilter = all ? " AND t.status<>'RUNNING'" : " AND t.status=?";
        String scopeFilter;
        List<Object> params = new ArrayList<>();
        if (!all) params.add(status.toUpperCase(Locale.ROOT));
        if (principal.roleCodes().contains("DOCTOR")) {
            scopeFilter = " AND EXISTS (SELECT 1 FROM subject_permission p WHERE p.subject_id=s.subject_id"
                    + " AND p.user_id=? AND p.permission_type IN ('VIEW','MANAGE'))";
            params.add(principal.userId());
        } else {
            scopeFilter = " AND s.authorized_user_id=?";
            params.add(principal.userId());
            if (!access.hasFullRawScoreVisibility(principal)) {
                scopeFilter += " AND EXISTS (SELECT 1 FROM subject_permission p WHERE p.subject_id=s.subject_id"
                        + " AND p.user_id=? AND p.permission_type IN ('VIEW','MANAGE'))";
                params.add(principal.userId());
            }
        }
        String from = """
                FROM research_table_task t
                JOIN research_table_snapshot s ON s.id=t.snapshot_id
                JOIN subject sub ON sub.id=s.subject_id
                WHERE 1=1
                """ + statusFilter + scopeFilter;
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + from, Long.class, params.toArray());
        String select = """
                SELECT t.id,t.snapshot_id,t.run_id,t.status,t.error_code,t.result_json,t.created_at,t.updated_at,
                       t.review_status,t.reviewed_by,t.reviewed_at,t.review_comment,
                       s.subject_id,s.authorized_user_id,sub.subject_code,sub.alias AS subject_alias
                """ + from + " ORDER BY t.created_at DESC,t.id DESC LIMIT ? OFFSET ?";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(safeSize);
        pageParams.add((long) (safePage - 1) * safeSize);
        List<Task> items = jdbc.query(select, (row, index) -> mapTask(row, principal), pageParams.toArray());
        return new ReviewQueue(items, total == null ? 0 : total, safePage, safeSize);
    }

    Task review(long taskId, String decision, String comment, AppUserPrincipal principal) {
        requireReviewRole(principal);
        String normalized = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("CONFIRMED", "NEEDS_DATA", "REJECTED").contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "复核结论不合法");
        }
        String text = comment == null ? "" : comment.trim();
        if (text.isEmpty() || text.length() > 2000) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "复核意见必填且不能超过 2000 字");
        }
        Task task = find(taskId, principal);
        if (!hasExplicitReviewGrant(principal.userId(), task.subjectId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (!"COMPLETED".equals(task.status())) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "仅已完成的研究任务可复核");
        }
        int updated = jdbc.update("""
                UPDATE research_table_task
                SET review_status=?,reviewed_by=?,reviewed_at=?,review_comment=?
                WHERE id=? AND status='COMPLETED' AND review_status='PENDING'
                """, normalized, principal.userId(), Timestamp.from(clock.instant()), text, taskId);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "该研究任务已被复核，请刷新后查看");
        }
        audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_REVIEW",
                "taskId=" + taskId + " decision=" + normalized);
        return find(taskId, principal);
    }

    /** Research results that belong to one subject, readable by the treating doctor as well. */
    List<Task> archive(long subjectId, AppUserPrincipal principal) {
        requireArchiveRole(principal);
        if (!access.canReadRawScores(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (principal.roleCodes().contains("DOCTOR")) {
            return jdbc.query(TASK_QUERY + """
                     WHERE s.subject_id=?
                     ORDER BY t.created_at DESC,t.id DESC LIMIT 50
                    """, (row, index) -> mapTask(row, principal), subjectId);
        }
        return jdbc.query(TASK_QUERY + """
                 WHERE s.subject_id=? AND s.authorized_user_id=?
                 ORDER BY t.created_at DESC,t.id DESC LIMIT 50
                """, (row, index) -> mapTask(row, principal), subjectId, principal.userId());
    }

    MigrationResult migrateLegacySnapshots(AppUserPrincipal principal) {
        requireAdmin(principal);
        List<Long> ids = jdbc.queryForList("""
                SELECT s.id FROM research_table_snapshot s
                WHERE (s.context_json IS NULL OR s.context_sha256 IS NULL)
                  AND s.data_scope='CONSTRUCTED_VALIDATION'
                  AND NOT EXISTS (SELECT 1 FROM research_table_task t WHERE t.snapshot_id=s.id)
                ORDER BY s.id
                """, Long.class);
        List<Long> referenced = jdbc.queryForList("""
                SELECT DISTINCT s.id FROM research_table_snapshot s
                JOIN research_table_task t ON t.snapshot_id=s.id
                WHERE (s.context_json IS NULL OR s.context_sha256 IS NULL)
                  AND s.data_scope='CONSTRUCTED_VALIDATION'
                ORDER BY s.id
                """, Long.class);
        int migrated = 0;
        List<Long> failed = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();
        for (Long id : referenced) {
            // Referenced snapshots must stay byte-identical: their task results are bound to
            // the stored request/input/context digests. Rebuild a new snapshot instead.
            jdbc.update("UPDATE research_table_snapshot SET host_sync_status='MIGRATION_SKIPPED' WHERE id=?", id);
            audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_SNAPSHOT_MIGRATION_SKIPPED",
                    "snapshotId=" + id);
            skipped.add(id);
        }
        for (Long id : ids) {
            try {
                String requestText = jdbc.queryForObject(
                        "SELECT request_json FROM research_table_snapshot WHERE id=?", String.class, id);
                String subjectCode = jdbc.queryForObject("""
                        SELECT sub.subject_code
                        FROM research_table_snapshot s
                        JOIN subject sub ON sub.id=s.subject_id
                        WHERE s.id=?
                        """, String.class, id);
                JsonNode raw = protocol.parse(requestText.getBytes(StandardCharsets.UTF_8));
                JsonNode request = normalizedRequest(raw, subjectCode);
                ObjectNode model = modelIdentity.identity();
                protocol.validateRequest(request, model);
                byte[] requestBytes = protocol.canonicalBytes(request);
                JsonNode context = protocol.registrationContext(request, model);
                jdbc.update("""
                        UPDATE research_table_snapshot
                        SET request_json=?,wire_sha256=?,request_sha256=?,model_json=?,context_json=?,
                            context_sha256=?,input_sha256=?,host_sync_status='PENDING',
                            bundle_json=NULL,host_synced_at=NULL
                        WHERE id=?
                        """, new String(requestBytes, StandardCharsets.UTF_8), protocol.digest(request),
                        protocol.digest(request), model.toString(),
                        context.toString(), protocol.digest(context),
                        protocol.digest(protocol.admissionCore(request, model)), id);
                audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_SNAPSHOT_MIGRATED",
                        "snapshotId=" + id);
                migrated++;
            } catch (Exception error) {
                jdbc.update("UPDATE research_table_snapshot SET host_sync_status='MIGRATION_FAILED' WHERE id=?",
                        id);
                audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_SNAPSHOT_MIGRATION_FAILED",
                        "snapshotId=" + id);
                failed.add(id);
            }
        }
        return new MigrationResult(migrated, failed.size(), skipped.size(), failed, skipped);
    }

    List<Task> history(long subjectId, AppUserPrincipal principal) {
        requireResearchRole(principal);
        if (!access.canReadRawScores(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return jdbc.query(TASK_QUERY + """
                 WHERE s.subject_id=? AND s.authorized_user_id=?
                 ORDER BY t.created_at DESC,t.id DESC LIMIT 50
                """, (row, index) -> mapTask(row, principal), subjectId, principal.userId());
    }

    Task retry(long taskId, AppUserPrincipal principal) {
        requireResearchRole(principal);
        Task task = find(taskId, principal);
        if (!"FAILED".equals(task.status()) && !OUTCOME_UNKNOWN.equals(task.status())) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "仅失败或结果未知的研究任务可重试");
        }
        Snapshot snapshot = jdbc.query("SELECT * FROM research_table_snapshot WHERE id=?",
                (row, index) -> readSnapshot(row, principal), task.snapshotId()).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
        // Refuse before claiming the task when the host is disabled; otherwise the retry
        // would be left RUNNING without an executor.
        client.requireConfigured();
        String executionId = UUID.randomUUID().toString();
        // Reset and claim in one transaction so scheduled lease recovery can never observe a
        // transient RUNNING row with no owner between the two statements.
        transaction.executeWithoutResult(status -> {
            int claimed = jdbc.update("""
                    UPDATE research_table_task SET status='RUNNING',execution_id=?,error_code=NULL,
                        result_json=NULL,executor_id=NULL,lease_expires_at=NULL,updated_at=?
                    WHERE id=? AND status IN ('FAILED','OUTCOME_UNKNOWN')
                    """, executionId, Timestamp.from(clock.instant()), taskId);
            if (claimed == 0) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "任务状态已变化，请刷新后重试");
            }
            if (!lease.claim(taskId, executionId)) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "任务已被其他实例领取，请刷新后重试");
            }
        });
        audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_RETRY",
                "taskId=" + taskId + " executionId=" + executionId);
        execute(taskId, snapshot, executionId);
        audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_RESULT",
                "taskId=" + taskId + " status=" + find(taskId, principal).status());
        return find(taskId, principal);
    }

    private Task mapTask(java.sql.ResultSet row, AppUserPrincipal principal) throws java.sql.SQLException {
        long subjectId = row.getLong("subject_id");
        long ownerId = row.getLong("authorized_user_id");
        boolean owner = principal.userId() == ownerId;
        boolean treatingDoctor = principal.roleCodes().contains("DOCTOR");
        // Every read requires a *current* archive permission; ownership alone no longer
        // grants access after it is revoked.
        if (!access.canReadRawScores(principal, subjectId) || (!owner && !treatingDoctor)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        String result = row.getString("result_json");
        try {
            Timestamp reviewedAt = row.getTimestamp("reviewed_at");
            long reviewedBy = row.getLong("reviewed_by");
            Long reviewer = row.wasNull() ? null : reviewedBy;
            return new Task(row.getLong("id"), row.getLong("snapshot_id"), subjectId,
                    row.getString("subject_code"), row.getString("subject_alias"),
                    row.getString("run_id"),
                    row.getString("status"), row.getString("error_code"),
                    result == null ? null : protocol.parse(result.getBytes(StandardCharsets.UTF_8)),
                    row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                    row.getString("review_status"), reviewer,
                    reviewedAt == null ? null : reviewedAt.toInstant(), row.getString("review_comment"));
        } catch (IOException invalid) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "研究任务结果不可读取");
        }
    }

    private void authorize(AppUserPrincipal principal, long subjectId, long owner) {
        if (principal.userId() != owner || !access.canReadRawScores(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    /** Clinical review is narrower than research read: an explicit VIEW/MANAGE grant is required. */
    private boolean hasExplicitReviewGrant(long userId, long subjectId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM subject_permission
                WHERE subject_id=? AND user_id=? AND permission_type IN ('VIEW','MANAGE')
                """, Long.class, subjectId, userId);
        return count != null && count > 0;
    }

    private static void requireMetricsRole(AppUserPrincipal principal) {
        if (principal.roleCodes().contains("PATIENT")
                || (!principal.roleCodes().contains("ADMIN")
                && !principal.roleCodes().contains("DOCTOR")
                && !principal.roleCodes().contains("RESEARCHER"))) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private static void requireReportRole(AppUserPrincipal principal) {
        if (principal.roleCodes().contains("PATIENT")
                || (!principal.roleCodes().contains("DOCTOR") && !principal.roleCodes().contains("RESEARCHER"))) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private static void requireReviewRole(AppUserPrincipal principal) {
        if (!principal.roleCodes().contains("DOCTOR") || principal.roleCodes().contains("PATIENT")) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private static void requireAdmin(AppUserPrincipal principal) {
        if (!principal.roleCodes().contains("ADMIN")) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private static void requireResearchRole(AppUserPrincipal principal) {
        if (principal.roleCodes().contains("PATIENT") || (!principal.roleCodes().contains("ADMIN")
                && !principal.roleCodes().contains("RESEARCHER"))) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private static void requireArchiveRole(AppUserPrincipal principal) {
        if (principal.roleCodes().contains("PATIENT")) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (!principal.roleCodes().contains("ADMIN")
                && !principal.roleCodes().contains("RESEARCHER")
                && !principal.roleCodes().contains("DOCTOR")) {
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
