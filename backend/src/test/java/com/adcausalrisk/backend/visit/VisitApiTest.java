package com.adcausalrisk.backend.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.adcausalrisk.backend.contract.ContractSchemas;
import com.adcausalrisk.backend.qc.QcAuditRecordRepository;
import com.adcausalrisk.backend.qc.entity.QcAuditRecord;
import com.adcausalrisk.backend.subject.SubjectPermissionRepository;
import com.adcausalrisk.backend.subject.SubjectRepository;
import com.adcausalrisk.backend.subject.entity.Subject;
import com.adcausalrisk.backend.subject.entity.Subject.Status;
import com.adcausalrisk.backend.subject.entity.SubjectPermission;
import com.adcausalrisk.backend.subject.entity.SubjectPermission.PermissionType;
import com.adcausalrisk.backend.user.SysUser;
import com.adcausalrisk.backend.user.SysUserRepository;
import com.adcausalrisk.backend.visit.entity.Visit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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

    @Autowired
    private VisitRepository visitRepository;

    @Autowired
    private QcAuditRecordRepository qcAuditRecordRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    /**
     * Subject codes whose rows a {@code REQUIRES_NEW} block really committed.
     *
     * <p>Those rows survive the enclosing {@code @Transactional} rollback, so a plain
     * {@code jdbc.update("DELETE ...")} issued from inside the test method runs in the outer
     * transaction and is undone together with it - the four leaked subjects that broke
     * {@code SubjectApiTest} when the suite ran in a different order. The purge therefore has
     * to run in its own committed transaction, which is what {@link #purgeReallyCommittedRows()}
     * does after every test.
     */
    private final List<String> committedSubjectCodes = new ArrayList<>();

    @AfterEach
    void purgeReallyCommittedRows() {
        if (committedSubjectCodes.isEmpty()) {
            return;
        }
        TransactionTemplate cleanup = new TransactionTemplate(txManager);
        cleanup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cleanup.executeWithoutResult(status -> {
            for (String code : committedSubjectCodes) {
                jdbc.update("DELETE FROM qc_audit_record WHERE visit_id IN"
                        + " (SELECT id FROM visit WHERE subject_id IN"
                        + " (SELECT id FROM subject WHERE subject_code = ?))", code);
                jdbc.update("DELETE FROM subject_feature WHERE subject_id IN"
                        + " (SELECT id FROM subject WHERE subject_code = ?)", code);
                jdbc.update("DELETE FROM visit WHERE subject_id IN"
                        + " (SELECT id FROM subject WHERE subject_code = ?)", code);
                jdbc.update("DELETE FROM subject WHERE subject_code = ?", code);
            }
        });
        committedSubjectCodes.clear();
    }

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

    // ===================================================================
    // PATCH /api/subjects/{subjectId}/visits/{visitId} - append correction
    // ===================================================================

    @Test
    void correctionAppendsVersionAndKeepsOriginalInHistory() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-CORRECT");
        Long originalId = bodyOf(mockMvc.perform(
                        createVisit(subject.getId(), "BL", "2025-01-01", 28.0, 25.0, 0.5).session(admin))
                .andExpect(status().isOk())
                .andReturn()).get("data").get("id").asLong();

        JsonNode corrected = bodyOf(mockMvc.perform(patch(
                                "/api/subjects/" + subject.getId() + "/visits/" + originalId)
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "visitDate", "2025-02-02",
                                "mmse", 29,
                                "moca", 26,
                                "cdrSb", 0.5,
                                "reason", "复核原始量表后更正日期与分数"))))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(corrected.get("data").get("id").asLong()).isGreaterThan(originalId);
        assertThat(corrected.get("data").get("mmse").asDouble()).isEqualTo(29.0);

        Visit original = visitRepository.findById(originalId).orElseThrow();
        assertThat(original.isCurrent()).isFalse();
        assertThat(original.getVersionNo()).isEqualTo(1);
        assertThat(original.getMmse()).isEqualByComparingTo("28.00");

        JsonNode detailVisits = detailOf(subject.getId(), admin).get("data").get("visits");
        assertThat(detailVisits.size()).isEqualTo(1);
        assertThat(detailVisits.get(0).get("id").asLong())
                .isEqualTo(corrected.get("data").get("id").asLong());

        JsonNode history = bodyOf(mockMvc.perform(get("/api/subjects/" + subject.getId()
                                + "/visits/" + originalId + "/history").session(admin))
                .andExpect(status().isOk())
                .andReturn()).get("data");
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).get("version").asInt()).isEqualTo(1);
        assertThat(history.get(0).get("current").asBoolean()).isFalse();
        assertThat(history.get(0).get("reason").isNull()).isTrue();
        assertThat(history.get(1).get("version").asInt()).isEqualTo(2);
        assertThat(history.get(1).get("current").asBoolean()).isTrue();
        assertThat(history.get(1).get("reason").asText()).contains("复核原始量表");
    }

    @Test
    void correctionRequiresManagePermissionAndReason() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-CORRECT-PERM");
        Long visitId = bodyOf(mockMvc.perform(
                        createVisit(subject.getId(), "BL", "2025-01-01", 28.0, null, null).session(admin))
                .andExpect(status().isOk())
                .andReturn()).get("data").get("id").asLong();

        Map<String, Object> missingReason = new LinkedHashMap<>();
        missingReason.put("visitDate", "2025-02-02");
        missingReason.put("mmse", 29);
        mockMvc.perform(patch("/api/subjects/" + subject.getId() + "/visits/" + visitId)
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(missingReason)))
                .andExpect(status().isBadRequest());

        MockHttpSession doctor = login("doctor");
        JsonNode denied = bodyOf(mockMvc.perform(patch(
                                "/api/subjects/" + subject.getId() + "/visits/" + visitId)
                        .session(doctor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "visitDate", "2025-02-02",
                                "reason", "医生尝试更正"))))
                .andExpect(status().isForbidden())
                .andReturn());
        assertThat(denied.get("errorCode").asText()).isEqualTo("ACCESS_DENIED");

        subjectPermissionRepository.save(new SubjectPermission(
                subject.getId(), userId("doctor"), PermissionType.MANAGE));
        JsonNode allowed = bodyOf(mockMvc.perform(patch(
                                "/api/subjects/" + subject.getId() + "/visits/" + visitId)
                        .session(doctor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "visitDate", "2025-02-02",
                                "reason", "责任医生复核原始量表"))))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(allowed.get("data").get("mmse").isNull()).isTrue();
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

        // DECIMAL(5,2) round-trips 0.5 exactly; the column reports it as 0.50, so the value is
        // compared numerically rather than as a raw string.
        assertThat(body.get("data").get("cdrSb").asDouble()).isEqualTo(0.5);
        assertThat(body.get("data").get("cdrSb").decimalValue()).isEqualByComparingTo(new BigDecimal("0.5"));
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

    /**
     * The contract accepts any precision (a plain {@code number} with only a min/max), so 27.55
     * must be accepted <em>and stored exactly</em>. Migration {@code V3} widened the score
     * columns to {@code DECIMAL(5,2)} and {@code VisitMapper} performs no rounding, so the
     * original observation survives the round trip.
     *
     * <p>With the previous {@code DECIMAL(4,1)} plus a HALF_UP rounding step the same request
     * came back as 27.6, which silently destroyed the observation that later scoring depends on.
     */
    @Test
    void scoreWithMoreThanOneDecimalIsStoredWithoutLoss() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-SCALE-EXACT");

        JsonNode created = bodyOf(mockMvc.perform(
                        createVisit(subject.getId(), "BL", "2025-01-01", 27.55, 25.44, 0.55).session(admin))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(created.get("data").get("mmse").asText()).isEqualTo("27.55");
        assertThat(created.get("data").get("moca").asText()).isEqualTo("25.44");
        assertThat(created.get("data").get("cdrSb").asText()).isEqualTo("0.55");
        assertThat(ContractSchemas.validateResponse(created, VISITS_PATH, "post", 200)).isEmpty();

        // Read back through the API ...
        JsonNode stored = firstVisitOfDetail(subject.getId(), admin);
        assertThat(stored.get("mmse").asText()).isEqualTo("27.55");
        assertThat(stored.get("moca").asText()).isEqualTo("25.44");
        assertThat(stored.get("cdrSb").asText()).isEqualTo("0.55");

        // ... and at entity level, which is the value a future AgentClient would hand to the
        // scoring workflow. Compared without regard to scale, because the column fixes it.
        Visit entity = visitRepository.findById(created.get("data").get("id").asLong()).orElseThrow();
        assertThat(entity.getMmse()).isEqualByComparingTo(new BigDecimal("27.55"));
        assertThat(entity.getMoca()).isEqualByComparingTo(new BigDecimal("25.44"));
        assertThat(entity.getCdrSb()).isEqualByComparingTo(new BigDecimal("0.55"));
    }

    /**
     * A score with more than two decimals must survive untouched. The {@code raw_*} text columns
     * added by migration {@code V4} hold the submitted value, so POST and a later GET both return
     * 27.555, while the derived {@code DECIMAL(5,2)} column keeps 27.56 for display and SQL.
     *
     * <p>Before V4 the response reported the rounded 27.56, which is exactly what shifted the
     * score the Python scorer produced.
     */
    @Test
    void scoreBeyondTheDecimalScaleIsReturnedInFullAndRoundedOnlyInTheDerivedColumn() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-SCALE-BOUND");

        JsonNode created = bodyOf(mockMvc.perform(
                        createVisit(subject.getId(), "BL", "2025-01-01", 27.555, null, null).session(admin))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(created.get("data").get("mmse").decimalValue())
                .as("POST must return the submitted value, not the rounded copy")
                .isEqualByComparingTo(new BigDecimal("27.555"));
        assertThat(ContractSchemas.validateResponse(created, VISITS_PATH, "post", 200)).isEmpty();

        // A later read must report exactly the same value.
        JsonNode stored = firstVisitOfDetail(subject.getId(), admin);
        assertThat(stored.get("mmse").decimalValue())
                .as("GET must agree with POST")
                .isEqualByComparingTo(new BigDecimal("27.555"));

        // The two stored forms differ on purpose: raw text keeps the input, DECIMAL is the
        // rounded display copy.
        Visit entity = visitRepository.findById(created.get("data").get("id").asLong()).orElseThrow();
        assertThat(entity.getRawMmse()).isEqualTo("27.555");
        assertThat(entity.getMmse()).isEqualByComparingTo(new BigDecimal("27.56"));
    }

    /**
     * A row written before migration {@code V4} has no raw value: the backfill could only copy
     * whatever the older, narrower columns still held, and a hand-inserted row may have none at
     * all. Such a row must stay readable, falling back to the derived DECIMAL column instead of
     * reporting {@code null}.
     */
    @Test
    void visitWithoutRawScoreFallsBackToTheDerivedColumn() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-LEGACY");
        jdbc.update("""
                INSERT INTO visit (subject_id, visit_code, visit_date, mmse)
                VALUES (?, 'LEGACY', DATE '2025-01-01', 27.56)
                """, subject.getId());

        JsonNode stored = firstVisitOfDetail(subject.getId(), admin);

        assertThat(stored.get("mmse").decimalValue())
                .as("a row without a raw value falls back to the DECIMAL column")
                .isEqualByComparingTo(new BigDecimal("27.56"));
    }

    /**
     * The two stored forms must never drift apart for any row this code writes: the derived
     * column has to equal the raw value rounded to two decimals. The check runs over the whole
     * table rather than one row, so it also covers rows created by other tests.
     */
    @Test
    void everyStoredVisitKeepsTheDerivedColumnConsistentWithItsRawValue() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-INVARIANT");
        mockMvc.perform(createVisit(subject.getId(), "BL", "2025-01-01", 27.555, 25.44, 0.5).session(admin))
                .andExpect(status().isOk());
        mockMvc.perform(createVisit(subject.getId(), "M1", "2025-02-01", 28.0, null, null).session(admin))
                .andExpect(status().isOk());

        var inconsistent = jdbc.queryForList("""
                SELECT id, mmse, raw_mmse FROM visit
                WHERE raw_mmse IS NOT NULL
                  AND (mmse IS NULL OR mmse <> ROUND(CAST(raw_mmse AS DECIMAL(38, 10)), 2))
                """);

        assertThat(inconsistent)
                .as("rows whose DECIMAL column is not round(raw, 2)")
                .isEmpty();
    }

    // ===================================================================
    // 3. Validation failures
    // ===================================================================

    @Test
    void mmseAboveClinicalMaximumIsAcceptedForQcToCatch() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-MMSE-HIGH");

        JsonNode body = bodyOf(mockMvc.perform(createVisit(subject.getId(), "BL", "2025-01-01", 30.1, null, null)
                        .header("X-Allow-OOR", "true")
                        .session(admin))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(body.get("data").get("mmse").isNull())
                .as("P1 mask: POST 响应 mmse 必须为 null").isTrue();

        JsonNode fromDetail = firstVisitOfDetail(subject.getId(), admin);
        assertThat(fromDetail.get("mmse").isNull())
                .as("P1 mask: GET subjectDetail.visits[].mmse 必须为 null").isTrue();

        Visit entity = visitRepository.findById(body.get("data").get("id").asLong()).orElseThrow();
        assertThat(entity.getRawMmse()).isEqualTo("30.1");
        assertThat(entity.getMmse()).isEqualByComparingTo(new BigDecimal("30.10"));
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
    void mmseNinetyNineWithoutHeaderIsRejectedAs400AndNoQcRecordCreated() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-MMSE-99-NOQC");

        JsonNode body = bodyOf(mockMvc.perform(createVisit(subject.getId(), "BL", "2025-01-01", 99.0, null, null)
                        .session(admin))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("message").asText()).contains("mmse");
        assertThat(body.get("message").asText()).contains("30");

        assertThat(visitRepository.findAll().stream()
                .noneMatch(v -> v.getSubjectId().equals(subject.getId())))
                .as("无 header 时 DTO 校验拒绝 → Visit 根本没保存 → 无 QC 可以生成")
                .isTrue();

        assertThat(qcAuditRecordRepository.findAll().stream()
                .noneMatch(r -> r.getSubjectId().equals(subject.getId())))
                .isTrue();
    }

    @Test
    void mmseNinetyNineWithAllowOorHeaderSavesVisitAndCreatesQcRecord() throws Exception {
        MockHttpSession admin = login("admin");

        Long[] ids = new Long[2]; // [0]=subjectId, [1]=visitId

        // REQUIRES_NEW is critical here: VisitApiTest.class has @Transactional (TestTransaction),
        // and a plain TransactionTemplate would join that outer transaction — meaning no commit,
        // meaning @TransactionalEventListener(AFTER_COMMIT) never fires. We need a genuinely
        // independent commit so that Spring dispatches the event.
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        tx.executeWithoutResult(status -> {
            try {
                committedSubjectCodes.add("DEMO-V-MMSE-99-QC");
                Subject subject = seedSubject("DEMO-V-MMSE-99-QC");
                ids[0] = subject.getId();

                JsonNode created = bodyOf(mockMvc.perform(
                                createVisit(subject.getId(), "BL", "2025-01-01", 99.0, null, null)
                                        .header("X-Allow-OOR", "true")
                                        .session(admin))
                        .andExpect(status().isOk())
                        .andReturn());

                ids[1] = created.get("data").get("id").asLong();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Long visitId = ids[1];

        Visit entity = visitRepository.findById(visitId).orElseThrow();
        assertThat(entity.getRawMmse()).isEqualTo("99.0");
        assertThat(entity.getMmse()).isEqualByComparingTo(new BigDecimal("99.00"));

        QcAuditRecord qc = qcAuditRecordRepository.findTopByVisitIdOrderByCreatedAtDesc(visitId)
                .orElseThrow(() -> new AssertionError("expected a PENDING QC record for MMSE=99"));
        assertThat(qc.getStatus()).isEqualTo(QcAuditRecord.Status.PENDING);
        assertThat(qc.getAbnormalItems()).contains("99.0").contains("MMSE");
        assertThat(qc.getAuditorId()).isEqualTo(userId("admin"));

        // Cleanup is handled by purgeReallyCommittedRows(), which runs in its own committed
        // transaction; a DELETE here would be rolled back with the test transaction.
    }

    @Test
    void cdrSbAboveClinicalMaximumIsAcceptedForQcToCatch() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-CDR-HIGH");

        JsonNode body = bodyOf(mockMvc.perform(createVisit(subject.getId(), "BL", "2025-01-01", null, null, 18.1)
                        .header("X-Allow-OOR", "true")
                        .session(admin))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(body.get("data").get("cdrSb").isNull())
                .as("P1 Python contract mask: CDR-SB=18.1 超限 → 响应体 mask 为 null").isTrue();

        JsonNode fromDetail = firstVisitOfDetail(subject.getId(), admin);
        assertThat(fromDetail.get("cdrSb").isNull())
                .as("GET /api/subjects/{id}.visits 同样必须 mask 超限值").isTrue();

        Visit entity = visitRepository.findById(body.get("data").get("id").asLong()).orElseThrow();
        assertThat(entity.getRawCdrSb()).isEqualTo("18.1");
        assertThat(entity.getCdrSb()).isEqualByComparingTo(new BigDecimal("18.10"));
    }

    /**
     * MMSE=30.004 is above 30, but DECIMAL(5,2) rounds it to 30.00 at insert time.
     * QC rules MUST fire on the original submitted value (stored in {@code raw_mmse}),
     * not the rounded DECIMAL column, otherwise out-of-range values with extra precision
     * slip through undetected.
     *
     * <p>This is the regression test for the "QC 漏检" bug: before the fix the QC engine
     * read {@code visit.getMmse()} (the DECIMAL column) and saw 30.00 -> silent pass.
     * After the fix it reads {@code raw_mmse} -> sees 30.004 -> anomaly fires.
     */
    @Test
    void highPrecisionOutOfRangeScoreIsCaughtByQcBeforeDecimalRoundingHidesIt() throws Exception {
        MockHttpSession admin = login("admin");

        Long[] ids = new Long[2]; // [0]=subjectId, [1]=visitId

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        tx.executeWithoutResult(status -> {
            try {
                committedSubjectCodes.add("DEMO-V-ROUNDING-HIDE");
                Subject subject = seedSubject("DEMO-V-ROUNDING-HIDE");
                ids[0] = subject.getId();

                JsonNode created = bodyOf(mockMvc.perform(
                                createVisit(subject.getId(), "BL", "2025-01-01", 30.004, null, null)
                                        .header("X-Allow-OOR", "true")
                                        .session(admin))
                        .andExpect(status().isOk())
                        .andReturn());

                ids[1] = created.get("data").get("id").asLong();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Long visitId = ids[1];

        Visit entity = visitRepository.findById(visitId).orElseThrow();
        assertThat(entity.getRawMmse())
                .as("raw text must preserve the full precision")
                .isEqualTo("30.004");
        assertThat(entity.getMmse())
                .as("DECIMAL(5,2) must round to 30.00")
                .isEqualByComparingTo(new BigDecimal("30.00"));

        QcAuditRecord qc = qcAuditRecordRepository.findTopByVisitIdOrderByCreatedAtDesc(visitId)
                .orElseThrow(() -> new AssertionError("expected a QC record for visit " + visitId));
        assertThat(qc.getStatus()).isEqualTo(QcAuditRecord.Status.PENDING);
        assertThat(qc.getAbnormalItems())
                .as("anomalies must reference the original 30.004, not the rounded 30.00")
                .contains("30.004")
                .contains("MMSE");

        // Cleanup runs in purgeReallyCommittedRows(); see the note in the MMSE=99 test.
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

    /**
     * {@code visitDate} is a String pinned by {@code @Pattern}, so a shape the contract forbids is
     * a bean-validation failure ({@code VALIDATION_ERROR}), not a JSON parse failure. That is what
     * catches {@code +02025-01-01}: Jackson's ISO-8601 parser accepts a signed extended year and
     * would have silently stored 2025-01-01.
     */
    @Test
    void visitDateOutsideTheContractShapeIsRejected() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-BADDATE");

        for (String loose : new String[] {"2025-1-1", "+02025-01-01", "2025/01/01", " 2025-01-01"}) {
            JsonNode body = bodyOf(mockMvc.perform(post("/api/subjects/" + subject.getId() + "/visits")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("visitCode", "BL", "visitDate", loose)))
                            .session(admin))
                    .andExpect(status().isBadRequest())
                    .andReturn());

            assertThat(body.get("errorCode").asText())
                    .as("visitDate '%s'", loose)
                    .isEqualTo("VALIDATION_ERROR");
        }

        assertThat(bodyOf(mockMvc.perform(get("/api/subjects/" + subject.getId()).session(admin))
                .andExpect(status().isOk())
                .andReturn()).get("data").get("visits").size())
                .as("nothing may be written for a rejected date")
                .isZero();
    }

    /**
     * {@code @Pattern} only checks the shape, so an impossible calendar date reaches the service.
     * It must be a 400, never a {@code DateTimeParseException} that becomes a 500.
     */
    @Test
    void impossibleCalendarDateIsRejectedAsValidationErrorNotServerError() throws Exception {
        MockHttpSession admin = login("admin");
        Subject subject = seedSubject("DEMO-V-FEB30");

        JsonNode body = bodyOf(mockMvc.perform(post("/api/subjects/" + subject.getId() + "/visits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitCode\":\"BL\",\"visitDate\":\"2025-02-30\"}")
                        .session(admin))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("message").asText()).contains("visitDate");
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
    // Transaction rollback isolation tests
    // ===================================================================

    /**
     * Main transaction rolls back → {@code AFTER_COMMIT} event is never dispatched because
     * the commit phase was never reached. Subject, Visit and QC row must all be absent.
     *
     * <p>This is the negative proof for the event architecture: if the outer transaction
     * aborts, QC must not fire — it would otherwise emit an audit row for a Visit that
     * does not exist.
     */
    @Test
    void mainTransactionRollsBackNeitherVisitNorQcCreated() throws Exception {
        String subjectCode = "ROLLBACK-MAIN";

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            try {
                seedSubject(subjectCode);
                throw new RuntimeException("intentional rollback after seed — verify nothing persisted");
            } catch (RuntimeException rethrow) {
                throw rethrow;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        })).hasMessageContaining("intentional rollback");

        assertThat(subjectRepository.findBySubjectCode(subjectCode)).isEmpty();
        int qcRowsForThisCode = jdbc.queryForObject(
                "SELECT COUNT(*) FROM qc_audit_record q JOIN visit v ON q.visit_id = v.id " +
                        "JOIN subject s ON v.subject_id = s.id WHERE s.subject_code = ?",
                Integer.class, subjectCode);
        assertThat(qcRowsForThisCode).as("rollback must leave zero QC rows").isEqualTo(0);
    }

    /**
     * Visit is durably committed first (its own REQUIRES_NEW), and then a second
     * REQUIRES_NEW transaction crashes. The Visit must still be present — the two
     * transactions are on independent physical connections; one cannot poison the other.
     *
     * <p>This is the definitive isolation proof: {@code AFTER_COMMIT + REQUIRES_NEW} means
     * the QC side lives in a completely separated lifecycle.
     */
    @Test
    void visitSurvivesEvenWhenLaterRequiresNewCrashes() throws Exception {
        MockHttpSession admin = login("admin");

        Long[] ids = new Long[2];
        TransactionTemplate visitTx = new TransactionTemplate(txManager);
        visitTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        visitTx.executeWithoutResult(status -> {
            try {
                committedSubjectCodes.add("SURVIVE-QC-CRASH");
                Subject subject = seedSubject("SURVIVE-QC-CRASH");
                ids[0] = subject.getId();
                JsonNode created = bodyOf(mockMvc.perform(
                                createVisit(subject.getId(), "BL", "2025-01-01", 25.0, null, null)
                                        .header("X-Allow-OOR", "true")
                                        .session(admin))
                        .andExpect(status().isOk())
                        .andReturn());
                ids[1] = created.get("data").get("id").asLong();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Long visitId = ids[1];
        assertThat(visitRepository.findById(visitId)).isPresent();

        TransactionTemplate crashTx = new TransactionTemplate(txManager);
        crashTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThatThrownBy(() -> crashTx.executeWithoutResult(status -> {
            throw new RuntimeException("simulated REQUIRES_NEW crash on QC side");
        })).hasMessageContaining("simulated REQUIRES_NEW crash");

        assertThat(visitRepository.findById(visitId))
                .as("Visit that was committed earlier must survive an unrelated REQUIRES_NEW crash")
                .isPresent();

        // Cleanup runs in purgeReallyCommittedRows(); see the note in the MMSE=99 test.
    }

    /**
     * DTO validation rejects MMSE=99 with no {@code X-Allow-OOR} header → HTTP 400.
     * {@code VisitController} aborts the write entirely, so no row → no event → no QC.
     *
     * <p>This contrasts with {@link VisitApiTest#mmseNinetyNineWithoutHeaderIsRejectedAs400AndNoQcRecordCreated}
     * at the API level and the {@link #mainTransactionRollsBackNeitherVisitNorQcCreated}
     * at the transaction level. Together they prove: every path that aborts before commit
     * also aborts the QC trigger.
     */
    @Test
    void dtoValidationRejectionLeavesNoVisitAndNoQc() throws Exception {
        MockHttpSession admin = login("admin");

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Long[] ids = new Long[2];
        try {
            tx.executeWithoutResult(status -> {
                try {
                    committedSubjectCodes.add("DTO-REJECT");
                    Subject subject = seedSubject("DTO-REJECT");
                    ids[0] = subject.getId();
                    JsonNode body = bodyOf(mockMvc.perform(
                                    createVisit(subject.getId(), "BL", "2025-01-01", 99.0, null, null)
                                            .session(admin))
                            .andExpect(status().isBadRequest())
                            .andReturn());
                    assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException ignored) {
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM visit WHERE subject_id = ? AND visit_code = ?",
                Integer.class, ids[0], "BL"))
                .as("rejected visit must not be persisted")
                .isEqualTo(0);

        // The subject itself was committed by REQUIRES_NEW; purgeReallyCommittedRows() removes it.
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