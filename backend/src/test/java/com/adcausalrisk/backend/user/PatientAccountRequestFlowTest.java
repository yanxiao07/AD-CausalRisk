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
