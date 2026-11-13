package com.adcausalrisk.backend.assessment;

import com.adcausalrisk.backend.agent.AgentRequest;
import com.adcausalrisk.backend.agent.AgentResult;
import com.adcausalrisk.backend.agent.AgentResultValidator;
import com.adcausalrisk.backend.agent.RestAgentClient;
import com.adcausalrisk.backend.assessment.entity.AssessmentTask;
import com.adcausalrisk.backend.assessment.entity.RiskAssessment;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.common.PageResult;
import com.adcausalrisk.backend.importjob.IdempotencyRecordRepository;
import com.adcausalrisk.backend.importjob.entity.IdempotencyRecord;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectAccessPolicy;
import com.adcausalrisk.backend.subject.SubjectRepository;
import com.adcausalrisk.backend.subject.entity.Subject;
import com.adcausalrisk.backend.visit.VisitRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates one assessment: persist a PENDING task, call the Python agent, persist the result.
 * The two writes are separate short transactions and the HTTP call sits between them, so no
 * database connection is held while the synchronous workflow runs.
 */
@Service
public class AssessmentService {

    private static final Logger log = LoggerFactory.getLogger(AssessmentService.class);

    /** Idempotency scope of "create an assessment task", distinct from the Python runId replay. */
    static final String OPERATION = "ASSESSMENT_TASK_CREATE";

    private static final int MAX_KEY_LENGTH = 100;

    /** Java-side stable code for a task that failed while its result was being stored. */
    static final String CODE_PERSIST_FAILED = "TASK_PERSIST_FAILED";

    static final String MESSAGE_PERSIST_FAILED = "评估结果保存失败，请重试或联系管理员";

    private final AssessmentTaskRepository taskRepository;
    private final RiskAssessmentRepository assessmentRepository;
    private final SubjectRepository subjectRepository;
    private final VisitRepository visitRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final SubjectAccessPolicy accessPolicy;
    private final RestAgentClient agentClient;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final java.time.Clock clock;
    private final com.adcausalrisk.backend.audit.AuditService audit;
    private final com.adcausalrisk.backend.qualitycheck.QualityCheckService quality;
    private final TransactionTemplate writeTransaction;
    private final java.util.concurrent.ThreadPoolExecutor executor =
            new java.util.concurrent.ThreadPoolExecutor(
                    2,
                    2,
                    0L,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(100));

    @jakarta.annotation.PreDestroy
    public void stopExecutor() {
        executor.shutdown();
    }

