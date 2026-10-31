package com.adcausalrisk.backend.assessment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.adcausalrisk.backend.agent.AgentRequest;
import com.adcausalrisk.backend.agent.AgentResult;
import com.adcausalrisk.backend.agent.AgentResultValidator;
import com.adcausalrisk.backend.agent.RestAgentClient;
import com.adcausalrisk.backend.assessment.entity.AssessmentTask;
import com.adcausalrisk.backend.assessment.entity.RiskAssessment;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.importjob.IdempotencyRecordRepository;
import com.adcausalrisk.backend.importjob.entity.IdempotencyRecord;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectAccessPolicy;
import com.adcausalrisk.backend.subject.SubjectRepository;
import com.adcausalrisk.backend.subject.entity.Subject;
import com.adcausalrisk.backend.visit.VisitRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrates one assessment: persist a PENDING task, call the Python agent, persist the
 * result. The two writes are separate short transactions and the HTTP call sits between them,
 * so no database connection is held while the synchronous workflow runs.
 */
@Service
public class AssessmentService {

    private static final Logger log = LoggerFactory.getLogger(AssessmentService.class);

    /** Idempotency scope of "create an assessment task", distinct from the Python runId replay. */
    static final String OPERATION = "ASSESSMENT_TASK_CREATE";

