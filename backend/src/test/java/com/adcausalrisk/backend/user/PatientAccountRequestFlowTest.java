package com.adcausalrisk.backend.user;

import com.adcausalrisk.backend.contract.ContractSchemas;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:patient_onboarding;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PatientAccountRequestFlowTest {
    private static final String PATH = "/api/patient-account-requests";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test
    void supplementalVisitInputsRoundTripAndRemainBoundToTheirVisitVersion() throws Exception {
        var doctor = actor("doctor", "DOCTOR");
        long subject = data(postAs("/api/subjects", doctor,
                """
                {"subjectCode":"OP-VISIT-INPUTS","alias":"人工资料","archiveType":"CARE"}
                """)).path("id").asLong();
        String archive = "/api/subjects/" + subject;
        String initial = """
                {"kind":"visit","visitCode":"BASE","visitDate":"2025-10-20","source":"人工量表",
                "mmse":27,"moca":25,"cdrSb":1.5,
                "modelInputs":{"ageYears":74.25,"educationYears":16,"cdrGlobal":0.5,"adasCog13":19,"faqTotal":0}}
                """;
        postAs(archive + "/observations", doctor, initial.replace("74.25", "121"))
                .andExpect(status().isBadRequest());
        var saved = json.readTree(postAs(archive + "/observations", doctor, initial).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(ContractSchemas.validateResponse(saved, "/api/subjects/{subjectId}/observations", "post", 200))
                .isEmpty();
        long visit = saved.at("/data/id").asLong();
        assertThat(saved.at("/data/modelInputs/ageYears").decimalValue()).isEqualByComparingTo("74.25");
        assertThat(saved.at("/data/modelInputs/faqTotal").isNumber()).isTrue();
        var detail = data(mvc.perform(get(archive).with(authentication(doctor))));
        assertThat(detail.at("/visits/0/modelInputs")).isEqualTo(saved.at("/data/modelInputs"));
        data(postAs(archive + "/quality-checks", doctor, "{}"));
        String path = archive + "/visits/" + visit;
        String correction = """
                {"visitDate":"2025-10-20","mmse":27,"moca":25,"cdrSb":1.5,"reason":"人工数据核对",
                "modelInputs":{"ageYears":74.25,"educationYears":16,"cdrGlobal":0.5,"adasCog13":19,"faqTotal":4}}
                """;
        var corrected = data(mvc.perform(patch(path).with(authentication(doctor))
                .contentType(MediaType.APPLICATION_JSON).content(correction)));
        assertThat(corrected.at("/modelInputs/faqTotal").asInt()).isEqualTo(4);
        var versions = data(mvc.perform(get(path + "/history").with(authentication(doctor))));
        assertThat(versions.size()).isEqualTo(2);
        assertThat(versions.at("/0/modelInputs/faqTotal").asInt()).isZero();
        assertThat(versions.at("/1/modelInputs/faqTotal").asInt()).isEqualTo(4);
        assertThat(data(mvc.perform(get(archive + "/assessment-request").with(authentication(doctor))))
                .path("qualityStatus").asText()).isEqualTo("STALE");
    }

    @Test
    void ordinaryCareArchiveRunsOperationsAndKeepsResearchExecutionSeparate() throws Exception {
        var doctor = actor("doctor", "DOCTOR");
        var admin = actor("admin", "ADMIN");
        var researcher = actor("researcher", "RESEARCHER");
        String creation = """
                {"subjectCode":"OP-20261120-001","alias":"流程核验资料","archiveType":"CARE"}
                """;
        postAs("/api/subjects", researcher, creation).andExpect(status().isForbidden());
        postAs("/api/subjects", doctor, creation.replace("OP-20261120-001", "DEMO-CARE"))
                .andExpect(status().isBadRequest());
        var created = json.readTree(postAs("/api/subjects", doctor, creation).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(ContractSchemas.validateResponse(created, "/api/subjects", "post", 200)).isEmpty();
        long subject = created.at("/data/id").asLong();
        String archive = "/api/subjects/" + subject;
        String assessment = archive + "/assessment-request";
        String page = "/api/subjects?dataScope=CARE_WORKFLOW&keyword=OP-20261120-001";
        assertThat(data(mvc.perform(get(page).with(authentication(doctor)))).path("total").asInt()).isOne();
        assertThat(data(mvc.perform(get("/api/subjects?keyword=OP-20261120-001")
                .with(authentication(researcher)))).path("total").asInt()).isZero();
        mvc.perform(get(archive).with(authentication(researcher))).andExpect(status().isForbidden());
        mvc.perform(get(page).with(authentication(researcher))).andExpect(status().isForbidden());
        postAs(assessment, doctor, "{}").andExpect(status().isConflict());

        visit(subject, doctor, "BASE", "2025-05-12", true);
        assertThat(jdbc.queryForObject("SELECT data_version FROM subject WHERE id=?", String.class, subject))
                .startsWith("care-").isNotEqualTo(created.at("/data/dataVersion").asText());
        data(postAs(archive + "/quality-checks", doctor, "{}"));
        assertThat(data(mvc.perform(get(assessment).with(authentication(doctor)))).path("state").asText())
                .isEqualTo("READY");
        var request = json.readTree(postAs(assessment, doctor, "{}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(ContractSchemas.validateResponse(request, "/api/subjects/{subjectId}/assessment-request", "post", 200))
                .isEmpty();
        assertThat(request.at("/data/dataScope").asText()).isEqualTo("CARE_WORKFLOW");
        assertThat(request.at("/data/state").asText()).isEqualTo("WAITING_FOR_VALIDATED_MODEL");
        assertThat(request.at("/data/taskId").isNull()).isTrue();
        assertThat(data(postAs(assessment, doctor, "{}")).path("requestId")).isEqualTo(request.at("/data/requestId"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM research_table_task t"
                + " JOIN research_table_snapshot s ON s.id=t.snapshot_id WHERE s.subject_id=?", Long.class, subject))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM controlled_assessment_request WHERE subject_id=?", Long.class, subject))
                .isZero();

        String patientRequest = json.createObjectNode().put("subjectId", subject).toString();
        assertThat(data(postAs(PATH, doctor, patientRequest)).path("status").asText()).isEqualTo("PENDING");
        long account = jdbc.queryForObject("SELECT id FROM patient_account_request WHERE subject_id=?", Long.class, subject);
        data(postAs(PATH + "/" + account + "/issue", admin, """
                {"username":"care-flow-fixture-patient","password":"DemoPass!2026","identityVerified":true}
                """));
        data(postAs("/api/follow-ups", doctor, json.createObjectNode().put("subjectId", subject)
                .put("scheduledAt", java.time.Instant.now().plusSeconds(172800).toString())
                .put("purpose", "流程核验随访").toString()));
        assertThat(data(mvc.perform(get("/api/follow-ups?dataScope=CARE_WORKFLOW&subjectId=" + subject)
                .with(authentication(doctor)))).path("total").asInt()).isOne();
        assertThat(data(mvc.perform(get("/api/patients/me/follow-up")
                .with(authentication(actor("care-flow-fixture-patient", "PATIENT")))))
                .at("/next/purpose").asText()).isEqualTo("流程核验随访");

        visit(subject, doctor, "UPDATED", "2025-06-12", true);
        assertThat(data(mvc.perform(get(assessment).with(authentication(doctor)))).path("qualityStatus").asText())
                .isEqualTo("STALE");
        postAs(assessment, doctor, "{}").andExpect(status().isConflict());

        data(mvc.perform(post(archive + "/visits").with(authentication(doctor)).header("X-Allow-OOR", true)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"visitCode":"NEEDS_CORRECTION","visitDate":"2025-07-12","mmse":31,"cdrSb":1.5}
                        """)));
        assertThat(data(postAs(archive + "/quality-checks", doctor, "{}")).path("qualityStatus").asText())
                .isEqualTo("BLOCKED");
        assertThat(data(mvc.perform(get("/api/qc/list?subjectCode=OP-20261120-001")
                .with(authentication(researcher)))).path("total").asInt()).isZero();
    }

    @Test
    void doctorQualityGateAndAdminVerifiedBindingUseCurrentDataAndExplicitDoctor() throws Exception {
        var doctor = actor("doctor", "DOCTOR");
        var admin = actor("admin", "ADMIN");
        var researcher = actor("researcher", "RESEARCHER");
        long subject = data(postAs("/api/subjects", doctor,
                """
                {"subjectCode":"DEMO-ONBOARDING-OK","alias":"人工建档验收","status":"ACTIVE"}
                """)).path("id").asLong();
        String check = "/api/subjects/" + subject + "/quality-checks";
        String body = json.createObjectNode().put("subjectId", subject).toString();
        String statePath = PATH + "/status?subjectId=" + subject;
        var state = mvc.perform(get(statePath).with(authentication(doctor)));
        var envelope = json.readTree(state.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(ContractSchemas.validateResponse(envelope, PATH + "/status", "get", 200)).isEmpty();
        assertThat(envelope.at("/data/qualityStatus").asText()).isEqualTo("NO_CHECK");
        postAs(PATH, doctor, body).andExpect(status().isConflict());
        postAs(PATH, researcher, body).andExpect(status().isForbidden());

        assertThat(data(postAs(check, doctor, "{}")).path("qualityStatus").asText()).isEqualTo("BLOCKED");
        postAs(PATH, doctor, body).andExpect(status().isConflict());
        visit(subject, doctor, "BASE", "2025-01-05", false);
        data(postAs(check, doctor, "{}"));
        assertThat(data(mvc.perform(get(statePath).with(authentication(doctor))))
                .path("qualityStatus").asText()).isEqualTo("INCOMPLETE");
        postAs(PATH, doctor, body).andExpect(status().isConflict());

        visit(subject, doctor, "COMPLETE", "2025-02-05", true);
        long firstQuality = data(postAs(check, doctor, "{}")).path("id").asLong();
        assertThat(data(postAs(PATH, doctor, body)).path("status").asText()).isEqualTo("PENDING");
        long request = jdbc.queryForObject("SELECT id FROM patient_account_request WHERE subject_id=?",
                Long.class, subject);
        postAs("/api/users", admin, """
                {"username":"demo-direct-patient","password":"DemoPass!2026","roles":["PATIENT"]}
                """).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username='demo-direct-patient'", Long.class)).isZero();
        postAs("/api/subjects/" + subject + "/permissions", admin, json.createObjectNode()
                .put("userId", userId("patient")).put("permissionType", "SUMMARY_READ").toString())
                .andExpect(status().isConflict());
        String issue = PATH + "/" + request + "/issue";
        String credentials = """
                {"username":"demo-onboarding-patient","password":"DemoPass!2026","identityVerified":true}
                """;
        postAs(issue, admin, credentials.replace("true", "false")).andExpect(status().isBadRequest());
        visit(subject, doctor, "CURRENT", "2025-03-05", true);
        postAs(issue, admin, credentials).andExpect(status().isConflict());
        long currentQuality = data(postAs(check, doctor, "{}")).path("id").asLong();
        assertThat(currentQuality).isGreaterThan(firstQuality);
        postAs(issue, admin, credentials).andExpect(status().isConflict());
        data(postAs(PATH, doctor, body));
        assertThat(jdbc.queryForObject("SELECT quality_check_id FROM patient_account_request WHERE id=?",
                Long.class, request)).isEqualTo(currentQuality);
        postAs(issue, admin, credentials.replace("demo-onboarding-patient", "patient")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT issued_user_id FROM patient_account_request WHERE id=?", Long.class, request)).isNull();

        jdbc.update("UPDATE sys_user SET display_name='主要医生',contact_phone='01000000000' WHERE username='doctor'");
        jdbc.update("UPDATE sys_user SET display_name='AAA其他医生' WHERE username='research_doctor'");
        jdbc.update("INSERT INTO subject_permission(subject_id,user_id,permission_type) VALUES(?,?,'MANAGE')",
                subject, userId("research_doctor"));
        jdbc.update("DELETE FROM subject_permission WHERE subject_id=? AND user_id=? AND permission_type='MANAGE'",
                subject, userId("doctor"));
        postAs(issue, admin, credentials).andExpect(status().isConflict());
        String handoff = PATH + "/" + request + "/responsible-doctor";
        String handoffBody = json.createObjectNode().put("doctorId", userId("research_doctor"))
                .put("identityVerified", true).toString();
        postAs(handoff, doctor, handoffBody).andExpect(status().isForbidden());
        assertThat(data(postAs(handoff, admin, handoffBody)).path("status").asText()).isEqualTo("PENDING");
        long patientId = data(postAs(issue, admin, credentials)).path("userId").asLong();
        var patient = actor("demo-onboarding-patient", "PATIENT");
        assertThat(jdbc.queryForObject("SELECT responsible_doctor_id FROM patient_account_request WHERE id=?",
                Long.class, request)).isEqualTo(userId("research_doctor"));
        assertThat(jdbc.queryForObject("SELECT requested_by FROM patient_account_request WHERE id=?",
                Long.class, request)).isEqualTo(userId("doctor"));
        assertThat(jdbc.queryForObject("SELECT permission_type FROM subject_permission WHERE subject_id=? AND user_id=?",
                String.class, subject, patientId)).isEqualTo("SUMMARY_READ");
        assertThat(data(mvc.perform(get("/api/patients/me/doctor").with(authentication(patient))))
                .path("name").asText()).isEqualTo("AAA其他医生");
        assertThat(data(mvc.perform(get("/api/patients/me/research-summary").with(authentication(patient))))
                .path("subjectId").asLong()).isEqualTo(subject);
        assertThat(data(mvc.perform(get(statePath).with(authentication(actor("research_doctor", "DOCTOR")))))
                .path("accountStatus").asText()).isEqualTo("ISSUED");
        postAs(issue, admin, credentials).andExpect(status().isConflict());
    }

    private void visit(long subject, UsernamePasswordAuthenticationToken doctor, String code,
                       String date, boolean complete) throws Exception {
        var record = json.createObjectNode().put("visitCode", code).put("visitDate", date).put("mmse", 26);
        if (complete) record.put("cdrSb", 1.5);
        data(postAs("/api/subjects/" + subject + "/visits", doctor, record.toString()));
    }

    private ResultActions postAs(String path, UsernamePasswordAuthenticationToken actor, String body) throws Exception {
        return mvc.perform(post(path).with(authentication(actor)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode data(ResultActions response) throws Exception {
        return json.readTree(response.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .path("data");
    }

    private long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, username);
    }

    private UsernamePasswordAuthenticationToken actor(String username, String role) {
        var principal = new AppUserPrincipal(userId(username), username, "", List.of(role), true);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }
}
