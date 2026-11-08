package com.adcausalrisk.backend.subject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.adcausalrisk.backend.contract.ContractSchemas;
import com.adcausalrisk.backend.subject.entity.Subject;
import com.adcausalrisk.backend.subject.entity.Subject.Status;
import com.adcausalrisk.backend.subject.entity.SubjectPermission;
import com.adcausalrisk.backend.subject.entity.SubjectPermission.PermissionType;
import com.adcausalrisk.backend.user.SysUser;
import com.adcausalrisk.backend.user.SysUserRepository;
import com.adcausalrisk.backend.visit.VisitRepository;
import com.adcausalrisk.backend.visit.entity.Visit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * End-to-end integration tests for the three subject endpoints. Runs against the real Spring
 * context, the real SecurityFilterChain and an in-memory H2 (test profile), with responses
 * validated against the frozen contract.
 *
 * <p>Authentication is established the same way the browser would: each role-specific test logs in
 * via {@code POST /api/auth/login} and reuses the returned session cookie. This guarantees the
 * {@code AppUserPrincipal} produced by {@link
 * com.adcausalrisk.backend.security.CustomUserDetailsService} is the one that reaches {@code
 * SubjectController} - bypassing the default Spring Security {@code User} that
 * {@code @WithMockUser} would inject.
 *
 * <p>Every test is {@code @Transactional} so inserts are rolled back automatically.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SubjectApiTest {

    private static final String DEMO_PASSWORD = "DemoPass!2026";

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private SubjectRepository subjectRepository;

    @Autowired private SubjectPermissionRepository subjectPermissionRepository;

    @Autowired private SysUserRepository sysUserRepository;

    @Autowired private VisitRepository visitRepository;

    @Autowired private JdbcTemplate jdbc;

    // ===================================================================
    // 1. POST /api/subjects - create
    // ===================================================================

    @Test
    void adminCreatesSubjectSuccessfully() throws Exception {
        MockHttpSession admin = login("admin");

        JsonNode body =
                bodyOf(
                        mockMvc.perform(createRequest("DEMO-AAA1", "测试档案AAA").session(admin))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("data").get("subjectCode").asText()).isEqualTo("DEMO-AAA1");
        assertThat(body.get("data").get("alias").asText()).isEqualTo("测试档案AAA");
        assertThat(body.get("data").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("data").get("dataVersion").asText()).startsWith("demo-");
        assertThat(body.get("data").has("createdAt")).isTrue();
        assertThat(body.get("data").get("id").asLong()).isPositive();
        assertThat(ContractSchemas.validateResponse(body, "/api/subjects", "post", 200)).isEmpty();
    }

    @Test
    void researcherCreatesSubjectSuccessfully() throws Exception {
        MockHttpSession researcher = login("researcher");

        JsonNode body =
                bodyOf(
                        mockMvc.perform(createRequest("DEMO-R1", "研究创建").session(researcher))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(body.get("data").get("subjectCode").asText()).isEqualTo("DEMO-R1");
    }

    @Test
    void subjectCodeThatDoesNotMatchThePatternIsRejected() throws Exception {
        MockHttpSession admin = login("admin");

        JsonNode body =
                bodyOf(
                        mockMvc.perform(createRequest("BADCODE", "坏格式").session(admin))
                                .andExpect(status().isBadRequest())
                                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("message").asText()).contains("subjectCode");
    }

    @Test
    void duplicateSubjectCodeIsRejected() throws Exception {
        MockHttpSession admin = login("admin");

        mockMvc.perform(createRequest("DEMO-DUP1", "第一次").session(admin))
                .andExpect(status().isOk());

        JsonNode body =
                bodyOf(
                        mockMvc.perform(createRequest("DEMO-DUP1", "第二次").session(admin))
                                .andExpect(status().isBadRequest())
                                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("message").asText()).contains("已存在");
    }

    @Test
    void missingAliasIsRejected() throws Exception {
        MockHttpSession admin = login("admin");

        JsonNode body =
                bodyOf(
                        mockMvc.perform(
                                        post("/api/subjects")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"subjectCode\":\"DEMO-NOALIAS\"}")
                                                .session(admin))
                                .andExpect(status().isBadRequest())
                                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void doctorCannotCreateSubject() throws Exception {
        MockHttpSession doctor = login("doctor");

        JsonNode body =
                bodyOf(
                        mockMvc.perform(createRequest("DEMO-DOCTOR1", "医生创建").session(doctor))
                                .andExpect(status().isForbidden())
                                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void patientCannotCreateSubject() throws Exception {
        MockHttpSession patient = login("patient");

        JsonNode body =
                bodyOf(
                        mockMvc.perform(createRequest("DEMO-PAT1", "患者创建").session(patient))
                                .andExpect(status().isForbidden())
                                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("ACCESS_DENIED");
    }

    // ===================================================================
    // 2. GET /api/subjects - paged list
    // ===================================================================

    @Test
    void adminSeesEverySubjectInTheTable() throws Exception {
        MockHttpSession admin = login("admin");
        seedSubjects("DEMO-L1", "DEMO-L2", "DEMO-L3");

        JsonNode body =
                bodyOf(
                        mockMvc.perform(
                                        get("/api/subjects")
                                                .param("page", "1")
                                                .param("pageSize", "10")
                                                .session(admin))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(body.get("code").asInt()).isZero();
        // The H2 schema is shared by every test class in the run, so the stable claim is
        // "the rows I seeded are visible and counted" - not "they are the only rows". Asserting
        // an absolute total made this test fail whenever another class committed a subject.
        assertThat(subjectCodesIn(body)).contains("DEMO-L1", "DEMO-L2", "DEMO-L3");
        assertThat(body.get("data").get("total").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(body.get("data").get("items").size()).isGreaterThanOrEqualTo(3);
        assertThat(ContractSchemas.validateResponse(body, "/api/subjects", "get", 200)).isEmpty();
    }

    @Test
    void researcherAlsoSeesEverySubject() throws Exception {
        MockHttpSession researcher = login("researcher");
        seedSubjects("DEMO-R1", "DEMO-R2");

        JsonNode body =
                bodyOf(
                        mockMvc.perform(
                                        get("/api/subjects")
                                                .param("page", "1")
                                                .param("pageSize", "20")
                                                .session(researcher))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(subjectCodesIn(body)).contains("DEMO-R1", "DEMO-R2");
        assertThat(body.get("data").get("total").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(body.get("data").get("items").size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void pageNumberIsClampedToMinimumOne() throws Exception {
        MockHttpSession admin = login("admin");
        seedSubjects("DEMO-P1", "DEMO-P2");

        JsonNode body =
                bodyOf(
                        mockMvc.perform(
                                        get("/api/subjects")
                                                .param("page", "0")
                                                .param("pageSize", "1")
                                                .session(admin))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(body.get("data").get("page").asInt()).isEqualTo(1);
        assertThat(body.get("data").get("items").size()).isEqualTo(1);
    }

    @Test
    void pageSizeIsClampedToRangeOneToHundred() throws Exception {
        MockHttpSession admin = login("admin");
        seedSubjects(generateManySubjectCodes(150).toArray(new String[0]));

        JsonNode tooBig =
                bodyOf(
                        mockMvc.perform(
                                        get("/api/subjects")
                                                .param("page", "1")
                                                .param("pageSize", "999")
                                                .session(admin))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(tooBig.get("data").get("pageSize").asInt()).isEqualTo(100);
        assertThat(tooBig.get("data").get("items").size()).isEqualTo(100);

        JsonNode tooSmall =
                bodyOf(
                        mockMvc.perform(
                                        get("/api/subjects")
                                                .param("page", "1")
                                                .param("pageSize", "-5")
                                                .session(admin))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(tooSmall.get("data").get("pageSize").asInt()).isEqualTo(1);
    }

    @Test
    void emptyPageReturnsAnEmptyItemsArray() throws Exception {
        MockHttpSession admin = login("admin");
        seedSubjects("DEMO-ONLY-PAGE1", "DEMO-ONLY-PAGE2");

        // One page past the last one, derived from the real total instead of a fixed number:
        // the table also holds rows committed by other test classes.
        JsonNode firstPage =
                bodyOf(
                        mockMvc.perform(
                                        get("/api/subjects")
                                                .param("page", "1")
                                                .param("pageSize", "20")
                                                .session(admin))
                                .andExpect(status().isOk())
                                .andReturn());
        long total = firstPage.get("data").get("total").asLong();
        int pageSize = 20;
        int beyondLastPage = (int) ((total + pageSize - 1) / pageSize) + 1;

        JsonNode body =
                bodyOf(
                        mockMvc.perform(
                                        get("/api/subjects")
                                                .param("page", String.valueOf(beyondLastPage))
                                                .param("pageSize", String.valueOf(pageSize))
                                                .session(admin))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(body.get("data").get("items").size()).isZero();
        assertThat(body.get("data").get("total").asLong()).isEqualTo(total);
    }

    @Test
    void doctorOnlySeesGrantedSubjects() throws Exception {
        MockHttpSession doctor = login("doctor");
        Subject granted = seedSubjectWithCode("DEMO-D-GRANTED");
        seedSubjectWithCode("DEMO-D-HIDDEN");

        grantPermission(granted.getId(), "doctor", PermissionType.VIEW);

        JsonNode body =
                bodyOf(
                        mockMvc.perform(
                                        get("/api/subjects")
                                                .param("page", "1")
                                                .param("pageSize", "20")
                                                .session(doctor))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(body.get("data").get("total").asLong()).isEqualTo(1);
        assertThat(body.get("data").get("items").size()).isEqualTo(1);
        assertThat(body.get("data").get("items").get(0).get("subjectCode").asText())
                .isEqualTo("DEMO-D-GRANTED");
    }

    @Test
    void doctorWithNoGrantsSeesEmptyList() throws Exception {
        MockHttpSession doctor = login("doctor");
        seedSubjectWithCode("DEMO-D-NOGRANT1");
        seedSubjectWithCode("DEMO-D-NOGRANT2");

        JsonNode body =
                bodyOf(
                        mockMvc.perform(
                                        get("/api/subjects")
                                                .param("page", "1")
                                                .param("pageSize", "20")
                                                .session(doctor))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(body.get("data").get("total").asLong()).isZero();
        assertThat(body.get("data").get("items").size()).isZero();
    }

    @Test
    void doctorPaginatesWithinTheirGrantedSetNotTheWholeTable() throws Exception {
        MockHttpSession doctor = login("doctor");
        seedSubjectWithCode("DEMO-D-G1");
        seedSubjectWithCode("DEMO-D-G2");
        Subject g3 = seedSubjectWithCode("DEMO-D-G3");
        seedSubjectWithCode("DEMO-D-G4");
        seedSubjectWithCode("DEMO-D-G5");
        seedSubjectWithCode("DEMO-D-H1");
        seedSubjectWithCode("DEMO-D-H2");

        Long doctorUserId = userId("doctor");
        grantPermission(g3.getId(), doctorUserId, PermissionType.VIEW);

        JsonNode body =
                bodyOf(
                        mockMvc.perform(
                                        get("/api/subjects")
                                                .param("page", "1")
                                                .param("pageSize", "2")
                                                .session(doctor))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(body.get("data").get("total").asLong()).isEqualTo(1);
    }

    // ===================================================================
    // 3. GET /api/subjects/{subjectId} - detail
    // ===================================================================

    /**
     * The contract requires {@code visits} and {@code features} to be present arrays. This subject
     * has no visits and no features, so both come back empty - since the visit endpoint landed, the
     * emptiness is data-driven rather than the endpoint hardcoding empty lists. A subject that does
     * have visits is covered by {@code VisitApiTest}.
     */
    @Test
    void detailContainsEmptyVisitsAndFeaturesArrays() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubjectWithCode("DEMO-DETAIL-1");

        JsonNode body =
                bodyOf(
                        mockMvc.perform(get("/api/subjects/" + subject.getId()).session(admin))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("data").get("subject").get("id").asLong()).isEqualTo(subject.getId());
        assertThat(body.get("data").get("visits").isArray()).isTrue();
        assertThat(body.get("data").get("visits").size()).isZero();
        assertThat(body.get("data").get("features").isArray()).isTrue();
        assertThat(body.get("data").get("features").size()).isZero();
        assertThat(ContractSchemas.validateResponse(body, "/api/subjects/{subjectId}", "get", 200))
                .isEmpty();
    }

    @Test
    void nonExistentSubjectReturnsBusinessErrorNotNotFound() throws Exception {
        MockHttpSession admin = login("admin");

        JsonNode body =
                bodyOf(
                        mockMvc.perform(get("/api/subjects/999999").session(admin))
                                .andExpect(status().isBadRequest())
                                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("message").asText()).contains("档案不存在");
    }

    @Test
    void doctorWithGrantsCanReadGrantedSubject() throws Exception {
        MockHttpSession doctor = login("doctor");
        Subject subject = seedSubjectWithCode("DEMO-DOCTOR-VISIBLE");
        grantPermission(subject.getId(), "doctor", PermissionType.VIEW);

        JsonNode body =
                bodyOf(
                        mockMvc.perform(get("/api/subjects/" + subject.getId()).session(doctor))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(body.get("data").get("subject").get("id").asLong()).isEqualTo(subject.getId());
    }

    @Test
    void doctorWithoutGrantIsDeniedForExistingSubject() throws Exception {
        MockHttpSession doctor = login("doctor");
        Subject subject = seedSubjectWithCode("DEMO-DOCTOR-DENIED");

        JsonNode body =
                bodyOf(
                        mockMvc.perform(get("/api/subjects/" + subject.getId()).session(doctor))
                                .andExpect(status().isForbidden())
                                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("ACCESS_DENIED");
    }

    /**
     * A PATIENT legitimately holds {@code SUMMARY_READ} and may read the subject, but raw scale
     * scores must not reach patient-facing output.
     *
     * <p>A visit is seeded first on purpose: without real data behind it, "visits is empty" would
     * pass even if the filter were missing. The admin assertion then proves the same visit exists
     * and is visible to a role that is allowed to see scores.
     */
    @Test
    void patientSummaryCannotSeeRawVisitScores() throws Exception {
        MockHttpSession admin = login("admin");
        MockHttpSession patient = login("patient");
        Subject subject = seedSubjectWithCode("DEMO-PATIENT-SUMMARY");
        visitRepository.save(
                new Visit(
                        subject.getId(),
                        "BL",
                        LocalDate.of(2025, 1, 1),
                        new BigDecimal("28.0"),
                        new BigDecimal("25.0"),
                        new BigDecimal("0.5")));
        grantPermission(subject.getId(), "patient", PermissionType.SUMMARY_READ);

        JsonNode asPatient =
                bodyOf(
                        mockMvc.perform(get("/api/subjects/" + subject.getId()).session(patient))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(asPatient.get("data").get("subject").get("id").asLong())
                .isEqualTo(subject.getId());
        assertThat(asPatient.get("data").get("visits").isArray()).isTrue();
        assertThat(asPatient.get("data").get("visits").size())
                .as("PATIENT must not receive raw scale scores")
                .isZero();
        assertThat(
                        ContractSchemas.validateResponse(
                                asPatient, "/api/subjects/{subjectId}", "get", 200))
                .isEmpty();

        // Control: the same subject and the same visit, read by a role allowed to see scores.
        JsonNode asAdmin =
                bodyOf(
                        mockMvc.perform(get("/api/subjects/" + subject.getId()).session(admin))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(asAdmin.get("data").get("visits").size()).isEqualTo(1);
        assertThat(asAdmin.get("data").get("visits").get(0).get("mmse").asDouble()).isEqualTo(28.0);
    }

    /**
     * A DOCTOR holding only {@code SUMMARY_READ} on this subject may read the summary, but not raw
     * scale scores. This is the escalation the per-subject check prevents: the grant is for the
     * summary, so it must not be upgraded into a full read.
     */
    @Test
    void doctorWithOnlySummaryReadCannotSeeRawVisitScores() throws Exception {
        MockHttpSession admin = login("admin");
        MockHttpSession doctor = login("doctor");
        Subject subject = seedSubjectWithCode("DEMO-DOCTOR-SUMMARY");
        visitRepository.save(
                new Visit(
                        subject.getId(),
                        "BL",
                        LocalDate.of(2025, 1, 1),
                        new BigDecimal("28.0"),
                        new BigDecimal("25.0"),
                        new BigDecimal("0.5")));
        grantPermission(subject.getId(), "doctor", PermissionType.SUMMARY_READ);

        JsonNode asDoctor =
                bodyOf(
                        mockMvc.perform(get("/api/subjects/" + subject.getId()).session(doctor))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(asDoctor.get("data").get("subject").get("id").asLong())
                .isEqualTo(subject.getId());
        assertThat(asDoctor.get("data").get("visits").size())
                .as("SUMMARY_READ must not expose raw scale scores")
                .isZero();

        // Control: the same subject and the same visit, read by a role that may see scores.
        JsonNode asAdmin =
                bodyOf(
                        mockMvc.perform(get("/api/subjects/" + subject.getId()).session(admin))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(asAdmin.get("data").get("visits").size()).isEqualTo(1);
    }

    @Test
    void doctorWithViewSeesRawVisitScores() throws Exception {
        MockHttpSession doctor = login("doctor");
        Subject subject = seedSubjectWithCode("DEMO-DOCTOR-VIEW");
        visitRepository.save(
                new Visit(
                        subject.getId(),
                        "BL",
                        LocalDate.of(2025, 1, 1),
                        new BigDecimal("28.0"),
                        new BigDecimal("25.0"),
                        new BigDecimal("0.5")));
        grantPermission(subject.getId(), "doctor", PermissionType.VIEW);

        JsonNode body =
                bodyOf(
                        mockMvc.perform(get("/api/subjects/" + subject.getId()).session(doctor))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(body.get("data").get("visits").size()).isEqualTo(1);
        assertThat(body.get("data").get("visits").get(0).get("mmse").asDouble()).isEqualTo(28.0);
    }

    @Test
    void doctorWithManageSeesRawVisitScores() throws Exception {
        MockHttpSession doctor = login("doctor");
        Subject subject = seedSubjectWithCode("DEMO-DOCTOR-MANAGE");
        visitRepository.save(
                new Visit(
                        subject.getId(),
                        "BL",
                        LocalDate.of(2025, 1, 1),
                        new BigDecimal("28.0"),
                        new BigDecimal("25.0"),
                        new BigDecimal("0.5")));
        grantPermission(subject.getId(), "doctor", PermissionType.MANAGE);

        JsonNode body =
                bodyOf(
                        mockMvc.perform(get("/api/subjects/" + subject.getId()).session(doctor))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(body.get("data").get("visits").size()).isEqualTo(1);
    }

    /**
     * A PATIENT role must win over every other role: an account that is both a patient and a
     * researcher still receives no raw scale scores. The extra account is created through JDBC
     * because {@code SysUser} deliberately exposes no way to attach roles.
     */
    @Test
    void patientWhoIsAlsoResearcherCannotSeeRawVisitScores() throws Exception {
        MockHttpSession admin = login("admin");
        jdbc.update(
                """
INSERT INTO sys_user (username, password_hash, status, created_at)
SELECT ?, password_hash, status, created_at FROM sys_user WHERE username = 'patient'
""",
                "patient_researcher");
        jdbc.update(
                """
                INSERT INTO user_role (user_id, role_id)
                SELECT u.id, r.id FROM sys_user u, sys_role r
                WHERE u.username = 'patient_researcher' AND r.role_code = 'PATIENT'
                """);
        jdbc.update(
                """
                INSERT INTO user_role (user_id, role_id)
                SELECT u.id, r.id FROM sys_user u, sys_role r
                WHERE u.username = 'patient_researcher' AND r.role_code = 'RESEARCHER'
                """);

        Subject subject = seedSubjectWithCode("DEMO-PATIENT-RESEARCHER");
        visitRepository.save(
                new Visit(
                        subject.getId(),
                        "BL",
                        LocalDate.of(2025, 1, 1),
                        new BigDecimal("28.0"),
                        new BigDecimal("25.0"),
                        new BigDecimal("0.5")));

        // RESEARCHER alone would grant full visibility, so no subject_permission row is needed;
        // the only thing standing between this account and the scores is the PATIENT gate.
        mockMvc.perform(
                        get("/api/subjects/" + subject.getId())
                                .session(login("patient_researcher")))
                .andExpect(status().isForbidden());
        jdbc.update(
                "INSERT INTO subject_permission (subject_id,user_id,permission_type) SELECT"
                        + " ?,id,'SUMMARY_READ' FROM sys_user WHERE username='patient_researcher'",
                subject.getId());
        JsonNode asPatientResearcher =
                bodyOf(
                        mockMvc.perform(
                                        get("/api/subjects/" + subject.getId())
                                                .session(login("patient_researcher")))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(asPatientResearcher.get("data").get("subject").get("id").asLong())
                .isEqualTo(subject.getId());
        assertThat(asPatientResearcher.get("data").get("visits").size())
                .as("a PATIENT role wins over RESEARCHER")
                .isZero();

        // Control: the visit exists and a role allowed to see scores receives it.
        JsonNode asAdmin =
                bodyOf(
                        mockMvc.perform(get("/api/subjects/" + subject.getId()).session(admin))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(asAdmin.get("data").get("visits").size()).isEqualTo(1);
    }

    /**
     * A PATIENT granted {@code VIEW} instead of {@code SUMMARY_READ} is still refused raw scores:
     * {@code canView} passes and only the role gate stops the data. Without this test that gate
     * could be inverted silently.
     */
    @Test
    void patientWithViewPermissionStillCannotSeeRawVisitScores() throws Exception {
        MockHttpSession admin = login("admin");
        MockHttpSession patient = login("patient");
        Subject subject = seedSubjectWithCode("DEMO-PATIENT-VIEW");
        visitRepository.save(
                new Visit(
                        subject.getId(),
                        "BL",
                        LocalDate.of(2025, 1, 1),
                        new BigDecimal("28.0"),
                        new BigDecimal("25.0"),
                        new BigDecimal("0.5")));
        grantPermission(subject.getId(), "patient", PermissionType.VIEW);

        JsonNode asPatient =
                bodyOf(
                        mockMvc.perform(get("/api/subjects/" + subject.getId()).session(patient))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(asPatient.get("data").get("visits").size())
                .as("PATIENT must not receive raw scores even with VIEW")
                .isZero();

        JsonNode asAdmin =
                bodyOf(
                        mockMvc.perform(get("/api/subjects/" + subject.getId()).session(admin))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(asAdmin.get("data").get("visits").size()).isEqualTo(1);
    }

    // ===================================================================
    // 4. Anonymous
    // ===================================================================

    @Test
    @WithAnonymousUser
    void anonymousIsRefusedForList() throws Exception {
        JsonNode body =
                bodyOf(
                        mockMvc.perform(get("/api/subjects"))
                                .andExpect(status().isUnauthorized())
                                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @WithAnonymousUser
    void anonymousIsRefusedForCreate() throws Exception {
        mockMvc.perform(createRequest("DEMO-ANON", "匿名创建")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithAnonymousUser
    void anonymousIsRefusedForDetail() throws Exception {
        mockMvc.perform(get("/api/subjects/1")).andExpect(status().isUnauthorized());
    }

    // ===================================================================
    // Helpers
    // ===================================================================

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

    private List<String> subjectCodesIn(JsonNode body) {
        List<String> codes = new java.util.ArrayList<>();
        body.get("data").get("items").forEach(item -> codes.add(item.get("subjectCode").asText()));
        return codes;
    }

    private Subject seedSubjectWithCode(String subjectCode) {
        return subjectRepository.save(
                new Subject(
                        subjectCode,
                        "种子档案-" + subjectCode,
                        Status.ACTIVE,
                        "demo-seed-" + subjectCode,
                        Instant.now()));
    }

    private void seedSubjects(String... subjectCodes) {
        for (String code : subjectCodes) {
            seedSubjectWithCode(code);
        }
    }

    private List<String> generateManySubjectCodes(int count) {
        java.util.ArrayList<String> codes = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            codes.add(String.format("DEMO-MANY-%04d", i));
        }
        return codes;
    }

    private Long userId(String username) {
        SysUser user =
                sysUserRepository
                        .findByUsername(username)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "seed user not found: " + username));
        return user.getId();
    }

    private void grantPermission(Long subjectId, String username, PermissionType type) {
        grantPermission(subjectId, userId(username), type);
    }

    private void grantPermission(Long subjectId, Long userId, PermissionType type) {
        subjectPermissionRepository.save(new SubjectPermission(subjectId, userId, type));
    }

    private MockHttpServletRequestBuilder createRequest(String subjectCode, String alias)
            throws Exception {
        return post("/api/subjects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        objectMapper.writeValueAsString(
                                Map.of("subjectCode", subjectCode, "alias", alias)));
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
