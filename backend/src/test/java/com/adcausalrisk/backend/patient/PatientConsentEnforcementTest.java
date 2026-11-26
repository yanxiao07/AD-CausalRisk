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
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 授权中心的执行效果：患者撤回后研究侧必须真的停下来，而不是只改一行记录。
 *
 * <p>覆盖四件事：撤回科研使用后管理员不能再新增验证队列授权、研究侧读不到该档案；
 * 科研随访同时受「科研使用」与「随访联系」约束，只关闭其中一个也不能写入或读取结局；
 * 临床复诊属于诊疗需要，两个开关都关闭也不受影响。同时保留「从未表态」的兼容路径：
 * 没有记录时仍然沿用医院既有的伦理与书面授权流程，不能静默阻断。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PatientConsentEnforcementTest {

    private static final String DEMO_PASSWORD = "DemoPass!2026";
    private static final String SUBJECT_CODE = "CONSENT-GATE-001";
    private static final String PROJECT = "CONSENT-GATE";

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private SubjectRepository subjectRepository;

    @Autowired private JdbcTemplate jdbc;

    private Long subjectId;
    private Long patientId;
    private Long doctorId;
    private Long auditIdFloor;
    private List<Map<String, Object>> patientPermissions;

    @BeforeEach
    void setUp() {
        auditIdFloor = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM audit_log", Long.class);
        Subject subject =
                subjectRepository.save(
                        new Subject(
                                SUBJECT_CODE,
                                "授权执行测试档案",
                                Subject.Status.ACTIVE,
                                "care-demo-consent-gate",
                                Instant.now()));
        subjectId = subject.getId();
        patientId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'patient'", Long.class);
        doctorId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'doctor'", Long.class);
        patientPermissions =
                jdbc.queryForList(
                        "SELECT subject_id, permission_type FROM subject_permission WHERE user_id = ?",
                        patientId);
        jdbc.update("DELETE FROM subject_permission WHERE user_id = ?", patientId);
        jdbc.update(
                "INSERT INTO subject_permission (subject_id, user_id, permission_type)"
                        + " VALUES (?, ?, 'VIEW')",
                subjectId,
                patientId);
        jdbc.update(
                "INSERT INTO subject_permission (subject_id, user_id, permission_type)"
                        + " VALUES (?, ?, 'MANAGE')",
                subjectId,
                doctorId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM validation_cohort_enrollment WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM validation_follow_up WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM follow_up_response WHERE follow_up_id IN"
                + " (SELECT id FROM follow_up_plan WHERE subject_id = ?)", subjectId);
        jdbc.update("DELETE FROM follow_up_plan WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM patient_consent_record WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM subject_permission WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM subject WHERE id = ?", subjectId);
        jdbc.update("DELETE FROM audit_log WHERE id > ?", auditIdFloor);
        for (Map<String, Object> row : patientPermissions) {
            jdbc.update(
                    "INSERT INTO subject_permission (subject_id, user_id, permission_type)"
                            + " VALUES (?, ?, ?)",
                    row.get("subject_id"),
                    patientId,
                    row.get("permission_type"));
        }
    }

    /** 从未表态不等于拒绝：没有记录时管理员仍可按医院书面授权流程登记。 */
    @Test
    void undecidedPatientKeepsExistingGrantFlow() throws Exception {
        grantAsAdmin().andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM validation_cohort_enrollment WHERE subject_id = ?",
                                Integer.class,
                                subjectId))
                .isEqualTo(1);
    }

    /** 撤回科研使用后立即拒绝新增队列授权，并从研究侧读取里消失；重新开启后恢复，历史不被覆盖。 */
    @Test
    void withdrawnResearchUseBlocksNewGrantAndHidesExistingEnrollment() throws Exception {
        MockHttpSession patient = login("patient");
        MockHttpSession researcher = login("researcher");
        grantAsAdmin().andExpect(status().isOk());
        assertThat(cohortTotal(researcher)).as("授权期内可读").isEqualTo(1);

        saveConsent(patient, false, true).andExpect(status().isOk());

        MvcResult refused = grantAsAdmin().andExpect(status().isBadRequest()).andReturn();
        assertThat(refused.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("撤回科研使用授权");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM validation_cohort_enrollment WHERE subject_id = ?",
                                Integer.class,
                                subjectId))
                .as("撤回只拦新增，已入组记录保留给管理员显式撤销")
                .isEqualTo(1);
        assertThat(cohortTotal(researcher)).as("撤回后研究侧不再读到该档案").isZero();

        saveConsent(patient, true, true).andExpect(status().isOk());
        grantAsAdmin().andExpect(status().isOk());
        assertThat(cohortTotal(researcher)).as("重新授权后恢复可读").isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM patient_consent_record WHERE subject_id = ?",
                                Integer.class,
                                subjectId))
                .as("撤回与恢复各留一行，历史可追溯")
                .isEqualTo(2);
    }

    /** 撤回随访联系后不能录入科研随访；重新开启后恢复。 */
    @Test
    void withdrawnFollowUpContactBlocksResearchFollowUp() throws Exception {
        MockHttpSession patient = login("patient");
        MockHttpSession doctor = login("doctor");
        saveConsent(patient, true, false).andExpect(status().isOk());

        saveResearchFollowUp(doctor).andExpect(status().isBadRequest());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM validation_follow_up WHERE subject_id = ?",
                                Integer.class,
                                subjectId))
                .isZero();

        saveConsent(patient, true, true).andExpect(status().isOk());
        saveResearchFollowUp(doctor).andExpect(status().isOk());
    }

    /**
     * 科研随访同时属于「科研使用」与「随访联系」：只关闭科研使用时，即使随访联系仍然开启，
     * 也不能写入或读取科研结局。已记录的结局保留，重新授权后恢复可读。
     */
    @Test
    void withdrawnResearchUseBlocksResearchFollowUpEvenWithContactAllowed() throws Exception {
        MockHttpSession patient = login("patient");
        MockHttpSession doctor = login("doctor");
        saveConsent(patient, true, true).andExpect(status().isOk());
        saveResearchFollowUp(doctor).andExpect(status().isOk());
        readResearchFollowUp(doctor).andExpect(status().isOk());

        saveConsent(patient, false, true).andExpect(status().isOk());

        MvcResult refused = saveResearchFollowUp(doctor).andExpect(status().isBadRequest()).andReturn();
        assertThat(refused.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("撤回科研使用授权");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM validation_follow_up WHERE subject_id = ?",
                                Integer.class,
                                subjectId))
                .as("撤回只拦写入，已记录的结局保留")
                .isEqualTo(1);

        readResearchFollowUp(doctor).andExpect(status().isForbidden());

        saveConsent(patient, true, true).andExpect(status().isOk());
        readResearchFollowUp(doctor).andExpect(status().isOk());
        saveResearchFollowUp(doctor).andExpect(status().isOk());
    }

    /** 临床复诊是诊疗需要，不因「随访联系」被关闭而阻断医生排期。 */
    @Test
    void clinicalFollowUpSurvivesContactWithdrawal() throws Exception {
        MockHttpSession patient = login("patient");
        saveConsent(patient, true, false).andExpect(status().isOk());

        scheduleClinicalFollowUp().andExpect(status().isOk());
    }

    /** 两个开关都关闭时，临床复诊仍不受影响：诊疗安全优先于科研授权。 */
    @Test
    void clinicalFollowUpSurvivesBothConsentWithdrawals() throws Exception {
        MockHttpSession patient = login("patient");
        saveConsent(patient, false, false).andExpect(status().isOk());

        scheduleClinicalFollowUp().andExpect(status().isOk());
        readResearchFollowUp(login("doctor")).andExpect(status().isForbidden());
        saveResearchFollowUp(login("doctor")).andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions scheduleClinicalFollowUp() throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("subjectId", subjectId);
        request.put("scheduledAt", Instant.now().plusSeconds(86_400 * 20).toString());
        request.put("purpose", "认知复查");
        return mockMvc.perform(
                post("/api/follow-ups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .session(login("doctor")));
    }

    private org.springframework.test.web.servlet.ResultActions readResearchFollowUp(MockHttpSession doctor)
            throws Exception {
        return mockMvc.perform(
                get("/api/subjects/{id}/validation-follow-up", subjectId).session(doctor));
    }

    private org.springframework.test.web.servlet.ResultActions grantAsAdmin() throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("project", PROJECT);
        request.put("subjectCode", SUBJECT_CODE);
        request.put("researcher", "researcher");
        request.put("authorizationRef", "ETHICS-2026-001");
        return mockMvc.perform(
                put("/api/research/validation-cohort/grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .session(login("admin")));
    }

    private org.springframework.test.web.servlet.ResultActions saveResearchFollowUp(MockHttpSession doctor)
            throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("outcome", "NO_EVENT");
        request.put("observedDate", LocalDate.now().minusDays(30).toString());
        request.put("sourceRef", "随访电话记录");
        return mockMvc.perform(
                put("/api/subjects/{id}/validation-follow-up", subjectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .session(doctor));
    }

    private org.springframework.test.web.servlet.ResultActions saveConsent(
            MockHttpSession session, boolean researchUse, boolean followUpContact) throws Exception {
        return mockMvc.perform(
                put("/api/patients/me/consent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("researchUse", researchUse, "followUpContact", followUpContact)))
                        .session(session));
    }

    private long cohortTotal(MockHttpSession researcher) throws Exception {
        JsonNode body =
                bodyOf(mockMvc.perform(get("/api/research/validation-cohort").session(researcher))
                        .andExpect(status().isOk())
                        .andReturn());
        return body.get("data").get("total").asLong();
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
