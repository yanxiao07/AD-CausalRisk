package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.audit.AuditService;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.importjob.IdempotencyRecordRepository;
import com.adcausalrisk.backend.importjob.entity.IdempotencyRecord;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
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
                   s.subject_id,s.authorized_user_id
            FROM research_table_task t JOIN research_table_snapshot s ON s.id=t.snapshot_id
            """;

    record Task(long taskId, long snapshotId, long subjectId, String runId, String status,
                String errorCode, JsonNode result, Instant createdAt, Instant updatedAt) {}
    record SnapshotOption(long snapshotId, Long taskId, String hostSyncStatus) {}
    record Registration(long snapshotId, String hostSyncStatus) {}

    private record Snapshot(byte[] requestBytes, JsonNode request, JsonNode model, String requestDigest,
                            String inputDigest, String contextDigest) {}
    private record Submission(long taskId, Snapshot snapshot) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final IdempotencyRecordRepository idempotency;
    private final SubjectAccessPolicy access;
    private final ResearchTableClient client;
    private final ResearchTableProtocol protocol;
    private final ResearchModelIdentity modelIdentity;
    private final AuditService audit;
    private final Clock clock;
    @Value("${research.table.test-fixtures-enabled:false}")
    private boolean testFixturesEnabled;

    ResearchTableAssessmentService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            IdempotencyRecordRepository idempotency, SubjectAccessPolicy access,
            ResearchTableClient client, ResearchTableProtocol protocol, ResearchModelIdentity modelIdentity,
            AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        this.idempotency = idempotency;
        this.access = access;
        this.client = client;
        this.protocol = protocol;
        this.modelIdentity = modelIdentity;
        this.audit = audit;
        this.clock = clock;
    }

    Task submit(long snapshotId, String key, AppUserPrincipal principal) {
        requireResearchRole(principal);
        if (key.isBlank() || key.length() > 100 || !key.equals(key.trim())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "幂等键须为1至100个字符");
        }
        String digest = snapshotKey(snapshotId);
        String syncStatus = jdbc.query("SELECT host_sync_status FROM research_table_snapshot WHERE id=?",
                (row, index) -> row.getString(1), snapshotId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
        if (!"SYNCED".equals(syncStatus)) {
            throw new BusinessException(ErrorCode.RESEARCH_UNAVAILABLE,
                    "该快照尚未同步到研究宿主，请等待登记完成或联系管理员");
        }
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

    Registration registerManualAndSync(long subjectId, JsonNode features, String indexDate, String dataVersion,
            String runId, AppUserPrincipal principal) {
        long id = registerManual(subjectId, features, indexDate, dataVersion, runId, principal);
        return new Registration(id, syncToHost(id, principal));
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
                updateSync(snapshotId, "FAILED");
                audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_SNAPSHOT_SYNC_FAILED",
                        "snapshotId=" + snapshotId + " status=" + (reply == null ? "unavailable" : reply.httpStatus()));
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
        return jdbc.query("""
                SELECT s.id,s.request_json,s.host_sync_status,t.id AS task_id
                FROM research_table_snapshot s LEFT JOIN research_table_task t ON t.snapshot_id=s.id
                WHERE s.subject_id=? AND s.authorized_user_id=? AND s.data_scope='CONSTRUCTED_VALIDATION'
                ORDER BY s.id
                """, (row, index) -> {
                    try {
                        JsonNode request = protocol.parse(row.getString("request_json").getBytes(StandardCharsets.UTF_8));
                        if (!TRAINED_MODEL.equals(request.path("modelKey").asText())) {
                            return null;
                        }
                        return new SnapshotOption(row.getLong("id"), row.getObject("task_id", Long.class),
                                row.getString("host_sync_status"));
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
        return jdbc.query(TASK_QUERY + " WHERE t.id=?", (row, index) -> mapTask(row, principal),
                id).stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
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
        jdbc.update("""
                UPDATE research_table_task SET status=?,error_code=NULL,result_json=NULL,updated_at=? WHERE id=?
                """, OUTCOME_UNKNOWN, Timestamp.from(clock.instant()), taskId);
        audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_RETRY", "taskId=" + taskId);
        execute(taskId, snapshot);
        audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_RESULT",
                "taskId=" + taskId + " status=" + find(taskId, principal).status());
        return find(taskId, principal);
    }

    private Task mapTask(java.sql.ResultSet row, AppUserPrincipal principal) throws java.sql.SQLException {
        long subjectId = row.getLong("subject_id");
        authorize(principal, subjectId, row.getLong("authorized_user_id"));
        String result = row.getString("result_json");
        try {
            return new Task(row.getLong("id"), row.getLong("snapshot_id"), subjectId, row.getString("run_id"),
                    row.getString("status"), row.getString("error_code"),
                    result == null ? null : protocol.parse(result.getBytes(StandardCharsets.UTF_8)),
                    row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
        } catch (IOException invalid) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "研究任务结果不可读取");
        }
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
