package com.adcausalrisk.backend.patient;

import static org.assertj.core.api.Assertions.assertThat;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 患者端 P0 三项：授权中心、健康时间轴、随访自助取消。
 *
 * <p>测试重点是边界而不是页面：授权记录只追加、撤回后最新状态生效；时间轴只给患者已复核的
 * 结论措辞，不出现原始分数；取消必须携带患者看到的那一版安排，否则拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PatientConsentTimelineApiTest {

    private static final String DEMO_PASSWORD = "DemoPass!2026";
    private static final String SUBJECT_CODE = "DEMO-PAT-CONSENT";

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private SubjectRepository subjectRepository;

    @Autowired private JdbcTemplate jdbc;

    private Long subjectId;
    private Long patientId;

    @BeforeEach
    void setUp() {
        Subject subject =
                subjectRepository.save(
                        new Subject(
                                SUBJECT_CODE,
                                "患者授权测试档案",
                                Subject.Status.ACTIVE,
                                "demo-patient-consent",
                                Instant.now()));
        subjectId = subject.getId();
        patientId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'patient'", Long.class);
        jdbc.update(
                "INSERT INTO subject_permission (subject_id, user_id, permission_type)"
                        + " VALUES (?, ?, 'VIEW')",
                subjectId,
                patientId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM follow_up_response WHERE follow_up_id IN"
                + " (SELECT id FROM follow_up_plan WHERE subject_id = ?)", subjectId);
        jdbc.update("DELETE FROM follow_up_plan WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM patient_consent_record WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM risk_assessment WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM assessment_task WHERE subject_id = ?", subjectId);
        jdbc.update("UPDATE visit SET replaces_id = NULL WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM visit WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM subject_permission WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM subject WHERE id = ?", subjectId);
        jdbc.update("DELETE FROM audit_log WHERE actor_id = ? AND event_type IN"
                + " ('PATIENT_CONSENT', 'PATIENT_FOLLOW_UP_CANCEL')", patientId);
    }

    @Test
    void consentStartsUndecidedThenRecordsEveryChange() throws Exception {
        MockHttpSession patient = login("patient");

        JsonNode initial =
                bodyOf(mockMvc.perform(get("/api/patients/me/consent").session(patient))
                        .andExpect(status().isOk())
                        .andReturn()).get("data");
        assertThat(initial.get("bound").asBoolean()).isTrue();
        assertThat(initial.get("decided").asBoolean()).isFalse();
        assertThat(initial.get("history")).isEmpty();

        JsonNode granted = saveConsent(patient, true, true);
        assertThat(granted.get("decided").asBoolean()).isTrue();
        assertThat(granted.get("researchUse").asBoolean()).isTrue();
        assertThat(granted.get("followUpContact").asBoolean()).isTrue();
        assertThat(granted.get("history")).hasSize(1);

        JsonNode withdrawn = saveConsent(patient, false, true);
        assertThat(withdrawn.get("researchUse").asBoolean()).isFalse();
        assertThat(withdrawn.get("followUpContact").asBoolean()).isTrue();
        assertThat(withdrawn.get("history")).hasSize(2);
        assertThat(withdrawn.get("history").get(0).get("researchUse").asBoolean()).isFalse();
        assertThat(withdrawn.get("history").get(1).get("researchUse").asBoolean()).isTrue();
    }

    @Test
    void consentRequiresBothFlagsAndPatientRole() throws Exception {
        MockHttpSession patient = login("patient");
        mockMvc.perform(
                        put("/api/patients/me/consent")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("researchUse", true)))
                                .session(patient))
                .andExpect(status().isBadRequest());

        MockHttpSession doctor = login("doctor");
        mockMvc.perform(get("/api/patients/me/consent").session(doctor))
                .andExpect(status().isForbidden());
    }

    @Test
    void timelineListsVisitAndFollowUpWithoutRawAssessmentPayload() throws Exception {
        jdbc.update(
                "INSERT INTO visit (subject_id, visit_code, visit_date, mmse, raw_mmse, is_current, version_no)"
                        + " VALUES (?, ?, ?, ?, ?, TRUE, 1)",
                subjectId,
                "V1",
                java.sql.Date.valueOf("2026-03-01"),
                new java.math.BigDecimal("27.5"),
                "27.5");
        jdbc.update(
                "INSERT INTO follow_up_plan (subject_id, scheduled_at, purpose, status, owner_id,"
                        + " visit_location, preparation)"
                        + " VALUES (?, ?, ?, 'PLANNED', ?, ?, ?)",
                subjectId,
                java.sql.Timestamp.from(Instant.now().plusSeconds(86_400 * 20)),
                "认知复查",
                patientId,
                "门诊三楼",
                "带上既往检查报告");

        MockHttpSession patient = login("patient");
        JsonNode data =
                bodyOf(mockMvc.perform(get("/api/patients/me/timeline").session(patient))
                        .andExpect(status().isOk())
                        .andReturn()).get("data");

        assertThat(data.get("bound").asBoolean()).isTrue();
        assertThat(data.get("subjectCode").asText()).isEqualTo(SUBJECT_CODE);
        JsonNode events = data.get("events");
        assertThat(events).hasSize(2);

        JsonNode visit = events.get(0);
        assertThat(visit.get("type").asText()).isEqualTo("FOLLOW_UP");
        assertThat(visit.get("detail").asText()).contains("门诊三楼").contains("带上既往检查报告");

        JsonNode visitRow = events.get(1);
        assertThat(visitRow.get("type").asText()).isEqualTo("VISIT");
        assertThat(visitRow.get("title").asText()).isEqualTo("访视 V1");
        assertThat(visitRow.get("values")).as("患者账号不得读到原始量表值").isEmpty();
        assertThat(events.toString()).as("原始量表值不得出现在时间轴").doesNotContain("27.5");

        JsonNode detail =
                bodyOf(mockMvc.perform(get("/api/subjects/{id}", subjectId).session(patient))
                        .andExpect(status().isOk())
                        .andReturn()).get("data");
        assertThat(detail.get("visits")).as("档案详情与时间轴必须用同一条权限规则").isEmpty();
    }

    @Test
    void cancelFollowUpRequiresTheRevisionThePatientSaw() throws Exception {
        long followUpId = plannedFollowUp();

        MockHttpSession patient = login("patient");

        JsonNode cancelled =
                bodyOf(mockMvc.perform(
                                post("/api/patients/me/follow-up/{id}/cancel", followUpId)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(Map.of("appointmentRevision", 0)))
                                        .session(patient))
                        .andExpect(status().isOk())
                        .andReturn()).get("data");
        assertThat(cancelled.get("next").isNull()).isTrue();
        JsonNode appointments = cancelled.get("appointments");
        assertThat(appointments).hasSize(1);
        assertThat(appointments.get(0).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(appointments.get(0).get("canRespond").asBoolean()).isFalse();

        String status =
                jdbc.queryForObject(
                        "SELECT status FROM follow_up_plan WHERE id = ?", String.class, followUpId);
        assertThat(status).isEqualTo("CANCELLED");

        mockMvc.perform(
                        post("/api/patients/me/follow-up/{id}/cancel", followUpId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("appointmentRevision", 0)))
                                .session(patient))
                .andExpect(status().isConflict());
    }

    @Test
    void cancelFollowUpRefusesAStaleRevision() throws Exception {
        long followUpId = plannedFollowUp();

        MockHttpSession patient = login("patient");
        mockMvc.perform(
                        post("/api/patients/me/follow-up/{id}/cancel", followUpId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("appointmentRevision", 7)))
                                .session(patient))
                .andExpect(status().isConflict());

        String status =
                jdbc.queryForObject(
                        "SELECT status FROM follow_up_plan WHERE id = ?", String.class, followUpId);
        assertThat(status).isEqualTo("PLANNED");
    }

    @Test
    void consentHistoryKeepsEveryDecisionInReverseOrderAndIsAudited() throws Exception {
        MockHttpSession patient = login("patient");

        saveConsent(patient, true, true);
        saveConsent(patient, false, true);
        JsonNode latest = saveConsent(patient, false, false);

        JsonNode history = latest.get("history");
        assertThat(history).hasSize(3);
        assertThat(history.get(0).get("researchUse").asBoolean()).isFalse();
        assertThat(history.get(0).get("followUpContact").asBoolean()).isFalse();
        assertThat(history.get(1).get("researchUse").asBoolean()).isFalse();
        assertThat(history.get(1).get("followUpContact").asBoolean()).isTrue();
        assertThat(history.get(2).get("researchUse").asBoolean()).isTrue();
        assertThat(history.get(2).get("followUpContact").asBoolean()).isTrue();

        assertThat(latest.get("decided").asBoolean()).isTrue();
        assertThat(latest.get("researchUse").asBoolean()).as("当前状态取最新一行").isFalse();
        assertThat(latest.get("updatedAt").isNull()).isFalse();

        Integer audits =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_log WHERE actor_id = ? AND event_type = 'PATIENT_CONSENT'",
                        Integer.class,
                        patientId);
        assertThat(audits).as("每次授权变更都要留下一条审计").isEqualTo(3);
    }

    @Test
    void correctedVisitAppearsOnceWithItsVersionLabel() throws Exception {
        jdbc.update(
                "INSERT INTO visit (subject_id, visit_code, visit_date, mmse, raw_mmse, is_current, version_no)"
                        + " VALUES (?, ?, ?, ?, ?, FALSE, 1)",
                subjectId,
                "V1",
                java.sql.Date.valueOf("2026-03-01"),
                new java.math.BigDecimal("27.5"),
                "27.5");
        Long superseded =
                jdbc.queryForObject(
                        "SELECT id FROM visit WHERE subject_id = ? AND version_no = 1",
                        Long.class,
                        subjectId);
        jdbc.update(
                "INSERT INTO visit (subject_id, visit_code, visit_date, mmse, raw_mmse, is_current,"
                        + " version_no, replaces_id)"
                        + " VALUES (?, ?, ?, ?, ?, TRUE, 2, ?)",
                subjectId,
                "V1",
                java.sql.Date.valueOf("2026-03-01"),
                new java.math.BigDecimal("29.0"),
                "29.0",
                superseded);

        MockHttpSession patient = login("patient");
        JsonNode events =
                bodyOf(mockMvc.perform(get("/api/patients/me/timeline").session(patient))
                        .andExpect(status().isOk())
                        .andReturn()).get("data").get("events");

        assertThat(events).as("被更正的旧版本不得在时间线上重复出现").hasSize(1);
        JsonNode visit = events.get(0);
        assertThat(visit.get("status").asText()).isEqualTo("已更正（第 2 版）");
        assertThat(visit.get("values")).as("更正后仍不得携带原始量表值").isEmpty();
        assertThat(events.toString()).as("新旧版本的原始值都不应外泄")
                .doesNotContain("27.5").doesNotContain("29.0");
    }

    @Test
    void timelineKeepsOnlyTheNewestHundredEvents() throws Exception {
        for (int i = 1; i <= 101; i++) {
            jdbc.update(
                    "INSERT INTO visit (subject_id, visit_code, visit_date, mmse, raw_mmse, is_current,"
                            + " version_no) VALUES (?, ?, ?, ?, ?, TRUE, 1)",
                    subjectId,
                    "V" + i,
                    java.sql.Date.valueOf(java.time.LocalDate.of(2026, 1, 1).plusDays(i)),
                    new java.math.BigDecimal("27.0"),
                    "27.0");
        }

        MockHttpSession patient = login("patient");
        JsonNode events =
                bodyOf(mockMvc.perform(get("/api/patients/me/timeline").session(patient))
                        .andExpect(status().isOk())
                        .andReturn()).get("data").get("events");

        assertThat(events).hasSize(100);
        assertThat(events.get(0).get("title").asText()).as("最新的排在最前").isEqualTo("访视 V101");
        assertThat(events.toString()).as("最老的一条被截断").doesNotContain("访视 V1\"");
    }

    /**
     * 同一条评估在首页与时间轴必须是同一句话。两个页面各自拼文案时，患者会看到两种说法，
     * 这是最容易在演示里被抓住的不一致。
     */
    @Test
    void summaryAndTimelineUseTheSameConclusionWording() throws Exception {
        insertAcknowledgedAssessment();

        MockHttpSession patient = login("patient");
        String summary =
                bodyOf(mockMvc.perform(get("/api/patients/me/summary").session(patient))
                        .andExpect(status().isOk())
                        .andReturn()).get("data").get("summary").asText();

        JsonNode events =
                bodyOf(mockMvc.perform(get("/api/patients/me/timeline").session(patient))
                        .andExpect(status().isOk())
                        .andReturn()).get("data").get("events");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("type").asText()).isEqualTo("ASSESSMENT");
        assertThat(events.get(0).get("detail").asText()).isEqualTo(summary);

        assertThat(summary).contains("验证排序较高").contains("不能判断您的真实患病风险");
        assertThat(summary).as("患者端不出现原始分数").doesNotContain("0.91");
        assertThat(events.toString()).doesNotContain("0.91").doesNotContain("riskScore");
    }

    /** 未绑定档案时三个页面都必须给出「未绑定」的空态，而不是 500 或空白页。 */
    @Test
    void unboundPatientGetsExplicitEmptyStates() throws Exception {
        MockHttpSession patient = login("patient");
        List<Map<String, Object>> saved =
                jdbc.queryForList(
                        "SELECT subject_id, permission_type FROM subject_permission WHERE user_id = ?",
                        patientId);
        try {
            jdbc.update("DELETE FROM subject_permission WHERE user_id = ?", patientId);

            JsonNode consent =
                    bodyOf(mockMvc.perform(get("/api/patients/me/consent").session(patient))
                            .andExpect(status().isOk())
                            .andReturn()).get("data");
            assertThat(consent.get("bound").asBoolean()).isFalse();
            assertThat(consent.get("decided").asBoolean()).isFalse();
            assertThat(consent.get("history")).isEmpty();

            mockMvc.perform(
                            put("/api/patients/me/consent")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(
                                            Map.of("researchUse", true, "followUpContact", false)))
                                    .session(patient))
                    .andExpect(status().isBadRequest());

            JsonNode timeline =
                    bodyOf(mockMvc.perform(get("/api/patients/me/timeline").session(patient))
                            .andExpect(status().isOk())
                            .andReturn()).get("data");
            assertThat(timeline.get("bound").asBoolean()).isFalse();
            assertThat(timeline.get("events")).isEmpty();

            JsonNode followUp =
                    bodyOf(mockMvc.perform(get("/api/patients/me/follow-up").session(patient))
                            .andExpect(status().isOk())
                            .andReturn()).get("data");
            assertThat(followUp.get("bound").asBoolean()).isFalse();
            assertThat(followUp.get("appointments")).isEmpty();
        } finally {
            for (Map<String, Object> row : saved) {
                jdbc.update(
                        "INSERT INTO subject_permission (subject_id, user_id, permission_type)"
                                + " VALUES (?, ?, ?)",
                        row.get("subject_id"),
                        patientId,
                        row.get("permission_type"));
            }
        }
    }

    private void insertAcknowledgedAssessment() throws Exception {
        java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
        jdbc.update(
                "INSERT INTO assessment_task (subject_id, status, request_digest, created_at, updated_at)"
                        + " VALUES (?, 'COMPLETED', ?, ?, ?)",
                subjectId,
                "timeline-consistency",
                now,
                now);
        Long taskId =
                jdbc.queryForObject(
                        "SELECT id FROM assessment_task WHERE subject_id = ? ORDER BY id DESC LIMIT 1",
                        Long.class,
                        subjectId);
        String agentResult =
                "{\"runId\":\"run-timeline\",\"currentStage\":\"EXPLANATION\",\"dataVersion\":\"v1\","
                        + "\"status\":\"COMPLETED\",\"contractVersion\":\"deepagent-v1\","
                        + "\"workflowVersion\":\"causalrisk-workflow-v1\","
                        + "\"modelVersion\":\"deepagent-causal-warning-v1\",\"qualityStatus\":\"PASS\","
                        + "\"riskScore\":0.91,\"riskLevel\":\"HIGH\",\"warnings\":[],"
                        + "\"graph\":{\"nodes\":[],\"edges\":[],\"paths\":[],"
                        + "\"graphVersion\":\"synthetic-prior-v1\"},\"explanations\":[],"
                        + "\"missingModalities\":[],\"limitations\":[],\"stages\":[],"
                        + "\"dataSource\":\"SYNTHETIC_DEMO\",\"reviewRequired\":true}";
        jdbc.update(
                "INSERT INTO risk_assessment (subject_id, task_id, agent_result_json, review_status,"
                        + " created_at, data_version, model_version, workflow_version, quality_status)"
                        + " VALUES (?, ?, ?, 'ACKNOWLEDGED', ?, ?, ?, ?, 'PASS')",
                subjectId,
                taskId,
                agentResult,
                now,
                "v1",
                "deepagent-causal-warning-v1",
                "causalrisk-workflow-v1");
    }

    private long plannedFollowUp() {
        jdbc.update(
                "INSERT INTO follow_up_plan (subject_id, scheduled_at, purpose, status, owner_id)"
                        + " VALUES (?, ?, ?, 'PLANNED', ?)",
                subjectId,
                java.sql.Timestamp.from(Instant.now().plusSeconds(86_400 * 10)),
                "认知复查",
                patientId);
        return jdbc.queryForObject(
                "SELECT id FROM follow_up_plan WHERE subject_id = ? ORDER BY id DESC LIMIT 1",
                Long.class,
                subjectId);
    }

    private JsonNode saveConsent(MockHttpSession session, boolean researchUse, boolean followUpContact)
            throws Exception {
        return bodyOf(mockMvc.perform(
                        put("/api/patients/me/consent")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("researchUse", researchUse, "followUpContact", followUpContact)))
                                .session(session))
                .andExpect(status().isOk())
                .andReturn()).get("data");
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(
                                                Map.of("username", username, "password", DEMO_PASSWORD))))
                        .andExpect(status().isOk())
                        .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).as("login %s must establish a session", username).isNotNull();
        return session;
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
