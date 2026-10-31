package com.adcausalrisk.backend.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.adcausalrisk.backend.agent.AgentRequest;
import com.adcausalrisk.backend.agent.AgentResult;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * Service-level guarantees that the HTTP layer cannot observe on its own: a task that reaches
 * PENDING must always leave it, and a replayed result must satisfy the same invariants as a
 * fresh one.
 */
class AssessmentServiceTest {

    private static final long SUBJECT_ID = 42L;
    private static final long USER_ID = 7L;
    private static final String DATA_VERSION = "demo-assessment-test";
    private static final String KEY = "assessment-key";

    private final AssessmentTaskRepository taskRepository = mock(AssessmentTaskRepository.class);
    private final RiskAssessmentRepository assessmentRepository = mock(RiskAssessmentRepository.class);
    private final SubjectRepository subjectRepository = mock(SubjectRepository.class);
    private final VisitRepository visitRepository = mock(VisitRepository.class);
    private final IdempotencyRecordRepository idempotencyRepository = mock(IdempotencyRecordRepository.class);
    private final SubjectAccessPolicy accessPolicy = mock(SubjectAccessPolicy.class);
    private final RestAgentClient agentClient = mock(RestAgentClient.class);
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final java.time.Clock clock = java.time.Clock.systemUTC();

    private AssessmentService service;
    private AssessmentTask task;
    private AppUserPrincipal principal;
    private Subject subject;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        service = new AssessmentService(
                taskRepository,
                assessmentRepository,
                subjectRepository,
                visitRepository,
                idempotencyRepository,
                accessPolicy,
                agentClient,
                objectMapper,
                jdbc,
                clock,
                transactionManager);