    public AssessmentService(
            AssessmentTaskRepository taskRepository,
            RiskAssessmentRepository assessmentRepository,
            SubjectRepository subjectRepository,
            VisitRepository visitRepository,
            IdempotencyRecordRepository idempotencyRepository,
            SubjectAccessPolicy accessPolicy,
            RestAgentClient agentClient,
            ObjectMapper objectMapper,
            JdbcTemplate jdbc,
            java.time.Clock clock,
            PlatformTransactionManager transactionManager,
            com.adcausalrisk.backend.qualitycheck.QualityCheckService quality,
            com.adcausalrisk.backend.audit.AuditService audit) {
        this.taskRepository = taskRepository;
        this.assessmentRepository = assessmentRepository;
        this.subjectRepository = subjectRepository;
        this.visitRepository = visitRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.accessPolicy = accessPolicy;
        this.agentClient = agentClient;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.clock = clock;
        this.quality = quality;
        this.audit = audit;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW, not the default REQUIRED: if this service is ever called from a
        // transactional caller, joining that transaction would hold a pooled connection for the
        // whole Python call and would defer the PENDING row until after the HTTP request.
        this.writeTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Task, optional result row and the parsed AgentResult, as needed by the HTTP layer. */
    public record Outcome(
            AssessmentTask task,
            RiskAssessment assessment,
            AgentResult result,
            String errorCode,
            String errorMessage) {}

    /**
     * {@code POST /api/assessment-tasks}.
     *
     * <p>The contract declares only 200/400/401/403 for this operation, so an agent failure is not
     * an HTTP error: the task becomes {@code FAILED} and the response still carries it.
     */
    public Outcome create(
            Long subjectId,
            String modelVersion,
            int predictionWindowMonths,
            String idempotencyKey,
            AppUserPrincipal principal) {
        return create(
                subjectId, modelVersion, predictionWindowMonths, idempotencyKey, principal, false);
    }

    public Outcome submit(
            Long subjectId,
            String modelVersion,
            int predictionWindowMonths,
            String idempotencyKey,
            AppUserPrincipal principal) {
        return create(
                subjectId, modelVersion, predictionWindowMonths, idempotencyKey, principal, true);
    }

    @org.springframework.beans.factory.annotation.Value("${assessment.test-rule-enabled:false}")
    private boolean testRuleEnabled;

    private Outcome create(
            Long subjectId,
            String modelVersion,
            int predictionWindowMonths,
            String idempotencyKey,
            AppUserPrincipal principal,
            boolean background) {
        // ADMIN / RESEARCHER 可为任意档案发起；DOCTOR 只能为已授权（VIEW/MANAGE）的档案发起。
        // 建档（POST /api/subjects）仍然只允许 ADMIN / RESEARCHER，两个权限刻意分开。
        if (!accessPolicy.canRequestAssessment(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权发起评估");
        }
        // The header is opaque to HTTP, but " abc" and "abc" must not become two idempotency
        // scopes: a client that pads the value would otherwise start a second 120s run.
        if (idempotencyKey != null) {
            idempotencyKey = idempotencyKey.trim();
        }
        requireKey(idempotencyKey);

        Subject subject =
                subjectRepository
                        .findById(subjectId)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.VALIDATION_ERROR, "档案不存在"));
        if (!accessPolicy.canView(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权访问该档案");
        }
        if (!AgentRequest.MODEL_VERSION.equals(modelVersion)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "modelVersion 仅支持 " + AgentRequest.MODEL_VERSION);
        }
        if (predictionWindowMonths < AgentRequest.MIN_WINDOW_MONTHS
                || predictionWindowMonths > AgentRequest.MAX_WINDOW_MONTHS) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "predictionWindowMonths 必须在 "
                            + AgentRequest.MIN_WINDOW_MONTHS
                            + "-"
                            + AgentRequest.MAX_WINDOW_MONTHS
                            + " 之间");
        }

        String digest =
                digestOf(subjectId, modelVersion, predictionWindowMonths, subject.getDataVersion());

        Optional<IdempotencyRecord> replay =
                idempotencyRepository.findByUserIdAndOperationAndIdempotencyKey(
                        principal.userId(), OPERATION, idempotencyKey);
        if (replay.isPresent()) {
            return replay(replay.get(), digest);
        }
        if (!testRuleEnabled) {
            throw new BusinessException(ErrorCode.RESEARCH_UNAVAILABLE, "新评估请使用已登记的研究模型快照");
        }

        var check = quality.check(subjectId, principal);
        AgentRequest request = buildRequest(subject, predictionWindowMonths);
        Seed seed = seed(principal, idempotencyKey, digest, subject, request, check.id());
        if (seed.replayed()) {
            // Another request with the same key already created this task. Replaying means
            // returning that task's current state - calling the agent again would run the
            // workflow twice and collide with the uq_risk_assessment_task constraint.
            return outcomeOf(seed.task(), null, null);
        }
        AssessmentTask seeded = seed.task();
        if (background) {
            try {
                executor.execute(
                        () -> {
                            try {
                                execute(seeded, request, subject, principal.userId());
                            } catch (RuntimeException failure) {
                                log.warn("异步任务 {} 已记录失败", seeded.getId());
                            }
                        });
            } catch (java.util.concurrent.RejectedExecutionException full) {
                return outcomeOf(
                        markFailedOrThrow(
                                seeded.getId(), full, "TASK_QUEUE_FULL", "任务队列已满，请稍后创建新任务"),
                        null,
                        null);
            }
            return outcomeOf(seeded, null, null);
        }
        return execute(seeded, request, subject, principal.userId());
    }

    private Outcome execute(
            AssessmentTask seeded, AgentRequest request, Subject subject, long actor) {
        boolean started = false;
        try {
            started =
                    jdbc.update(
                                    "UPDATE assessment_task SET"
                                            + " status='RUNNING',updated_at=CURRENT_TIMESTAMP WHERE"
                                            + " id=? AND status='PENDING'",
                                    seeded.getId())
                            == 1;
            if (!started)
                return outcomeOf(taskRepository.findById(seeded.getId()).orElseThrow(), null, null);
            Integer enabled =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM model_configuration WHERE model_version=? AND"
                                    + " enabled=TRUE",
                            Integer.class,
                            request.modelVersion());
            if (enabled == null || enabled == 0)
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AGENT_UNAVAILABLE: 模型已停用");
            AgentResult result = agentClient.createRun(request);
            return persist(seeded.getId(), subject.getId(), result);
        } catch (TaskClosedAsFailed closed) {
            // persist() already closed the row with a precise code (TASK_PERSIST_FAILED,
            // AGENT_CONTRACT_MISMATCH, ...). Re-marking it here would overwrite that code with the
            // generic AGENT_FAILED and the re-fetch would misreport the cause.
            AssessmentTask failed = closed.task();
            Throwable cause = closed.getCause();
            if (cause instanceof BusinessException) {
                return new Outcome(
                        failed, null, null, failed.getErrorCode(), failed.getErrorMessage());
            }
            throw cause instanceof RuntimeException runtime ? runtime : closed;
        } catch (RuntimeException failure) {
            // Anything that escapes from here would leave the row PENDING forever, so the task is
            // closed out first and the original failure is what the caller sees.
            String errorCode = stableErrorCode(failure);
            AssessmentTask failed =
                    markFailedOrThrow(
                            seeded.getId(), failure, errorCode, userFacingMessage(errorCode));
            if (failure instanceof BusinessException) {
                // The contract declares only 200/400/401/403 for this operation, so an agent
                // failure is a FAILED task in a 200 response, not an HTTP error.
                //
                // Only the persisted stable code and the fixed user-facing sentence are returned:
                // the original exception text can contain upstream URLs, response bodies or stack
                // details, and must never reach the browser - not even on the first response.
                return new Outcome(
                        failed, null, null, failed.getErrorCode(), failed.getErrorMessage());
            }
            throw failure;
        } finally {
            if (started)
                try {
                    taskRepository
                            .findById(seeded.getId())
                            .ifPresent(
                                    task ->
                                            audit.recordQuietly(
                                                    actor,
                                                    "ASSESSMENT_TASK_FINISHED",
                                                    "taskId="
                                                            + task.getId()
                                                            + " subjectId="
                                                            + subject.getId()
                                                            + " status="
                                                            + task.getStatus()
                                                            + " errorCode="
                                                            + task.getErrorCode()));
                } catch (RuntimeException auditFailure) {
                    log.warn("任务 {} 完成审计不可用", seeded.getId());
                }
        }
    }

    /** {@code GET /api/assessment-tasks/{taskId}}. */
    public Outcome findTask(Long taskId, AppUserPrincipal principal) {
        AssessmentTask task =
                taskRepository
                        .findById(taskId)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.VALIDATION_ERROR, "评估任务不存在"));
        // The task view carries runId and stage summaries, i.e. raw-pipeline internals. Visibility
        // must match GET /api/assessments/{id}: canView alone would let a bound PATIENT read them
        // by guessing the id, even though the list endpoints already refuse.
        if (!accessPolicy.canReadRawScores(principal, task.getSubjectId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权查看该评估任务");
        }
        RiskAssessment assessment = assessmentRepository.findByTaskId(taskId).orElse(null);
        // A FAILED task keeps its persisted code/reason, so a re-fetch (or an idempotent replay)
        // explains the failure instead of returning a bare status.
        return new Outcome(
                task,
                assessment,
                assessment == null ? null : parse(assessment),
                task.getErrorCode(),
                task.getErrorMessage());
    }

    /**
     * {@code GET /api/assessments/{assessmentId}}.
     *
     * <p>Only the full {@code Assessment} shape is served here, which includes the raw result;
     * callers without raw-score access get 403. The patient summary is a separate contract type and
     * belongs with the patient views.
     */
    public Outcome findAssessment(Long assessmentId, AppUserPrincipal principal) {
        RiskAssessment assessment =
                assessmentRepository
                        .findById(assessmentId)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.VALIDATION_ERROR, "评估结果不存在"));
        if (!accessPolicy.canReadRawScores(principal, assessment.getSubjectId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权查看该评估结果");
        }
        AssessmentTask task =
                taskRepository
                        .findById(assessment.getTaskId())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.INTERNAL_ERROR, "评估任务与结果不一致"));
        return new Outcome(task, assessment, parse(assessment), null, null);
    }

    // ------------------------------------------------------------------
    // list endpoints (P0): review queue + task history
    // ------------------------------------------------------------------

    /** One row of {@code GET /api/assessments}; deliberately without {@code agentResult}. */
    public record AssessmentSummary(
            long assessmentId,
            long subjectId,
            long taskId,
            java.math.BigDecimal riskScore,
            String riskLevel,
            String qualityStatus,
            String reviewStatus,
            Instant createdAt) {}

    /** One row of {@code GET /api/assessment-tasks}; no stage details. */
    public record AssessmentTaskSummary(
            long taskId,
            long subjectId,
            String status,
            String runId,
            Long assessmentId,
            String errorCode,
            String message,
            Instant createdAt,
            Instant updatedAt) {}

    private static final int MAX_PAGE_SIZE = 100;

    /**
     * {@code GET /api/assessments} - paged result summaries, newest first.
     *
     * <p>The response intentionally carries no {@code agentResult}: a page of 100 full results
     * would be tens of megabytes and would freeze the review queue. Only the scalar fields the list
     * needs are returned; {@code riskScore}/{@code riskLevel} are read out of the stored JSON with
     * a single {@code readTree} pass, and a malformed legacy row degrades to nulls instead of
     * failing the whole page.
     *
     * <p>Visibility follows the same rule as the subject list: ADMIN/RESEARCHER see everything,
     * DOCTOR/PATIENT only their granted subjects.
     */
    public PageResult<AssessmentSummary> listAssessments(
            int page, int pageSize, AppUserPrincipal principal) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
        Pageable pageable =
                PageRequest.of(safePage - 1, safePageSize, Sort.by(Sort.Direction.DESC, "id"));

        Page<RiskAssessment> paged;
        if (accessPolicy.hasFullRawScoreVisibility(principal)) {
            paged = assessmentRepository.findAll(pageable);
        } else {
            // Raw-score window, applied before paging: a PATIENT (or a SUMMARY_READ-only doctor)
            // must not see these rows at all, and filtering after paging would leak the total.
            java.util.Set<Long> visible = accessPolicy.rawScoreVisibleSubjectIds(principal);
            paged =
                    visible.isEmpty()
                            ? Page.empty(pageable)
                            : assessmentRepository.findBySubjectIdIn(visible, pageable);
        }
        List<AssessmentSummary> items =
                paged.getContent().stream().map(this::toAssessmentSummary).toList();
        return new PageResult<>(items, safePage, safePageSize, paged.getTotalElements());
    }

    private AssessmentSummary toAssessmentSummary(RiskAssessment assessment) {
        String level = null;
        java.math.BigDecimal score = null;
        try {
            JsonNode node = objectMapper.readTree(assessment.getAgentResultJson());
            JsonNode scoreNode = node.get("riskScore");
            if (scoreNode != null && scoreNode.isNumber()) {
                score = scoreNode.decimalValue();
            }
            JsonNode levelNode = node.get("riskLevel");
            if (levelNode != null && levelNode.isTextual()) {
                level = levelNode.asText();
            }
        } catch (JsonProcessingException | RuntimeException ignored) {
            // A stored result that cannot be parsed must not break the whole page.
            log.warn("评估结果 {} 的 agentResult 无法解析，列表摘要降级为 null", assessment.getId());
        }
        return new AssessmentSummary(
                assessment.getId(),
                assessment.getSubjectId(),
                assessment.getTaskId(),
                score,
                level,
                assessment.getQualityStatus() == null ? null : assessment.getQualityStatus().name(),
                assessment.getReviewStatus() == null ? null : assessment.getReviewStatus().name(),
                assessment.getCreatedAt());
    }

    /**
     * {@code GET /api/assessment-tasks} - paged task history, newest first.
     *
     * <p>Assessment ids are resolved for the whole page in one query, so a 100-row page costs two
     * queries rather than 101.
     */
    public PageResult<AssessmentTaskSummary> listTasks(
            int page, int pageSize, AppUserPrincipal principal) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
        Pageable pageable =
                PageRequest.of(safePage - 1, safePageSize, Sort.by(Sort.Direction.DESC, "id"));

        Page<AssessmentTask> paged;
        if (accessPolicy.hasFullRawScoreVisibility(principal)) {
            paged = taskRepository.findAll(pageable);
        } else {
            // Task history carries runId and assessment ids: same raw-score window as the list.
            java.util.Set<Long> visible = accessPolicy.rawScoreVisibleSubjectIds(principal);
            paged =
                    visible.isEmpty()
                            ? Page.empty(pageable)
                            : taskRepository.findBySubjectIdIn(visible, pageable);
        }

        List<Long> taskIds = paged.getContent().stream().map(AssessmentTask::getId).toList();
        java.util.Map<Long, Long> assessmentByTask =
                taskIds.isEmpty()
                        ? java.util.Map.of()
                        : assessmentRepository.findByTaskIdIn(taskIds).stream()
                                .collect(
                                        java.util.stream.Collectors.toMap(
                                                RiskAssessment::getTaskId,
                                                RiskAssessment::getId,
                                                (first, second) -> first));

        List<AssessmentTaskSummary> items =
                paged.getContent().stream()
                        .map(
                                task ->
                                        new AssessmentTaskSummary(
                                                task.getId(),
                                                task.getSubjectId(),
                                                task.getStatus().name(),
                                                task.getRunId(),
                                                assessmentByTask.get(task.getId()),
                                                task.getErrorCode(),
                                                task.getErrorMessage(),
                                                task.getCreatedAt(),
                                                task.getUpdatedAt()))
                        .toList();
        return new PageResult<>(items, safePage, safePageSize, paged.getTotalElements());
    }

    // ------------------------------------------------------------------
    // write path
    // ------------------------------------------------------------------

    /** A seeded task plus whether it was created here or adopted from a concurrent request. */
    private record Seed(AssessmentTask task, boolean replayed) {}

    private Seed seed(
            AppUserPrincipal principal,
            String idempotencyKey,
            String digest,
            Subject subject,
            AgentRequest request,
            long qualityCheckId) {
        Instant now = Instant.now(clock);
        try {
            return writeTransaction.execute(
                    status -> {
                        String currentVersion =
                                jdbc.queryForObject(
                                        "SELECT data_version FROM subject WHERE id=? FOR UPDATE",
                                        String.class,
                                        subject.getId());
                        if (currentVersion != null
                                && !currentVersion.equals(subject.getDataVersion()))
                            throw new BusinessException(
                                    ErrorCode.VALIDATION_ERROR, "输入在创建任务前已变化，请重新加载档案");
                        AssessmentTask task =
                                new AssessmentTask(subject.getId(), request.runId(), digest, now);
                        taskRepository.saveAndFlush(task);
                        String inputJson;
                        String provenance;
                        try {
                            inputJson = objectMapper.writeValueAsString(request);
                            provenance =
                                    objectMapper.writeValueAsString(
                                            java.util.Map.of(
                                                    "visits",
                                                    jdbc.queryForList(
                                                            "SELECT"
                                                                + " id,source,missing_reason,data_version,import_job_id"
                                                                + " FROM visit WHERE subject_id=?"
                                                                + " ORDER BY visit_date,id",
                                                            subject.getId()),
                                                    "features",
                                                    jdbc.queryForList(
                                                            "SELECT"
                                                                + " id,source,missing_reason,data_version,import_job_id"
                                                                + " FROM subject_feature WHERE"
                                                                + " subject_id=? ORDER BY"
                                                                + " recorded_at,id",
                                                            subject.getId())));
                        } catch (JsonProcessingException invalid) {
                            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法保存评估输入快照");
                        }
                        jdbc.update(
                                "INSERT INTO"
                                    + " task_input_snapshot(task_id,request_json,input_digest,data_version,captured_at,quality_check_id,provenance_json)"
                                    + " VALUES(?,?,?,?,?,?,?)",
                                task.getId(),
                                inputJson,
                                sha256(inputJson),
                                request.dataVersion(),
                                java.sql.Timestamp.from(now),
                                qualityCheckId,
                                provenance);
                        idempotencyRepository.saveAndFlush(
                                new IdempotencyRecord(
                                        principal.userId(),
                                        OPERATION,
                                        idempotencyKey,
                                        digest,
                                        task.getId()));
                        return new Seed(task, false);
                    });
        } catch (DataIntegrityViolationException race) {
            // Two requests with the same key. The loser adopts the winner's task instead of
            // running the workflow a second time, which is also what keeps it from colliding
            // with uq_risk_assessment_task when both try to store a result.
            log.debug("评估任务并发创建，改为重放赢家的任务", race);
            return new Seed(awaitWinner(principal, idempotencyKey, digest), true);
        }
    }

    /**
     * Looks the winner up. The exact constraint violation can fire before the winner's commit is
     * visible, so this retries briefly before giving up with a retryable 400.
     */
    private AssessmentTask awaitWinner(
            AppUserPrincipal principal, String idempotencyKey, String digest) {
        for (int attempt = 0; attempt < 5; attempt++) {
            Optional<IdempotencyRecord> winner =
                    idempotencyRepository.findByUserIdAndOperationAndIdempotencyKey(
                            principal.userId(), OPERATION, idempotencyKey);
            if (winner.isPresent()) {
                if (!winner.get().getRequestDigest().equals(digest)) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR, "该 Idempotency-Key 已用于不同的评估请求");
                }
                return taskRepository
                        .findById(winner.get().getResultId())
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.VALIDATION_ERROR, "评估任务不存在"));
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "评估请求处理冲突，请重试");
    }

    private Outcome replay(IdempotencyRecord record, String digest) {
        if (!record.getRequestDigest().equals(digest)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "该 Idempotency-Key 已用于不同的评估请求");
        }
        AssessmentTask task =
                taskRepository
                        .findById(record.getResultId())
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.VALIDATION_ERROR, "评估任务不存在"));
        return outcomeOf(task, null, null);
    }

    private Outcome outcomeOf(AssessmentTask task, String errorCode, String errorMessage) {
        RiskAssessment assessment = assessmentRepository.findByTaskId(task.getId()).orElse(null);
        // No explicit code? Fall back to what the row recorded when it was closed as FAILED,
        // so replays and concurrent-winner lookups keep the original explanation.
        String code = errorCode != null ? errorCode : task.getErrorCode();
        String message = errorCode != null ? errorMessage : task.getErrorMessage();
        return new Outcome(
                task, assessment, assessment == null ? null : parse(assessment), code, message);
    }

    private Outcome persist(Long taskId, Long subjectId, AgentResult result) {
        try {
            Instant now = Instant.now(clock);
            String json;
            try {
                json = objectMapper.writeValueAsString(result);
            } catch (JsonProcessingException failure) {
                throw new BusinessException(
                        ErrorCode.INTERNAL_ERROR,
                        "AGENT_CONTRACT_MISMATCH: 结果无法序列化 " + failure.getOriginalMessage());
            }
            return writeTransaction.execute(
                    status -> {
                        AssessmentTask task =
                                taskRepository
                                        .findById(taskId)
                                        .orElseThrow(
                                                () ->
                                                        new BusinessException(
                                                                ErrorCode.INTERNAL_ERROR,
                                                                "评估任务丢失"));
                        if (result.status() == AgentResult.Status.BLOCKED) {
                            task.markBlocked(now);
                        } else {
                            task.markCompleted(now);
                        }
                        taskRepository.saveAndFlush(task);

                        RiskAssessment assessment =
                                new RiskAssessment(
                                        subjectId,
                                        taskId,
                                        json,
                                        RiskAssessment.QualityStatus.valueOf(
                                                result.qualityStatus().name()),
                                        result.dataVersion(),
                                        result.modelVersion(),
                                        result.workflowVersion(),
                                        now);
                        assessmentRepository.saveAndFlush(assessment);
                        jdbc.update(
                                "UPDATE risk_assessment SET risk_score=?,risk_level=? WHERE id=?",
                                result.riskScore(),
                                result.riskLevel().name(),
                                assessment.getId());
                        return new Outcome(task, assessment, result, null, null);
                    });
        } catch (RuntimeException failure) {
            // Serialisation, an unknown qualityStatus, a unique-constraint clash: all of them
            // would roll the transaction back and leave the task PENDING. Close it out first with
            // the most precise code available, then let create() know the row is already closed.
            String errorCode = persistFailureCode(failure);
            AssessmentTask failed =
                    markFailedOrThrow(taskId, failure, errorCode, userFacingMessage(errorCode));
            throw new TaskClosedAsFailed(failed, failure);
        }
    }

    /**
     * Marker raised after a row has already been closed as FAILED with a precise code.
     *
     * <p>{@link #create} uses it to skip its generic "mark FAILED" step: overwriting {@code
     * TASK_PERSIST_FAILED} (or {@code AGENT_CONTRACT_MISMATCH}) with {@code AGENT_FAILED} would
     * make a later re-fetch report the wrong cause.
     */
    private static final class TaskClosedAsFailed extends RuntimeException {

        private final transient AssessmentTask task;

        TaskClosedAsFailed(AssessmentTask task, RuntimeException cause) {
            super(cause.getMessage(), cause);
            this.task = task;
        }

        AssessmentTask task() {
            return task;
        }
    }

    /**
     * The code to persist when the result could not be stored: keep an explicit {@code AGENT_*}
     * prefix when the failure carries one (serialisation/contract mismatch), otherwise fall back to
     * the Java-side {@code TASK_PERSIST_FAILED}.
     */
    static String persistFailureCode(Throwable failure) {
        if (failure instanceof BusinessException) {
            String code = RestAgentClient.errorCodeOf(failure);
            if (code != null && !code.isBlank() && !RestAgentClient.CODE_FAILED.equals(code)) {
                return code;
            }
        }
        return CODE_PERSIST_FAILED;
    }

    private AssessmentTask markFailed(Long taskId, String errorCode, String errorMessage) {
        Instant now = Instant.now(clock);
        return writeTransaction.execute(
                status -> {
                    AssessmentTask task =
                            taskRepository
                                    .findById(taskId)
                                    .orElseThrow(
                                            () ->
                                                    new BusinessException(
                                                            ErrorCode.INTERNAL_ERROR, "评估任务丢失"));
                    task.markFailed(now, errorCode, errorMessage);
                    return taskRepository.saveAndFlush(task);
                });
    }

    /**
     * Marks the task FAILED in its own committed transaction and returns the reloaded row. If the
     * update itself fails (the database is gone), the original failure is the one propagated, and
     * the PENDING row is left for the reconciliation task instead of hidden.
     *
     * <p>The stable code and the fixed reason are persisted with the row, so a later {@code GET
     * /api/assessment-tasks/{id}} or an idempotent replay can still explain why the task failed.
     * Only the code and a whitelisted sentence are stored - never a stack trace, an upstream
     * response body or a host address.
     */
    private AssessmentTask markFailedOrThrow(
            Long taskId, RuntimeException failure, String errorCode, String errorMessage) {
        try {
            return markFailed(taskId, errorCode, errorMessage);
        } catch (RuntimeException secondary) {
            failure.addSuppressed(secondary);
            log.error("评估任务 {} 无法标记为 FAILED，保持 PENDING 等待对账", taskId, secondary);
            throw failure;
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private AgentRequest buildRequest(Subject subject, int predictionWindowMonths) {
        List<AgentRequest.Visit> visits =
                visitRepository.findBySubjectIdOrderByVisitDateAscIdAsc(subject.getId()).stream()
                        .map(
                                visit ->
                                        new AgentRequest.Visit(
                                                visit.getVisitCode(),
                                                visit.getVisitDate().toString(),
                                                original(visit.getRawMmse(), visit.getMmse()),
                                                original(visit.getRawMoca(), visit.getMoca()),
                                                original(visit.getRawCdrSb(), visit.getCdrSb())))
                        .toList();

        // subject_feature has no JPA entity yet; the two Python feature names are the same
        // strings the import pipeline stores, so a direct projection keeps this read-only.
        List<AgentRequest.Feature> features =
                jdbc.query(
                        "SELECT feature_name, feature_value, recorded_at, unit FROM subject_feature"
                                + " WHERE subject_id = ? ORDER BY recorded_at, id",
                        (rs, rowNum) ->
                                new AgentRequest.Feature(
                                        rs.getString("feature_name"),
                                        rs.getBigDecimal("feature_value"),
                                        rs.getDate("recorded_at").toLocalDate().toString(),
                                        rs.getString("unit")),
                        subject.getId());

        return AgentRequest.of(
                "run-" + UUID.randomUUID(),
                subject.getSubjectCode(),
                subject.getDataVersion(),
                predictionWindowMonths,
                visits,
                features);
    }

    private AgentResult parse(RiskAssessment assessment) {
        AgentResult result;
        try {
            result = objectMapper.readValue(assessment.getAgentResultJson(), AgentResult.class);
        } catch (JsonProcessingException failure) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "AGENT_CONTRACT_MISMATCH: 已存结果无法解析 " + failure.getOriginalMessage());
        }
        // Stored JSON may predate the current rules (or have been edited by hand); the same
        // invariants that guard a fresh response must guard a replayed one.
        AgentResultValidator.validate(result);
        return result;
    }

    private static void requireKey(String idempotencyKey) {
        if (idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Idempotency-Key 长度须为 1-" + MAX_KEY_LENGTH);
        }
    }

    /**
     * The stable code persisted on a FAILED row. Business exceptions already carry an {@code
     * AGENT_*} prefix that {@link RestAgentClient#errorCodeOf(Throwable)} extracts; anything else
     * (an unexpected runtime failure) is reported as {@code AGENT_FAILED}.
     */
    static String stableErrorCode(Throwable failure) {
        String code = RestAgentClient.errorCodeOf(failure);
        return code == null || code.isBlank() ? RestAgentClient.CODE_FAILED : code;
    }

    /**
     * A fixed, user-facing sentence for a persisted failure code.
     *
     * <p>Deliberately a whitelist: the stored message never contains a stack trace, an exception
     * class name, an upstream response body, or a host/port. Two requests failing the same way
     * therefore produce byte-identical {@code error_message} values.
     */
    static String userFacingMessage(String errorCode) {
        String code = errorCode == null ? "" : errorCode;
        return switch (code) {
            case RestAgentClient.CODE_TIMEOUT -> "调用算法服务超时，未在时限内返回结果";
            case RestAgentClient.CODE_UNAVAILABLE -> "无法连接算法服务，请确认 Python 服务已启动";
            case RestAgentClient.CODE_VALIDATION -> "算法服务判定请求数据不合法";
            case RestAgentClient.CODE_RUN_CONFLICT -> "该 runId 已有结果，已按重放处理";
            case RestAgentClient.CODE_RUN_NOT_FOUND -> "算法服务未找到该 runId";
            case RestAgentClient.CODE_PAYLOAD_TOO_LARGE -> "请求体超过算法服务限制";
            case RestAgentClient.CODE_PROTOCOL_ERROR -> "算法服务返回的结构不符合契约";
            case RestAgentClient.CODE_CAPACITY -> "算法服务繁忙，请稍后重试";
            case AgentResultValidator.CODE -> "算法服务返回的结果不符合契约，已拒绝入库";
            case CODE_PERSIST_FAILED -> MESSAGE_PERSIST_FAILED;
            default -> "算法服务执行失败";
        };
    }

    static String digestOf(
            Long subjectId, String modelVersion, int predictionWindowMonths, String dataVersion) {
        String canonical =
                subjectId + "|" + modelVersion + "|" + predictionWindowMonths + "|" + dataVersion;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private static java.math.BigDecimal original(String raw, java.math.BigDecimal fallback) {
        return raw == null ? fallback : new java.math.BigDecimal(raw);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public Outcome cancel(long id, AppUserPrincipal principal) {
        var outcome = findTask(id, principal);
        int changed =
                jdbc.update(
                        "UPDATE assessment_task SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP"
                                + " WHERE id=? AND status='PENDING'",
                        id);
        if (changed == 0)
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "仅尚未调用算法的排队任务可取消");
        return findTask(id, principal);
    }
}
