package com.adcausalrisk.backend.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /api/patients/me/research-summary} - the patient-facing research result boundary.
 *
 * <p>The patient must receive a qualitative sentence and the research-use notice only. A confirmed
 * CIF must never appear as a number, and pending or rejected results must not leak the underlying
 * result JSON.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PatientResearchSummaryApiTest {

    private static final String DEMO_PASSWORD = "DemoPass!2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private JdbcTemplate jdbc;

    private Long subjectId;
    private Long patientId;
    private Long doctorId;
    private Long snapshotId;

    @BeforeEach
    void setUp() {
        Subject subject =
                subjectRepository.save(
                        new Subject(
                                "DEMO-RS-" + UUID.randomUUID().toString().substring(0, 8),
                                "研究摘要测试档案",
                                Subject.Status.ACTIVE,
                                "demo-patient-research-summary",
                                Instant.now()));
        subjectId = subject.getId();
        patientId =
                jdbc.queryForObject(
                        "SELECT id FROM sys_user WHERE username = 'patient'", Long.class);
        doctorId =
                jdbc.queryForObject(
                        "SELECT id FROM sys_user WHERE username = 'doctor'", Long.class);
        jdbc.update(
                "INSERT INTO subject_permission (subject_id, user_id, permission_type)"
                        + " VALUES (?, ?, 'VIEW')",
                subjectId,
                patientId);
        jdbc.update(
                """
                INSERT INTO research_table_snapshot
                  (subject_id,authorized_user_id,request_json,wire_sha256,request_sha256,
                   model_json,data_scope,input_sha256,context_sha256,host_sync_status)
                VALUES (?,?, '{}', ?, ?, '{}', 'CONSTRUCTED_VALIDATION', ?, ?, 'SYNCED')
                """,
                subjectId,
                doctorId,
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
                "d".repeat(64));
        snapshotId =
                jdbc.queryForObject(
                        "SELECT id FROM research_table_snapshot WHERE subject_id = ?"
                                + " ORDER BY id DESC LIMIT 1",
                        Long.class,
                        subjectId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update(
                "DELETE FROM research_table_task WHERE snapshot_id IN"
                        + " (SELECT id FROM research_table_snapshot WHERE subject_id = ?)",
                subjectId);
        jdbc.update("DELETE FROM research_table_snapshot WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM subject_permission WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM subject WHERE id = ?", subjectId);
    }

    @Test
    void confirmedResearchSummaryIsQualitativeAndDeidentified() throws Exception {
        insertTask(
                "CONFIRMED",
                """
                {"dataScope":"CONSTRUCTED_VALIDATION","clinicalUse":false,
                 "admission":{"predictionAllowed":true},
                 "prediction":{"adCif24m":0.31,"otherDementiaCif24m":0.12},
                 "model":{"family":"logistic"},"explanation":{"contributions":[]}}
                """);

        MockHttpSession patient = login("patient");
        JsonNode body = bodyOf(get("/api/patients/me/research-summary"), patient);
        JsonNode data = body.get("data");

        assertThat(data.get("state").asText()).isEqualTo("CONFIRMED");
        assertThat(data.get("summary").asText())
                .contains("医生已复核")
                .contains("偏高")
                .contains("建议咨询医生");
        assertThat(data.get("notice").asText()).contains("研究验证用途").contains("非临床诊断");
        assertThat(data.get("researchUseOnly").asBoolean()).isTrue();
        assertThat(body.toString())
                .doesNotContain("0.31", "0.12", "adCif", "otherDementiaCif", "weights", "graph",
                        "resultJson", "logistic");
    }

    @Test
    void pendingResearchResultDoesNotLeakCif() throws Exception {
        insertTask(
                "PENDING",
                """
                {"admission":{"predictionAllowed":true},
                 "prediction":{"adCif24m":0.31,"otherDementiaCif24m":0.12}}
                """);

        MockHttpSession patient = login("patient");
        JsonNode body = bodyOf(get("/api/patients/me/research-summary"), patient);
        JsonNode data = body.get("data");

        assertThat(data.get("state").asText()).isEqualTo("PENDING");
        assertThat(data.get("summary").asText()).contains("等待医生复核");
        assertThat(body.toString()).doesNotContain("0.31", "0.12", "adCif", "otherDementiaCif");
    }

    @Test
    void noResearchTaskReturnsPlainEmptyState() throws Exception {
        MockHttpSession patient = login("patient");
        JsonNode body = bodyOf(get("/api/patients/me/research-summary"), patient);

        assertThat(body.get("data").get("state").asText()).isEqualTo("NO_TASK");
        assertThat(body.get("data").get("summary").asText()).contains("目前还没有");
    }

    @Test
    void nonPatientRoleIsDenied() throws Exception {
        MockHttpSession doctor = login("doctor");
        mockMvc.perform(get("/api/patients/me/research-summary").session(doctor))
                .andExpect(status().isForbidden());
    }

    private void insertTask(String reviewStatus, String resultJson) {
        jdbc.update(
                """
                INSERT INTO research_table_task
                  (snapshot_id,run_id,status,error_code,result_json,created_at,updated_at,
                   execution_id,review_status,reviewed_by,reviewed_at,review_comment,
                   executor_id,lease_expires_at)
                VALUES (?,?, 'COMPLETED', NULL, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        NULL, ?, NULL, CURRENT_TIMESTAMP, '测试复核', NULL, NULL)
                """,
                snapshotId,
                "run-patient-research-" + reviewStatus,
                resultJson,
                reviewStatus);
    }

    private JsonNode bodyOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode bodyOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            MockHttpSession session)
            throws Exception {
        MvcResult result = mockMvc.perform(request.session(session)).andExpect(status().isOk()).andReturn();
        return bodyOf(result);
    }

    private MockHttpSession login(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(
                        post("/api/auth/login")
                                .session(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "username", username,
                                                        "password", DEMO_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return session;
    }
}
