package com.adcausalrisk.backend.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
        jdbc.update("DELETE FROM patient_research_publication WHERE task_id IN (SELECT id FROM research_table_task WHERE snapshot_id=?)",
                snapshotId);
        jdbc.update(
                "DELETE FROM research_table_task WHERE snapshot_id IN"
                        + " (SELECT id FROM research_table_snapshot WHERE subject_id = ?)",
                subjectId);
        jdbc.update("DELETE FROM research_table_snapshot WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM subject_permission WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM subject WHERE id = ?", subjectId);
    }

    @Test
    void patientSeesOnlyExplicitlyPublishedDoctorMessageAndWithdrawalPreservesReview() throws Exception {
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
        assertThat(data.get("summary").asText()).contains("医生已完成复核").doesNotContain("偏高");
        assertThat(data.path("publishedMessage").isNull()).isTrue();
        assertThat(data.get("notice").asText()).contains("研究验证用途").contains("非临床诊断");
        assertThat(data.get("researchUseOnly").asBoolean()).isTrue();
        assertThat(body.toString())
                .doesNotContain("0.31", "0.12", "adCif", "otherDementiaCif", "weights", "graph",
                        "resultJson", "logistic");
        jdbc.update("INSERT INTO subject_permission(subject_id,user_id,permission_type) VALUES(?,?,'MANAGE')",
                subjectId, doctorId);
        long id = jdbc.queryForObject("SELECT id FROM research_table_task WHERE snapshot_id=?", Long.class, snapshotId);
        String publication = "/api/research/table-assessment-tasks/" + id + "/patient-publication";
        String message = "复诊时请带上已经保存的检查记录。";
        String payload = objectMapper.writeValueAsString(Map.of("message", message));
        mockMvc.perform(put(publication).session(patient).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isForbidden());
        MockHttpSession doctor = login("doctor");
        assertThat(bodyOf(put(publication).contentType(MediaType.APPLICATION_JSON).content(payload), doctor)
                .at("/data/published").asBoolean()).isTrue();
        assertThat(bodyOf(get("/api/patients/me/research-summary"), patient).at("/data/publishedMessage").asText())
                .isEqualTo(message);
        assertThat(bodyOf(delete(publication), doctor).at("/data/published").asBoolean()).isFalse();
        var withdrawn = bodyOf(get("/api/patients/me/research-summary"), patient);
        assertThat(withdrawn.at("/data/publishedMessage").isNull()).isTrue();
        assertThat(withdrawn.at("/data/state").asText()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM patient_research_publication WHERE task_id=?",
                Integer.class, id)).isEqualTo(2);
    }

    @Test
    void confirmedButNonPredictiveResultKeepsReviewedStateAndLimitation() throws Exception {
        insertTask(
                "CONFIRMED",
                """
                {"dataScope":"CONSTRUCTED_VALIDATION","clinicalUse":false,
                 "admission":{"predictionAllowed":false},
                 "prediction":{"adCif24m":0.31,"otherDementiaCif24m":0.12}}
                """);

        MockHttpSession patient = login("patient");
        JsonNode body = bodyOf(get("/api/patients/me/research-summary"), patient);
        JsonNode data = body.get("data");

        assertThat(data.get("state").asText()).isEqualTo("CONFIRMED");
        assertThat(data.get("summary").asText())
                .contains("医生已完成复核")
                .doesNotContain("偏高");
        assertThat(data.get("notice").asText()).contains("研究验证用途").contains("非临床诊断");
        assertThat(body.toString()).doesNotContain("0.31", "0.12", "adCif", "otherDementiaCif");
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
    void anUnevaluatedControlledSnapshotPreventsFallingBackToADemoResult() throws Exception {
        insertTask("CONFIRMED", """
                {"admission":{"predictionAllowed":true},"prediction":{"adCif24m":0.31}}
                """);
        jdbc.update("""
                INSERT INTO research_table_snapshot(subject_id,authorized_user_id,request_json,wire_sha256,
                  request_sha256,model_json,data_scope,input_sha256,context_sha256,host_sync_status)
                VALUES (?,?,'{}',?,?,'{}','CONTROLLED_RESEARCH',?,?,'SYNCED')
                """, subjectId, doctorId, "e".repeat(64), "f".repeat(64), "1".repeat(64), "2".repeat(64));

        JsonNode body = bodyOf(get("/api/patients/me/research-summary"), login("patient"));
        assertThat(body.at("/data/state").asText()).isEqualTo("NO_TASK");
        assertThat(body.toString()).doesNotContain("测试样例", "偏高");
    }

    @Test
    void noResearchTaskReturnsPlainEmptyState() throws Exception {
        MockHttpSession patient = login("patient");
        JsonNode body = bodyOf(get("/api/patients/me/research-summary"), patient);

        assertThat(body.get("data").get("state").asText()).isEqualTo("NO_TASK");
        assertThat(body.get("data").get("summary").asText()).contains("目前还没有");
    }

    @Test
    void ordinaryCareRehearsalDoesNotAppearOutsideItsIsolatedProfile() throws Exception {
        jdbc.update("UPDATE subject SET subject_code=?,data_version='care-test' WHERE id=?",
                "OP-SUMMARY-" + UUID.randomUUID(), subjectId);
        jdbc.update("UPDATE research_table_snapshot SET doctor_handoff=TRUE WHERE id=?", snapshotId);
        insertTask("CONFIRMED", """
                {"prediction":{"adCif24m":0.31},"clinicalUse":false}
                """);
        JsonNode body = bodyOf(get("/api/patients/me/research-summary"), login("patient"));
        assertThat(body.at("/data/state").asText()).isEqualTo("NO_TASK");
        assertThat(body.toString()).doesNotContain("adCif24m", "0.31");
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
