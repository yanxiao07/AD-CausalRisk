package com.adcausalrisk.backend.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.adcausalrisk.backend.agent.AgentRequest;
import com.adcausalrisk.backend.agent.AgentResult;
import com.adcausalrisk.backend.assessment.AssessmentTaskRepository;
import com.adcausalrisk.backend.assessment.RiskAssessmentRepository;
import com.adcausalrisk.backend.assessment.entity.AssessmentTask;
import com.adcausalrisk.backend.assessment.entity.RiskAssessment;
import com.adcausalrisk.backend.subject.SubjectRepository;
import com.adcausalrisk.backend.subject.entity.Subject;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Review + audit behaviour that only a real transaction and a real security context can show.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}: the review service commits in its own {@code
 * REQUIRES_NEW} transaction (so the mirrored {@code review_status} survives), and the audit aspect
 * writes in another one. A test transaction could neither see those commits nor roll them back, so
 * the fixtures are committed up front and removed in {@link #cleanUp()}.
 *
 * <p>Nothing here mocks {@code AuditService}: the audit row is read back from {@code audit_log}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssessmentReviewApiTest {

    private static final String DEMO_PASSWORD = "DemoPass!2026";
    private static final String SUBJECT_CODE = "DEMO-REV-API";
    private static final String AUDIT_EVENT = "ASSESSMENT_REVIEW";

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private SubjectRepository subjectRepository;

    @Autowired private AssessmentTaskRepository taskRepository;

    @Autowired private RiskAssessmentRepository assessmentRepository;

    @Autowired private JdbcTemplate jdbc;

    private Long subjectId;
    private Long taskId;
    private Long assessmentId;

    @BeforeEach
    void seedAssessedSubject() {
        Subject subject =
                subjectRepository.save(
                        new Subject(
                                SUBJECT_CODE + "-" + UUID.randomUUID().toString().substring(0, 8),
                                "复核接口测试档案",
                                Subject.Status.ACTIVE,
                                "demo-review-api",
                                Instant.now()));
        subjectId = subject.getId();

        AssessmentTask task =
                taskRepository.save(
                        new AssessmentTask(
                                subjectId,
                                "run-review-" + UUID.randomUUID(),
                                "digest",
                                Instant.now()));
        taskId = task.getId();

        RiskAssessment assessment =
                assessmentRepository.save(
                        new RiskAssessment(
                                subjectId,
                                taskId,
                                agentResultJson(),
                                RiskAssessment.QualityStatus.PASS,
                                "demo-review-api",
                                AgentRequest.MODEL_VERSION,
                                AgentResult.WORKFLOW_VERSION,
                                Instant.now()));
        assessmentId = assessment.getId();
    }

    @AfterEach
    void cleanUp() {
        // Foreign keys: review -> assessment -> task -> subject, plus the audit rows the aspect
        // wrote.
        if (assessmentId != null) {
            jdbc.update("DELETE FROM assessment_review WHERE assessment_id = ?", assessmentId);
        }
        jdbc.update("DELETE FROM audit_log WHERE event_type = ?", AUDIT_EVENT);
        if (assessmentId != null) {
            jdbc.update("DELETE FROM risk_assessment WHERE id = ?", assessmentId);
        }
        if (taskId != null) {
            jdbc.update("DELETE FROM assessment_task WHERE id = ?", taskId);
        }
        if (subjectId != null) {
            jdbc.update("DELETE FROM subject WHERE id = ?", subjectId);
        }
    }

    /**
     * Scenario 1: an ACKNOWLEDGED review must be visible in {@code risk_assessment.review_status},
     * which only happens if the service's own transaction really committed.
     */
    @Test
    void acknowledgedReviewUpdatesReviewStatusInTheDatabase() throws Exception {
        MockHttpSession admin = login("admin");

        JsonNode body =
                bodyOf(
                        mockMvc.perform(
                                        post("/api/assessments/" + assessmentId + "/review")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        reviewJson(
                                                                "ACKNOWLEDGED", "已阅，风险分层与随访建议一致"))
                                                .session(admin))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("data").get("assessmentId").asLong()).isEqualTo(assessmentId);
        assertThat(body.get("data").get("decision").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(body.get("data").get("comment").asText()).contains("已阅");

        String reviewStatus =
                jdbc.queryForObject(
                        "SELECT review_status FROM risk_assessment WHERE id = ?",
                        String.class,
                        assessmentId);
        assertThat(reviewStatus)
                .as("the mirror column must be committed")
                .isEqualTo("ACKNOWLEDGED");

        Long reviewRows =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM assessment_review WHERE assessment_id = ? AND"
                                + " reviewer_id IS NOT NULL",
                        Long.class,
                        assessmentId);
        assertThat(reviewRows).isEqualTo(1L);

        // The @AuditLog aspect writes a real row; nothing is mocked here.
        Long auditRows =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_log WHERE event_type = ?",
                        Long.class,
                        AUDIT_EVENT);
        assertThat(auditRows).as("the annotated review must leave an audit row").isEqualTo(1L);
    }

    /**
     * Two reviewers on the same assessment: the second is refused instead of silently overwriting
     * the first decision. Written sequentially on purpose — the guard is a conditional UPDATE, so
     * the same 400 appears whether the two calls are serialised or actually racing.
     */
    @Test
    void secondReviewIsRejectedAsAlreadyReviewed() throws Exception {
        MockHttpSession admin = login("admin");
        MockHttpSession researcher = login("admin");

        mockMvc.perform(
                        post("/api/assessments/" + assessmentId + "/review")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reviewJson("ACKNOWLEDGED", "第一位复核者"))
                                .session(admin))
                .andExpect(status().isOk());

        JsonNode body =
                bodyOf(
                        mockMvc.perform(
                                        post("/api/assessments/" + assessmentId + "/review")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(reviewJson("NEEDS_DATA", "第二位复核者"))
                                                .session(researcher))
                                .andExpect(status().isBadRequest())
                                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("message").asText()).contains("已被他人复核");

        assertThat(
                        jdbc.queryForObject(
                                "SELECT review_status FROM risk_assessment WHERE id = ?",
                                String.class,
                                assessmentId))
                .as("the first decision must survive the refused second review")
                .isEqualTo("ACKNOWLEDGED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM assessment_review WHERE assessment_id = ?",
                                Long.class,
                                assessmentId))
                .as("the refused review must not be inserted")
                .isEqualTo(1L);
    }

    /** Scenario 2: a patient may not review an assessment, and the attempt changes nothing. */
    @Test
    void patientCannotReview() throws Exception {
        MockHttpSession patient = login("patient");

        JsonNode body =
                bodyOf(
                        mockMvc.perform(
                                        post("/api/assessments/" + assessmentId + "/review")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(reviewJson("ACKNOWLEDGED", "患者尝试复核"))
                                                .session(patient))
                                .andExpect(status().isForbidden())
                                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("ACCESS_DENIED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT review_status FROM risk_assessment WHERE id = ?",
                                String.class,
                                assessmentId))
                .as("a refused review must not touch the row")
                .isEqualTo("PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM assessment_review WHERE assessment_id = ?",
                                Long.class,
                                assessmentId))
                .isZero();
    }

    /**
     * Scenario 3: the audit trail is administrator-only; a doctor and a patient are both refused.
     */
    @Test
    void nonAdminCannotReadAuditLogs() throws Exception {
        for (String username : new String[] {"doctor", "patient"}) {
            JsonNode body =
                    bodyOf(
                            mockMvc.perform(get("/api/audit-logs").session(login(username)))
                                    .andExpect(status().isForbidden())
                                    .andReturn());
            assertThat(body.get("errorCode").asText())
                    .as("%s must be refused", username)
                    .isEqualTo("ACCESS_DENIED");
        }

        // The gate is role-based, not "always deny": an administrator gets the list.
        mockMvc.perform(get("/api/audit-logs").session(login("admin"))).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

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

    private String reviewJson(String decision, String comment) throws Exception {
        return objectMapper.writeValueAsString(Map.of("decision", decision, "comment", comment));
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Minimal stored result; the review path never parses it, only the read paths would. */
    private static String agentResultJson() {
        return "{\"runId\":\"run-review\",\"currentStage\":\"EXPLANATION\",\"dataVersion\":\"demo-review-api\","
                   + "\"status\":\"COMPLETED\",\"contractVersion\":\"deepagent-v1\","
                   + "\"workflowVersion\":\"causalrisk-workflow-v1\",\"modelVersion\":\"deepagent-causal-warning-v1\","
                   + "\"qualityStatus\":\"PASS\",\"riskScore\":"
                + new BigDecimal("0.42")
                + ",\"riskLevel\":\"MEDIUM\","
                + "\"warnings\":[],\"graph\":{\"nodes\":[],\"edges\":[],\"paths\":[],\"graphVersion\":\"synthetic-prior-v1\"},"
                + "\"explanations\":[],\"missingModalities\":[],\"limitations\":[],"
                + "\"stages\":[{\"stage\":\"DATA_FUSION\",\"status\":\"COMPLETED\",\"durationMs\":1.0,\"summary\":{}}],"
                + "\"dataSource\":\"SYNTHETIC_DEMO\",\"reviewRequired\":true}";
    }
}
