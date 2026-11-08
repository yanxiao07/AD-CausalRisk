package com.adcausalrisk.backend.closeout;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.adcausalrisk.backend.agent.*;
import com.adcausalrisk.backend.common.*;
import com.fasterxml.jackson.databind.*;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Real MVC/security/database tests on synthetic fixtures; only the upstream agent is mocked. */
@SpringBootTest(
        properties =
                "spring.datasource.url=jdbc:h2:mem:business_closeout;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BusinessCloseoutTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean RestAgentClient agent;
    long subject;
    String code;
    MockHttpSession admin;

    @BeforeEach
    void setup() throws Exception {
        code = "DEMO-CLOSE-" + UUID.randomUUID().toString().substring(0, 8);
        admin = login("admin", "DemoPass!2026");
        subject =
                data(call(
                                post("/api/subjects"),
                                admin,
                                Map.of("subjectCode", code, "alias", "合成验收", "status", "ACTIVE"),
                                200))
                        .path("id")
                        .asLong();
        when(agent.createRun(any())).thenAnswer(invocation -> result(invocation.getArgument(0)));
    }

    @AfterEach
    void cleanup() throws Exception {
        for (int i = 0;
                i < 100 && count("assessment_task WHERE status IN ('PENDING','RUNNING')") > 0;
                i++) Thread.sleep(20);
        jdbc.update("DELETE FROM task_input_snapshot");
        jdbc.update("DELETE FROM assessment_review");
        jdbc.update("DELETE FROM risk_assessment");
        jdbc.update("DELETE FROM assessment_task");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM qc_audit_record");
        jdbc.update("DELETE FROM quality_check");
        jdbc.update("DELETE FROM follow_up_plan");
        jdbc.update("DELETE FROM subject_feature");
        jdbc.update("DELETE FROM visit");
        jdbc.update("DELETE FROM subject_permission");
        jdbc.update("DELETE FROM data_import_job");
        jdbc.update("DELETE FROM subject");
        jdbc.update(
                "DELETE FROM user_preferences WHERE user_id IN (SELECT id FROM sys_user WHERE"
                        + " username LIKE 'close_%')");
        jdbc.update(
                "DELETE FROM user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username"
                        + " LIKE 'close_%')");
        jdbc.update(
                "DELETE FROM audit_log WHERE actor_id IN (SELECT id FROM sys_user WHERE username"
                        + " LIKE 'close_%')");
        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'close_%'");
    }

    int count(String from) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + from, Integer.class);
    }

    JsonNode data(MvcResult response) throws Exception {
        return json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
    }

    MvcResult call(
            MockHttpServletRequestBuilder request,
            MockHttpSession session,
            Object body,
            int expected)
            throws Exception {
        request.session(session);
        if (body != null)
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        var result = mvc.perform(request).andExpect(status().is(expected)).andReturn();
        if (result.getResponse().getContentType() != null
                && result.getResponse().getContentType().contains("application/json")) {
            var document =
                    json.readTree(
                            java.nio.file.Files.readString(
                                    java.nio.file.Path.of(
                                            "../contracts/java-business.target.openapi.json")));
            var entries = document.path("paths").fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                String pattern = entry.getKey().replaceAll("\\{[^}]+}", "[^/]+");
                if (entry.getValue()
                                .has(
                                        result.getRequest()
                                                .getMethod()
                                                .toLowerCase(java.util.Locale.ROOT))
                        && result.getRequest().getRequestURI().matches(pattern)) {
                    var method = result.getRequest().getMethod().toLowerCase(java.util.Locale.ROOT);
                    assertThat(
                                    com.adcausalrisk.backend.contract.ContractSchemas
                                            .validateResponse(
                                                    json.readTree(
                                                            result.getResponse()
                                                                    .getContentAsByteArray()),
                                                    entry.getKey(),
                                                    method,
                                                    expected))
                            .as(entry.getKey() + " " + method)
                            .isEmpty();
                    break;
                }
            }
        }
        return result;
    }

    MockHttpSession login(String username, String password) throws Exception {
        return (MockHttpSession)
                mvc.perform(
                                post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsBytes(
                                                        Map.of(
                                                                "username",
                                                                username,
                                                                "password",
                                                                password))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    long user(String name) {
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, name);
    }

    void grant(String name, String permission) throws Exception {
        call(
                post("/api/subjects/" + subject + "/permissions"),
                admin,
                Map.of("userId", user(name), "permissionType", permission),
                200);
    }

    JsonNode visit(String id, BigDecimal mmse) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("visitCode", id);
        body.put("visitDate", "2025-01-01");
        body.put("mmse", mmse);
        body.put("moca", null);
        body.put("cdrSb", null);
        return data(
                call(
                        post("/api/subjects/" + subject + "/visits").header("X-Allow-OOR", "true"),
                        admin,
                        body,
                        200));
    }

    JsonNode task(String key, boolean async) throws Exception {
        return data(
                call(
                        post("/api/assessment-tasks" + (async ? "/submit" : ""))
                                .header("Idempotency-Key", key),
                        admin,
                        Map.of(
                                "subjectId",
                                subject,
                                "modelVersion",
                                AgentRequest.MODEL_VERSION,
                                "predictionWindowMonths",
                                24),
                        200));
    }

    Map<String, Object> follow() {
        return Map.of(
                "subjectId",
                subject,
                "scheduledAt",
                Instant.now().plusSeconds(86400).toString(),
                "purpose",
                "合成随访");
    }

    AgentResult result(AgentRequest request) {
        return new AgentResult(
                request.runId(),
                "EXPLANATION",
                request.dataVersion(),
                AgentResult.Status.COMPLETED,
                AgentResult.CONTRACT_VERSION,
                AgentResult.WORKFLOW_VERSION,
                AgentResult.MODEL_VERSION,
                AgentResult.QualityStatus.WARN,
                new BigDecimal("0.42"),
                AgentResult.RiskLevel.MEDIUM,
                List.of(),
                new AgentResult.Graph(List.of(), List.of(), List.of(), "synthetic-prior-v1"),
                List.of(),
                List.of(),
                List.of("合成演示"),
                List.of(
                        new AgentResult.Stage(
                                AgentResult.StageName.EXPLANATION,
                                AgentResult.StageStatus.COMPLETED,
                                1,
                                Map.of())),
                AgentResult.DATA_SOURCE,
                true);
    }

    @Test
    void clinicalWritesRequireDoctorRawGrantOrAdministrator() throws Exception {
        grant("patient", "SUMMARY_READ");
        grant("doctor", "SUMMARY_READ");
        for (String name : List.of("patient", "doctor", "researcher"))
            call(post("/api/follow-ups"), login(name, "DemoPass!2026"), follow(), 403);
        assertThat(count("follow_up_plan")).isZero();
        grant("doctor", "VIEW");
        call(post("/api/follow-ups"), login("doctor", "DemoPass!2026"), follow(), 200);
        assertThat(count("follow_up_plan")).isEqualTo(1);
    }

    @Test
    void cancelledPlanCannotBeRescheduledByOmittingStatus() throws Exception {
        long id = data(call(post("/api/follow-ups"), admin, follow(), 200)).path("id").asLong();
        call(patch("/api/follow-ups/" + id), admin, Map.of("status", "CANCELLED"), 200);
        call(patch("/api/follow-ups/" + id), admin, Map.of("purpose", "绕过终态"), 400);
        call(
                patch("/api/follow-ups/" + id),
                admin,
                Map.of("scheduledAt", Instant.now().toString()),
                400);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT purpose FROM follow_up_plan WHERE id=?", String.class, id))
                .isEqualTo("合成随访");
    }

    @Test
    void researchDoctorNeedsAnExplicitClinicalObjectGrantDespiteGlobalResearchRead()
            throws Exception {
        var mixed = login("research_doctor", "DemoPass!2026");
        call(get("/api/subjects/" + subject), mixed, null, 200);
        call(post("/api/follow-ups"), mixed, follow(), 403);
        long assessment = task("mixed-clinical", false).path("assessmentId").asLong();
        var review = Map.of("decision", "ACKNOWLEDGED", "comment", "合成多角色授权检查");
        call(post("/api/assessments/" + assessment + "/review"), mixed, review, 403);
        grant("research_doctor", "VIEW");
        call(post("/api/follow-ups"), mixed, follow(), 200);
        call(post("/api/assessments/" + assessment + "/review"), mixed, review, 200);
    }

    @Test
    void qualityChecksAreIndependentAndHumanApprovalDoesNotRepairMissingCognition()
            throws Exception {
        visit("BL", null);
        jdbc.update("UPDATE qc_audit_record SET status='APPROVED' WHERE subject_id=?", subject);
        var first =
                data(call(post("/api/subjects/" + subject + "/quality-checks"), admin, null, 200));
        var second =
                data(call(post("/api/subjects/" + subject + "/quality-checks"), admin, null, 200));
        assertThat(first.path("qualityStatus").asText()).isEqualTo("BLOCKED");
        assertThat(second.path("id").asLong()).isNotEqualTo(first.path("id").asLong());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT input_digest FROM quality_check WHERE id=?",
                                String.class,
                                second.path("id").asLong()))
                .hasSize(64);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT coverage_json FROM quality_check WHERE id=?",
                                String.class,
                                first.path("id").asLong()))
                .contains("missing", "sources");
    }

    @Test
    void featureDetailContainsStoredValuesAndPatientCannotReadSources() throws Exception {
        String before =
                jdbc.queryForObject(
                        "SELECT data_version FROM subject WHERE id=?", String.class, subject);
        call(
                post("/api/subjects/" + subject + "/features"),
                admin,
                Map.of(
                        "featureName",
                        "APOE_E4_COUNT",
                        "featureValue",
                        1,
                        "recordedAt",
                        "2025-01-01",
                        "unit",
                        "count"),
                200);
        var detail = data(call(get("/api/subjects/" + subject), admin, null, 200));
        assertThat(detail.path("features").size()).isEqualTo(1);
        assertThat(detail.path("subject").path("dataVersion").asText()).isNotEqualTo(before);
        grant("patient", "SUMMARY_READ");
        var patient = login("patient", "DemoPass!2026");
        assertThat(
                        data(call(get("/api/subjects/" + subject), patient, null, 200))
                                .path("features")
                                .size())
                .isZero();
        call(get("/api/subjects/" + subject + "/feature-sources"), patient, null, 403);
    }

    @Test
    void observationRequiresSourceAndMissingReasonWithoutPartialWrites() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "visit");
        body.put("visitCode", "BL");
        body.put("visitDate", "2025-01-01");
        body.put("source", "合成表单");
        call(post("/api/subjects/" + subject + "/observations"), admin, body, 400);
        assertThat(count("visit")).isZero();
        body.put("missingReason", "合成缺失");
        call(post("/api/subjects/" + subject + "/observations"), admin, body, 200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT source FROM visit WHERE subject_id=?",
                                String.class,
                                subject))
                .isEqualTo("合成表单");
    }

    @Test
    void rawOutOfRangeValueIsNeverRoundedIntoValidInput() throws Exception {
        visit("BL", new BigDecimal("30.004"));
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "AGENT_FAILED: internal-secret"))
                .when(agent)
                .createRun(any());
        var response = task("raw", false);
        assertThat(response.path("status").asText()).isEqualTo("FAILED");
        assertThat(response.toString()).doesNotContain("internal-secret");
        var capture = org.mockito.ArgumentCaptor.forClass(AgentRequest.class);
        verify(agent).createRun(capture.capture());
        assertThat(capture.getValue().visits().get(0).mmse()).isEqualByComparingTo("30.004");
    }

    @Test
    void inputSnapshotAndQualityBindingSurviveLaterSourceChangesAndReplay() throws Exception {
        visit("BL", new BigDecimal("28"));
        var first = task("snapshot", false);
        long id = first.path("taskId").asLong();
        var before = data(call(get("/api/assessment-tasks/" + id + "/input"), admin, null, 200));
        task("snapshot", false);
        assertThat(count("quality_check")).isEqualTo(1);
        verify(agent, times(1)).createRun(any());
        visit("M06", new BigDecimal("27"));
        var after = data(call(get("/api/assessment-tasks/" + id + "/input"), admin, null, 200));
        assertThat(after).isEqualTo(before);
        assertThat(before.path("request").path("visits").size()).isEqualTo(1);
        assertThat(before.path("qualityCheckId").asLong()).isPositive();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT data_version FROM subject WHERE id=?",
                                String.class,
                                subject))
                .isNotEqualTo(before.path("dataVersion").asText());
    }

    @Test
    void asynchronousTasksExposeRunningAndQueuedCancellationWithoutSecondAgentCall()
            throws Exception {
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            started.countDown();
                            if (!release.await(10, TimeUnit.SECONDS))
                                throw new IllegalStateException("timeout");
                            return result(invocation.getArgument(0));
                        })
                .when(agent)
                .createRun(any());
        List<Long> ids = new ArrayList<>();
        try {
            ids.add(task("async1", true).path("taskId").asLong());
            ids.add(task("async2", true).path("taskId").asLong());
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var queued = task("async3", true);
            ids.add(queued.path("taskId").asLong());
            assertThat(queued.path("status").asText()).isEqualTo("PENDING");
            assertThat(
                            data(call(get("/api/assessment-tasks/" + ids.get(0)), admin, null, 200))
                                    .path("status")
                                    .asText())
                    .isEqualTo("RUNNING");
            call(post("/api/assessment-tasks/" + ids.get(0) + "/cancel"), admin, Map.of(), 409);
            call(post("/api/assessment-tasks/" + ids.get(2) + "/cancel"), admin, Map.of(), 200);
        } finally {
            release.countDown();
        }
        for (int i = 0;
                i < 200 && count("assessment_task WHERE status IN ('PENDING','RUNNING')") > 0;
                i++) Thread.sleep(20);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM assessment_task WHERE id=?",
                                String.class,
                                ids.get(2)))
                .isEqualTo("CANCELLED");
        verify(agent, times(2)).createRun(any());
    }

    @Test
    void researcherCannotReviewAnAssessment() throws Exception {
        long assessment = task("review", false).path("assessmentId").asLong();
        call(
                post("/api/assessments/" + assessment + "/review"),
                login("researcher", "DemoPass!2026"),
                Map.of("decision", "ACKNOWLEDGED", "comment", "越权尝试"),
                403);
        assertThat(count("assessment_review")).isZero();
    }

    @Test
    void passwordResetRevokesExistingSessionAndRoleUpdatesTakeEffectImmediately() throws Exception {
        long id =
                data(call(
                                post("/api/users"),
                                admin,
                                Map.of(
                                        "username",
                                        "close_doctor",
                                        "password",
                                        "123456",
                                        "roles",
                                        List.of("DOCTOR")),
                                200))
                        .path("id")
                        .asLong();
        var session = login("close_doctor", "123456");
        call(get("/api/users"), session, null, 403);
        call(patch("/api/users/" + id), admin, Map.of("roles", List.of("ADMIN")), 200);
        call(get("/api/users"), session, null, 200);
        call(patch("/api/users/" + id), admin, Map.of("password", "654321"), 200);
        call(get("/api/auth/me"), session, null, 401);
        login("close_doctor", "654321");
    }

    @Test
    void objectGrantAndRevokeApplyToAlreadyLoggedInDoctor() throws Exception {
        var doctor = login("doctor", "DemoPass!2026");
        call(get("/api/subjects/" + subject), doctor, null, 403);
        grant("doctor", "VIEW");
        call(get("/api/subjects/" + subject), doctor, null, 200);
        long id =
                jdbc.queryForObject(
                        "SELECT id FROM subject_permission WHERE subject_id=? AND user_id=?",
                        Long.class,
                        subject,
                        user("doctor"));
        call(delete("/api/subjects/" + subject + "/permissions/" + id), admin, null, 200);
        call(get("/api/subjects/" + subject), doctor, null, 403);
    }

    @Test
    void searchAndStatusAreAppliedBeforePagingAndWildcardsAreLiteral() throws Exception {
        for (int i = 0; i < 4; i++)
            call(
                    post("/api/subjects"),
                    admin,
                    Map.of(
                            "subjectCode",
                            code + "-" + i,
                            "alias",
                            i == 3 ? "needle%_" : "other",
                            "status",
                            i == 3 ? "ARCHIVED" : "ACTIVE"),
                    200);
        var page =
                data(
                        call(
                                get("/api/subjects")
                                        .param("keyword", "needle%_")
                                        .param("status", "ARCHIVED")
                                        .param("pageSize", "1"),
                                admin,
                                null,
                                200));
        assertThat(page.path("total").asInt()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("alias").asText()).isEqualTo("needle%_");
        assertThat(
                        data(call(get("/api/subjects").param("keyword", "%"), admin, null, 200))
                                .path("total")
                                .asInt())
                .isEqualTo(1);
    }

    @Test
    void mappedImportPreservesOriginalAndCreatesQcForMissingScores() throws Exception {
        String original = "person,code,date,a,b,c\n" + code + ",BL,2025-01-01,,,\n";
        var upload =
                new MockMultipartFile(
                        "file",
                        "synthetic.csv",
                        "text/csv",
                        original.getBytes(StandardCharsets.UTF_8));
        var response =
                mvc.perform(
                                multipart("/api/import-jobs")
                                        .file(upload)
                                        .param(
                                                "mapping",
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "person",
                                                                "subjectCode",
                                                                "code",
                                                                "visitCode",
                                                                "date",
                                                                "visitDate",
                                                                "a",
                                                                "mmse",
                                                                "b",
                                                                "moca",
                                                                "c",
                                                                "cdrSb")))
                                        .header("Idempotency-Key", "mapped")
                                        .session(admin))
                        .andExpect(status().isOk())
                        .andReturn();
        long id = data(response).path("jobId").asLong();
        assertThat(data(response).path("status").asText()).isEqualTo("PREVIEW_READY");
        call(post("/api/import-jobs/" + id + "/commit"), admin, null, 200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT original_csv_text FROM data_import_job WHERE id=?",
                                String.class,
                                id))
                .isEqualTo(original);
        assertThat(count("qc_audit_record")).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT import_job_id FROM visit WHERE subject_id=?",
                                Long.class,
                                subject))
                .isEqualTo(id);
    }

    @Test
    void reportExportsAreAuthorizedAndPdfContainsReadableChinese() throws Exception {
        long assessment = task("export", false).path("assessmentId").asLong();
        grant("patient", "SUMMARY_READ");
        for (String format : List.of("html", "json", "pdf"))
            call(
                    get("/api/reports/" + assessment + "/download").param("format", format),
                    login("patient", "DemoPass!2026"),
                    null,
                    403);
        var response =
                call(
                                get("/api/reports/" + assessment + "/download")
                                        .param("format", "pdf"),
                                admin,
                                null,
                                200)
                        .getResponse();
        assertThat(response.getContentType()).startsWith("application/pdf");
        try (var pdf = Loader.loadPDF(response.getContentAsByteArray())) {
            assertThat(new PDFTextStripper().getText(pdf))
                    .contains("合成研究排序报告", "不构成诊断", "SYNTHETIC_DEMO");
        }
        assertThat(
                        call(
                                        get("/api/reports/" + assessment + "/download")
                                                .param("format", "html"),
                                        admin,
                                        null,
                                        200)
                                .getResponse()
                                .getContentAsString(StandardCharsets.UTF_8))
                .contains("<!doctype html>");
        assertThat(
                        json.readTree(
                                        call(
                                                        get("/api/reports/"
                                                                        + assessment
                                                                        + "/download")
                                                                .param("format", "json"),
                                                        admin,
                                                        null,
                                                        200)
                                                .getResponse()
                                                .getContentAsByteArray())
                                .path("inputBinding")
                                .size())
                .isEqualTo(1);
    }

    @Test
    void patientFollowupUsesFuturePlanAndReturnsOverdueCountWithoutNotes() throws Exception {
        grant("patient", "SUMMARY_READ");
        var past = new HashMap<>(follow());
        past.put("scheduledAt", Instant.now().minusSeconds(86400).toString());
        call(post("/api/follow-ups"), admin, past, 200);
        call(post("/api/follow-ups"), admin, follow(), 200);
        var view =
                data(
                        call(
                                get("/api/patients/me/follow-up"),
                                login("patient", "DemoPass!2026"),
                                null,
                                200));
        assertThat(view.path("overdueCount").asInt()).isEqualTo(1);
        assertThat(Instant.parse(view.path("next").path("scheduledAt").asText()))
                .isAfter(Instant.now());
        assertThat(view.toString()).doesNotContain("ownerId", "notes");
    }

    @Test
    void preferencesPersistAndInvalidModelConfigurationIsRejected() throws Exception {
        var values = Map.of("density", "compact", "fontSize", 20, "notifications", false);
        call(patch("/api/me/preferences"), admin, values, 200);
        assertThat(data(call(get("/api/me/preferences"), admin, null, 200)))
                .isEqualTo(json.valueToTree(values));
        call(patch("/api/me/preferences"), admin, Map.of("fontSize", 100), 400);
        call(patch("/api/system/models/unknown"), admin, Map.of("enabled", false), 400);
        call(get("/api/system/info"), login("researcher", "DemoPass!2026"), null, 403);
    }

    @Test
    void creationAndCompletionAuditBindObjectIdsWithoutRawBody() throws Exception {
        var created = task("audit-binding", false);
        assertThat(
                        jdbc.queryForList(
                                "SELECT summary FROM audit_log WHERE"
                                        + " event_type='ASSESSMENT_TASK_FINISHED'",
                                String.class))
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains(
                                                "taskId=" + created.path("taskId").asLong(),
                                                "status=COMPLETED")
                                        .doesNotContain("visits", "riskScore", "password"));
        assertThat(
                        jdbc.queryForList(
                                "SELECT summary FROM audit_log WHERE event_type='BUSINESS_WRITE'",
                                String.class))
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("POST /api/subjects", "id=" + subject)
                                        .doesNotContain("合成验收"));
    }

    @Test
    void missingLatestCognitionCannotBeFilledFromOlderVisit() throws Exception {
        visit("BL", new BigDecimal("28"));
        call(
                post("/api/subjects/" + subject + "/visits"),
                admin,
                Map.of("visitCode", "M06", "visitDate", "2025-07-01"),
                200);
        var quality =
                data(call(post("/api/subjects/" + subject + "/quality-checks"), admin, null, 200));
        assertThat(quality.path("qualityStatus").asText()).isEqualTo("BLOCKED");
    }

    @Test
    void assignedResearchScopeIsEnforcedOnReadsWritesImportsAndNewProfiles() throws Exception {
        long researcher =
                data(call(
                                post("/api/users"),
                                admin,
                                Map.of(
                                        "username",
                                        "close_scope",
                                        "password",
                                        "123456",
                                        "roles",
                                        List.of("RESEARCHER")),
                                200))
                        .path("id")
                        .asLong();
        var session = login("close_scope", "123456");
        assertThat(data(call(get("/api/subjects"), session, null, 200)).path("total").asInt())
                .isZero();
        call(get("/api/subjects/" + subject), session, null, 403);
        Map<String, Object> feature =
                Map.of(
                        "featureName",
                        "APOE_E4_COUNT",
                        "featureValue",
                        1,
                        "recordedAt",
                        "2025-01-01",
                        "unit",
                        "count");
        call(post("/api/subjects/" + subject + "/features"), session, feature, 403);
        call(
                post("/api/subjects/" + subject + "/permissions"),
                admin,
                Map.of("userId", researcher, "permissionType", "VIEW"),
                200);
        call(get("/api/subjects/" + subject), session, null, 200);
        call(post("/api/subjects/" + subject + "/features"), session, feature, 403);
        call(
                post("/api/subjects/" + subject + "/permissions"),
                admin,
                Map.of("userId", researcher, "permissionType", "MANAGE"),
                200);
        call(post("/api/subjects/" + subject + "/features"), session, feature, 200);
        long own =
                data(call(
                                post("/api/subjects"),
                                session,
                                Map.of("subjectCode", code + "-R", "alias", "自建合成档案"),
                                200))
                        .path("id")
                        .asLong();
        assertThat(
                        data(call(
                                        get("/api/subjects/" + own + "/capabilities"),
                                        session,
                                        null,
                                        200))
                                .path("manage")
                                .asBoolean())
                .isTrue();
        long other =
                data(call(
                                post("/api/subjects"),
                                admin,
                                Map.of("subjectCode", code + "-X", "alias", "未授权合成档案"),
                                200))
                        .path("id")
                        .asLong();
        String csv =
                "subjectCode,visitCode,visitDate,mmse,moca,cdrSb\n"
                        + code
                        + "-X,BL,2025-01-01,28,,\n";
        var upload =
                new MockMultipartFile(
                        "file", "synthetic.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        var preview =
                data(
                        mvc.perform(
                                        multipart("/api/import-jobs")
                                                .file(upload)
                                                .header("Idempotency-Key", "scope-import")
                                                .session(session))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(preview.path("validRows").asInt()).isZero();
        assertThat(count("visit WHERE subject_id=" + other)).isZero();
        call(patch("/api/users/" + researcher), admin, Map.of("dataScope", "ALL"), 200);
        call(get("/api/subjects/" + other), session, null, 200);
        call(patch("/api/users/" + researcher), admin, Map.of("dataScope", "ASSIGNED"), 200);
        call(get("/api/subjects/" + other), session, null, 403);
    }

    @Test
    void qcDecisionRequiresManageAndPatientVetoEvenForLegacyMixedRole() throws Exception {
        visit("BL", null);
        long qc =
                jdbc.queryForObject(
                        "SELECT id FROM qc_audit_record WHERE subject_id=?", Long.class, subject);
        long researcher =
                data(call(
                                post("/api/users"),
                                admin,
                                Map.of(
                                        "username",
                                        "close_qc",
                                        "password",
                                        "123456",
                                        "roles",
                                        List.of("RESEARCHER")),
                                200))
                        .path("id")
                        .asLong();
        var session = login("close_qc", "123456");
        Map<String, Object> decision = Map.of("status", "APPROVED", "auditComment", "合成审核");
        call(post("/api/qc/" + qc + "/audit"), session, decision, 403);
        call(
                post("/api/subjects/" + subject + "/permissions"),
                admin,
                Map.of("userId", researcher, "permissionType", "MANAGE"),
                200);
        jdbc.update(
                "INSERT INTO user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE"
                        + " role_code='PATIENT'",
                researcher);
        call(post("/api/qc/" + qc + "/audit"), session, decision, 403);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM qc_audit_record WHERE id=?", String.class, qc))
                .isEqualTo("PENDING");
    }

    @Test
    void interruptedTasksAreClosedWithoutRecomputationAndLegacyInputIsUnavailable()
            throws Exception {
        for (String state : List.of("PENDING", "RUNNING"))
            jdbc.update(
                    "INSERT INTO"
                        + " assessment_task(subject_id,status,run_id,request_digest,created_at,updated_at)"
                        + " VALUES(?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    subject,
                    state,
                    "run-" + state,
                    "digest");
        new com.adcausalrisk.backend.assessment.InterruptedTaskRecovery(jdbc).recover();
        assertThat(count("assessment_task WHERE status='FAILED' AND error_code='TASK_INTERRUPTED'"))
                .isEqualTo(2);
        verifyNoInteractions(agent);
        long id = jdbc.queryForObject("SELECT MIN(id) FROM assessment_task", Long.class);
        assertThat(
                        data(call(get("/api/assessment-tasks/" + id + "/input"), admin, null, 200))
                                .path("available")
                                .asBoolean())
                .isFalse();
    }

    @Test
    void nextSevenDaysExcludesAlreadyOverdueTodayAndDatesBeyondSevenDays() throws Exception {
        for (long seconds : List.of(-1L, 5 * 86400L, 8 * 86400L)) {
            var body = new HashMap<>(follow());
            body.put("scheduledAt", Instant.now().plusSeconds(seconds).toString());
            call(post("/api/follow-ups"), admin, body, 200);
        }
        assertThat(
                        data(call(
                                        get("/api/follow-ups")
                                                .param("subjectId", String.valueOf(subject))
                                                .param("due", "next7"),
                                        admin,
                                        null,
                                        200))
                                .path("total")
                                .asInt())
                .isEqualTo(1);
        assertThat(
                        data(call(
                                        get("/api/follow-ups")
                                                .param("subjectId", String.valueOf(subject))
                                                .param("due", "overdue"),
                                        admin,
                                        null,
                                        200))
                                .path("total")
                                .asInt())
                .isEqualTo(1);
    }

    @Test
    void disablingModelRejectsNewExecutionWithoutCallingAgent() throws Exception {
        try {
            call(
                    patch("/api/system/models/" + AgentRequest.MODEL_VERSION),
                    admin,
                    Map.of("enabled", false),
                    200);
            var result = task("disabled", false);
            assertThat(result.path("status").asText()).isEqualTo("FAILED");
            assertThat(result.path("errorCode").asText()).isEqualTo("AGENT_UNAVAILABLE");
            verifyNoInteractions(agent);
        } finally {
            call(
                    patch("/api/system/models/" + AgentRequest.MODEL_VERSION),
                    admin,
                    Map.of("enabled", true),
                    200);
        }
    }

    @Test
    void researchCatalogExposesMetadataWithoutLoadingParametersAndRejectsPatient()
            throws Exception {
        var catalog = data(call(get("/api/research/evidence"), admin, null, 200));
        assertThat(catalog.path("models").size()).isEqualTo(15);
        for (var model : catalog.path("models"))
            assertThat(model.path("parameterBytesRead").asBoolean()).isFalse();
        assertThat(catalog.path("predictionAdmission").asText()).startsWith("NO_GO");
        call(get("/api/research/evidence"), login("patient", "DemoPass!2026"), null, 403);
        call(
                get("/api/research/evidence/table-perturbation"),
                login("doctor", "DemoPass!2026"),
                null,
                403);
    }

    @Test
    void researchReportsAreByteBoundToWhitelistedCommittedAggregates() throws Exception {
        var report =
                data(
                        call(
                                get("/api/research/evidence/table-perturbation"),
                                login("researcher", "DemoPass!2026"),
                                null,
                                200));
        byte[] source;
        try (var stream =
                new org.springframework.core.io.ClassPathResource(
                                "research-evidence/Training_Perturbation_Results.md")
                        .getInputStream()) {
            source = stream.readAllBytes();
        }
        assertThat(report.path("sha256").asText())
                .isEqualTo(
                        java.util.HexFormat.of()
                                .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                                .digest(source)));
        assertThat(report.path("markdown").asText()).contains("置信区间", "150");
        call(get("/api/research/evidence/unregistered"), admin, null, 400);
    }
}
