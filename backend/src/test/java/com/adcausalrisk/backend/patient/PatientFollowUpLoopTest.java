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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 患者端随访自助与医生端的完整闭环。
 *
 * <p>患者端新增的「取消」不是孤立功能：医生排复诊 → 患者确认/申请改期/取消 → 医生在复诊列表里
 * 看到结果，才构成一条闭环。本类刻意跨角色读写同一个 {@code follow_up_plan}：每一笔动作都从
 * 一个角色的接口写入、从另一个角色的接口读出，任何一侧「写了但另一侧看不见」都会在这里失败。
 *
 * <p>不开启测试事务：随访写入使用独立事务，测试事务与它互相看不见，因此夹具直接提交、在
 * {@link #cleanUp()} 里按外键顺序清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PatientFollowUpLoopTest {

    private static final String DEMO_PASSWORD = "DemoPass!2026";

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private SubjectRepository subjectRepository;

    @Autowired private JdbcTemplate jdbc;

    private Long subjectId;
    private Long otherSubjectId;
    private Long patientId;
    private Long doctorId;

    @BeforeEach
    void setUp() {
        subjectId = newSubject("随访闭环档案");
        otherSubjectId = newSubject("他人档案");
        patientId = userId("patient");
        doctorId = userId("doctor");

        grant(subjectId, patientId, "VIEW");
        grant(subjectId, doctorId, "VIEW");
    }

    @AfterEach
    void cleanUp() {
        for (Long id : new Long[] {subjectId, otherSubjectId}) {
            if (id == null) {
                continue;
            }
            jdbc.update("DELETE FROM follow_up_response WHERE follow_up_id IN"
                    + " (SELECT id FROM follow_up_plan WHERE subject_id = ?)", id);
            jdbc.update("DELETE FROM follow_up_plan WHERE subject_id = ?", id);
            jdbc.update("DELETE FROM subject_permission WHERE subject_id = ?", id);
            jdbc.update("DELETE FROM subject WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM audit_log WHERE actor_id = ? AND event_type ="
                + " 'PATIENT_FOLLOW_UP_CANCEL'", patientId);
    }

    /**
     * 医生（这里由管理员代排）排一次复诊 → 患者在自助页看到地点与准备事项 → 确认 →
     * 医生的复诊列表出现「已确认」。三步读的是同一行数据库记录。
     */
    @Test
    void doctorArrangesThenPatientConfirmsAndDoctorSeesIt() throws Exception {
        long followUpId = createFollowUp("认知复查", "门诊三楼", "带上既往检查报告");

        MockHttpSession patient = login("patient");
        JsonNode appointmentsBefore = patientAppointments(patient);
        assertThat(appointmentsBefore).hasSize(1);

        JsonNode appointment = appointmentsBefore.get(0);
        assertThat(appointment.get("purpose").asText()).isEqualTo("认知复查");
        assertThat(appointment.get("visitLocation").asText()).isEqualTo("门诊三楼");
        assertThat(appointment.get("preparation").asText()).isEqualTo("带上既往检查报告");
        assertThat(appointment.get("canRespond").asBoolean()).isTrue();
        assertThat(appointment.get("appointmentRevision").asLong()).isZero();

        JsonNode next = bodyOf(mockMvc.perform(
                        get("/api/patients/me/follow-up").session(patient))
                .andExpect(status().isOk())
                .andReturn()).get("data").get("next");
        assertThat(next).as("待确认的复诊应当出现在首页提醒位置").isNotNull();
        assertThat(next.get("id").asLong()).isEqualTo(followUpId);

        patientRespond(patient, followUpId, 0, "CONFIRM", null);

        JsonNode doctorRow = doctorFollowUp(login("doctor"), followUpId);
        assertThat(doctorRow.get("responseStatus").asText())
                .as("医生端必须看到患者的确认").isEqualTo("CONFIRMED");
        assertThat(doctorRow.get("status").asText()).isEqualTo("PLANNED");

        JsonNode after = patientAppointments(patient).get(0);
        assertThat(after.get("responseStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(after.get("status").asText()).isEqualTo("PLANNED");
        assertThat(after.get("canRespond").asBoolean()).as("确认后仍可在到院前改期").isTrue();
    }

    /**
     * 患者申请改期并写说明 → 医生看到说明 → 医生拒绝改期 → 患者看到「改期已拒绝」。
     * 说明文字必须原样送达，否则医生无法判断患者为什么来不了。
     */
    @Test
    void rescheduleNoteReachesDoctorAndDeclineComesBackToPatient() throws Exception {
        long followUpId = createFollowUp("认知复查", "门诊三楼", "空腹前来");

        MockHttpSession patient = login("patient");
        patientRespond(patient, followUpId, 0, "REQUEST_RESCHEDULE", "那天上午要上班，希望改到下午");

        MockHttpSession doctor = login("doctor");
        JsonNode doctorRow = doctorFollowUp(doctor, followUpId);
        assertThat(doctorRow.get("responseStatus").asText()).isEqualTo("RESCHEDULE_REQUESTED");
        assertThat(doctorRow.get("responseNote").asText()).isEqualTo("那天上午要上班，希望改到下午");

        mockMvc.perform(
                        post("/api/follow-ups/{id}/decline-reschedule", followUpId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("appointmentRevision", 0)))
                                .session(doctor))
                .andExpect(status().isOk());

        JsonNode after = patientAppointments(patient).get(0);
        assertThat(after.get("responseStatus").asText())
                .as("医生拒绝改期后，患者自助页必须给出结论而不是一直停在申请中")
                .isEqualTo("RESCHEDULE_DECLINED");
        assertThat(after.get("canRespond").asBoolean()).isTrue();
    }

    /** 患者在自助页取消 → 医生复诊列表立刻变成「已取消」，而不是两套状态各自为政。 */
    @Test
    void patientCancellationIsVisibleToTheDoctor() throws Exception {
        long followUpId = createFollowUp("认知复查", "门诊三楼", null);

        MockHttpSession patient = login("patient");
        mockMvc.perform(
                        post("/api/patients/me/follow-up/{id}/cancel", followUpId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("appointmentRevision", 0)))
                                .session(patient))
                .andExpect(status().isOk());

        JsonNode doctorRow = doctorFollowUp(login("doctor"), followUpId);
        assertThat(doctorRow.get("status").asText())
                .as("取消写的是 follow_up_plan.status，医生端读的必须是同一张表")
                .isEqualTo("CANCELLED");

        JsonNode patientRow = patientAppointments(patient).get(0);
        assertThat(patientRow.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(patientRow.get("canRespond").asBoolean()).isFalse();
    }

    /**
     * 患者先确认、后取消：同一版本的回执必须被「已取消」覆盖，医生端不能同时读到
     * 「已取消」和「患者已确认」两个互相矛盾的说法。
     */
    @Test
    void patientCancellationOverwritesTheConfirmationOfTheSameRevision() throws Exception {
        long followUpId = createFollowUp("认知复查", "门诊三楼", null);

        MockHttpSession patient = login("patient");
        patientRespond(patient, followUpId, 0, "CONFIRM", null);
        assertThat(doctorFollowUp(login("doctor"), followUpId).get("responseStatus").asText())
                .isEqualTo("CONFIRMED");

        patientCancel(patient, followUpId, 0);

        JsonNode doctorRow = doctorFollowUp(login("doctor"), followUpId);
        assertThat(doctorRow.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(doctorRow.get("responseStatus").asText())
                .as("取消必须覆盖同一版本的确认，而不是留下两条并存的表态")
                .isEqualTo("CANCELLED");
        assertThat(doctorRow.get("responseNote").isNull()).isTrue();

        JsonNode patientRow = patientAppointments(patient).get(0);
        assertThat(patientRow.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(patientRow.get("responseStatus").asText()).isEqualTo("CANCELLED");
        assertThat(patientRow.get("canRespond").asBoolean()).isFalse();

        assertThat(responseRowCount(followUpId)).as("同一版本只保留一行回执").isEqualTo(1);
        assertThat(dbResponseStatus(followUpId, 0)).isEqualTo("CANCELLED");
    }

    /** 患者没确认就直接取消，医生端也应当看到「患者已取消」，而不是一直停在「等患者确认」。 */
    @Test
    void cancellationWithoutAnyEarlierResponseStillReadsAsCancelled() throws Exception {
        long followUpId = createFollowUp("认知复查", "门诊三楼", null);

        MockHttpSession patient = login("patient");
        patientCancel(patient, followUpId, 0);

        JsonNode doctorRow = doctorFollowUp(login("doctor"), followUpId);
        assertThat(doctorRow.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(doctorRow.get("responseStatus").asText()).isEqualTo("CANCELLED");
        assertThat(responseRowCount(followUpId)).isEqualTo(1);
    }

    /**
     * 患者申请改期后取消：改期申请同样被取消覆盖，医生端不会看到一个已经作废的待处理申请。
     */
    @Test
    void cancellationAlsoClearsAPendingRescheduleRequest() throws Exception {
        long followUpId = createFollowUp("认知复查", "门诊三楼", null);

        MockHttpSession patient = login("patient");
        patientRespond(patient, followUpId, 0, "REQUEST_RESCHEDULE", "那天要上班");
        patientCancel(patient, followUpId, 0);

        JsonNode doctorRow = doctorFollowUp(login("doctor"), followUpId);
        assertThat(doctorRow.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(doctorRow.get("responseStatus").asText()).isEqualTo("CANCELLED");
        assertThat(doctorRow.get("responseNote").isNull())
                .as("取消后不应再留着已经作废的改期说明")
                .isTrue();
        assertThat(dbResponseStatus(followUpId, 0)).isEqualTo("CANCELLED");
    }

    /**
     * 对象级越权：患者只能取消自己档案下的安排。别的档案同样排了复诊，患者拿着它的 id
     * 直接调接口必须被拒绝，并且数据库不能变化。
     */
    @Test
    void patientCannotCancelAnotherSubjectsAppointment() throws Exception {
        long followUpId = createFollowUp(otherSubjectId, "认知复查", "门诊三楼", null);

        MockHttpSession patient = login("patient");
        JsonNode body = bodyOf(mockMvc.perform(
                        post("/api/patients/me/follow-up/{id}/cancel", followUpId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("appointmentRevision", 0)))
                                .session(patient))
                .andExpect(status().isForbidden())
                .andReturn());
        assertThat(body.get("errorCode").asText()).isEqualTo("ACCESS_DENIED");

        assertThat(dbStatus(followUpId)).as("被拒绝的取消不得改动任何一行").isEqualTo("PLANNED");
    }

    /** 患者端接口只对患者开放：医生与管理员调用一律 403。 */
    @Test
    void patientOnlyEndpointsRefuseOtherRoles() throws Exception {
        long followUpId = createFollowUp("认知复查", "门诊三楼", null);

        for (String username : new String[] {"doctor", "admin"}) {
            MockHttpSession session = login(username);
            mockMvc.perform(get("/api/patients/me/consent").session(session))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/patients/me/timeline").session(session))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/patients/me/follow-up").session(session))
                    .andExpect(status().isForbidden());
            mockMvc.perform(
                            post("/api/patients/me/follow-up/{id}/cancel", followUpId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(Map.of("appointmentRevision", 0)))
                                    .session(session))
                    .andExpect(status().isForbidden());
        }

        assertThat(dbStatus(followUpId)).isEqualTo("PLANNED");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Long newSubject(String alias) {
        return subjectRepository
                .save(new Subject(
                        "DEMO-LOOP-" + UUID.randomUUID().toString().substring(0, 8),
                        alias,
                        Subject.Status.ACTIVE,
                        "demo-patient-loop",
                        Instant.now()))
                .getId();
    }

    private Long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private void grant(Long subjectId, Long userId, String permission) {
        jdbc.update(
                "INSERT INTO subject_permission (subject_id, user_id, permission_type)"
                        + " VALUES (?, ?, ?)",
                subjectId,
                userId,
                permission);
    }

    private long createFollowUp(String purpose, String location, String preparation) throws Exception {
        return createFollowUp(subjectId, purpose, location, preparation);
    }

    private long createFollowUp(Long targetSubject, String purpose, String location, String preparation)
            throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("subjectId", targetSubject);
        request.put("scheduledAt", Instant.now().plus(20, ChronoUnit.DAYS).toString());
        request.put("purpose", purpose);
        if (location != null) {
            request.put("visitLocation", location);
        }
        if (preparation != null) {
            request.put("preparation", preparation);
        }
        JsonNode body = bodyOf(mockMvc.perform(
                        post("/api/follow-ups")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                                .session(login("admin")))
                .andExpect(status().isOk())
                .andReturn());
        return body.get("data").get("id").asLong();
    }

    private void patientRespond(MockHttpSession session, long followUpId, long revision,
                                String action, String note) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("appointmentRevision", revision);
        request.put("action", action);
        if (note != null) {
            request.put("note", note);
        }
        mockMvc.perform(
                        post("/api/patients/me/follow-up/{id}/response", followUpId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                                .session(session))
                .andExpect(status().isOk());
    }

    private void patientCancel(MockHttpSession session, long followUpId, long revision)
            throws Exception {
        mockMvc.perform(
                        post("/api/patients/me/follow-up/{id}/cancel", followUpId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("appointmentRevision", revision)))
                                .session(session))
                .andExpect(status().isOk());
    }

    private JsonNode patientAppointments(MockHttpSession session) throws Exception {
        return bodyOf(mockMvc.perform(
                        get("/api/patients/me/follow-up").session(session))
                .andExpect(status().isOk())
                .andReturn()).get("data").get("appointments");
    }

    private JsonNode doctorFollowUp(MockHttpSession session, long followUpId) throws Exception {
        JsonNode rows = bodyOf(mockMvc.perform(
                        get("/api/subjects/{id}/follow-ups", subjectId).session(session))
                .andExpect(status().isOk())
                .andReturn()).get("data");
        for (JsonNode row : rows) {
            if (row.get("id").asLong() == followUpId) {
                return row;
            }
        }
        throw new AssertionError("医生端没有看到复诊 " + followUpId);
    }

    private String dbStatus(long followUpId) {
        return jdbc.queryForObject(
                "SELECT status FROM follow_up_plan WHERE id = ?", String.class, followUpId);
    }

    private String dbResponseStatus(long followUpId, long revision) {
        return jdbc.queryForObject(
                "SELECT response_status FROM follow_up_response WHERE follow_up_id = ?"
                        + " AND appointment_revision = ?",
                String.class,
                followUpId,
                revision);
    }

    private int responseRowCount(long followUpId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM follow_up_response WHERE follow_up_id = ?",
                Integer.class,
                followUpId);
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
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
