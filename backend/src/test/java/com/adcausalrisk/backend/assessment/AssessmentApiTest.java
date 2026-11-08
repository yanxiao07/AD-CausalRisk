package com.adcausalrisk.backend.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.adcausalrisk.backend.agent.AgentRequest;
import com.adcausalrisk.backend.agent.AgentResult;
import com.adcausalrisk.backend.agent.RestAgentClient;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.contract.ContractSchemas;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectRepository;
import com.adcausalrisk.backend.subject.entity.Subject;
import com.adcausalrisk.backend.user.SysUser;
import com.adcausalrisk.backend.user.SysUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * HTTP-level guarantees of the assessment task.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}: the service writes the PENDING row in its own
 * {@code REQUIRES_NEW} transaction so that it survives the HTTP call, which means a test
 * transaction could neither see it nor roll it back. The subject is therefore committed up front
 * and every row this class creates is deleted in {@link #cleanUp()}.
 *
 * <p>{@link RestAgentClient} is replaced by a mock: these tests are about the Java side of the
 * contract, and the real client is already covered by its own unit-level checks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssessmentApiTest {

    private static final String DEMO_PASSWORD = "DemoPass!2026";
    private static final String SUBJECT_CODE = "DEMO-AS-API";

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private SubjectRepository subjectRepository;

    @Autowired private SysUserRepository sysUserRepository;

    @Autowired private AssessmentService assessmentService;

    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private RestAgentClient agentClient;

    private Long subjectId;

    @BeforeEach
    void createSubject() {
        Subject subject =
                subjectRepository.save(
                        new Subject(
                                SUBJECT_CODE,
                                "评估接口测试档案",
                                Subject.Status.ACTIVE,
                                "demo-assessment-api",
                                Instant.now()));
        subjectId = subject.getId();
    }

    @AfterEach
    void cleanUp() {
        // Children first: these tables have ON DELETE RESTRICT towards subject.
        jdbc.update("DELETE FROM subject_permission WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM risk_assessment WHERE subject_id = ?", subjectId);
        jdbc.update(
                "DELETE FROM task_input_snapshot WHERE task_id IN (SELECT id FROM assessment_task"
                        + " WHERE subject_id = ?)",
                subjectId);
        jdbc.update("DELETE FROM quality_check WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM assessment_task WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM subject WHERE id = ?", subjectId);
        jdbc.update(
                "DELETE FROM idempotency_record WHERE operation = ?", AssessmentService.OPERATION);
    }

    /**
     * The contract declares only 200/400/401/403 for this operation, so a missing result must be a
     * 400 validation failure, not a 404.
     */
    @Test
    void missingAssessmentIsAValidationErrorNot404() throws Exception {
        MockHttpSession admin = login("admin");

        JsonNode body =
                bodyOf(
                        mockMvc.perform(get("/api/assessments/999999").session(admin))
                                .andExpect(status().isBadRequest())
                                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("message").asText()).contains("评估结果不存在");
    }

    @Test
    void predictionWindowBelowSixIsRejected() throws Exception {
        assertWindowRejected(login("admin"), 5);
    }

    @Test
    void predictionWindowAboveSixtyIsRejected() throws Exception {
        assertWindowRejected(login("admin"), 61);
    }

    /**
     * A doctor holding a grant on the subject may start an assessment for it (V9 seeds exactly this
     * for the demo subjects). Without a grant the same call is 403 - see the test below.
     */
    @Test
    void doctorWithGrantCanCreateAssessment() throws Exception {
        MockHttpSession doctor = login("doctor");
        Long doctorId =
                jdbc.queryForObject(
                        "SELECT id FROM sys_user WHERE username = 'doctor'", Long.class);
        jdbc.update(
                "INSERT INTO subject_permission (subject_id, user_id, permission_type)"
                        + " VALUES (?, ?, 'VIEW')",
                subjectId,
                doctorId);

        when(agentClient.createRun(any(AgentRequest.class))).thenReturn(completedResult("0.62"));

        JsonNode body =
                bodyOf(
                        mockMvc.perform(
                                        post("/api/assessment-tasks")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .header("Idempotency-Key", "doctor-granted")
                                                .content(createRequestJson(24))
                                                .session(doctor))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(body.get("data").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(body.get("data").get("assessmentId").isNull()).isFalse();
    }

    @Test
    void doctorCannotCreateAssessment() throws Exception {
        MockHttpSession doctor = login("doctor");

        JsonNode body =
                bodyOf(
                        mockMvc.perform(
                                        post("/api/assessment-tasks")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .header("Idempotency-Key", "doctor-denied")
                                                .content(createRequestJson(24))
                                                .session(doctor))
                                .andExpect(status().isForbidden())
                                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("ACCESS_DENIED");
    }

    /**
     * A failed agent call must keep its stable code and readable reason across a re-fetch.
     *
     * <p>Before V10 the pair only existed in the first response: {@code GET /assessment-tasks/{id}}
     * came back FAILED with {@code errorCode=null}. This test pins the persisted contract and also
     * checks that the stored message stays a fixed sentence (no exception body, no host address).
     */
    @Test
    void failedTaskKeepsErrorCodeAndMessageOnRefetch() throws Exception {
        when(agentClient.createRun(any(AgentRequest.class)))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.INTERNAL_ERROR,
                                RestAgentClient.CODE_TIMEOUT
                                        + ": I/O error on POST request for"
                                        + " \"http://127.0.0.1:8091/api/agent/v1/runs\": Read timed"
                                        + " out"));
        MockHttpSession admin = login("admin");

        JsonNode created =
                bodyOf(
                        mockMvc.perform(
                                        post("/api/assessment-tasks")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .header("Idempotency-Key", "assessment-timeout")
                                                .content(createRequestJson(24))
                                                .session(admin))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(created.get("data").get("status").asText()).isEqualTo("FAILED");
        assertThat(created.get("data").get("errorCode").asText())
                .isEqualTo(RestAgentClient.CODE_TIMEOUT);
        // The *first* response must already be sanitised: the raw BusinessException message
        // contains the upstream URL and the failure detail, and none of it may reach the caller.
        String firstMessage = created.get("data").get("message").asText();
        assertThat(firstMessage).isEqualTo("调用算法服务超时，未在时限内返回结果");
        assertThat(firstMessage)
                .doesNotContain("127.0.0.1")
                .doesNotContain("8091")
                .doesNotContain("I/O error")
                .doesNotContain("BusinessException");
        long taskId = created.get("data").get("taskId").asLong();

        JsonNode refetched =
                bodyOf(
                        mockMvc.perform(get("/api/assessment-tasks/" + taskId).session(admin))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(refetched.get("data").get("status").asText()).isEqualTo("FAILED");
        assertThat(refetched.get("data").get("errorCode").asText())
                .isEqualTo(RestAgentClient.CODE_TIMEOUT);
        String message = refetched.get("data").get("message").asText();
        assertThat(message).isEqualTo("调用算法服务超时，未在时限内返回结果");
        // never leak internals into the persisted reason
        assertThat(message)
                .doesNotContain("127.0.0.1")
                .doesNotContain("8091")
                .doesNotContain("I/O error")
                .doesNotContain("BusinessException");
    }

    /** The list is a summary surface: paged scalar fields, and never the raw agentResult. */
    @Test
    void assessmentListIsPagedWithoutAgentResult() throws Exception {
        when(agentClient.createRun(any(AgentRequest.class))).thenReturn(completedResult("0.77"));
        MockHttpSession admin = login("admin");

        JsonNode created =
                bodyOf(
                        mockMvc.perform(
                                        post("/api/assessment-tasks")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .header("Idempotency-Key", "assessment-list")
                                                .content(createRequestJson(24))
                                                .session(admin))
                                .andExpect(status().isOk())
                                .andReturn());
        long assessmentId = created.get("data").get("assessmentId").asLong();

        JsonNode page =
                bodyOf(
                        mockMvc.perform(get("/api/assessments?page=1&pageSize=5").session(admin))
                                .andExpect(status().isOk())
                                .andReturn());

        JsonNode data = page.get("data");
        assertThat(data.get("page").asInt()).isEqualTo(1);
        assertThat(data.get("pageSize").asInt()).isEqualTo(5);
        assertThat(data.get("total").asLong()).isGreaterThanOrEqualTo(1);

        JsonNode row = null;
        for (JsonNode item : data.get("items")) {
            if (item.get("subjectId").asLong() == subjectId) {
                row = item;
                break;
            }
        }
        assertThat(row).as("the created assessment must appear in the list").isNotNull();
        assertThat(row.get("assessmentId").asLong()).isEqualTo(assessmentId);
        assertThat(row.get("reviewStatus").asText()).isEqualTo("PENDING");
        assertThat(row.get("riskLevel").asText()).isEqualTo("HIGH");
        // The whole point of the endpoint: no heavy result payload on the list.
        assertThat(row.has("agentResult")).isFalse();
    }

    @Test
    void taskListIsPagedAndCarriesAssessmentId() throws Exception {
        when(agentClient.createRun(any(AgentRequest.class))).thenReturn(completedResult("0.5"));
        MockHttpSession admin = login("admin");

        JsonNode created =
                bodyOf(
                        mockMvc.perform(
                                        post("/api/assessment-tasks")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .header("Idempotency-Key", "task-list")
                                                .content(createRequestJson(24))
                                                .session(admin))
                                .andExpect(status().isOk())
                                .andReturn());
        long taskId = created.get("data").get("taskId").asLong();
        long assessmentId = created.get("data").get("assessmentId").asLong();

        JsonNode page =
                bodyOf(
                        mockMvc.perform(
                                        get("/api/assessment-tasks?page=1&pageSize=5")
                                                .session(admin))
                                .andExpect(status().isOk())
                                .andReturn());

        JsonNode row = null;
        for (JsonNode item : page.get("data").get("items")) {
            if (item.get("taskId").asLong() == taskId) {
                row = item;
                break;
            }
        }
        assertThat(row).as("the created task must appear in the history").isNotNull();
        assertThat(row.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(row.get("assessmentId").asLong()).isEqualTo(assessmentId);
        assertThat(page.get("data").get("total").asLong()).isGreaterThanOrEqualTo(1);
    }

    /** A patient without a grant on this subject must not see it in either list. */
    @Test
    void listsAreFilteredBySubjectVisibility() throws Exception {
        when(agentClient.createRun(any(AgentRequest.class))).thenReturn(completedResult("0.4"));
        MockHttpSession admin = login("admin");
        bodyOf(
                mockMvc.perform(
                                post("/api/assessment-tasks")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("Idempotency-Key", "visibility")
                                        .content(createRequestJson(24))
                                        .session(admin))
                        .andExpect(status().isOk())
                        .andReturn());

        MockHttpSession patient = login("patient");
        JsonNode assessments =
                bodyOf(
                        mockMvc.perform(get("/api/assessments").session(patient))
                                .andExpect(status().isOk())
                                .andReturn());
        for (JsonNode item : assessments.get("data").get("items")) {
            assertThat(item.get("subjectId").asLong()).isNotEqualTo(subjectId);
        }

        JsonNode tasks =
                bodyOf(
                        mockMvc.perform(get("/api/assessment-tasks").session(patient))
                                .andExpect(status().isOk())
                                .andReturn());
        for (JsonNode item : tasks.get("data").get("items")) {
            assertThat(item.get("subjectId").asLong()).isNotEqualTo(subjectId);
        }
    }

    /**
     * A {@code subject_permission} grant alone must not open the raw-score lists: those rows carry
     * riskScore and runId, so a PATIENT is refused even when bound to the subject - exactly like
     * {@code GET /api/assessments/{id}} refuses them.
     */
    @Test
    void patientGrantDoesNotOpenRawScoreLists() throws Exception {
        when(agentClient.createRun(any(AgentRequest.class))).thenReturn(completedResult("0.66"));
        MockHttpSession admin = login("admin");
        JsonNode created =
                bodyOf(
                        mockMvc.perform(
                                        post("/api/assessment-tasks")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .header("Idempotency-Key", "patient-raw-denied")
                                                .content(createRequestJson(24))
                                                .session(admin))
                                .andExpect(status().isOk())
                                .andReturn());
        long assessmentId = created.get("data").get("assessmentId").asLong();
        long taskId = created.get("data").get("taskId").asLong();

        Long patientId =
                jdbc.queryForObject(
                        "SELECT id FROM sys_user WHERE username = 'patient'", Long.class);
        jdbc.update(
                "INSERT INTO subject_permission (subject_id, user_id, permission_type)"
                        + " VALUES (?, ?, 'VIEW')",
                subjectId,
                patientId);

        MockHttpSession patient = login("patient");
        JsonNode assessments =
                bodyOf(
                        mockMvc.perform(get("/api/assessments").session(patient))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(assessments.get("data").get("items")).isEmpty();

        JsonNode tasks =
                bodyOf(
                        mockMvc.perform(get("/api/assessment-tasks").session(patient))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(tasks.get("data").get("items")).isEmpty();

        // The detail endpoint already refuses; the lists must not become a bypass.
        bodyOf(
                mockMvc.perform(get("/api/assessments/" + assessmentId).session(patient))
                        .andExpect(status().isForbidden())
                        .andReturn());

        // Task detail carries runId + stage summaries, so it follows the same rule.
        bodyOf(
                mockMvc.perform(get("/api/assessment-tasks/" + taskId).session(patient))
                        .andExpect(status().isForbidden())
                        .andReturn());
    }

    /**
     * PATIENT + ADMIN must not regain the whole raw-score table.
     *
     * <p>The patient veto has to run <i>before</i> the full-visibility branch: otherwise the admin
     * role pages the entire table and leaks riskScore / runId, while the same account still gets
     * 403 from the detail endpoints.
     */
    @Test
    void patientPlusAdminStillGetsEmptyRawLists() throws Exception {
        assertPatientRoleMasksRawLists("admin");
    }

    @Test
    void patientPlusResearcherStillGetsEmptyRawLists() throws Exception {
        assertPatientRoleMasksRawLists("researcher");
    }

    private void assertPatientRoleMasksRawLists(String username) throws Exception {
        when(agentClient.createRun(any(AgentRequest.class))).thenReturn(completedResult("0.5"));
        MockHttpSession owner = login(username);
        JsonNode created =
                bodyOf(
                        mockMvc.perform(
                                        post("/api/assessment-tasks")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .header("Idempotency-Key", "multi-role-" + username)
                                                .content(createRequestJson(24))
                                                .session(owner))
                                .andExpect(status().isOk())
                                .andReturn());
        long assessmentId = created.get("data").get("assessmentId").asLong();

        Long userId =
                jdbc.queryForObject(
                        "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        Long patientRole =
                jdbc.queryForObject(
                        "SELECT id FROM sys_role WHERE role_code = 'PATIENT'", Long.class);
        jdbc.update("INSERT INTO user_role (user_id, role_id) VALUES (?, ?)", userId, patientRole);
        try {
            MockHttpSession multiRole = login(username);

            JsonNode assessments =
                    bodyOf(
                            mockMvc.perform(get("/api/assessments").session(multiRole))
                                    .andExpect(status().isOk())
                                    .andReturn());
            assertThat(assessments.get("data").get("items")).isEmpty();
            assertThat(assessments.get("data").get("total").asLong()).isZero();

            JsonNode tasks =
                    bodyOf(
                            mockMvc.perform(get("/api/assessment-tasks").session(multiRole))
                                    .andExpect(status().isOk())
                                    .andReturn());
            assertThat(tasks.get("data").get("items")).isEmpty();
            assertThat(tasks.get("data").get("total").asLong()).isZero();

            // The detail endpoints already answer 403 for the same account; lists must match.
            bodyOf(
                    mockMvc.perform(get("/api/assessments/" + assessmentId).session(multiRole))
                            .andExpect(status().isForbidden())
                            .andReturn());
        } finally {
            jdbc.update(
                    "DELETE FROM user_role WHERE user_id = ? AND role_id = ?", userId, patientRole);
        }
    }

    /** End-to-end write: PENDING -> agent mock -> COMPLETED task plus a stored result. */
    @Test
    void completedAssessmentIsPersistedAndReadBack() throws Exception {
        when(agentClient.createRun(any(AgentRequest.class))).thenReturn(completedResult("0.8304"));
        MockHttpSession admin = login("admin");

        JsonNode created =
                bodyOf(
                        mockMvc.perform(
                                        post("/api/assessment-tasks")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .header("Idempotency-Key", "assessment-happy")
                                                .content(createRequestJson(24))
                                                .session(admin))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(created.get("data").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(created.get("data").get("runId").asText()).startsWith("run-");
        assertThat(created.get("data").get("stages").size()).isEqualTo(1);
        long assessmentId = created.get("data").get("assessmentId").asLong();

        JsonNode result =
                bodyOf(
                        mockMvc.perform(get("/api/assessments/" + assessmentId).session(admin))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(result.get("data").get("agentResult").get("riskScore").decimalValue())
                .isEqualByComparingTo("0.8304");
        assertThat(result.get("data").get("reviewStatus").asText()).isEqualTo("PENDING");
    }

    /**
     * {@code GET /api/reports/{assessmentId}} - the last contract operation that had no coverage.
     * It reuses the assessment read permission, so an ADMIN receives the full report and a PATIENT
     * is refused before any payload is built.
     */
    @Test
    void reportIsReadableByAdminAndForbiddenForPatient() throws Exception {
        when(agentClient.createRun(any(AgentRequest.class))).thenReturn(completedResult("0.8304"));
        MockHttpSession admin = login("admin");

        JsonNode created =
                bodyOf(
                        mockMvc.perform(
                                        post("/api/assessment-tasks")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .header("Idempotency-Key", "assessment-report")
                                                .content(createRequestJson(24))
                                                .session(admin))
                                .andExpect(status().isOk())
                                .andReturn());
        long assessmentId = created.get("data").get("assessmentId").asLong();
        assertThat(assessmentId).isPositive();

        JsonNode report =
                bodyOf(
                        mockMvc.perform(get("/api/reports/" + assessmentId).session(admin))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(report.get("code").asInt()).isZero();
        assertThat(report.get("data").get("assessmentId").asLong()).isEqualTo(assessmentId);
        assertThat(report.get("data").get("agentResult").get("riskScore").decimalValue())
                .isEqualByComparingTo("0.8304");
        assertThat(report.get("data").get("agentResult").get("status").asText())
                .isEqualTo("COMPLETED");
        assertThat(
                        ContractSchemas.validateResponse(
                                report, "/api/reports/{assessmentId}", "get", 200))
                .as("the report view must match the frozen contract")
                .isEmpty();

        JsonNode refused =
                bodyOf(
                        mockMvc.perform(
                                        get("/api/reports/" + assessmentId)
                                                .session(login("patient")))
                                .andExpect(status().isForbidden())
                                .andReturn());

        assertThat(refused.get("errorCode").asText()).isEqualTo("ACCESS_DENIED");
        assertThat(refused.get("data").isNull()).as("a refusal must not carry the report").isTrue();
    }

    /**
     * Two callers with the same key must not produce two tasks. The loser may answer 400 when the
     * winner has not committed yet, but it must never be a 5xx and never a second row.
     */
    @Test
    void concurrentRequestsWithTheSameKeyCreateOneTask() throws Exception {
        when(agentClient.createRun(any(AgentRequest.class))).thenReturn(completedResult("0.5"));
        AppUserPrincipal admin = principal("admin");

        List<Object> outcomes = race(admin, "assessment-race");

        assertThat(outcomes)
                .as("one row, no 5xx")
                .allSatisfy(
                        outcome ->
                                assertThat(outcome)
                                        .isInstanceOfAny(
                                                AssessmentService.Outcome.class,
                                                com.adcausalrisk.backend.common.BusinessException
                                                        .class));
        Integer tasks =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM assessment_task WHERE subject_id = ?",
                        Integer.class,
                        subjectId);
        assertThat(tasks).as("the unique key must keep a single task").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private List<Object> race(AppUserPrincipal admin, String key) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>(2);
            for (int i = 0; i < 2; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        return assessmentService.create(
                                                subjectId,
                                                AgentRequest.MODEL_VERSION,
                                                24,
                                                key,
                                                admin);
                                    } catch (RuntimeException failure) {
                                        return failure;
                                    }
                                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>(2);
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertWindowRejected(MockHttpSession session, int months) throws Exception {
        JsonNode body =
                bodyOf(
                        mockMvc.perform(
                                        post("/api/assessment-tasks")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .header("Idempotency-Key", "window-" + months)
                                                .content(createRequestJson(months))
                                                .session(session))
                                .andExpect(status().isBadRequest())
                                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
    }

    private String createRequestJson(int predictionWindowMonths) throws Exception {
        return objectMapper.writeValueAsString(
                Map.of(
                        "subjectId", subjectId,
                        "modelVersion", AgentRequest.MODEL_VERSION,
                        "predictionWindowMonths", predictionWindowMonths));
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "username",
                                                                username,
                                                                "password",
                                                                DEMO_PASSWORD))))
                        .andExpect(status().isOk())
                        .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).as("login %s must establish a session", username).isNotNull();
        return session;
    }

    private AppUserPrincipal principal(String username) {
        SysUser user = sysUserRepository.findByUsername(username).orElseThrow();
        return new AppUserPrincipal(
                user.getId(), user.getUsername(), "", user.getRoleCodes(), true);
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static AgentResult completedResult(String riskScore) {
        return new AgentResult(
                "run-mock",
                "EXPLANATION",
                "demo-assessment-api",
                AgentResult.Status.COMPLETED,
                AgentResult.CONTRACT_VERSION,
                AgentResult.WORKFLOW_VERSION,
                AgentResult.MODEL_VERSION,
                AgentResult.QualityStatus.PASS,
                new BigDecimal(riskScore),
                AgentResult.RiskLevel.HIGH,
                List.of(),
                new AgentResult.Graph(List.of(), List.of(), List.of(), "synthetic-prior-v1"),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new AgentResult.Stage(
                                AgentResult.StageName.DATA_FUSION,
                                AgentResult.StageStatus.COMPLETED,
                                1.0,
                                Map.of())),
                AgentResult.DATA_SOURCE,
                true);
    }
}
