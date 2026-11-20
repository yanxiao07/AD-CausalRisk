package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.audit.AuditService;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.common.PageResult;
import com.adcausalrisk.backend.importjob.IdempotencyRecordRepository;
import com.adcausalrisk.backend.importjob.csv.CsvParser;
import com.adcausalrisk.backend.importjob.csv.CsvRecord;
import com.adcausalrisk.backend.importjob.entity.IdempotencyRecord;
import com.adcausalrisk.backend.qualitycheck.CurrentArchiveQuality;
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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
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
import java.util.Objects;
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
                   s.subject_id,s.authorized_user_id,s.data_scope,sub.subject_code,sub.alias AS subject_alias
            FROM research_table_task t
            JOIN research_table_snapshot s ON s.id=t.snapshot_id
            JOIN subject sub ON sub.id=s.subject_id
            """;

    record Task(long taskId, long snapshotId, long subjectId, String subjectCode, String subjectAlias,
                String runId, String status, String errorCode, String dataScope, JsonNode result,
                Instant createdAt, Instant updatedAt,
                String reviewStatus, Long reviewedBy, Instant reviewedAt, String reviewComment) {}

    record ReviewQueue(List<Task> items, long total, int page, int pageSize) {}
    record PatientPublication(boolean published, String message) {}
    record SnapshotOption(long snapshotId, Long taskId, String hostSyncStatus,
                          boolean compatible, String compatibilityMessage, String modelKey, String dataScope) {}
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
            String subjectDataVersion,
            String qualityStatus,
            String qualityDigest,
            String qualityDataVersion) {}

    record MigrationResult(int migrated, int failed, int skipped, List<Long> failedSnapshotIds,
                           List<Long> skippedSnapshotIds) {}

    private record Snapshot(byte[] requestBytes, JsonNode request, JsonNode model, String dataScope,
                            ControlledResearchCompletion.Binding binding) {}
    private record Submission(long taskId, Snapshot snapshot, String executionId) {}

    private record SyncSnapshot(long subjectId, long ownerId, String request, String model,
                                String context, String dataScope, String wireSha256,
                                String requestSha256, String inputSha256, String contextSha256) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final IdempotencyRecordRepository idempotency;
    private final SubjectAccessPolicy access;
    private final ResearchTableClient client;
    private final SyntheticCareIdentity syntheticCare;
    private final ResearchTableProtocol protocol;
    private final ControlledResearchCompletion controlledCompletion;
    private final ResearchModelIdentity modelIdentity;
    private final ResearchTaskLease lease;
    private final QualityCheckService qualityChecks;
    private final CurrentArchiveQuality archiveQuality;
    private final AuditService audit;
    private final Clock clock;
    @Value("${research.table.test-fixtures-enabled:false}")
    private boolean testFixturesEnabled;
    @Value("${research.table.controlled-admission-enabled:false}")
    private boolean controlledAdmissionEnabled;

    ResearchTableAssessmentService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            IdempotencyRecordRepository idempotency, SubjectAccessPolicy access,
            ResearchTableClient client, SyntheticCareIdentity syntheticCare,
            ResearchTableProtocol protocol, ResearchModelIdentity modelIdentity,
            ResearchTaskLease lease, QualityCheckService qualityChecks, AuditService audit, Clock clock,
            ControlledResearchCompletion controlledCompletion,
            CurrentArchiveQuality archiveQuality) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        this.idempotency = idempotency;
        this.access = access;
        this.client = client;
        this.syntheticCare = syntheticCare;
        this.protocol = protocol;
        this.controlledCompletion = controlledCompletion;
        this.modelIdentity = modelIdentity;
        this.lease = lease;
        this.qualityChecks = qualityChecks;
        this.archiveQuality = archiveQuality;
        this.audit = audit;
        this.clock = clock;
    }

    Task submit(long snapshotId, String key, AppUserPrincipal principal) {
        return submit(snapshotId, null, key, principal);
    }

    Task submit(long snapshotId, Long subjectId, String key, AppUserPrincipal principal) {
        requireResearchRole(principal);
        return submitBound(snapshotId, subjectId, key, principal, false, null);
    }

    Task submitDoctorFixture(long snapshotId, long subjectId, AppUserPrincipal principal) {
        if (!principal.roleCodes().contains("DOCTOR") || principal.roleCodes().contains("PATIENT")
                || !hasExplicitManageGrant(principal.userId(), subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return submitBound(snapshotId, subjectId, "doctor-assessment-" + snapshotId, principal, true, null);
    }

    record ControlledDispatch(long requestId, long qualityId) {}

    Task submitControlledRequest(long snapshotId, long subjectId, ControlledDispatch dispatch, AppUserPrincipal principal) {
        requireResearchRole(principal);
        return submitBound(snapshotId, subjectId, "controlled-assessment-" + dispatch.requestId(), principal, false, dispatch);
    }

    private Task submitBound(long snapshotId, Long subjectId, String key, AppUserPrincipal principal,
                             boolean doctorFixture, ControlledDispatch dispatch) {
        if (key.isBlank() || key.length() > 100 || !key.equals(key.trim())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "幂等键须为1至100个字符");
        }
        String digest = snapshotKey(snapshotId);
        Submission submission;
        try {
            submission = transaction.execute(status -> reserve(snapshotId, subjectId, key, digest, principal, doctorFixture, dispatch));
        } catch (DataIntegrityViolationException race) {
            return replay(key, digest, principal).map(id -> find(id, principal)).orElseThrow(() -> race);
        }
        long taskId = submission.taskId();
        if (submission.snapshot() != null) {
            audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_SUBMIT",
                    "taskId=" + taskId + " executionId=" + submission.executionId());
            execute(taskId, submission.snapshot(), submission.executionId(),
                    dispatch == null ? null : controlledCompletion.endpoint());
            Task task = find(taskId, principal);
            audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_RESULT",
                    "taskId=" + taskId + " status=" + task.status());
            return task;
        }
        return find(taskId, principal);
    }

    String intakeState(long subjectId, AppUserPrincipal principal) {
        requireResearchRole(principal);
        if (!access.canReadRawScores(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return jdbc.query("""
                SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM research_table_snapshot s
                                             WHERE s.subject_id=sub.id AND s.doctor_handoff=TRUE)
                                 THEN 'NO_INTAKE'
                            WHEN EXISTS (SELECT 1 FROM subject_permission p WHERE p.subject_id=sub.id
                                         AND p.user_id=? AND p.permission_type IN ('VIEW','MANAGE'))
                                 THEN 'READY'
                            ELSE 'WAITING_RESEARCH_GRANT' END
                FROM subject sub
                WHERE sub.id=?
                """, (row, index) -> row.getString(1), principal.userId(), subjectId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
    }

    long register(long subjectId, JsonNode request, AppUserPrincipal principal) {
        requireResearchRole(principal);
        return register(subjectId, request, principal, false);
    }

    private long register(long subjectId, JsonNode request, AppUserPrincipal principal, boolean handoff) {
        if (!access.canReadRawScores(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        var wire = fixtureIdentity(subjectId, handoff);
        if (wire == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "研究档案须使用独立来源冻结的快照");
        }
        ObjectNode model = modelIdentity.identity(request.path("modelKey").asText(), protocol);
        if (model == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "研究模型未配置");
        }
        ObjectNode normalized = normalizedRequest(request, wire.subjectCode());
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
                        request_sha256,model_json,data_scope,input_sha256,context_sha256,context_json,doctor_handoff)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
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
            statement.setBoolean(11, handoff);
            return statement;
        }, keys);
        long id = keys.getKey().longValue();
        audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_SNAPSHOT_REGISTERED",
                "snapshotId=" + id + " subjectId=" + subjectId);
        return id;
    }

    long registerCareFixture(long subjectId, CurrentArchiveQuality.Visit visit, AppUserPrincipal actor) {
        var wire = fixtureIdentity(subjectId, true);
        if (!syntheticCare.enabled() || wire == null || !wire.subjectCode().startsWith("DEMO-CARE-")
                || !hasExplicitManageGrant(actor.userId(), subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        var extra = visit.modelInputs();
        var features = JsonNodeFactory.instance.objectNode();
        // Use the same JSON number representation as the research CSV path. SQL decimal scale
        // is a storage detail and must not change the digest verified by the Python host.
        features.put("mmse", visit.mmse().doubleValue());
        features.put("cdr_sb", visit.cdrSb().doubleValue());
        features.put("age_years", extra.ageYears().doubleValue());
        features.put("education_years", extra.educationYears().doubleValue());
        features.put("cdr_global", extra.cdrGlobal().doubleValue());
        features.put("adas__total13", extra.adasCog13().doubleValue());
        features.put("faq__faqtotal", extra.faqTotal().doubleValue());
        var request = manualRequest(wire.subjectCode(), features, visit.date(), wire.dataVersion(),
                "run-care-" + UUID.randomUUID());
        return register(subjectId, request, actor, true);
    }

    String syncCareFixture(long snapshotId, AppUserPrincipal actor) {
        return syncToHost(snapshotId, actor, true);
    }

    private SyntheticCareIdentity.Wire fixtureIdentity(long subjectId, boolean handoff) {
        return jdbc.query("SELECT subject_code,data_version FROM subject WHERE id=?",
                (row, index) -> syntheticCare.resolve(subjectId, row.getString(1), row.getString(2), handoff), subjectId)
                .stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    Registration recordDoctorIntake(long subjectId, JsonNode features, String date, AppUserPrincipal principal) {
        if (!principal.roleCodes().contains("DOCTOR") || !access.canManage(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        var subject = jdbc.queryForMap("SELECT subject_code,data_version FROM subject WHERE id=?", subjectId);
        String code = (String) subject.get("subject_code");
        if (!code.startsWith("DEMO-") || !features.isObject()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "本入口用于模拟档案录入");
        }
        try {
            LocalDate.parse(date);
        } catch (java.time.DateTimeException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请输入有效的评估日期");
        }
        for (String feature : MANUAL_FEATURES) {
            if (!features.path(feature).isNumber()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请填写七项模型输入");
            }
        }
        var request = manualRequest(code, features, date, (String) subject.get("data_version"),
                "run-intake-" + UUID.randomUUID());
        long id = register(subjectId, request, principal, true);
        return new Registration(id, syncToHost(id, principal, true));
    }

    record DoctorIntakeView(long snapshotId, String indexDate, String hostSyncStatus, JsonNode features) {}

    PageResult<DoctorIntakeView> doctorIntakeHistory(long subjectId, int page, int pageSize,
                                                     AppUserPrincipal principal) {
        if (subjectId <= 0 || page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "档案编号或分页参数无效");
        }
        if (!principal.roleCodes().contains("DOCTOR") || principal.roleCodes().contains("PATIENT")) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        long permitted = jdbc.queryForObject("""
                SELECT COUNT(*) FROM subject sub JOIN subject_permission p ON p.subject_id=sub.id
                WHERE sub.id=? AND sub.subject_code LIKE 'DEMO-%' AND p.user_id=? AND p.permission_type='MANAGE'
                """, Long.class, subjectId, principal.userId());
        if (permitted == 0) throw new BusinessException(ErrorCode.ACCESS_DENIED);
        String scope = " FROM research_table_snapshot WHERE subject_id=? AND doctor_handoff=TRUE"
                + " AND data_scope='CONSTRUCTED_VALIDATION'";
        long total = jdbc.queryForObject("SELECT COUNT(*)" + scope, Long.class, subjectId);
        var items = jdbc.query("SELECT id,request_json,wire_sha256,host_sync_status" + scope
                        + " ORDER BY id DESC LIMIT ? OFFSET ?", (row, index) -> {
                    byte[] bytes = row.getString("request_json").getBytes(StandardCharsets.UTF_8);
                    try {
                        if (!sha256(bytes).equals(row.getString("wire_sha256"))) {
                            throw new IOException("Stored snapshot bytes changed");
                        }
                        JsonNode request = protocol.parse(bytes);
                        var values = JsonNodeFactory.instance.objectNode();
                        for (String feature : MANUAL_FEATURES) {
                            JsonNode value = request.path("features").path(feature).path("value");
                            values.set(feature, value.isNumber() ? value : JsonNodeFactory.instance.nullNode());
                        }
                        return new DoctorIntakeView(row.getLong("id"), request.path("indexDate").asText(),
                                row.getString("host_sync_status"), values);
                    } catch (IOException invalid) {
                        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "已保存的测试快照无法读取");
                    }
                }, subjectId, pageSize, (long) (page - 1) * pageSize);
        return new PageResult<>(items, page, pageSize, total);
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
        if (storedModel.equals(modelIdentity.identity(request.path("modelKey").asText(), protocol))) {
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
                               s.data_version AS subject_data_version,
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
                        row.getString("subject_data_version"), row.getString("quality_status"),
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
                        || !visit.qualityDataVersion().equals(visit.subjectDataVersion())) {
                    items.add(new BatchItem(subjectId, null, null, null, null,
                            "最新质控的数据版本早于当前档案，请重新质控"));
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
                        visit.subjectDataVersion(), null, principal);
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

    private static final List<String> MODEL_CSV_FEATURES = List.of(
            "age_years", "education_years", "cdr_global", "adas__total13", "faq__faqtotal");
    private static final List<String> MODEL_CSV_HEADER = List.of("subjectCode", "visitDate",
            "age_years", "education_years", "cdr_global", "adas__total13", "faq__faqtotal");
    record ModelCsvRow(int row, String subjectCode, String status, Long snapshotId, String hostSyncStatus, String message) {}

    List<ModelCsvRow> modelCsv(byte[] bytes, boolean preview, AppUserPrincipal actor) {
        requireResearchRole(actor);
        List<CsvRecord> records;
        try {
            records = CsvParser.parse(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "CSV 必须使用 UTF-8 编码");
        }
        if (records.size() < 2 || records.size() > 51 || !MODEL_CSV_HEADER.equals(records.getFirst().fields())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请使用七变量模型 CSV 模板，每次上传 1 至 50 条测试档案");
        }
        Set<String> seen = new LinkedHashSet<>();
        List<ModelCsvRow> result = new ArrayList<>();
        for (CsvRecord row : records.subList(1, records.size())) {
            String code = row.field(0);
            try {
                if (row.fields().size() != MODEL_CSV_HEADER.size() || !code.matches("DEMO-[A-Za-z0-9-]{1,45}")) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "需要完整字段和 DEMO 档案编号");
                }
                if (!seen.add(code)) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "每个档案只能出现一次");
                Long subjectId = jdbc.query("""
                        SELECT s.id FROM subject s WHERE s.subject_code=? AND NOT EXISTS (
                            SELECT 1 FROM research_table_snapshot r WHERE r.subject_id=s.id AND r.data_scope='CONTROLLED_RESEARCH')
                        """,
                        (rs, index) -> rs.getLong(1), code).stream().findFirst().orElse(null);
                if (subjectId == null || !access.canReadRawScores(actor, subjectId)) {
                    throw new BusinessException(ErrorCode.ACCESS_DENIED, "档案不存在或没有资料访问权限");
                }
                var registration = transaction.execute(status -> {
                    jdbc.queryForObject("SELECT id FROM subject WHERE id=? FOR UPDATE", Long.class, subjectId);
                    var request = modelCsvRequest(row, subjectId, actor);
                    if (preview) return null;
                    return jdbc.query("""
                            SELECT id,host_sync_status FROM research_table_snapshot WHERE subject_id=? AND authorized_user_id=?
                                AND request_sha256=? AND data_scope='CONSTRUCTED_VALIDATION'
                            ORDER BY id LIMIT 1
                            """, (rs, index) -> new Registration(rs.getLong(1), rs.getString(2)),
                            subjectId, actor.userId(), protocol.digest(request)).stream().findFirst()
                            .orElseGet(() -> new Registration(register(subjectId, request, actor), "PENDING"));
                });
                Long id = registration == null ? null : registration.snapshotId();
                String sync = registration == null ? null : "SYNCED".equals(registration.hostSyncStatus())
                        ? registration.hostSyncStatus() : syncToHost(registration.snapshotId(), actor);
                result.add(new ModelCsvRow(row.lineNumber(), code, preview ? "READY" : "SAVED", id, sync, null));
            } catch (BusinessException problem) {
                result.add(new ModelCsvRow(row.lineNumber(), code, "INVALID", null, null, problem.getMessage()));
            }
        }
        return result;
    }

    private ObjectNode modelCsvRequest(CsvRecord row, long subjectId, AppUserPrincipal actor) {
        Integer controlled = jdbc.queryForObject("""
                SELECT COUNT(*) FROM research_table_snapshot WHERE subject_id=? AND data_scope='CONTROLLED_RESEARCH'
                """, Integer.class, subjectId);
        if (controlled != null && controlled > 0) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "请从受控研究入口办理此档案");
        }
        var gate = archiveQuality.forSubject(subjectId);
        if (!gate.ready() || gate.visit() == null || !gate.visit().date().equals(row.field(1))) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "访视日期须与当前通过质控的最近访视一致");
        }
        String version = jdbc.queryForObject("SELECT data_version FROM subject WHERE id=?", String.class, subjectId);
        ObjectNode features = JsonNodeFactory.instance.objectNode();
        features.put("mmse", gate.visit().mmse().doubleValue());
        features.put("cdr_sb", gate.visit().cdrSb().doubleValue());
        for (int index = 0; index < MODEL_CSV_FEATURES.size(); index++) {
            var text = row.field(index + 2);
            if (!text.matches("[0-9]{1,4}(\\.[0-9]{1,6})?")) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, MODEL_CSV_FEATURES.get(index) + " 必须填写非负数值");
            }
            features.put(MODEL_CSV_FEATURES.get(index), new BigDecimal(text).doubleValue());
        }
        var request = manualRequest(row.field(0), features, gate.visit().date(), version, "run-csv-preview");
        request.put("runId", "run-csv-" + protocol.digest(request.deepCopy().put("owner", actor.userId())).substring(0, 32));
        var normalized = normalizedRequest(request, row.field(0));
        try {
            protocol.validateRequest(normalized, modelIdentity.identity());
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "七变量数值不符合模型输入要求，请核对单位和范围");
        }
        return normalized;
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
    TaskSummary summary(String dataScope, AppUserPrincipal principal) {
        requireResearchRole(principal);
        Instant start = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
        List<Object> args = new ArrayList<>(List.of(principal.userId(), Timestamp.from(start)));
        if (dataScope != null) args.add(dataScope);
        String scope = dataScope == null ? "" : " AND s.data_scope=?";
        List<Task> tasks = jdbc.query(TASK_QUERY + " WHERE s.authorized_user_id=? AND t.created_at>=?"
                + scope + " ORDER BY t.created_at DESC,t.id DESC", (row, index) -> {
                    try {
                        return mapTask(row, principal);
                    } catch (BusinessException denied) {
                        if (denied.errorCode() == ErrorCode.ACCESS_DENIED) {
                            return null;
                        }
                        throw denied;
                    }
                }, args.toArray())
                .stream().filter(java.util.Objects::nonNull).toList();
        long completed = tasks.stream().filter(task -> "COMPLETED".equals(task.status())).count();
        long failed = tasks.stream().filter(task -> "FAILED".equals(task.status())
                || OUTCOME_UNKNOWN.equals(task.status())).count();
        long average = Math.round(tasks.stream().filter(task -> "COMPLETED".equals(task.status()))
                .mapToLong(task -> Math.max(0, Duration.between(task.createdAt(), task.updatedAt()).toSeconds()))
                .average().orElse(0));
        return new TaskSummary(tasks.size(), completed, failed, average,
                tasks.stream().limit(5).toList());
    }

    Metrics metrics(String dataScope, AppUserPrincipal principal) {
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
        if (dataScope != null) {
            scope += " AND s.data_scope=?";
            args.add(dataScope);
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
        return syncToHost(snapshotId, principal, false);
    }

    private String syncToHost(long snapshotId, AppUserPrincipal principal, boolean doctorIntake) {
        SyncSnapshot snapshot = jdbc.query("""
                SELECT subject_id,authorized_user_id,request_json,model_json,context_json,data_scope,
                       wire_sha256,request_sha256,input_sha256,context_sha256
                FROM research_table_snapshot WHERE id=?
                """, (result, index) -> new SyncSnapshot(result.getLong(1), result.getLong(2),
                result.getString(3), result.getString(4), result.getString(5), result.getString(6),
                result.getString(7), result.getString(8), result.getString(9), result.getString(10)),
                snapshotId).stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
        long subjectId = snapshot.subjectId();
        if ((!doctorIntake && snapshot.ownerId() != principal.userId() && !canReceiveDoctorIntake(snapshotId, principal))
                || !access.canReadRawScores(principal, subjectId)
                || snapshot.context() == null || !"CONSTRUCTED_VALIDATION".equals(snapshot.dataScope())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        try {
            byte[] requestBytes = snapshot.request().getBytes(StandardCharsets.UTF_8);
            JsonNode request = protocol.parse(requestBytes);
            JsonNode model = protocol.parse(snapshot.model().getBytes(StandardCharsets.UTF_8));
            JsonNode context = protocol.parse(snapshot.context().getBytes(StandardCharsets.UTF_8));
            if (!sha256(requestBytes).equals(snapshot.wireSha256())
                    || !protocol.digest(request).equals(snapshot.requestSha256())
                    || !protocol.digest(protocol.admissionCore(request, model)).equals(snapshot.inputSha256())
                    || !protocol.digest(context).equals(snapshot.contextSha256())) {
                throw new IllegalArgumentException("Frozen snapshot digest mismatch");
            }
            String runId = request.path("runId").asText();
            ObjectNode bundle = JsonNodeFactory.instance.objectNode();
            bundle.put("schemaVersion", "research-dynamic-snapshot-v1");
            bundle.put("runId", runId);
            bundle.set("request", request);
            bundle.set("model", model);
            bundle.set("context", context);
            bundle.put("requestSha256", snapshot.requestSha256());
            bundle.put("inputSha256", snapshot.inputSha256());
            bundle.put("contextSha256", snapshot.contextSha256());
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
            if (reply != null && reply.httpStatus() == 503) {
                updateSync(snapshotId, "PENDING");
                return "PENDING";
            }
            if (!matchesSyncReply(reply, snapshotId, runId)) {
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
        } catch (IOException | IllegalArgumentException invalid) {
            updateSync(snapshotId, "FAILED");
            audit.recordQuietly(principal.userId(), "RESEARCH_TABLE_SNAPSHOT_SYNC_FAILED",
                    "snapshotId=" + snapshotId + " error=frozen-snapshot");
            return "FAILED";
        }
    }

    private static boolean matchesSyncReply(ResearchTableClient.Reply reply, long snapshotId, String runId) {
        if (reply == null || reply.httpStatus() != 200) {
            return false;
        }
        JsonNode body = reply.body();
        JsonNode returnedId = body.path("snapshotId");
        return "SYNCED".equals(body.path("status").asText())
                && runId.equals(body.path("runId").asText())
                && returnedId.isIntegralNumber() && returnedId.canConvertToLong()
                && returnedId.longValue() == snapshotId;
    }

    private boolean canReceiveDoctorIntake(long snapshotId, AppUserPrincipal principal) {
        if (!principal.roleCodes().contains("RESEARCHER")) return false;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM research_table_snapshot s
                WHERE s.id=? AND s.doctor_handoff=TRUE
                AND EXISTS (SELECT 1 FROM subject_permission p WHERE p.subject_id=s.subject_id
                            AND p.user_id=? AND p.permission_type IN ('VIEW','MANAGE'))
                AND NOT EXISTS (SELECT 1 FROM research_table_task t WHERE t.snapshot_id=s.id)
                """, Integer.class, snapshotId, principal.userId());
        return count != null && count != 0;
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
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "features 必须为模型观测对象");
        }
        ObjectNode features = (ObjectNode) node;
        for (var names = features.fieldNames(); names.hasNext();) {
            String feature = names.next();
            JsonNode raw = features.get(feature);
            if (raw == null || !raw.isObject()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "缺少特征 " + feature);
            }
            ObjectNode observation = (ObjectNode) raw;
            boolean missing = !observation.has("value") || observation.get("value").isNull();
            boolean demographic = feature.equals("age_years") || feature.equals("education_years");
            boolean mri = feature.startsWith("mri_");
            boolean apoe = feature.equals("apoe4_count");
            observation.put("unit", demographic ? "years" : apoe ? "count" : mri ? "mm3" : "score");
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
                observation.put("completion", demographic || apoe || mri ? "NOT_APPLICABLE" : "COMPLETE");
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
                SELECT s.id,s.request_json,s.model_json,s.host_sync_status,s.data_scope,t.id AS task_id
                FROM research_table_snapshot s LEFT JOIN research_table_task t ON t.snapshot_id=s.id
                WHERE s.subject_id=? AND (s.authorized_user_id=? OR (
                  ? AND s.doctor_handoff=TRUE AND t.id IS NULL AND EXISTS (
                    SELECT 1 FROM subject_permission p WHERE p.subject_id=s.subject_id
                    AND p.user_id=? AND p.permission_type IN ('VIEW','MANAGE'))))
                  AND (s.data_scope='CONSTRUCTED_VALIDATION' OR (? AND s.data_scope='CONTROLLED_RESEARCH'))
                ORDER BY s.id
                """, (row, index) -> {
                    try {
                        JsonNode request = protocol.parse(row.getString("request_json").getBytes(StandardCharsets.UTF_8));
                        String modelKey = request.path("modelKey").asText();
                        boolean compatible;
                        try {
                            JsonNode storedModel = protocol.parse(
                                    row.getString("model_json").getBytes(StandardCharsets.UTF_8));
                            compatible = "CONTROLLED_RESEARCH".equals(row.getString("data_scope"))
                                    ? "SYNCED".equals(row.getString("host_sync_status"))
                                    : storedModel.equals(modelIdentity.identity(modelKey, protocol));
                        } catch (IOException | IllegalArgumentException invalid) {
                            compatible = false;
                        }
                        return new SnapshotOption(row.getLong("id"), row.getObject("task_id", Long.class),
                                row.getString("host_sync_status"), compatible,
                                compatible ? null : "当前宿主未配置该模型版本或软件锁，请重新登记",
                                modelKey, row.getString("data_scope"));
                    } catch (IOException | IllegalArgumentException invalid) {
                        throw new BusinessException(ErrorCode.RESEARCH_UNAVAILABLE, "冻结研究快照配置无效");
                    }
                }, subjectId, principal.userId(), principal.roleCodes().contains("RESEARCHER"), principal.userId(),
                controlledAdmissionEnabled).stream().filter(java.util.Objects::nonNull).toList();
    }

    private Submission reserve(long snapshotId, Long subjectId, String key, String digest, AppUserPrincipal principal,
                               boolean doctorFixture, ControlledDispatch dispatch) {
        // Serialize this actor's reservations across snapshots and release the lock before HTTP.
        jdbc.queryForObject("SELECT id FROM sys_user WHERE id=? FOR UPDATE", Long.class, principal.userId());
        if (doctorFixture || dispatch != null) {
            jdbc.queryForObject("SELECT id FROM subject WHERE id=? FOR UPDATE", Long.class, subjectId);
        }
        Snapshot snapshot = jdbc.query("SELECT * FROM research_table_snapshot WHERE id=? FOR UPDATE",
                (row, index) -> {
                    if (doctorFixture && (!row.getBoolean("doctor_handoff")
                            || !"CONSTRUCTED_VALIDATION".equals(row.getString("data_scope")))) {
                        throw new BusinessException(ErrorCode.ACCESS_DENIED);
                    }
                    Snapshot frozen = readSnapshot(row, principal);
                    if (subjectId != null && subjectId != row.getLong("subject_id")) {
                        throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "该快照不属于当前选中的受试者");
                    }
                    if (doctorFixture && (!hasExplicitManageGrant(principal.userId(), subjectId)
                            || !isCurrentDoctorFixture(subjectId, frozen))) {
                        throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "请用当前访视重新质控并保存匹配资料");
                    }
                    return frozen;
                }, snapshotId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
        Optional<Long> existing = replay(key, digest, principal);
        if (existing.isPresent()) {
            return new Submission(existing.get(), null, null);
        }
        if (dispatch != null) reserveControlledDispatch(dispatch, snapshot, principal);
        String syncStatus = jdbc.queryForObject(
                "SELECT host_sync_status FROM research_table_snapshot WHERE id=?", String.class, snapshotId);
        if (!"SYNCED".equals(syncStatus)) {
            throw new BusinessException(ErrorCode.RESEARCH_UNAVAILABLE,
                    "该快照尚未同步到研究宿主，请等待登记完成或联系管理员");
        }
        var tasks = jdbc.queryForList("SELECT id FROM research_table_task WHERE snapshot_id=?", Long.class, snapshotId);
        if (!tasks.isEmpty()) {
            if (doctorFixture && isCareFixture(snapshot.request())) return new Submission(tasks.get(0), null, null);
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "该冻结快照已有关联任务");
        }
        jdbc.update("UPDATE research_table_snapshot SET authorized_user_id=? WHERE id=? AND doctor_handoff=TRUE",
                principal.userId(), snapshotId);
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

    private boolean isCurrentDoctorFixture(long subjectId, Snapshot snapshot) {
        var wire = fixtureIdentity(subjectId, true);
        return wire != null && wire.subjectCode().equals(snapshot.request().path("subjectCode").textValue())
                && wire.dataVersion().equals(snapshot.request().path("dataVersion").textValue())
                && archiveQuality.forSubject(subjectId).matchesVisit(snapshot.request())
                && jdbc.queryForObject("SELECT COUNT(*) FROM research_table_snapshot"
                        + " WHERE subject_id=? AND data_scope='CONTROLLED_RESEARCH'", Long.class, subjectId) == 0;
    }

    private boolean isCareFixture(JsonNode request) {
        return syntheticCare.enabled() && request.path("runId").asText().startsWith("run-care-");
    }

    private void reserveControlledDispatch(ControlledDispatch dispatch, Snapshot snapshot, AppUserPrincipal principal) {
        var binding = snapshot.binding();
        requireControlledDispatch(dispatch.qualityId(), snapshot, principal);
        int changed = jdbc.update("""
                UPDATE controlled_assessment_request SET snapshot_id=?
                WHERE id=? AND subject_id=? AND quality_check_id=? AND snapshot_id IS NULL
                """, binding.snapshotId(), dispatch.requestId(), binding.subjectId(), dispatch.qualityId());
        if (changed != 1) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "评估申请已关联任务，请刷新状态");
        }
    }

    private void requireControlledDispatch(long qualityId, Snapshot snapshot, AppUserPrincipal principal) {
        var binding = snapshot.binding();
        var gate = archiveQuality.forSubject(binding.subjectId());
        if (!"CONTROLLED_RESEARCH".equals(snapshot.dataScope()) || !principal.roleCodes().contains("RESEARCHER")
                || !hasExplicitReviewGrant(principal.userId(), binding.subjectId())
                || !"RETROSPECTIVE_SNAPSHOT".equals(snapshot.request().path("availabilityPolicy").asText())
                || !java.util.Objects.equals(qualityId, gate.id()) || !gate.matchesVisit(snapshot.request())
                || !binding.requestSha256().equals(protocol.digest(snapshot.request()))
                || !binding.inputSha256().equals(protocol.digest(protocol.admissionCore(snapshot.request(), snapshot.model())))
                || !controlledCompletion.permits(client.configuredAssessmentEndpoint(), binding)) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "当前质控、档案授权或执行许可已变化");
        }
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
        boolean careHandoff = syntheticCare.enabled() && row.getBoolean("doctor_handoff")
                && principal.roleCodes().contains("DOCTOR") && hasExplicitManageGrant(principal.userId(), subject)
                && jdbc.queryForObject("SELECT COUNT(*) FROM subject WHERE id=? AND data_version LIKE 'care-%'", Long.class, subject) == 1;
        if (!access.canReadRawScores(principal, subject)
                || (owner != principal.userId() && !careHandoff && !canReceiveDoctorIntake(row.getLong("id"), principal))) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        String dataScope = row.getString("data_scope");
        if (!"CONSTRUCTED_VALIDATION".equals(dataScope)
                && !(controlledAdmissionEnabled && "CONTROLLED_RESEARCH".equals(dataScope))) {
            throw new BusinessException(ErrorCode.RESEARCH_UNAVAILABLE, "当前宿主未启用此快照数据范围");
        }
        try {
            byte[] bytes = row.getString("request_json").getBytes(StandardCharsets.UTF_8);
            if (!sha256(bytes).equals(digestColumn(row, "wire_sha256"))) {
                throw new IllegalArgumentException("Frozen snapshot bytes changed");
            }
            JsonNode request = protocol.parse(bytes);
            JsonNode model = protocol.parse(row.getString("model_json").getBytes(StandardCharsets.UTF_8));
            protocol.validateRequest(request, model);
            if (!testFixturesEnabled && !TRAINED_MODEL.equals(request.path("modelKey").asText())
                    && !model.equals(modelIdentity.identity(request.path("modelKey").asText(), protocol))) {
                throw new IllegalArgumentException("Expected the registered trained model");
            }
            var binding = new ControlledResearchCompletion.Binding(row.getLong("id"), subject,
                    request.path("runId").textValue(),
                    digestColumn(row, "request_sha256"), digestColumn(row, "input_sha256"),
                    digestColumn(row, "context_sha256"));
            return new Snapshot(bytes, request, model, dataScope, binding);
        } catch (IOException | RuntimeException invalid) {
            throw new BusinessException(ErrorCode.RESEARCH_UNAVAILABLE, "冻结研究快照配置无效");
        }
    }

    private void execute(long taskId, Snapshot snapshot, String executionId) {
        execute(taskId, snapshot, executionId, null);
    }

    private void execute(long taskId, Snapshot snapshot, String executionId, java.net.URI requiredEndpoint) {
        String status = OUTCOME_UNKNOWN;
        String code = "RESEARCH_OUTCOME_UNKNOWN";
        String result = null;
        try {
            ResearchTableClient.Reply reply = requiredEndpoint == null ? client.assess(snapshot.requestBytes())
                    : client.assess(snapshot.requestBytes(), requiredEndpoint);
            if (reply != null && reply.httpStatus() == 200) {
                var binding = snapshot.binding();
                String validated = protocol.validateResponse(reply.body(), snapshot.request(), snapshot.model(),
                        snapshot.dataScope(), binding.requestSha256(), binding.inputSha256(), binding.contextSha256());
                if ("CONTROLLED_RESEARCH".equals(snapshot.dataScope()) && "COMPLETED".equals(validated)) {
                    validateControlledCompletion(snapshot, reply);
                }
                result = reply.body().toString();
                status = validated;
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

    private void validateControlledCompletion(Snapshot snapshot, ResearchTableClient.Reply reply) {
        var binding = snapshot.binding();
        JsonNode request = snapshot.request();
        if (!controlledCompletion.permits(reply.endpoint(), binding)
                || !"RETROSPECTIVE_SNAPSHOT".equals(request.path("availabilityPolicy").asText())
                || !binding.requestSha256().equals(protocol.digest(request))
                || !binding.inputSha256().equals(protocol.digest(protocol.admissionCore(request, snapshot.model())))) {
            throw new IllegalArgumentException("Controlled completion authorization does not match the snapshot");
        }
        String subjectCode = jdbc.queryForObject("SELECT subject_code FROM subject WHERE id=?",
                String.class, binding.subjectId());
        if (!request.path("subjectCode").textValue().equals(subjectCode)) {
            throw new IllegalArgumentException("Controlled completion subject does not match the snapshot");
        }
    }

    private record ReportScope(String filter, List<Object> params) {}

    /**
     * The report list, report detail and export share one authorization rule. A doctor needs an
     * explicit VIEW/MANAGE grant for the subject; a researcher is limited to their own snapshots
     * unless they have full raw-score visibility, in which case every researcher snapshot is
     * readable. This intentionally does not fall back to the broader task detail rule.
     */
    private ReportScope reportScope(AppUserPrincipal principal) {
        requireReportRole(principal);
        List<Object> params = new ArrayList<>();
        String filter;
        if (principal.roleCodes().contains("DOCTOR")) {
            filter = " AND EXISTS (SELECT 1 FROM subject_permission p WHERE p.subject_id=s.subject_id"
                    + " AND p.user_id=? AND p.permission_type IN ('VIEW','MANAGE'))";
            params.add(principal.userId());
        } else {
            filter = " AND s.authorized_user_id=?";
            params.add(principal.userId());
            if (!access.hasFullRawScoreVisibility(principal)) {
                filter += " AND EXISTS (SELECT 1 FROM subject_permission p WHERE p.subject_id=s.subject_id"
                        + " AND p.user_id=? AND p.permission_type IN ('VIEW','MANAGE'))";
                params.add(principal.userId());
            }
        }
        return new ReportScope(filter, List.copyOf(params));
    }

    Task report(long id, AppUserPrincipal principal) {
        ReportScope scope = reportScope(principal);
        List<Object> params = new ArrayList<>();
        params.add(id);
        params.addAll(scope.params());
        return jdbc.query(TASK_QUERY + " WHERE t.id=?" + scope.filter(),
                        (row, index) -> mapTask(row, principal), params.toArray())
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
    }

    Task find(long id, AppUserPrincipal principal) {
        requireArchiveRole(principal);
        return jdbc.query(TASK_QUERY + " WHERE t.id=?", (row, index) -> mapTask(row, principal),
                id).stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
    }

    ReviewQueue reviewQueue(String status, int page, int pageSize, String dataScope, AppUserPrincipal principal) {
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
                """ + reviewFilter + permissionFilter + (dataScope == null ? "" : " AND s.data_scope=?");
        List<Object> params = new ArrayList<>();
        if (!all) params.add(status.toUpperCase(Locale.ROOT));
        params.add(principal.userId());
        if (dataScope != null) params.add(dataScope);

        Long total = jdbc.queryForObject("SELECT COUNT(*) " + from, Long.class, params.toArray());
        String select = """
                SELECT t.id,t.snapshot_id,t.run_id,t.status,t.error_code,t.result_json,t.created_at,t.updated_at,
                       t.review_status,t.reviewed_by,t.reviewed_at,t.review_comment,
                       s.subject_id,s.authorized_user_id,s.data_scope,sub.subject_code,sub.alias AS subject_alias
                """ + from + " ORDER BY t.created_at DESC,t.id DESC LIMIT ? OFFSET ?";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(safeSize);
        pageParams.add((long) (safePage - 1) * safeSize);
        List<Task> items = jdbc.query(select, (row, index) -> mapTask(row, principal), pageParams.toArray());
        return new ReviewQueue(items, total == null ? 0 : total, safePage, safeSize);
    }

    ReviewQueue reports(String status, int page, int pageSize, String dataScope, Long subjectId, AppUserPrincipal principal) {
        ReportScope scope = reportScope(principal);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        boolean all = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status);
        String statusFilter = all ? " AND t.status<>'RUNNING'" : " AND t.status=?";
        List<Object> params = new ArrayList<>();
        if (!all) params.add(status.toUpperCase(Locale.ROOT));
        params.addAll(scope.params());
        if (dataScope != null) params.add(dataScope);
        if (subjectId != null) params.add(subjectId);
        String from = """
                FROM research_table_task t
                JOIN research_table_snapshot s ON s.id=t.snapshot_id
                JOIN subject sub ON sub.id=s.subject_id
                WHERE 1=1
                """ + statusFilter + scope.filter() + (dataScope == null ? "" : " AND s.data_scope=?")
                        + (subjectId == null ? "" : " AND s.subject_id=?");
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + from, Long.class, params.toArray());
        String select = """
                SELECT t.id,t.snapshot_id,t.run_id,t.status,t.error_code,t.result_json,t.created_at,t.updated_at,
                       t.review_status,t.reviewed_by,t.reviewed_at,t.review_comment,
                       s.subject_id,s.authorized_user_id,s.data_scope,sub.subject_code,sub.alias AS subject_alias
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

    PatientPublication publication(long id, AppUserPrincipal actor) {
        requirePublicationReview(id, actor);
        return lastPublication(id);
    }

    PatientPublication changePublication(long id, String message, boolean publish, AppUserPrincipal actor) {
        String text = message == null ? "" : message.strip();
        if (publish && (text.isEmpty() || text.length() > 1000)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "患者说明须填写 1 至 1000 个字符");
        }
        return transaction.execute(ignored -> {
            requireReviewRole(actor);
            jdbc.query("SELECT id FROM research_table_task WHERE id=? FOR UPDATE", (row, index) -> row.getLong(1), id);
            var task = requirePublicationReview(id, actor);
            if (!"COMPLETED".equals(task.status()) || !"CONFIRMED".equals(task.reviewStatus())) {
                throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "请先确认本次医生复核");
            }
            var previous = lastPublication(id);
            var next = new PatientPublication(publish, publish ? text : null);
            if (!previous.equals(next)) {
                jdbc.update("""
                        INSERT INTO patient_research_publication(task_id,actor_id,action,message,recorded_at)
                        VALUES(?,?,?,?,CURRENT_TIMESTAMP)
                        """, id, actor.userId(), publish ? "PUBLISHED" : "WITHDRAWN", next.message());
                audit.recordQuietly(actor.userId(), publish ? "PATIENT_SUMMARY_PUBLISHED" : "PATIENT_SUMMARY_WITHDRAWN",
                        "taskId=" + id);
            }
            return next;
        });
    }

    private Task requirePublicationReview(long id, AppUserPrincipal actor) {
        requireReviewRole(actor);
        Task task = find(id, actor);
        if (!hasExplicitManageGrant(actor.userId(), task.subjectId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "仅有档案管理权限的医生可以发布患者说明");
        }
        return task;
    }

    private PatientPublication lastPublication(long id) {
        return jdbc.query("""
                SELECT action,message FROM patient_research_publication WHERE task_id=? ORDER BY id DESC LIMIT 1
                """, (row, index) -> new PatientPublication("PUBLISHED".equals(row.getString(1)), row.getString(2)), id)
                .stream().findFirst().orElseGet(() -> new PatientPublication(false, null));
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
        boolean controlled = "CONTROLLED_RESEARCH".equals(snapshot.dataScope());
        java.net.URI allowedEndpoint = controlled ? controlledCompletion.endpoint() : null;
        // Reset and claim in one transaction so scheduled lease recovery can never observe a
        // transient RUNNING row with no owner between the two statements.
        transaction.executeWithoutResult(status -> {
            if (controlled) {
                long subjectId = snapshot.binding().subjectId();
                jdbc.queryForObject("SELECT id FROM subject WHERE id=? FOR UPDATE", Long.class, subjectId);
                Long qualityId = jdbc.query("""
                        SELECT a.quality_check_id FROM controlled_assessment_request a
                        WHERE a.subject_id=? AND a.snapshot_id=? AND a.id=(
                            SELECT MAX(id) FROM controlled_assessment_request WHERE subject_id=?)
                        """, (row, index) -> row.getLong(1), subjectId, snapshot.binding().snapshotId(), subjectId)
                        .stream().findFirst().orElse(null);
                if (qualityId == null || allowedEndpoint == null) {
                    throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "原评估申请已失效，请重新核验资料");
                }
                requireControlledDispatch(qualityId, snapshot, principal);
            }
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
        execute(taskId, snapshot, executionId, allowedEndpoint);
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
                    row.getString("status"), row.getString("error_code"), row.getString("data_scope"),
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

    private boolean hasExplicitManageGrant(long userId, long subjectId) {
        return !jdbc.queryForList("""
                SELECT user_id FROM subject_permission WHERE subject_id=? AND user_id=? AND permission_type='MANAGE'
                """, Long.class, subjectId, userId).isEmpty();
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