        task = new AssessmentTask(SUBJECT_ID, "run-test", "digest", Instant.now());
        when(taskRepository.saveAndFlush(any(AssessmentTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.findById(any())).thenReturn(Optional.of(task));

        subject = new Subject("DEMO-900", "评估测试档案", Subject.Status.ACTIVE, DATA_VERSION, Instant.now());
        when(subjectRepository.findById(SUBJECT_ID)).thenReturn(Optional.of(subject));
        when(visitRepository.findBySubjectIdOrderByVisitDateAscIdAsc(SUBJECT_ID)).thenReturn(List.of());
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        principal = new AppUserPrincipal(USER_ID, "researcher", "hash", List.of("RESEARCHER"), true);

        when(accessPolicy.canRequestAssessment(any(), any())).thenReturn(true);
        when(accessPolicy.canView(principal, SUBJECT_ID)).thenReturn(true);
        when(idempotencyRepository.findByUserIdAndOperationAndIdempotencyKey(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    /**
     * P0-2: an exception type the client does not classify would previously escape and leave the
     * row PENDING forever. It must be rethrown, but only after the task is closed out.
     */
    @Test
    void unexpectedAgentFailureClosesTheTaskAsFailed() {
        when(agentClient.createRun(any(AgentRequest.class)))
                .thenThrow(new IllegalStateException("unexpected client failure"));

        assertThatThrownBy(() -> service.create(SUBJECT_ID, AgentRequest.MODEL_VERSION, 24, KEY, principal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected client failure");

        assertThat(task.getStatus()).isEqualTo(AssessmentTask.Status.FAILED);
    }

    /** P0-3: a serialisation failure happens before the write transaction, so persist must close it. */
    @Test
    void serialisationFailureClosesTheTaskAsFailed() throws Exception {
        AgentResult result = mock(AgentResult.class);
        when(agentClient.createRun(any(AgentRequest.class))).thenReturn(result);
        when(objectMapper.writeValueAsString(result)).thenThrow(mock(JsonProcessingException.class));

        // A BusinessException is reported as a FAILED task in a 200 response (the contract has no
        // 5xx for this operation), so the call returns instead of throwing.
        AssessmentService.Outcome outcome =
                service.create(SUBJECT_ID, AgentRequest.MODEL_VERSION, 24, KEY, principal);

        assertThat(outcome.task().getStatus()).isEqualTo(AssessmentTask.Status.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("AGENT_CONTRACT_MISMATCH");
        assertThat(outcome.result()).isNull();
        verify(assessmentRepository, never()).saveAndFlush(any(RiskAssessment.class));
    }

    /** P1-3: " key " and "key" are the same idempotency scope, so the padded call must replay. */
    @Test
    void paddedIdempotencyKeyReplaysTheSameTask() throws Exception {
        String digest = AssessmentService.digestOf(
                SUBJECT_ID, AgentRequest.MODEL_VERSION, 24, DATA_VERSION);
        IdempotencyRecord record = new IdempotencyRecord(USER_ID, AssessmentService.OPERATION, KEY, digest, 99L);
        when(idempotencyRepository.findByUserIdAndOperationAndIdempotencyKey(USER_ID, AssessmentService.OPERATION, KEY))
                .thenReturn(Optional.of(record));
        when(taskRepository.findById(99L)).thenReturn(Optional.of(task));

        AssessmentService.Outcome outcome =
                service.create(SUBJECT_ID, AgentRequest.MODEL_VERSION, 24, "  " + KEY + "  ", principal);

        assertThat(outcome.task()).isSameAs(task);
        verify(taskRepository, never()).saveAndFlush(any(AssessmentTask.class));
        verify(agentClient, never()).createRun(any(AgentRequest.class));
    }

    /** P1-5: a stored result that predates the rules must not be served as-is. */
    @Test
    void storedResultViolatingTheContractIsRejectedOnRead() throws Exception {
        AgentResult outOfRange = completedResult(new BigDecimal("2.0"));
        RiskAssessment stored = new RiskAssessment(
                SUBJECT_ID, 5L, "{}", RiskAssessment.QualityStatus.PASS,
                DATA_VERSION, AgentRequest.MODEL_VERSION, AgentResult.WORKFLOW_VERSION, Instant.now());
        when(assessmentRepository.findById(5L)).thenReturn(Optional.of(stored));
        when(accessPolicy.canReadRawScores(principal, SUBJECT_ID)).thenReturn(true);
        when(objectMapper.readValue(anyString(), eq(AgentResult.class))).thenReturn(outOfRange);

        assertThatThrownBy(() -> service.findAssessment(5L, principal))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AGENT_CONTRACT_MISMATCH")
                .hasMessageContaining("riskScore");
    }

    /** P1-6: creating for a subject outside the caller's scope is an object-level denial. */
    @Test
    void creatingForAnInvisibleSubjectIsAccessDenied() {
        when(accessPolicy.canView(principal, SUBJECT_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.create(SUBJECT_ID, AgentRequest.MODEL_VERSION, 24, KEY, principal))
                .isInstanceOf(BusinessException.class)
                .satisfies(failure -> assertThat(((BusinessException) failure).errorCode())
                        .isEqualTo(ErrorCode.ACCESS_DENIED));

        verify(taskRepository, never()).saveAndFlush(any(AssessmentTask.class));
    }

    private static AgentResult completedResult(BigDecimal riskScore) {
        return new AgentResult(
                "run-test",
                "EXPLANATION",
                DATA_VERSION,
                AgentResult.Status.COMPLETED,
                AgentResult.CONTRACT_VERSION,
                AgentResult.WORKFLOW_VERSION,
                AgentResult.MODEL_VERSION,
                AgentResult.QualityStatus.PASS,
                riskScore,
                AgentResult.RiskLevel.HIGH,
                List.of(),
                new AgentResult.Graph(List.of(), List.of(), List.of(), "synthetic-prior-v1"),
                List.of(),
                List.of(),
                List.of(),
                List.of(new AgentResult.Stage(
                        AgentResult.StageName.DATA_FUSION,
                        AgentResult.StageStatus.COMPLETED,
                        1.5,
                        Map.of())),
                AgentResult.DATA_SOURCE,
                true);
    }
}
