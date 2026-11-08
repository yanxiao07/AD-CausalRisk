package com.adcausalrisk.backend.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.adcausalrisk.backend.agent.AgentRequest;
import com.adcausalrisk.backend.agent.AgentResult;
import com.adcausalrisk.backend.agent.RestAgentClient;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/patients/me/summary} - the de-identified patient view.
 *
 * <p>The point of these tests is the boundary: the patient gets a plain-language conclusion and a
 * follow-up sentence, and never the raw score, the graph or the explanation payload. Any other role
 * is refused outright.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PatientSummaryApiTest {

    private static final String DEMO_PASSWORD = "DemoPass!2026";
    private static final String SUBJECT_CODE = "DEMO-PAT-SUM";
    private static final String DATA_VERSION = "demo-patient-summary";

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private SubjectRepository subjectRepository;

    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private RestAgentClient agentClient;

    private Long subjectId;
    private Long patientId;
    private Long adminId;
    private Long assessmentId;

    @BeforeEach
    void setUp() throws Exception {
        Subject subject =
                subjectRepository.save(
                        new Subject(
                                SUBJECT_CODE,
                                "患者摘要测试档案",
                                Subject.Status.ACTIVE,
                                DATA_VERSION,
                                Instant.now()));
        subjectId = subject.getId();
        patientId =
                jdbc.queryForObject(
                        "SELECT id FROM sys_user WHERE username = 'patient'", Long.class);
        adminId =
                jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
        jdbc.update(
                "INSERT INTO subject_permission (subject_id, user_id, permission_type)"
                        + " VALUES (?, ?, 'VIEW')",
                subjectId,
                patientId);

        when(agentClient.createRun(any(AgentRequest.class))).thenReturn(completedResult("0.91"));
        MockHttpSession admin = login("admin");
        JsonNode created =
                bodyOf(
                        mockMvc.perform(
                                        post("/api/assessment-tasks")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .header(
                                                        "Idempotency-Key",
                                                        "patient-summary-" + subjectId)
                                                .content(
                                                        objectMapper.writeValueAsString(
                                                                Map.of(
                                                                        "subjectId",
                                                                        subjectId,
                                                                        "modelVersion",
                                                                        AgentRequest.MODEL_VERSION,
                                                                        "predictionWindowMonths",
                                                                        24)))
                                                .session(admin))
                                .andExpect(status().isOk())
                                .andReturn());
        assessmentId = created.get("data").get("assessmentId").asLong();
    }

    @AfterEach
    void cleanUp() {
        // Children first: every FK towards subject is ON DELETE RESTRICT.
        jdbc.update("DELETE FROM follow_up_plan WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM subject_permission WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM risk_assessment WHERE subject_id = ?", subjectId);
        jdbc.update(
                "DELETE FROM task_input_snapshot WHERE task_id IN (SELECT id FROM assessment_task"
                        + " WHERE subject_id = ?)",
                subjectId);
        jdbc.update("DELETE FROM quality_check WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM assessment_task WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM subject WHERE id = ?", subjectId);
        jdbc.update("DELETE FROM idempotency_record WHERE operation = ?", "ASSESSMENT_TASK_CREATE");
    }

    @Test
    void pendingAssessmentIsSummarisedWithoutScore() throws Exception {
        MockHttpSession patient = login("patient");

        JsonNode body =
                bodyOf(
                        mockMvc.perform(get("/api/patients/me/summary").session(patient))
                                .andExpect(status().isOk())
                                .andReturn());

        JsonNode data = body.get("data");
        assertThat(data.get("hasAssessment").asBoolean()).isTrue();
        assertThat(data.get("pendingReview").asBoolean()).isTrue();
        assertThat(data.get("assessmentId").asLong()).isEqualTo(assessmentId);
        assertThat(data.get("subjectCode").asText()).isEqualTo(SUBJECT_CODE);
        assertThat(data.get("summary").asText()).contains("等待医生复核");
        // De-identified: no raw score, no level, no graph.
        assertThat(data.get("summary").asText()).doesNotContain("0.91");
        assertThat(body.toString())
                .doesNotContain("riskScore")
                .doesNotContain("agentResult")
                .doesNotContain("graph");
        assertThat(data.get("limitations")).isNotEmpty();
    }

    /**
     * A bound subject with no assessment yet must be a normal empty page, not a 500.
     *
     * <p>This is the regression that {@code Map.entry(id, null)} caused: the map factory rejects
     * null values, so the "no report" branch threw before it could be filtered out.
     */
    @Test
    void boundPatientWithoutAssessmentGets200() throws Exception {
        List<Map<String, Object>> saved =
                jdbc.queryForList(
                        "SELECT subject_id, permission_type FROM subject_permission WHERE user_id ="
                                + " ?",
                        patientId);
        Subject fresh =
                subjectRepository.save(
                        new Subject(
                                "DEMO-PAT-EMPTY",
                                "无评估档案",
                                Subject.Status.ACTIVE,
                                "demo-empty",
                                Instant.now()));
        try {
            jdbc.update("DELETE FROM subject_permission WHERE user_id = ?", patientId);
            jdbc.update(
                    "INSERT INTO subject_permission (subject_id, user_id, permission_type)"
                            + " VALUES (?, ?, 'VIEW')",
                    fresh.getId(),
                    patientId);

            MockHttpSession patient = login("patient");
            JsonNode body =
                    bodyOf(
                            mockMvc.perform(get("/api/patients/me/summary").session(patient))
                                    .andExpect(status().isOk())
                                    .andReturn());

            JsonNode data = body.get("data");
            assertThat(data.get("hasAssessment").asBoolean()).isFalse();
            assertThat(data.get("assessmentId").isNull()).isTrue();
            assertThat(data.get("subjectCode").asText()).isEqualTo("DEMO-PAT-EMPTY");
            assertThat(data.get("summary").asText()).contains("还没有风险评估记录");
            assertThat(data.get("followUpSummary").asText()).contains("没有已安排的复诊");
        } finally {
            jdbc.update("DELETE FROM subject_permission WHERE subject_id = ?", fresh.getId());
            for (Map<String, Object> row : saved) {
                jdbc.update(
                        "INSERT INTO subject_permission (subject_id, user_id, permission_type)"
                                + " VALUES (?, ?, ?)",
                        row.get("subject_id"),
                        patientId,
                        row.get("permission_type"));
            }
            jdbc.update("DELETE FROM subject WHERE id = ?", fresh.getId());
        }
    }

    @Test
    void acknowledgedHighRiskSummaryExplainsLevelWithoutScore() throws Exception {
        jdbc.update(
                "UPDATE risk_assessment SET review_status = 'ACKNOWLEDGED' WHERE id = ?",
                assessmentId);
        MockHttpSession patient = login("patient");

        JsonNode data =
                bodyOf(
                                mockMvc.perform(get("/api/patients/me/summary").session(patient))
                                        .andExpect(status().isOk())
                                        .andReturn())
                        .get("data");

        assertThat(data.get("pendingReview").asBoolean()).isFalse();
        assertThat(data.get("summary").asText()).contains("演示排序较高").contains("不能判断您的真实患病风险");
        assertThat(data.get("summary").asText()).doesNotContain("0.91");
    }

    @Test
    void scheduledFollowUpAppearsInSummary() throws Exception {
        jdbc.update(
                "INSERT INTO follow_up_plan (subject_id, scheduled_at, purpose, status, owner_id)"
                        + " VALUES (?, ?, ?, 'PLANNED', ?)",
                subjectId,
                java.sql.Timestamp.from(Instant.now().plusSeconds(86_400 * 30)),
                "认知复查",
                adminId);
        MockHttpSession patient = login("patient");

        JsonNode data =
                bodyOf(
                                mockMvc.perform(get("/api/patients/me/summary").session(patient))
                                        .andExpect(status().isOk())
                                        .andReturn())
                        .get("data");

        assertThat(data.get("followUpSummary").asText()).contains("已为您安排").contains("复诊");
    }

    @Test
    void nonPatientRoleIsForbidden() throws Exception {
        MockHttpSession doctor = login("doctor");

        JsonNode body =
                bodyOf(
                        mockMvc.perform(get("/api/patients/me/summary").session(doctor))
                                .andExpect(status().isForbidden())
                                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("ACCESS_DENIED");
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

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static AgentResult completedResult(String riskScore) {
        return new AgentResult(
                "run-mock",
                "EXPLANATION",
                DATA_VERSION,
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
                List.of("结论基于既往数据，不能替代医生面诊"),
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