    private static final int MAX_KEY_LENGTH = 100;

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
    private final TransactionTemplate writeTransaction;

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
            PlatformTransactionManager transactionManager) {
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
        this.writeTransaction = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW, not the default REQUIRED: if this service is ever called from a
        // transactional caller, joining that transaction would hold a pooled connection for the
        // whole Python call and would defer the PENDING row until after the HTTP request.
        this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Task, optional result row and the parsed AgentResult, as needed by the HTTP layer. */
    public record Outcome(
            AssessmentTask task,
            RiskAssessment assessment,
            AgentResult result,
            String errorCode,
            String errorMessage) {
    }

    /**
     * {@code POST /api/assessment-tasks}.
     *
     * <p>The contract declares only 200/400/401/403 for this operation, so an agent failure is
     * not an HTTP error: the task becomes {@code FAILED} and the response still carries it.
     */
    public Outcome create(
            Long subjectId, String modelVersion, int predictionWindowMonths,
            String idempotencyKey, AppUserPrincipal principal) {
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

        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "档案不存在"));
        if (!accessPolicy.canView(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权访问该档案");
        }
        if (!AgentRequest.MODEL_VERSION.equals(modelVersion)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "modelVersion 仅支持 " + AgentRequest.MODEL_VERSION);
        }
        if (predictionWindowMonths < AgentRequest.MIN_WINDOW_MONTHS
                || predictionWindowMonths > AgentRequest.MAX_WINDOW_MONTHS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "predictionWindowMonths 必须在 " + AgentRequest.MIN_WINDOW_MONTHS
                            + "-" + AgentRequest.MAX_WINDOW_MONTHS + " 之间");
        }

        String digest = digestOf(subjectId, modelVersion, predictionWindowMonths, subject.getDataVersion());

        Optional<IdempotencyRecord> replay = idempotencyRepository
                .findByUserIdAndOperationAndIdempotencyKey(principal.userId(), OPERATION, idempotencyKey);
        if (replay.isPresent()) {
            return replay(replay.get(), digest);
        }

        AgentRequest request = buildRequest(subject, predictionWindowMonths);
        Seed seed = seed(principal, idempotencyKey, digest, subject, request);
        if (seed.replayed()) {
            // Another request with the same key already created this task. Replaying means
            // returning that task's current state - calling the agent again would run the
            // workflow twice and collide with the uq_risk_assessment_task constraint.
            return outcomeOf(seed.task(), null, null);
        }
        AssessmentTask seeded = seed.task();

        try {
            AgentResult result = agentClient.createRun(request);
            return persist(seeded.getId(), subject.getId(), result);
        } catch (RuntimeException failure) {
            // Anything that escapes from here would leave the row PENDING forever, so the task is
            // closed out first and the original failure is what the caller sees.
            AssessmentTask failed = markFailedOrThrow(seeded.getId(), failure);
            if (failure instanceof BusinessException business) {
                // The contract declares only 200/400/401/403 for this operation, so an agent
                // failure is a FAILED task in a 200 response, not an HTTP error.
                return new Outcome(failed, null, null,
                        RestAgentClient.errorCodeOf(business), business.getMessage());
            }
            throw failure;
        }
    }

    /** {@code GET /api/assessment-tasks/{taskId}}. */
    public Outcome findTask(Long taskId, AppUserPrincipal principal) {
        AssessmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "评估任务不存在"));
        if (!accessPolicy.canView(principal, task.getSubjectId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权查看该评估任务");
        }
        RiskAssessment assessment = assessmentRepository.findByTaskId(taskId).orElse(null);
        return new Outcome(task, assessment, assessment == null ? null : parse(assessment), null, null);
    }

    /**
     * {@code GET /api/assessments/{assessmentId}}.
     *
     * <p>Only the full {@code Assessment} shape is served here, which includes the raw result;
     * callers without raw-score access get 403. The patient summary is a separate contract type
     * and belongs with the patient views.
     */
    public Outcome findAssessment(Long assessmentId, AppUserPrincipal principal) {
        RiskAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "评估结果不存在"));
        if (!accessPolicy.canReadRawScores(principal, assessment.getSubjectId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权查看该评估结果");
        }
        AssessmentTask task = taskRepository.findById(assessment.getTaskId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "评估任务与结果不一致"));
        return new Outcome(task, assessment, parse(assessment), null, null);
    }

    // ------------------------------------------------------------------
    // write path
    // ------------------------------------------------------------------

    /** A seeded task plus whether it was created here or adopted from a concurrent request. */
    private record Seed(AssessmentTask task, boolean replayed) {
    }

    private Seed seed(
            AppUserPrincipal principal, String idempotencyKey, String digest,
            Subject subject, AgentRequest request) {
        Instant now = Instant.now(clock);
        try {
            return writeTransaction.execute(status -> {
                AssessmentTask task = new AssessmentTask(
                        subject.getId(), request.runId(), digest, now);
                taskRepository.saveAndFlush(task);
                idempotencyRepository.saveAndFlush(new IdempotencyRecord(
                        principal.userId(), OPERATION, idempotencyKey, digest, task.getId()));
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
            Optional<IdempotencyRecord> winner = idempotencyRepository
                    .findByUserIdAndOperationAndIdempotencyKey(
                            principal.userId(), OPERATION, idempotencyKey);
            if (winner.isPresent()) {
                if (!winner.get().getRequestDigest().equals(digest)) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "该 Idempotency-Key 已用于不同的评估请求");
                }
                return taskRepository.findById(winner.get().getResultId())
                        .orElseThrow(() -> new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "评估任务不存在"));
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
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "该 Idempotency-Key 已用于不同的评估请求");
        }
        AssessmentTask task = taskRepository.findById(record.getResultId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "评估任务不存在"));
        return outcomeOf(task, null, null);
    }

    private Outcome outcomeOf(AssessmentTask task, String errorCode, String errorMessage) {
        RiskAssessment assessment = assessmentRepository.findByTaskId(task.getId()).orElse(null);
        return new Outcome(task, assessment, assessment == null ? null : parse(assessment),
                errorCode, errorMessage);
    }

    private Outcome persist(Long taskId, Long subjectId, AgentResult result) {
        try {
            Instant now = Instant.now(clock);
            String json;
            try {
                json = objectMapper.writeValueAsString(result);
            } catch (JsonProcessingException failure) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "AGENT_CONTRACT_MISMATCH: 结果无法序列化 " + failure.getOriginalMessage());
            }
            return writeTransaction.execute(status -> {
                AssessmentTask task = taskRepository.findById(taskId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "评估任务丢失"));
                if (result.status() == AgentResult.Status.BLOCKED) {
                    task.markBlocked(now);
                } else {
                    task.markCompleted(now);
                }
                taskRepository.saveAndFlush(task);

                RiskAssessment assessment = new RiskAssessment(
                        subjectId,
                        taskId,
                        json,
                        RiskAssessment.QualityStatus.valueOf(result.qualityStatus().name()),
                        result.dataVersion(),
                        result.modelVersion(),
                        result.workflowVersion(),
                        now);
                assessmentRepository.saveAndFlush(assessment);
                return new Outcome(task, assessment, result, null, null);
            });
        } catch (RuntimeException failure) {
            // Serialisation, an unknown qualityStatus, a unique-constraint clash: all of them
            // would roll the transaction back and leave the task PENDING. Close it out first.
            markFailedOrThrow(taskId, failure);
            throw failure;
        }
    }

    private AssessmentTask markFailed(Long taskId) {
        Instant now = Instant.now(clock);
        return writeTransaction.execute(status -> {
            AssessmentTask task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "评估任务丢失"));
            task.markFailed(now);
            return taskRepository.saveAndFlush(task);
        });
    }

    /**
     * Marks the task FAILED in its own committed transaction and returns the reloaded row.
     * If the update itself fails (the database is gone), the original failure is the one
     * propagated, and the PENDING row is left for the reconciliation task instead of hidden.
     */
    private AssessmentTask markFailedOrThrow(Long taskId, RuntimeException failure) {
        try {
            return markFailed(taskId);
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
        List<AgentRequest.Visit> visits = visitRepository
                .findBySubjectIdOrderByVisitDateAscIdAsc(subject.getId()).stream()
                .map(visit -> new AgentRequest.Visit(
                        visit.getVisitCode(),
                        visit.getVisitDate().toString(),
                        visit.getMmse(),
                        visit.getMoca(),
                        visit.getCdrSb()))
                .toList();

        // subject_feature has no JPA entity yet; the two Python feature names are the same
        // strings the import pipeline stores, so a direct projection keeps this read-only.
        List<AgentRequest.Feature> features = jdbc.query(
                "SELECT feature_name, feature_value, recorded_at, unit FROM subject_feature"
                        + " WHERE subject_id = ? ORDER BY recorded_at, id",
                (rs, rowNum) -> new AgentRequest.Feature(
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
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "AGENT_CONTRACT_MISMATCH: 已存结果无法解析 " + failure.getOriginalMessage());
        }
        // Stored JSON may predate the current rules (or have been edited by hand); the same
        // invariants that guard a fresh response must guard a replayed one.
        AgentResultValidator.validate(result);
        return result;
    }

    private static void requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Idempotency-Key 长度须为 1-" + MAX_KEY_LENGTH);
        }
    }

    static String digestOf(Long subjectId, String modelVersion, int predictionWindowMonths, String dataVersion) {
        String canonical = subjectId + "|" + modelVersion + "|" + predictionWindowMonths + "|" + dataVersion;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }
}
