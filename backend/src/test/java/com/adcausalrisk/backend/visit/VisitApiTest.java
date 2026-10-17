package com.adcausalrisk.backend.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.adcausalrisk.backend.contract.ContractSchemas;
import com.adcausalrisk.backend.subject.SubjectPermissionRepository;
import com.adcausalrisk.backend.subject.SubjectRepository;
import com.adcausalrisk.backend.subject.entity.Subject;
import com.adcausalrisk.backend.subject.entity.Subject.Status;
import com.adcausalrisk.backend.subject.entity.SubjectPermission;
import com.adcausalrisk.backend.subject.entity.SubjectPermission.PermissionType;
import com.adcausalrisk.backend.user.SysUser;
import com.adcausalrisk.backend.user.SysUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end integration tests for the single visit endpoint
 * ({@code POST /api/subjects/{subjectId}/visits}), including the read closure through
 * {@code GET /api/subjects/{subjectId}} -> {@code SubjectDetail.visits}.
 *
 * <p>Runs against the real Spring context, the real SecurityFilterChain and an in-memory H2
 * (test profile), with responses validated against the frozen contract.
 *
 * <p>Authentication is established the way the browser does it: {@code POST /api/auth/login}
 * returns a session which is then reused, so the {@code AppUserPrincipal} produced by
 * {@code CustomUserDetailsService} is what reaches the controller (rather than the default
 * Spring Security {@code User} that {@code @WithMockUser} would inject).
 *
 * <p>Every test is {@code @Transactional}, so the inserted subjects and visits are rolled
 * back. The class deliberately repeats the exact annotations of {@code SubjectApiTest} so
 * Spring reuses one cached application context for both.
 *
 * <p>The one branch this class cannot reach deterministically - the database unique
 * constraint firing after the duplicate pre-check has passed - is covered by
 * {@link VisitServiceTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class VisitApiTest {

    private static final String DEMO_PASSWORD = "DemoPass!2026";

    private static final String VISITS_PATH = "/api/subjects/{subjectId}/visits";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private SubjectPermissionRepository subjectPermissionRepository;

    @Autowired
    private SysUserRepository sysUserRepository;

    // ===================================================================
    // 1. POST /api/subjects/{subjectId}/visits - happy path
    // ===================================================================

    @Test
    void adminCreatesVisitWithAllScores() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-ADMIN");

        JsonNode body = bodyOf(mockMvc.perform(createVisit(subject.getId(), "BL", "2025-01-01", 28.0, 25.0, 0.5)
                        .session(admin))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("errorCode").isNull()).isTrue();
        assertThat(body.get("data").get("id").asLong()).isPositive();
        assertThat(body.get("data").get("subjectId").asLong()).isEqualTo(subject.getId());
        assertThat(body.get("data").get("visitCode").asText()).isEqualTo("BL");
        assertThat(body.get("data").get("visitDate").asText()).isEqualTo("2025-01-01");
        assertThat(body.get("data").get("mmse").asDouble()).isEqualTo(28.0);
        assertThat(body.get("data").get("moca").asDouble()).isEqualTo(25.0);
        assertThat(body.get("data").get("cdrSb").asDouble()).isEqualTo(0.5);
        assertThat(ContractSchemas.validateResponse(body, VISITS_PATH, "post", 200)).isEmpty();
    }

    @Test
    void researcherCreatesVisit() throws Exception {
        MockHttpSession researcher = login("researcher");
        Subject subject = seedSubject("DEMO-V-RES");

        JsonNode body = bodyOf(mockMvc.perform(createVisit(subject.getId(), "BL", "2025-01-01", null, null, null)
                        .session(researcher))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(body.get("data").get("visitCode").asText()).isEqualTo("BL");
    }

    @Test
    void visitWithoutScoresStoresNullAndNotZero() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-NOSCORE");

        JsonNode created = bodyOf(mockMvc.perform(
                        createVisit(subject.getId(), "BL", "2025-01-01", null, null, null).session(admin))
                .andExpect(status().isOk())
                .andReturn()).get("data");

        // The keys must be present and null: a missing score is never turned into 0.
        assertThat(created.has("mmse")).isTrue();
        assertThat(created.get("mmse").isNull()).isTrue();
        assertThat(created.get("moca").isNull()).isTrue();
        assertThat(created.get("cdrSb").isNull()).isTrue();

        JsonNode fromDetail = firstVisitOfDetail(subject.getId(), admin);
        assertThat(fromDetail.get("mmse").isNull()).isTrue();
        assertThat(fromDetail.get("cdrSb").isNull()).isTrue();
    }

    @Test
    void createdVisitAppearsInSubjectDetail() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-DETAIL");

        mockMvc.perform(createVisit(subject.getId(), "BL", "2025-01-01", 28.0, 25.0, 0.5).session(admin))
                .andExpect(status().isOk());

        JsonNode detail = detailOf(subject.getId(), admin);

        assertThat(detail.get("data").get("subject").get("id").asLong()).isEqualTo(subject.getId());
        assertThat(detail.get("data").get("visits").size()).isEqualTo(1);
        JsonNode visit = detail.get("data").get("visits").get(0);
        assertThat(visit.get("visitCode").asText()).isEqualTo("BL");
        assertThat(visit.get("visitDate").asText()).isEqualTo("2025-01-01");
        assertThat(visit.get("mmse").asDouble()).isEqualTo(28.0);
        assertThat(visit.get("subjectId").asLong()).isEqualTo(subject.getId());
        // features is still empty until the feature endpoint exists
        assertThat(detail.get("data").get("features").size()).isZero();
        assertThat(ContractSchemas.validateResponse(detail, "/api/subjects/{subjectId}", "get", 200)).isEmpty();
    }

    @Test
    void detailReturnsVisitsOrderedByVisitDateThenId() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-ORDER");

        // Inserted deliberately out of order.
        mockMvc.perform(createVisit(subject.getId(), "M2", "2025-03-01", null, null, null).session(admin))
                .andExpect(status().isOk());
        mockMvc.perform(createVisit(subject.getId(), "BL", "2025-01-01", null, null, null).session(admin))
                .andExpect(status().isOk());
        mockMvc.perform(createVisit(subject.getId(), "M1", "2025-02-01", null, null, null).session(admin))
                .andExpect(status().isOk());

        JsonNode visits = detailOf(subject.getId(), admin).get("data").get("visits");

        assertThat(visits.size()).isEqualTo(3);
        assertThat(visits.get(0).get("visitDate").asText()).isEqualTo("2025-01-01");
        assertThat(visits.get(1).get("visitDate").asText()).isEqualTo("2025-02-01");
        assertThat(visits.get(2).get("visitDate").asText()).isEqualTo("2025-03-01");
    }

    @Test
    void sameVisitCodeIsAllowedOnDifferentSubjects() throws Exception {
        MockHttpSession admin = login("admin");
        Subject first = seedSubject("DEMO-V-DUP-A");
        Subject second = seedSubject("DEMO-V-DUP-B");

        // visit_code is unique per subject (uq_visit_subject_code), not globally.
        mockMvc.perform(createVisit(first.getId(), "BL", "2025-01-01", null, null, null).session(admin))
                .andExpect(status().isOk());
        mockMvc.perform(createVisit(second.getId(), "BL", "2025-01-01", null, null, null).session(admin))
                .andExpect(status().isOk());

        assertThat(detailOf(first.getId(), admin).get("data").get("visits").size()).isEqualTo(1);
        assertThat(detailOf(second.getId(), admin).get("data").get("visits").size()).isEqualTo(1);
    }

    // ===================================================================
    // 2. Boundaries
    // ===================================================================

    @Test
    void scoreLowerBoundsAreAccepted() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-MIN");

        JsonNode body = bodyOf(mockMvc.perform(createVisit(subject.getId(), "BL", "2025-01-01", 0.0, 0.0, 0.0)
                        .session(admin))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(body.get("data").get("mmse").asDouble()).isZero();
        assertThat(body.get("data").get("cdrSb").asDouble()).isZero();
    }

    @Test
    void scoreUpperBoundsAreAccepted() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-MAX");

        JsonNode body = bodyOf(mockMvc.perform(createVisit(subject.getId(), "BL", "2025-01-01", 30.0, 30.0, 18.0)
                        .session(admin))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(body.get("data").get("mmse").asDouble()).isEqualTo(30.0);
        assertThat(body.get("data").get("moca").asDouble()).isEqualTo(30.0);
        assertThat(body.get("data").get("cdrSb").asDouble()).isEqualTo(18.0);
    }

    @Test
    void visitCodeOfMaximumLengthIsAccepted() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-LONGCODE");

        String code = "V".repeat(50);
        JsonNode body = bodyOf(mockMvc.perform(createVisit(subject.getId(), code, "2025-01-01", null, null, null)
                        .session(admin))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(body.get("data").get("visitCode").asText()).isEqualTo(code);
    }

    @Test
    void oneDecimalScoreIsPreserved() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-SCALE");

        JsonNode body = bodyOf(mockMvc.perform(createVisit(subject.getId(), "BL", "2025-01-01", null, null, 0.5)
                        .session(admin))
                .andExpect(status().isOk())
                .andReturn());

        // DECIMAL(4,1) round-trips 0.5 exactly through BigDecimal -> Double -> JSON.
        assertThat(body.get("data").get("cdrSb").asDouble()).isEqualTo(0.5);
        assertThat(body.get("data").get("cdrSb").asText()).isEqualTo("0.5");
    }

    @Test
    void visitDateIsEchoedAsIsoDate() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-DATE");

        JsonNode body = bodyOf(mockMvc.perform(createVisit(subject.getId(), "BL", "2024-12-31", null, null, null)
                        .session(admin))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(body.get("data").get("visitDate").asText()).isEqualTo("2024-12-31");
    }

    // ===================================================================
    // 3. Validation failures
    // ===================================================================

    @Test
    void mmseAboveMaximumIsRejected() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-MMSE-HIGH");

        JsonNode body = bodyOf(mockMvc.perform(createVisit(subject.getId(), "BL", "2025-01-01", 30.1, null, null)
                        .session(admin))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("message").asText()).contains("mmse");
    }

    @Test
    void mmseBelowMinimumIsRejected() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-MMSE-LOW");

        JsonNode body = bodyOf(mockMvc.perform(createVisit(subject.getId(), "BL", "2025-01-01", -0.1, null, null)
                        .session(admin))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void cdrSbAboveMaximumIsRejected() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-CDR-HIGH");

        JsonNode body = bodyOf(mockMvc.perform(createVisit(subject.getId(), "BL", "2025-01-01", null, null, 18.1)
                        .session(admin))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("message").asText()).contains("cdrSb");
    }

    @Test
    void scoreWithMoreThanOneDecimalIsRejected() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-SCALE-TOO-FINE");

        // DECIMAL(4,1) would round 27.55 to 27.6; the request is refused instead so that the
        // stored value always equals the submitted value.
        JsonNode body = bodyOf(mockMvc.perform(createVisit(subject.getId(), "BL", "2025-01-01", 27.55, null, null)
                        .session(admin))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("message").asText()).contains("mmse");
    }

    @Test
    void blankVisitCodeIsRejected() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-BLANK");

        JsonNode body = bodyOf(mockMvc.perform(createVisitRequest(subject.getId(), "  ", "2025-01-01")
                        .session(admin))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("message").asText()).contains("visitCode");
    }

    @Test
    void visitCodeLongerThanFiftyIsRejected() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-TOOLONG");

        JsonNode body = bodyOf(mockMvc.perform(createVisit(subject.getId(), "V".repeat(51), "2025-01-01",
                        null, null, null).session(admin))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void missingVisitDateIsRejected() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-NODATE");

        JsonNode body = bodyOf(mockMvc.perform(post("/api/subjects/" + subject.getId() + "/visits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitCode\":\"BL\"}")
                        .session(admin))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("message").asText()).contains("visitDate");
    }

    @Test
    void malformedVisitDateIsRejectedAsMalformedJsonNotAsValidationError() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-BADDATE");

        // "2025-1-1" is not ISO-8601, so it fails during deserialization (MALFORMED_JSON)
        // rather than during bean validation (VALIDATION_ERROR).
        JsonNode body = bodyOf(mockMvc.perform(post("/api/subjects/" + subject.getId() + "/visits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitCode\":\"BL\",\"visitDate\":\"2025-1-1\"}")
                        .session(admin))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("MALFORMED_JSON");
    }

    @Test
    void unknownRequestPropertyIsRejected() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-UNKNOWN");

        JsonNode body = bodyOf(mockMvc.perform(post("/api/subjects/" + subject.getId() + "/visits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitCode\":\"BL\",\"visitDate\":\"2025-01-01\",\"subjectId\":1}")
                        .session(admin))
                .andExpect(status().isBadRequest())
                .andReturn());

        // The contract sets additionalProperties: false on Visit.
        assertThat(body.get("errorCode").asText()).isEqualTo("MALFORMED_JSON");
    }

    // ===================================================================
    // 4. Business failures
    // ===================================================================

    @Test
    void duplicateVisitCodeOnSameSubjectIsRejectedAndDoesNotOverwrite() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-DUP");

        mockMvc.perform(createVisit(subject.getId(), "BL", "2025-01-01", 28.0, null, null).session(admin))
                .andExpect(status().isOk());

        JsonNode body = bodyOf(mockMvc.perform(createVisit(subject.getId(), "BL", "2025-02-02", 20.0, null, null)
                        .session(admin))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("message").asText()).contains("已存在");

        JsonNode visits = detailOf(subject.getId(), admin).get("data").get("visits");
        assertThat(visits.size()).isEqualTo(1);
        assertThat(visits.get(0).get("visitDate").asText()).isEqualTo("2025-01-01");
        assertThat(visits.get(0).get("mmse").asDouble()).isEqualTo(28.0);
    }

    @Test
    void visitOnNonExistentSubjectIsRejectedAsValidationErrorNotServerError() throws Exception {
        MockHttpSession admin = login("admin");

        JsonNode body = bodyOf(mockMvc.perform(createVisit(999_999L, "BL", "2025-01-01", null, null, null)
                        .session(admin))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("message").asText()).contains("档案不存在");
    }

    @Test
    void nonNumericSubjectIdIsRejected() throws Exception {
        MockHttpSession admin = login("admin");

        JsonNode body = bodyOf(mockMvc.perform(post("/api/subjects/abc/visits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitCode\":\"BL\",\"visitDate\":\"2025-01-01\"}")
                        .session(admin))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
    }

    // ===================================================================
    // 5. Permissions
    // ===================================================================

    @Test
    void doctorCannotCreateVisit() throws Exception {
        MockHttpSession doctor = login("doctor");
        Subject subject = seedSubject("DEMO-V-DOCTOR");

        JsonNode body = bodyOf(mockMvc.perform(createVisit(subject.getId(), "BL", "2025-01-01", null, null, null)
                        .session(doctor))
                .andExpect(status().isForbidden())
                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void patientCannotCreateVisit() throws Exception {
        MockHttpSession patient = login("patient");
        Subject subject = seedSubject("DEMO-V-PATIENT");

        JsonNode body = bodyOf(mockMvc.perform(createVisit(subject.getId(), "BL", "2025-01-01", null, null, null)
                        .session(patient))
                .andExpect(status().isForbidden())
                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void doctorWithManageGrantStillCannotCreateVisit() throws Exception {
        MockHttpSession doctor = login("doctor");
        Subject subject = seedSubject("DEMO-V-MANAGE");
        // Visit creation is guarded by SubjectAccessPolicy.canCreate, which is role-based;
        // a per-subject MANAGE grant does not entitle the holder to create visits.
        subjectPermissionRepository.save(new SubjectPermission(
                subject.getId(), userId("doctor"), PermissionType.MANAGE));

        JsonNode body = bodyOf(mockMvc.perform(createVisit(subject.getId(), "BL", "2025-01-01", null, null, null)
                        .session(doctor))
                .andExpect(status().isForbidden())
                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @WithAnonymousUser
    void anonymousIsRefusedForCreate() throws Exception {
        JsonNode body = bodyOf(mockMvc.perform(post("/api/subjects/1/visits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitCode\":\"BL\",\"visitDate\":\"2025-01-01\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("UNAUTHENTICATED");
    }

    // ===================================================================
    // Helpers
    // ===================================================================

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

    private Subject seedSubject(String subjectCode) {
        return subjectRepository.save(new Subject(subjectCode, "种子档案-" + subjectCode,
                Status.ACTIVE, "demo-seed-" + subjectCode, Instant.now()));
    }

    private Long userId(String username) {
        SysUser user = sysUserRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("seed user not found: " + username));
        return user.getId();
    }

    private MockHttpServletRequestBuilder createVisit(Long subjectId, String visitCode, String visitDate,
                                                      Double mmse, Double moca, Double cdrSb) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("visitCode", visitCode);
        body.put("visitDate", visitDate);
        if (mmse != null) {
            body.put("mmse", mmse);
        }
        if (moca != null) {
            body.put("moca", moca);
        }
        if (cdrSb != null) {
            body.put("cdrSb", cdrSb);
        }
        return createVisitRequest(subjectId, body);
    }

    private MockHttpServletRequestBuilder createVisitRequest(Long subjectId,
                                                             Map<String, Object> body) throws Exception {
        return post("/api/subjects/" + subjectId + "/visits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private MockHttpServletRequestBuilder createVisitRequest(Long subjectId, String visitCode,
                                                             String visitDate) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("visitCode", visitCode);
        body.put("visitDate", visitDate);
        return createVisitRequest(subjectId, body);
    }

    private JsonNode detailOf(Long subjectId, MockHttpSession session) throws Exception {
        return bodyOf(mockMvc.perform(get("/api/subjects/" + subjectId).session(session))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode firstVisitOfDetail(Long subjectId, MockHttpSession session) throws Exception {
        return detailOf(subjectId, session).get("data").get("visits").get(0);
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
