package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.contract.ContractSchemas;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:research_table_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResearchTableAssessmentTest {
    private static final String PATH = "/api/research/table-assessment-tasks";
    private static final String SUPPLEMENTS = "/api/research/supplements";
    private static final String TOKEN = "constructed-test-token";
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicInteger SNAPSHOT_REQUESTS = new AtomicInteger();
    private static final AtomicReference<String> SNAPSHOT_REPLY_MODE = new AtomicReference<>("MATCH");
    private static final AtomicReference<Exchange> EXCHANGE = new AtomicReference<>();
    private static final ExecutorService HOST_THREADS = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer SERVER = startServer();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired ResearchTableProtocol protocol;
    @Autowired ResearchTableAssessmentService service;
    @Autowired com.adcausalrisk.backend.qualitycheck.QualityCheckService qualityChecks;
    @Autowired ResearchTaskRecovery recovery;
    @Autowired ResearchTaskLease lease;
    @Autowired com.adcausalrisk.backend.config.SystemSettingService settings;
    @Autowired ResearchTableClient client;

    private record Exchange(byte[] request, byte[] response, int status, boolean delay,
                            CountDownLatch started, AtomicReference<String> problem) {}

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add("research.table.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        properties.add("research.table.bearer-token", () -> TOKEN);
        properties.add("research.table.read-timeout", () -> "600ms");
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
        HOST_THREADS.close();
    }

    @BeforeEach
    void clearResearchTasks() {
        SNAPSHOT_REPLY_MODE.set("MATCH");
        jdbc.update("DELETE FROM controlled_assessment_request");
        jdbc.update("DELETE FROM research_supplement_request");
        jdbc.update("DELETE FROM idempotency_record WHERE operation='RESEARCH_TABLE_ASSESSMENT'");
        jdbc.update("DELETE FROM patient_research_publication");
        jdbc.update("DELETE FROM research_table_task");
        jdbc.update("DELETE FROM research_table_snapshot");
    }

    @Test
    void doctorRequestsMatchingTestInputOnceWithoutAPatientAccount() throws Exception {
        String code = "DEMO-DOCTOR-REQUEST-" + UUID.randomUUID().toString().substring(0, 8);
        long subjectId = subject(code);
        String route = "/api/subjects/" + subjectId + "/assessment-request";
        var doctor = actor("doctor", "DOCTOR");
        mvc.perform(get(route).with(authentication(doctor))).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO subject_permission(subject_id,user_id,permission_type) VALUES(?,?,'MANAGE')",
                subjectId, userId("doctor"));
        mvc.perform(post(route).with(authentication(doctor))).andExpect(status().isConflict());
        String date = "2025-06-01";
        insertRequestVisit(subjectId, date);
        insertQuality(subjectId, "WARN");
        assertThat(body(mvc.perform(get(route).with(authentication(doctor)))).at("/data/state").asText())
                .isEqualTo("NEEDS_SNAPSHOT");

        String payload = """
                {"subjectId":%d,"indexDate":"%s","features":{"age_years":71,"education_years":12,
                 "mmse":25,"cdr_sb":2,"cdr_global":0.5,"adas__total13":19,"faq__faqtotal":6}}
                """.formatted(subjectId, date);
        var snapshot = body(mvc.perform(post(PATH + "/snapshots/doctor-intake").with(authentication(doctor))
                .contentType(MediaType.APPLICATION_JSON).content(payload))).path("data");
        assertThat(snapshot.path("hostSyncStatus").asText()).isEqualTo("SYNCED");
        long snapshotId = snapshot.path("snapshotId").asLong();
        String requestText = jdbc.queryForObject("SELECT request_json FROM research_table_snapshot WHERE id=?",
                String.class, snapshotId);
        JsonNode request = protocol.parse(requestText.getBytes(StandardCharsets.UTF_8));
        reply(request, successfulReply(requestText.getBytes(StandardCharsets.UTF_8)), 200, false);
        int called = REQUESTS.get();
        var first = body(mvc.perform(post(route).with(authentication(doctor))));
        assertThat(ContractSchemas.validateResponse(first, "/api/subjects/{subjectId}/assessment-request", "post", 200)).isEmpty();
        assertThat(first.at("/data/state").asText()).isEqualTo("REVIEW_PENDING");
        assertThat(body(mvc.perform(post(route).with(authentication(doctor)))).path("data")).isEqualTo(first.path("data"));
        assertThat(REQUESTS.get() - called).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM patient_account_request WHERE subject_id=?",
                Integer.class, subjectId)).isZero();
        jdbc.update("UPDATE visit SET mmse=24 WHERE subject_id=?", subjectId);
        assertThat(body(mvc.perform(get(route).with(authentication(doctor)))).at("/data/qualityStatus").asText())
                .isEqualTo("STALE");
        mvc.perform(post(route).with(authentication(doctor))).andExpect(status().isConflict());
        insertQuality(subjectId, "PASS");
        assertThat(body(mvc.perform(get(route).with(authentication(doctor)))).at("/data/state").asText())
                .isEqualTo("NEEDS_SNAPSHOT");
    }

    @Test
    void controlledDoctorRequestRequiresExplicitResearchGrantAndExecutionAuthorization() throws Exception {
        long frozen = snapshot(fixture("request-seven"), fixture("response-seven"), "researcher");
        long subjectId = jdbc.queryForObject("SELECT subject_id FROM research_table_snapshot WHERE id=?", Long.class, frozen);
        jdbc.update("UPDATE research_table_snapshot SET data_scope='CONTROLLED_RESEARCH' WHERE id=?", frozen);
        jdbc.update("INSERT INTO subject_permission(subject_id,user_id,permission_type) VALUES(?,?,'MANAGE')",
                subjectId, userId("doctor"));
        insertRequestVisit(subjectId, "2025-06-01");
        insertQuality(subjectId, "PASS");
        String route = "/api/subjects/" + subjectId + "/assessment-request";
        String queue = "/api/research/assessment-requests";
        var doctor = actor("doctor", "DOCTOR");
        var researcher = actor("researcher", "RESEARCHER");
        var patient = actor("patient", "PATIENT");
        int calls = REQUESTS.get();
        var created = body(mvc.perform(post(route).with(authentication(doctor)))).path("data");
        assertThat(created.path("state").asText()).isEqualTo("RESEARCH_REVIEW");
        assertThat(body(mvc.perform(post(route).with(authentication(doctor)))).path("data")).isEqualTo(created);
        assertThat(body(mvc.perform(get(queue).with(authentication(researcher)))).at("/data/total").asLong()).isZero();
        String dispatch = queue + "/" + created.path("requestId").asLong() + "/execute";
        mvc.perform(post(dispatch).with(authentication(researcher))).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO subject_permission(subject_id,user_id,permission_type) VALUES(?,?,'VIEW')",
                subjectId, userId("researcher"));
        var listing = body(mvc.perform(get(queue).with(authentication(researcher))));
        assertThat(ContractSchemas.validateResponse(listing, queue, "get", 200)).isEmpty();
        assertThat(listing.at("/data/items/0/canExecute").asBoolean()).isFalse();
        mvc.perform(post(dispatch).with(authentication(researcher))).andExpect(status().isConflict());
        mvc.perform(get(route).with(authentication(patient))).andExpect(status().isForbidden());
        mvc.perform(get(queue).with(authentication(patient))).andExpect(status().isForbidden());
        assertThat(REQUESTS.get()).isEqualTo(calls);
    }

    @Test
    void modelCsvPreviewsAndRegistersOneFullTestSnapshotWithoutRunningPrediction() throws Exception {
        String code = "DEMO-FULL-MODEL-CSV";
        long subjectId = subject(code);
        insertRequestVisit(subjectId, "2025-06-01");
        insertQuality(subjectId, "WARN");
        jdbc.update("INSERT INTO subject_permission(subject_id,user_id,permission_type) VALUES(?,?,'MANAGE')",
                subjectId, userId("researcher"));
        var researcher = actor("researcher", "RESEARCHER");
        var csv = new MockMultipartFile("file", "model.csv", "text/csv", ("""
                subjectCode,visitDate,age_years,education_years,cdr_global,adas__total13,faq__faqtotal
                DEMO-FULL-MODEL-CSV,2025-06-01,73,12,0.5,14,3
                DEMO-DOES-NOT-EXIST,2025-06-01,73,12,0.5,14,3
                """).getBytes(StandardCharsets.UTF_8));
        String path = PATH + "/snapshots/batch-csv";
        int assessments = REQUESTS.get();
        int syncs = SNAPSHOT_REQUESTS.get();
        var preview = body(mvc.perform(multipart(path).file(csv).with(authentication(researcher))));
        assertThat(ContractSchemas.validateResponse(preview, path, "post", 200)).isEmpty();
        assertThat(preview.at("/data/0/status").asText()).isEqualTo("READY");
        assertThat(preview.at("/data/1/status").asText()).isEqualTo("INVALID");
        assertThat(SNAPSHOT_REQUESTS.get()).isEqualTo(syncs);
        var committed = body(mvc.perform(multipart(path).file(csv).param("preview", "false").with(authentication(researcher))));
        long snapshot = committed.at("/data/0/snapshotId").asLong();
        assertThat(snapshot).isPositive();
        assertThat(committed.at("/data/0/hostSyncStatus").asText()).isEqualTo("SYNCED");
        var request = json.readTree(jdbc.queryForObject("SELECT request_json FROM research_table_snapshot WHERE id=?", String.class, snapshot));
        assertThat(request.path("features").size()).isEqualTo(7);
        assertThat(request.at("/features/mmse/value").asDouble()).isEqualTo(25.0);
        assertThat(request.at("/features/faq__faqtotal/value").asDouble()).isEqualTo(3.0);
        assertThat(body(mvc.perform(multipart(path).file(csv).param("preview", "false").with(authentication(researcher))))
                .at("/data/0/snapshotId").asLong()).isEqualTo(snapshot);
        assertThat(SNAPSHOT_REQUESTS.get()).isEqualTo(syncs + 1);
        assertThat(REQUESTS.get()).isEqualTo(assessments);
    }

    private void insertRequestVisit(long subjectId, String date) {
        jdbc.update("INSERT INTO visit(subject_id,visit_code,visit_date,mmse,cdr_sb) VALUES(?,?,?,?,?)",
                subjectId, "V-REQUEST", java.sql.Date.valueOf(date), 25, 2);
    }

    @Test
    void canonicalDigestMatchesThePythonRuntimeFixture() throws Exception {
        assertThat(protocol.digest(fixture("request-seven")))
                .isEqualTo(fixture("response-seven").get("requestSha256").asText());
    }

    @Test
    void supplementPreservesOriginalAndReturnsNewTaskWithExplicitGrants() throws Exception {
        var researcher = actor("researcher", "RESEARCHER");
        var doctor = actor("doctor", "DOCTOR");
        var patient = actor("patient", "PATIENT");
        var other = actor("research_doctor", "RESEARCHER");
        var request = fixture("request-blocked");
        var response = fixture("response-blocked");
        long originalSnapshot = snapshot(request, response, "researcher");
        long subjectId = jdbc.queryForObject("SELECT subject_id FROM research_table_snapshot WHERE id=?",
                Long.class, originalSnapshot);
        for (String username : List.of("doctor", "researcher", "research_doctor")) {
            jdbc.update("INSERT INTO subject_permission(subject_id,user_id,permission_type) VALUES(?,?,'VIEW')",
                    subjectId, userId(username));
        }
        reply(request, response, 200, false);
        var original = body(submit(originalSnapshot, "supplement-original", researcher)).path("data");
        long originalId = original.path("taskId").asLong();
        assertThat(original.path("status").asText()).isEqualTo("BLOCKED");
        int hostCalls = REQUESTS.get();
        String createBody = json.createObjectNode().put("originalTaskId", originalId)
                .put("note", "请核查原始量表与缺失依据").toString();
        var opened = body(mvc.perform(post(SUPPLEMENTS).with(authentication(doctor))
                .contentType(MediaType.APPLICATION_JSON).content(createBody)));
        assertThat(ContractSchemas.validateResponse(opened, SUPPLEMENTS, "post", 200)).isEmpty();
        long id = opened.at("/data/id").asLong();
        assertThat(opened.at("/data/status").asText()).isEqualTo("OPEN");
        assertThat(opened.at("/data/issues").size()).isEqualTo(response.at("/admission/issues").size());
        assertThat(body(mvc.perform(post(SUPPLEMENTS).with(authentication(doctor))
                .contentType(MediaType.APPLICATION_JSON).content(createBody))).path("data"))
                .isEqualTo(opened.path("data"));
        mvc.perform(get(SUPPLEMENTS).with(authentication(patient))).andExpect(status().isForbidden());
        var queue = body(mvc.perform(get(SUPPLEMENTS).with(authentication(researcher))
                .param("originalTaskId", String.valueOf(originalId))));
        assertThat(ContractSchemas.validateResponse(queue, SUPPLEMENTS, "get", 200)).isEmpty();
        assertThat(queue.at("/data/total").asInt()).isEqualTo(1);

        var claim = body(changeSupplement(id, "CLAIM", null, researcher));
        assertThat(claim.at("/data/assignedTo").asLong()).isEqualTo(userId("researcher"));
        changeSupplement(id, "CLAIM", null, other).andExpect(status().isConflict());
        changeSupplement(id, "RETURN", originalId, researcher).andExpect(status().isConflict());
        changeSupplement(id, "RETURN", originalId, other).andExpect(status().isForbidden());
        body(changeSupplement(id, "RELEASE", null, doctor));
        body(changeSupplement(id, "CLAIM", null, researcher));

        var newRequest = fixture("request-seven");
        var newResponse = fixture("response-seven");
        long updatedSnapshot = snapshot(newRequest, newResponse, "researcher");
        jdbc.update("UPDATE research_table_snapshot SET subject_id=? WHERE id=?", subjectId, updatedSnapshot);
        reply(newRequest, newResponse, 200, false);
        var replacement = body(submit(updatedSnapshot, "supplement-replacement", researcher)).path("data");
        long replacementId = replacement.path("taskId").asLong();
        assertThat(replacement.path("status").asText()).isEqualTo("COMPLETED");
        mvc.perform(post(SUPPLEMENTS).with(authentication(doctor)).contentType(MediaType.APPLICATION_JSON)
                .content(json.createObjectNode().put("originalTaskId", replacementId).put("note", "尚未复核").toString()))
                .andExpect(status().isConflict());
        var returned = body(changeSupplement(id, "RETURN", replacementId, researcher));
        assertThat(ContractSchemas.validateResponse(returned, SUPPLEMENTS + "/{id}", "patch", 200)).isEmpty();
        assertThat(returned.at("/data/status").asText()).isEqualTo("RETURNED");
        assertThat(returned.at("/data/replacementTaskId").asLong()).isEqualTo(replacementId);
        assertThat(body(changeSupplement(id, "RETURN", replacementId, researcher)).path("data"))
                .isEqualTo(returned.path("data"));
        assertThat(body(mvc.perform(get(PATH + "/reports/" + originalId).with(authentication(doctor))))
                .at("/data/status").asText()).isEqualTo("BLOCKED");
        assertThat(body(mvc.perform(get(PATH + "/reviews").with(authentication(doctor))))
                .at("/data/items/0/taskId").asLong()).isEqualTo(replacementId);
        assertThat(REQUESTS.get() - hostCalls).isEqualTo(1);
    }

    @Test
    void doctorIntakeRequiresResearchGrantInsteadOfPatientAccount() throws Exception {
        var researcher = actor("researcher", "RESEARCHER");
        long frozen = snapshot(fixture("request-seven"), fixture("response-seven"), "doctor");
        jdbc.update("UPDATE research_table_snapshot SET doctor_handoff=TRUE WHERE id=?", frozen);
        long subject = jdbc.queryForObject("SELECT subject_id FROM research_table_snapshot WHERE id=?",
                Long.class, frozen);
        assertThat(body(mvc.perform(get(PATH + "/snapshots").param("subjectId", String.valueOf(subject))
                .with(authentication(researcher)))).path("data").size()).isZero();
        submit(frozen, "intake-no-grant", researcher).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO subject_permission(subject_id,user_id,permission_type) VALUES(?,?,'VIEW')",
                subject, userId("researcher"));
        assertThat(body(mvc.perform(get(PATH + "/intake-state").param("subjectId", String.valueOf(subject))
                .with(authentication(researcher)))).at("/data/status").asText()).isEqualTo("READY");
        assertThat(body(mvc.perform(get(PATH + "/snapshots").param("subjectId", String.valueOf(subject))
                .with(authentication(researcher)))).at("/data/0/snapshotId").asLong()).isEqualTo(frozen);
        reply(fixture("request-seven"), fixture("response-seven"), 200, false);
        assertThat(body(submit(frozen, "intake-with-grant", researcher)).at("/data/status").asText())
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM patient_account_request WHERE subject_id=?",
                Long.class, subject)).isZero();
    }

    @Test
    void doctorCanReadPersistedTestIntakeWithoutCreatingVisitsOrTasks() throws Exception {
        var doctor = actor("doctor", "DOCTOR");
        var request = fixture("request-seven");
        long frozen = snapshot(request, fixture("response-seven"), "doctor");
        long subject = jdbc.queryForObject("SELECT subject_id FROM research_table_snapshot WHERE id=?",
                Long.class, frozen);
        String route = PATH + "/snapshots/doctor-intake";
        mvc.perform(get(route).param("subjectId", Long.toString(subject)).with(authentication(doctor)))
                .andExpect(status().isForbidden());
        jdbc.update("INSERT INTO subject_permission(subject_id,user_id,permission_type) VALUES(?,?,'MANAGE')",
                subject, userId("doctor"));
        assertThat(body(mvc.perform(get(route).param("subjectId", Long.toString(subject))
                .with(authentication(doctor)))).at("/data/total").asLong()).isZero();
        jdbc.update("UPDATE research_table_snapshot SET doctor_handoff=TRUE WHERE id=?", frozen);
        JsonNode response = body(mvc.perform(get(route).param("subjectId", Long.toString(subject))
                .with(authentication(doctor))));
        assertThat(ContractSchemas.validateResponse(response, route, "get", 200)).isEmpty();
        assertThat(response.at("/data/total").asLong()).isEqualTo(1);
        var item = response.at("/data/items/0");
        assertThat(item.path("snapshotId").asLong()).isEqualTo(frozen);
        assertThat(item.path("indexDate")).isEqualTo(request.path("indexDate"));
        assertThat(item.at("/features/mmse")).isEqualTo(request.at("/features/mmse/value"));
        assertThat(item.has("request") || item.has("runId")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visit WHERE subject_id=?", Long.class, subject)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM research_table_task WHERE snapshot_id=?", Long.class, frozen)).isZero();
        mvc.perform(get(route).param("subjectId", Long.toString(subject))
                .with(authentication(actor("patient", "PATIENT")))).andExpect(status().isForbidden());
    }

    private ResultActions changeSupplement(long id, String action, Long task,
                                           UsernamePasswordAuthenticationToken actor) throws Exception {
        var payload = json.createObjectNode().put("action", action);
        if (task != null) payload.put("replacementTaskId", task);
        return mvc.perform(patch(SUPPLEMENTS + "/" + id).with(authentication(actor))
                .contentType(MediaType.APPLICATION_JSON).content(payload.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"seven", "ten", "blocked"})
    void persistsValidatedResultsAndReplaysWithoutCallingPython(String sample) throws Exception {
        var request = fixture("request-" + sample);
        var response = fixture("response-" + sample);
        long snapshot = snapshot(request, response, "admin");
        var exchange = reply(request, response, 200, false);
        int before = REQUESTS.get();
        var actor = actor("admin", "ADMIN");

        JsonNode created = body(submit(snapshot, sample, actor));
        assertThat(ContractSchemas.validateResponse(created, PATH, "post", 200)).isEmpty();
        JsonNode task = created.get("data");
        assertThat(task.get("status")).isEqualTo(response.get("status"));
        assertThat(task.get("result")).isEqualTo(response);
        assertThat(body(submit(snapshot, sample, actor)).get("data")).isEqualTo(task);
        long id = task.get("taskId").longValue();
        JsonNode history = body(mvc.perform(get(PATH + "/" + id).with(authentication(actor))));
        assertThat(ContractSchemas.validateResponse(history, PATH + "/{id}", "get", 200)).isEmpty();
        assertThat(history.get("data")).isEqualTo(task);
        assertThat(REQUESTS.get() - before).isEqualTo(1);
        assertThat(exchange.problem().get()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM risk_assessment", Integer.class)).isZero();
    }

    @Test
    void rejectsSnapshotFromAnotherSelectedSubjectEvenWithAccessToBoth() throws Exception {
        long snapshot = snapshot(fixture("request-seven"), fixture("response-seven"), "admin");
        long selected = subject("DEMO-OTHER-" + UUID.randomUUID());
        int before = REQUESTS.get();
        mvc.perform(post(PATH).with(authentication(actor("admin", "ADMIN")))
                        .header("Idempotency-Key", "misbound-snapshot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("snapshotId", snapshot).put("subjectId", selected).toString()))
                .andExpect(status().isConflict());
        assertThat(REQUESTS.get()).isEqualTo(before);
    }

    @Test
    void rejectsMismatchedSyncReceiptsAndDoesNotSendChangedFrozenData() throws Exception {
        String code = "DEMO-SYNC-" + UUID.randomUUID();
        var actor = actor("admin", "ADMIN");
        var payload = json.createObjectNode().put("subjectId", subject(code))
                .set("request", uniqueRequest().put("subjectCode", code).put("modelKey", "trained-seven-logistic"));
        JsonNode registered = body(mvc.perform(post(PATH + "/snapshots/register")
                .with(authentication(actor)).contentType(MediaType.APPLICATION_JSON)
                .content(payload.toString()))).path("data");
        long id = registered.path("snapshotId").asLong();
        assertThat(registered.path("hostSyncStatus").asText()).isEqualTo("SYNCED");

        for (String mode : List.of("WRONG_RUN", "WRONG_SNAPSHOT")) {
            SNAPSHOT_REPLY_MODE.set(mode);
            JsonNode sync = body(mvc.perform(post(PATH + "/snapshots/" + id + "/sync")
                    .with(authentication(actor)))).path("data");
            assertThat(sync.path("hostSyncStatus").asText()).isEqualTo("FAILED");
        }

        SNAPSHOT_REPLY_MODE.set("MATCH");
        int before = SNAPSHOT_REQUESTS.get();
        jdbc.update("UPDATE research_table_snapshot SET context_sha256=? WHERE id=?", "0".repeat(64), id);
        JsonNode sync = body(mvc.perform(post(PATH + "/snapshots/" + id + "/sync")
                .with(authentication(actor)))).path("data");
        assertThat(sync.path("hostSyncStatus").asText()).isEqualTo("FAILED");
        assertThat(SNAPSHOT_REQUESTS.get()).isEqualTo(before);
    }

    @Test
    void registersAControlledSnapshotForTheCurrentResearcher() throws Exception {
        String code = "DEMO-REG-" + UUID.randomUUID();
        long subjectId = subject(code);
        var request = uniqueRequest().put("subjectCode", code).put("modelKey", "trained-seven-logistic");

        var payload = json.createObjectNode();
        payload.put("subjectId", subjectId);
        payload.set("request", request);

        var actor = actor("admin", "ADMIN");
        JsonNode created = json.readTree(mvc.perform(post(PATH + "/snapshots/register")
                        .with(authentication(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(ContractSchemas.validateResponse(
                created, PATH + "/snapshots/register", "post", 200)).isEmpty();
        long snapshotId = created.get("data").get("snapshotId").longValue();

        var row = jdbc.queryForMap("SELECT * FROM research_table_snapshot WHERE id=?", snapshotId);
        assertThat(((Number) row.get("authorized_user_id")).longValue()).isEqualTo(userId("admin"));
        assertThat(row.get("data_scope")).isEqualTo("CONSTRUCTED_VALIDATION");
        JsonNode storedRequest = json.readTree((String) row.get("request_json"));
        JsonNode storedModel = json.readTree((String) row.get("model_json"));
        assertThat(storedModel.get("runtimeSourceSha256").asText())
                .isEqualTo("f69db2e64f0810d6abb18516e3e845067f69cad9c8422c4c09be5e4329b1450e");
        assertThat(row.get("request_sha256")).isEqualTo(protocol.digest(storedRequest));
        assertThat(row.get("input_sha256")).isEqualTo(
                protocol.digest(protocol.admissionCore(storedRequest, storedModel)));
        assertThat(row.get("context_sha256")).isEqualTo(
                protocol.digest(json.readTree((String) row.get("context_json"))));
        assertThat(row.get("wire_sha256")).isEqualTo(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(protocol.canonicalBytes(storedRequest))));

        JsonNode listed = body(mvc.perform(get(PATH + "/snapshots?subjectId=" + subjectId)
                .with(authentication(actor))));
        assertThat(listed.get("data").get(0).get("snapshotId").longValue()).isEqualTo(snapshotId);
    }

    @Test
    void marksOlderModelSnapshotsIncompatibleAndReRegistersWithoutRewritingHistory() throws Exception {
        String code = "DEMO-COMPAT-" + UUID.randomUUID().toString().substring(0, 8);
        long subjectId = subject(code);
        var request = uniqueRequest().put("subjectCode", code).put("modelKey", "trained-seven-logistic");

        var payload = json.createObjectNode();
        payload.put("subjectId", subjectId);
        payload.set("request", request);
        JsonNode created = body(mvc.perform(post(PATH + "/snapshots/register")
                .with(authentication(actor("admin", "ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.toString())));
        long oldSnapshotId = created.get("data").get("snapshotId").longValue();

        var oldRow = jdbc.queryForMap(
                "SELECT request_json,model_json FROM research_table_snapshot WHERE id=?", oldSnapshotId);
        var oldModel = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree((String) oldRow.get("model_json"));
        oldModel.put("softwareLockSha256", "0".repeat(64));
        jdbc.update("UPDATE research_table_snapshot SET model_json=? WHERE id=?",
                oldModel.toString(), oldSnapshotId);

        JsonNode listed = body(mvc.perform(get(PATH + "/snapshots?subjectId=" + subjectId)
                .with(authentication(actor("admin", "ADMIN")))));
        JsonNode oldOption = null;
        for (JsonNode item : listed.get("data")) {
            if (item.get("snapshotId").longValue() == oldSnapshotId) oldOption = item;
        }
        assertThat(oldOption).isNotNull();
        assertThat(oldOption.get("compatible").asBoolean()).isFalse();
        assertThat(oldOption.get("compatibilityMessage").asText()).contains("重新登记");

        JsonNode reRegistered = body(mvc.perform(post(
                        PATH + "/snapshots/" + oldSnapshotId + "/re-register")
                .with(authentication(actor("admin", "ADMIN")))));
        long newSnapshotId = reRegistered.get("data").get("snapshotId").longValue();
        assertThat(newSnapshotId).isNotEqualTo(oldSnapshotId);

        var oldAfter = jdbc.queryForMap(
                "SELECT request_json,model_json FROM research_table_snapshot WHERE id=?", oldSnapshotId);
        assertThat(oldAfter.get("model_json")).isEqualTo(oldModel.toString());
        assertThat(oldAfter.get("request_json")).isEqualTo(oldRow.get("request_json"));

        JsonNode after = body(mvc.perform(get(PATH + "/snapshots?subjectId=" + subjectId)
                .with(authentication(actor("admin", "ADMIN")))));
        JsonNode newOption = null;
        for (JsonNode item : after.get("data")) {
            if (item.get("snapshotId").longValue() == newSnapshotId) newOption = item;
        }
        assertThat(newOption).isNotNull();
        assertThat(newOption.get("compatible").asBoolean()).isTrue();
    }

    @Test
    void registersManualSevenVariableFeatures() throws Exception {
        String code = "DEMO-MANUAL-" + UUID.randomUUID();
        long subjectId = subject(code);

        var payload = json.createObjectNode();
        payload.put("subjectId", subjectId);
        payload.put("indexDate", "2026-11-15");
        var features = payload.putObject("features");
        features.put("age_years", 72);
        features.put("education_years", 14);
        features.put("mmse", 26);
        features.put("cdr_sb", 1.5);
        features.put("cdr_global", 0.5);
        features.put("adas__total13", 18);
        features.put("faq__faqtotal", 4);

        var actor = actor("admin", "ADMIN");
        JsonNode created = json.readTree(mvc.perform(post(PATH + "/snapshots/register")
                        .with(authentication(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        long snapshotId = created.get("data").get("snapshotId").longValue();

        var row = jdbc.queryForMap("SELECT * FROM research_table_snapshot WHERE id=?", snapshotId);
        JsonNode request = json.readTree((String) row.get("request_json"));
        assertThat(request.get("subjectCode").asText()).isEqualTo(code);
        assertThat(request.get("features").get("mmse").get("value").asInt()).isEqualTo(26);
        assertThat(request.get("features").get("cdr_sb").get("unit").asText()).isEqualTo("score");
        JsonNode model = json.readTree((String) row.get("model_json"));
        assertThat(model.get("manifestSha256").asText())
                .isEqualTo("46ad244fe612306cc95a2a457c44ac234361db5ab70184c2ee2226527883a511");
        assertThat(row.get("data_scope")).isEqualTo("CONSTRUCTED_VALIDATION");
    }

    @Test
    void batchBuildsSnapshotsFromTheLatestVisitWithoutInventingMissingVariables() throws Exception {
        String code = "DEMO-BATCH-" + UUID.randomUUID();
        long subjectId = subject(code);
        jdbc.update("""
                INSERT INTO visit(subject_id,visit_code,visit_date,mmse,cdr_sb,raw_mmse,raw_cdr_sb)
                VALUES(?,?,?,?,?,?,?)
                """, subjectId, "V-BATCH", java.sql.Date.valueOf("2026-11-13"),
                new java.math.BigDecimal("28.0"), new java.math.BigDecimal("0.5"), "27.5", "0.5");
        insertQuality(subjectId, "PASS");

        var payload = json.createObjectNode();
        payload.putArray("subjectIds").add(subjectId);
        payload.put("evaluate", false);

        JsonNode created = body(mvc.perform(post(PATH + "/snapshots/batch")
                .with(authentication(actor("admin", "ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.toString())));
        JsonNode item = created.get("data").get("items").get(0);
        assertThat(item.get("hostSyncStatus").asText()).isEqualTo("SYNCED");
        long snapshotId = item.get("snapshotId").longValue();
        var row = jdbc.queryForMap("SELECT * FROM research_table_snapshot WHERE id=?", snapshotId);
        JsonNode request = json.readTree((String) row.get("request_json"));
        assertThat(request.get("indexDate").asText()).isEqualTo("2026-11-13");
        assertThat(request.get("features").get("mmse").get("value").decimalValue())
                .isEqualByComparingTo("27.5");
        assertThat(request.get("features").get("cdr_sb").get("value").decimalValue())
                .isEqualByComparingTo("0.5");
        assertThat(request.get("features").get("age_years").get("missingReason").asText())
                .isEqualTo("NOT_COLLECTED");
        assertThat(request.get("features").get("mmse").get("value").isDouble()).isTrue();
        assertThat(request.get("features").get("mmse").get("value").asText()).isEqualTo("27.5");
    }

    @Test
    void batchRefusesSubjectWhoseLatestQualityCheckIsNotPassing() throws Exception {
        String code = "DEMO-BATCH-QC-" + UUID.randomUUID();
        long subjectId = subject(code);
        jdbc.update("""
                INSERT INTO visit(subject_id,visit_code,visit_date,mmse,cdr_sb)
                VALUES(?,?,?,?,?)
                """, subjectId, "V-BATCH-QC", java.sql.Date.valueOf("2026-11-13"),
                new java.math.BigDecimal("28.0"), new java.math.BigDecimal("0.5"));
        insertQuality(subjectId, "WARN");

        var payload = json.createObjectNode();
        payload.putArray("subjectIds").add(subjectId);
        payload.put("evaluate", false);
        JsonNode created = body(mvc.perform(post(PATH + "/snapshots/batch")
                .with(authentication(actor("admin", "ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.toString())));
        JsonNode item = created.get("data").get("items").get(0);
        assertThat(item.get("snapshotId").isNull()).isTrue();
        assertThat(item.get("message").asText()).contains("WARN");
    }

    @Test
    void batchRejectsPassThatWasBoundToAnOlderVisitAfterANewVisitArrives() throws Exception {
        String code = "DEMO-BATCH-STALE-QC-" + UUID.randomUUID().toString().substring(0, 8);
        long subjectId = subject(code);
        jdbc.update("""
                INSERT INTO visit(subject_id,visit_code,visit_date,mmse,cdr_sb)
                VALUES(?,?,?,?,?)
                """, subjectId, "V-BATCH-OLD", java.sql.Date.valueOf("2026-11-13"),
                new java.math.BigDecimal("28.0"), new java.math.BigDecimal("0.5"));
        insertQuality(subjectId, "PASS");
        jdbc.update("""
                INSERT INTO visit(subject_id,visit_code,visit_date,mmse,cdr_sb)
                VALUES(?,?,?,?,?)
                """, subjectId, "V-BATCH-NEW", java.sql.Date.valueOf("2026-11-18"),
                new java.math.BigDecimal("27.0"), new java.math.BigDecimal("0.5"));

        var payload = json.createObjectNode();
        payload.putArray("subjectIds").add(subjectId);
        payload.put("evaluate", true);
        JsonNode created = body(mvc.perform(post(PATH + "/snapshots/batch")
                .with(authentication(actor("admin", "ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.toString())));
        JsonNode item = created.get("data").get("items").get(0);
        assertThat(item.get("snapshotId").isNull()).isTrue();
        assertThat(item.get("message").asText()).contains("未绑定当前数据");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM research_table_snapshot WHERE subject_id=?", Long.class, subjectId))
                .isZero();
    }

    @Test
    void batchUsesCurrentArchiveVersionWhenOnlyFeatureEvidenceChanged() throws Exception {
        String code = "DEMO-BATCH-VERSION-" + UUID.randomUUID().toString().substring(0, 8);
        long subjectId = subject(code);
        jdbc.update("""
                INSERT INTO visit(subject_id,visit_code,visit_date,mmse,cdr_sb,data_version)
                VALUES(?,?,?,?,?,?)
                """, subjectId, "V-BATCH-VERSION", java.sql.Date.valueOf("2026-11-13"),
                new java.math.BigDecimal("28.0"), new java.math.BigDecimal("0.5"), "demo-v1");
        jdbc.update("UPDATE subject SET data_version='demo-v2' WHERE id=?", subjectId);
        insertQuality(subjectId, "PASS");

        var payload = json.createObjectNode();
        payload.putArray("subjectIds").add(subjectId);
        payload.put("evaluate", false);
        JsonNode created = body(mvc.perform(post(PATH + "/snapshots/batch")
                .with(authentication(actor("admin", "ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.toString())));
        JsonNode item = created.get("data").get("items").get(0);
        assertThat(item.get("snapshotId").isNull()).isFalse();
        assertThat(item.get("hostSyncStatus").asText()).isEqualTo("SYNCED");
    }

    @Test
    void batchEvaluationSubmitsAndReadsBackTheCreatedTask() throws Exception {
        String code = "DEMO-BE-" + UUID.randomUUID();
        long subjectId = subject(code);
        jdbc.update("""
                INSERT INTO visit(subject_id,visit_code,visit_date,mmse,cdr_sb)
                VALUES(?,?,?,?,?)
                """, subjectId, "V-BATCH-EVAL", java.sql.Date.valueOf("2026-11-13"),
                new java.math.BigDecimal("28.00"), new java.math.BigDecimal("0.50"));
        insertQuality(subjectId, "PASS");

        ResearchTableClient original = (ResearchTableClient) org.springframework.test.util.ReflectionTestUtils
                .getField(service, "client");
        ResearchTableClient mock = org.mockito.Mockito.mock(ResearchTableClient.class);
        org.mockito.Mockito.when(mock.registerSnapshot(org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenAnswer(invocation -> new ResearchTableClient.Reply(200,
                        snapshotReceipt(json.readTree((byte[]) invocation.getArgument(0)), "MATCH")));
        org.mockito.Mockito.when(mock.assess(org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenAnswer(invocation -> {
                    try {
                        return new ResearchTableClient.Reply(200, successfulReply(invocation.getArgument(0)));
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });
        org.springframework.test.util.ReflectionTestUtils.setField(service, "client", mock);
        try {
            var payload = json.createObjectNode();
            payload.putArray("subjectIds").add(subjectId);
            payload.put("evaluate", true);

            JsonNode created = body(mvc.perform(post(PATH + "/snapshots/batch")
                    .with(authentication(actor("admin", "ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload.toString())));
            JsonNode item = created.get("data").get("items").get(0);
            assertThat(item.get("hostSyncStatus").asText()).isEqualTo("SYNCED");
            long taskId = item.get("taskId").longValue();
            assertThat(item.get("taskStatus").asText()).isEqualTo("COMPLETED");
            JsonNode task = body(mvc.perform(get(PATH + "/" + taskId)
                    .with(authentication(actor("admin", "ADMIN"))))).get("data");
            assertThat(task.get("taskId").longValue()).isEqualTo(taskId);
            assertThat(task.get("status").asText()).isEqualTo("COMPLETED");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "client", original);
        }
    }

    private ObjectNode successfulReply(byte[] requestBytes) throws Exception {
        JsonNode request = protocol.parse(requestBytes);
        var row = jdbc.queryForMap("SELECT model_json,context_json FROM research_table_snapshot ORDER BY id DESC LIMIT 1");
        JsonNode model = protocol.parse(((String) row.get("model_json")).getBytes(StandardCharsets.UTF_8));
        JsonNode context = protocol.parse(((String) row.get("context_json")).getBytes(StandardCharsets.UTF_8));
        ObjectNode response = fixture("response-seven");
        for (String name : List.of("contractVersion", "runId", "modelKey", "subjectCode", "dataVersion",
                "indexDate", "availabilityPolicy", "predictionWindowMonths")) {
            response.set(name, request.get(name));
        }
        response.set("model", model);
        response.put("dataScope", "CONSTRUCTED_VALIDATION");
        response.put("status", "COMPLETED");
        response.put("requestSha256", protocol.digest(request));

        ObjectNode admission = (ObjectNode) response.get("admission");
        admission.set("model", model);
        admission.set("featureOrder", model.get("features"));
        admission.put("availabilityPolicy", request.get("availabilityPolicy").asText());
        admission.put("recordCount", 1);
        admission.put("blockedRecordCount", 0);
        admission.putArray("issues");
        ObjectNode missing = admission.putObject("missingFeatureCounts");
        for (JsonNode feature : model.get("features")) {
            String name = feature.asText();
            missing.put(name, request.get("features").get(name).get("value").isNull() ? 1 : 0);
        }

        ObjectNode binding = (ObjectNode) response.get("binding");
        binding.put("inputSha256", protocol.digest(protocol.admissionCore(request, model)));
        binding.put("trustedContextSha256", protocol.digest(context));
        binding.set("model", model);
        binding.set("featureOrder", model.get("features"));
        binding.put("predictionPolicy", model.get("predictionPolicy").asText());
        binding.put("predictionWindowMonths", 24);
        return response;
    }

    @Test
    void registeredManualSnapshotPassesPythonBundleVerification(@TempDir Path temp) throws Exception {
        String code = "DEMO-PY-" + UUID.randomUUID();
        long subjectId = subject(code);
        var payload = json.createObjectNode();
        payload.put("subjectId", subjectId);
        payload.put("indexDate", "2026-11-15");
        var features = payload.putObject("features");
        features.put("age_years", 72);
        features.put("education_years", 14);
        features.put("mmse", 26);
        features.put("cdr_sb", 1.5);
        features.put("cdr_global", 0.5);
        features.put("adas__total13", 18);
        features.put("faq__faqtotal", 4);

        JsonNode created = json.readTree(mvc.perform(post(PATH + "/snapshots/register")
                        .with(authentication(actor("admin", "ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        long snapshotId = created.get("data").get("snapshotId").longValue();
        var row = jdbc.queryForMap("SELECT * FROM research_table_snapshot WHERE id=?", snapshotId);

        Files.writeString(temp.resolve("request.json"), (String) row.get("request_json"), StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("model.json"), (String) row.get("model_json"), StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("context.json"), (String) row.get("context_json"), StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("verify.py"), """
                import json, sys
                sys.path.insert(0, sys.argv[1])
                from research_runtime.dynamic_snapshot import bundle_from_parts
                base = sys.argv[2]
                req = json.load(open(base + "/request.json", encoding="utf-8"))
                model = json.load(open(base + "/model.json", encoding="utf-8"))
                context = json.load(open(base + "/context.json", encoding="utf-8"))
                bundle_from_parts(run_id=req["runId"], request=req, model=model, context=context,
                                  snapshot_id=int(sys.argv[3]), subject_id=int(sys.argv[4]))
                print("VERIFY_OK")
                """, StandardCharsets.UTF_8);

        Path repo = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
        Path python = repo.resolve(".venv").resolve("Scripts").resolve("python.exe");
        Assumptions.assumeTrue(Files.isRegularFile(python), "Python environment is not available");

        Process process = new ProcessBuilder(python.toString(), temp.resolve("verify.py").toString(),
                repo.toString(), temp.toString(), Long.toString(snapshotId), Long.toString(subjectId))
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(output).contains("VERIFY_OK");
    }

    @Test
    void rejectsRegistrationWhoseSubjectCodeDoesNotMatchTheSubject() throws Exception {
        String code = "DEMO-REG-" + UUID.randomUUID();
        long subjectId = subject(code);
        var request = uniqueRequest().put("subjectCode", "DEMO-OTHER");
        var payload = json.createObjectNode();
        payload.put("subjectId", subjectId);
        payload.set("request", request);

        mvc.perform(post(PATH + "/snapshots/register")
                        .with(authentication(actor("admin", "ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsPatientMixedRolesAndWrongOwner() throws Exception {
        var request = uniqueRequest();
        long snapshot = snapshot(request, fixture("response-seven"), "admin");
        int before = REQUESTS.get();
        long patientRole = jdbc.queryForObject("SELECT id FROM sys_role WHERE role_code='PATIENT'", Long.class);
        jdbc.update("INSERT INTO user_role(user_id,role_id) VALUES(?,?)", userId("admin"), patientRole);
        try {
            submit(snapshot, "denied", actor("admin", "ADMIN", "PATIENT")).andExpect(status().isForbidden());
        } finally {
            jdbc.update("DELETE FROM user_role WHERE user_id=? AND role_id=?", userId("admin"), patientRole);
        }
        submit(snapshot, "denied", actor("researcher", "RESEARCHER")).andExpect(status().isForbidden());
        mvc.perform(post(PATH).with(authentication(actor("admin", "ADMIN")))
                .header("Idempotency-Key", "injected").contentType(MediaType.APPLICATION_JSON)
                .content("{\"snapshotId\":" + snapshot + ",\"trusted_context\":{}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH).with(authentication(actor("admin", "ADMIN")))
                .header("Idempotency-Key", "decimal").contentType(MediaType.APPLICATION_JSON)
                .content("{\"snapshotId\":" + snapshot + ".0}"))
                .andExpect(status().isBadRequest());
        assertThat(REQUESTS.get()).isEqualTo(before);
    }

    @Test
    void refusesModifiedSnapshotAndControlledResearchBeforeSending() throws Exception {
        long snapshot = snapshot(uniqueRequest(), fixture("response-seven"), "admin");
        var actor = actor("admin", "ADMIN");
        int before = REQUESTS.get();
        jdbc.update("UPDATE research_table_snapshot SET wire_sha256=? WHERE id=?", "0".repeat(64), snapshot);
        submit(snapshot, "digest", actor).andExpect(status().isServiceUnavailable());
        jdbc.update("UPDATE research_table_snapshot SET data_scope='CONTROLLED_RESEARCH' WHERE id=?", snapshot);
        submit(snapshot, "scope", actor).andExpect(status().isServiceUnavailable());
        assertThat(REQUESTS.get()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM research_table_task", Integer.class)).isZero();
    }

    @Test
    void admissionAloneNeverPersistsAControlledSuccess() throws Exception {
        var request = fixture("request-seven");
        var response = fixture("response-seven").put("dataScope", "CONTROLLED_RESEARCH");
        long snapshot = snapshot(request, response, "admin");
        jdbc.update("UPDATE research_table_snapshot SET data_scope='CONTROLLED_RESEARCH' WHERE id=?", snapshot);
        reply(request, response, 200, false);
        int before = REQUESTS.get();
        var admin = actor("admin", "ADMIN");
        ReflectionTestUtils.setField(service, "controlledAdmissionEnabled", true);
        try {
            JsonNode task = body(submit(snapshot, "admission-only", admin)).path("data");
            assertThat(task.path("status").asText()).isEqualTo("OUTCOME_UNKNOWN");
            assertThat(task.path("errorCode").asText()).isEqualTo("RESEARCH_PROTOCOL_INVALID");
            assertThat(task.path("result").isNull()).isTrue();
            assertThat(body(submit(snapshot, "admission-only", admin)).path("data")).isEqualTo(task);
            assertThat(REQUESTS.get() - before).isEqualTo(1);
            mvc.perform(post(PATH + "/" + task.path("taskId").asLong() + "/retry").with(authentication(admin)))
                    .andExpect(status().isConflict());
            assertThat(REQUESTS.get() - before).isEqualTo(1);
        } finally {
            ReflectionTestUtils.setField(service, "controlledAdmissionEnabled", false);
        }
    }

    @Test
    void timeoutRetainsUnknownAndTheSameKeyCannotSendAgain() throws Exception {
        var request = uniqueRequest();
        long snapshot = snapshot(request, fixture("response-seven"), "admin");
        var actor = actor("admin", "ADMIN");
        var exchange = reply(request, fixture("response-seven"), 200, true);
        int before = REQUESTS.get();
        JsonNode task = body(submit(snapshot, "timeout", actor)).get("data");
        assertThat(task.get("status").asText()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(task.get("errorCode").asText()).isEqualTo("RESEARCH_OUTCOME_UNKNOWN");
        assertThat(task.get("result").isNull()).isTrue();
        assertThat(body(submit(snapshot, "timeout", actor)).get("data")).isEqualTo(task);
        submit(snapshot, "another-key", actor).andExpect(status().isConflict());
        long other = snapshot(uniqueRequest(), fixture("response-seven"), "admin");
        submit(other, "timeout", actor).andExpect(status().isConflict());
        assertThat(REQUESTS.get() - before).isEqualTo(1);
        assertThat(exchange.problem().get()).isNull();
    }

    @Test
    void exposesPersistedTaskHistoryForTheOwnedSubject() throws Exception {
        var request = fixture("request-seven");
        long snapshot = snapshot(request, fixture("response-seven"), "admin");
        reply(request, fixture("response-seven"), 200, false);
        var actor = actor("admin", "ADMIN");
        JsonNode task = body(submit(snapshot, "history", actor)).get("data");
        long subjectId = task.get("subjectId").asLong();
        JsonNode history = body(mvc.perform(get(PATH + "/history").param("subjectId", Long.toString(subjectId))
                .with(authentication(actor)))).get("data");
        assertThat(history.isArray()).isTrue();
        assertThat(history.size()).isEqualTo(1);
        assertThat(history.get(0).get("taskId")).isEqualTo(task.get("taskId"));
        assertThat(history.get(0).get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void treatingDoctorCanReadTheSubjectResearchArchiveButNotOwnerHistory() throws Exception {
        var request = fixture("request-seven");
        long snapshot = snapshot(request, fixture("response-seven"), "admin");
        reply(request, fixture("response-seven"), 200, false);
        JsonNode task = body(submit(snapshot, "archive", actor("admin", "ADMIN"))).get("data");
        long subjectId = task.get("subjectId").asLong();
        long taskId = task.get("taskId").asLong();
        jdbc.update("INSERT INTO subject_permission(subject_id,user_id,permission_type) VALUES(?,?,?)",
                subjectId, userId("doctor"), "VIEW");

        var doctor = actor("doctor", "DOCTOR");
        JsonNode archive = body(mvc.perform(get(PATH + "/archive").param("subjectId", Long.toString(subjectId))
                .with(authentication(doctor)))).get("data");
        assertThat(archive.isArray()).isTrue();
        assertThat(archive.size()).isEqualTo(1);
        assertThat(archive.get(0).get("taskId").asLong()).isEqualTo(taskId);
        assertThat(archive.get(0).get("status").asText()).isEqualTo("COMPLETED");

        body(mvc.perform(get(PATH + "/" + taskId).with(authentication(doctor))))
                .get("data").get("taskId").asLong();
        mvc.perform(get(PATH + "/history").param("subjectId", Long.toString(subjectId))
                .with(authentication(doctor))).andExpect(status().isForbidden());
        mvc.perform(get(PATH + "/archive").param("subjectId", Long.toString(subjectId))
                .with(authentication(actor("patient", "PATIENT")))).andExpect(status().isForbidden());
    }

    @Test
    void revokedDoctorLosesAccessToExistingResearchTasks() throws Exception {
        var request = fixture("request-seven");
        long snapshot = snapshot(request, fixture("response-seven"), "admin");
        reply(request, fixture("response-seven"), 200, false);
        JsonNode task = body(submit(snapshot, "revoked", actor("admin", "ADMIN"))).get("data");
        long subjectId = task.get("subjectId").asLong();
        long taskId = task.get("taskId").asLong();
        jdbc.update("INSERT INTO subject_permission(subject_id,user_id,permission_type) VALUES(?,?,?)",
                subjectId, userId("doctor"), "VIEW");

        var doctor = actor("doctor", "DOCTOR");
        mvc.perform(get(PATH + "/" + taskId).with(authentication(doctor))).andExpect(status().isOk());

        jdbc.update("DELETE FROM subject_permission WHERE subject_id=? AND user_id=?",
                subjectId, userId("doctor"));

        mvc.perform(get(PATH + "/" + taskId).with(authentication(doctor))).andExpect(status().isForbidden());
        mvc.perform(get(PATH + "/archive").param("subjectId", Long.toString(subjectId))
                .with(authentication(doctor))).andExpect(status().isForbidden());
    }

    @Test
    void doctorSeesAndReviewsCompletedResearchTasks() throws Exception {
        var request = fixture("request-seven");
        long snapshot = snapshot(request, fixture("response-seven"), "admin");
        reply(request, fixture("response-seven"), 200, false);
        JsonNode task = body(submit(snapshot, "review", actor("admin", "ADMIN"))).get("data");
        long subjectId = task.get("subjectId").asLong();
        long taskId = task.get("taskId").asLong();
        jdbc.update("INSERT INTO subject_permission(subject_id,user_id,permission_type) VALUES(?,?,?)",
                subjectId, userId("doctor"), "VIEW");

        var doctor = actor("doctor", "DOCTOR");
        JsonNode queue = body(mvc.perform(get(PATH + "/reviews").with(authentication(doctor)))).get("data");
        assertThat(queue.get("total").asLong()).isEqualTo(1);
        JsonNode queued = queue.get("items").get(0);
        assertThat(queued.get("taskId").asLong()).isEqualTo(taskId);
        assertThat(queued.get("reviewStatus").asText()).isEqualTo("PENDING");
        assertThat(queued.get("subjectCode").asText()).isNotBlank();
        assertThat(queued.get("subjectAlias").asText()).isNotBlank();

        JsonNode reviewed = body(mvc.perform(post(PATH + "/" + taskId + "/review")
                        .with(authentication(doctor)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRMED\",\"comment\":\"核对影像与量表一致\"}")))
                .get("data");
        assertThat(reviewed.get("reviewStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(reviewed.get("reviewedBy").asLong()).isEqualTo(userId("doctor"));
        assertThat(reviewed.get("reviewComment").asText()).isEqualTo("核对影像与量表一致");

        mvc.perform(post(PATH + "/" + taskId + "/review")
                        .with(authentication(doctor)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECTED\",\"comment\":\"再次复核\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void reportsRespectRoleAndSubjectGrant() throws Exception {
        var request = fixture("request-seven");
        long snapshot = snapshot(request, fixture("response-seven"), "admin");
        reply(request, fixture("response-seven"), 200, false);
        JsonNode task = body(submit(snapshot, "reports", actor("admin", "ADMIN"))).get("data");
        long subjectId = task.get("subjectId").asLong();

        mvc.perform(get(PATH + "/reports").with(authentication(actor("admin", "ADMIN"))))
                .andExpect(status().isForbidden());

        var doctor = actor("doctor", "DOCTOR");
        JsonNode empty = body(mvc.perform(get(PATH + "/reports").with(authentication(doctor)))).get("data");
        assertThat(empty.get("total").asLong()).isZero();

        jdbc.update("INSERT INTO subject_permission(subject_id,user_id,permission_type) VALUES(?,?,?)",
                subjectId, userId("doctor"), "VIEW");
        JsonNode visible = body(mvc.perform(get(PATH + "/reports").with(authentication(doctor)))).get("data");
        assertThat(visible.get("total").asLong()).isEqualTo(1);
        assertThat(visible.get("items").get(0).get("taskId").asLong()).isEqualTo(task.get("taskId").asLong());
    }

    @Test
    void reportScopeAndSubjectFilterBeforePickingLatestResult() throws Exception {
        var request = fixture("request-seven");
        var result = fixture("response-seven");
        long controlled = snapshot(request, result, "researcher");
        long demo = snapshot(request, result, "researcher");
        long subject = jdbc.queryForObject("SELECT subject_id FROM research_table_snapshot WHERE id=?",
                Long.class, controlled);
        jdbc.update("UPDATE research_table_snapshot SET data_scope='CONTROLLED_RESEARCH' WHERE id=?", controlled);
        jdbc.update("""
                INSERT INTO research_table_task(snapshot_id,run_id,status,created_at,updated_at)
                VALUES(?,?,'BLOCKED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, controlled, "run-scoped-controlled");
        jdbc.update("""
                INSERT INTO research_table_task(snapshot_id,run_id,status,review_status,created_at,updated_at)
                VALUES(?,?,'COMPLETED','PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, demo, "run-scoped-demo");
        var researcher = actor("researcher", "RESEARCHER");

        JsonNode latest = body(mvc.perform(get(PATH + "/reports").with(authentication(researcher))
                .param("dataScope", "CONTROLLED_RESEARCH").param("subjectId", Long.toString(subject))
                .param("pageSize", "1"))).get("data");
        assertThat(latest.get("total").asLong()).isEqualTo(1);
        assertThat(latest.at("/items/0/status").asText()).isEqualTo("BLOCKED");
        assertThat(latest.at("/items/0/dataScope").asText()).isEqualTo("CONTROLLED_RESEARCH");

        JsonNode demoPage = body(mvc.perform(get(PATH + "/reports").with(authentication(researcher))
                .param("dataScope", "CONSTRUCTED_VALIDATION").param("pageSize", "1"))).get("data");
        assertThat(demoPage.get("total").asLong()).isEqualTo(1);
        assertThat(demoPage.at("/items/0/dataScope").asText()).isEqualTo("CONSTRUCTED_VALIDATION");
        mvc.perform(get(PATH + "/reports").with(authentication(researcher))
                .param("dataScope", "SYNTHETIC_DEMO")).andExpect(status().isBadRequest());
    }

    @Test
    void researcherReportsExcludeRevokedSubjectInsteadOfFailingWholePage() throws Exception {
        var request = fixture("request-seven");
        long snapshot = snapshot(request, fixture("response-seven"), "researcher");
        reply(request, fixture("response-seven"), 200, false);
        JsonNode task = body(submit(snapshot, "reports-revoked",
                actor("researcher", "RESEARCHER"))).get("data");
        long subjectId = task.get("subjectId").asLong();
        long researcherId = userId("researcher");
        String originalScope = jdbc.queryForObject(
                "SELECT data_scope FROM sys_user WHERE id=?", String.class, researcherId);
        jdbc.update("UPDATE sys_user SET data_scope='ASSIGNED' WHERE id=?", researcherId);
        try {
            jdbc.update("INSERT INTO subject_permission(subject_id,user_id,permission_type) VALUES(?,?,?)",
                    subjectId, researcherId, "VIEW");
            var researcher = actor("researcher", "RESEARCHER");
            JsonNode visible = body(mvc.perform(get(PATH + "/reports")
                    .with(authentication(researcher)))).get("data");
            assertThat(visible.get("total").asLong()).isGreaterThanOrEqualTo(1);

            jdbc.update("DELETE FROM subject_permission WHERE subject_id=? AND user_id=?",
                    subjectId, researcherId);
            JsonNode revoked = body(mvc.perform(get(PATH + "/reports")
                    .with(authentication(researcher)))).get("data");
            assertThat(revoked.get("total").asLong()).isZero();
        } finally {
            jdbc.update("UPDATE sys_user SET data_scope=? WHERE id=?", originalScope, researcherId);
        }
    }

    @Test
    void dualRoleResearcherWithGlobalReadCannotReviewWithoutSubjectGrant() throws Exception {
        var request = fixture("request-seven");
        long snapshot = snapshot(request, fixture("response-seven"), "admin");
        reply(request, fixture("response-seven"), 200, false);
        JsonNode task = body(submit(snapshot, "dual-role", actor("admin", "ADMIN"))).get("data");
        long taskId = task.get("taskId").asLong();

        long dualUserId = userId("research_doctor");
        String originalScope = jdbc.queryForObject(
                "SELECT data_scope FROM sys_user WHERE id=?", String.class, dualUserId);
        jdbc.update("DELETE FROM subject_permission WHERE user_id=? AND subject_id=?",
                dualUserId, task.get("subjectId").asLong());
        jdbc.update("UPDATE sys_user SET data_scope='ALL' WHERE id=?", dualUserId);
        try {
            // The seeded account really holds DOCTOR + RESEARCHER in the database.
            assertThat(jdbc.queryForList("""
                    SELECT r.role_code FROM user_role ur
                    JOIN sys_role r ON r.id=ur.role_id
                    WHERE ur.user_id=?
                    """, String.class, dualUserId))
                    .containsExactlyInAnyOrder("DOCTOR", "RESEARCHER");
            var dualRole = actor("research_doctor", "DOCTOR", "RESEARCHER");

            // Report list, detail and export all require the explicit archive grant. Global
            // research visibility alone must not widen the report use case.
            mvc.perform(get(PATH + "/reports/" + taskId).with(authentication(dualRole)))
                    .andExpect(status().isForbidden());
            mvc.perform(get(PATH + "/" + taskId + "/export")
                            .with(authentication(dualRole)))
                    .andExpect(status().isForbidden());
            JsonNode hiddenReports = body(mvc.perform(get(PATH + "/reports")
                    .with(authentication(dualRole)))).get("data");
            assertThat(hiddenReports.get("total").asLong()).isZero();

            // Review submission also needs an explicit subject grant.
            JsonNode queue = body(mvc.perform(get(PATH + "/reviews")
                    .with(authentication(dualRole)))).get("data");
            assertThat(queue.get("total").asLong()).isZero();
            mvc.perform(post(PATH + "/" + taskId + "/review")
                            .with(authentication(dualRole)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"CONFIRMED\",\"comment\":\"无档案授权不得复核\"}"))
                    .andExpect(status().isForbidden());

            jdbc.update("INSERT INTO subject_permission(subject_id,user_id,permission_type) VALUES(?,?,?)",
                    task.get("subjectId").asLong(), dualUserId, "VIEW");
            try {
                mvc.perform(get(PATH + "/reports/" + taskId).with(authentication(dualRole)))
                        .andExpect(status().isOk());
                mvc.perform(get(PATH + "/" + taskId + "/export")
                                .with(authentication(dualRole))
                                .param("format", "json"))
                        .andExpect(status().isOk());
                JsonNode visibleReports = body(mvc.perform(get(PATH + "/reports")
                        .with(authentication(dualRole)))).get("data");
                assertThat(visibleReports.get("total").asLong()).isEqualTo(1);
            } finally {
                jdbc.update("DELETE FROM subject_permission WHERE subject_id=? AND user_id=?",
                        task.get("subjectId").asLong(), dualUserId);
            }
        } finally {
            jdbc.update("UPDATE sys_user SET data_scope=? WHERE id=?", originalScope, dualUserId);
        }
    }

    @Test
    void reviewQueueRejectsNonClinicalRoles() throws Exception {
        mvc.perform(get(PATH + "/reviews").with(authentication(actor("researcher", "RESEARCHER"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATH + "/reviews").with(authentication(actor("patient", "PATIENT"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATH + "/reviews").with(authentication(actor("admin", "ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void retriesUnknownTaskAndRefusesCompletedTask() throws Exception {
        var request = fixture("request-seven");
        long snapshot = snapshot(request, fixture("response-seven"), "admin");
        var actor = actor("admin", "ADMIN");
        reply(request, fixture("response-seven"), 200, true);
        JsonNode failed = body(submit(snapshot, "retry", actor)).get("data");
        assertThat(failed.get("status").asText()).isEqualTo("OUTCOME_UNKNOWN");
        long taskId = failed.get("taskId").asLong();
        reply(request, fixture("response-seven"), 200, false);
        JsonNode retried = body(mvc.perform(post(PATH + "/" + taskId + "/retry")
                .with(authentication(actor)))).get("data");
        assertThat(retried.get("taskId").asLong()).isEqualTo(taskId);
        assertThat(retried.get("status").asText()).isEqualTo("COMPLETED");
        mvc.perform(post(PATH + "/" + taskId + "/retry").with(authentication(actor)))
                .andExpect(status().isConflict());
    }

    @Test
    void reservesConcurrentReplayBeforeCallingTheHost() throws Exception {
        var request = uniqueRequest();
        long snapshot = snapshot(request, fixture("response-seven"), "admin");
        var actor = actor("admin", "ADMIN");
        var exchange = reply(request, fixture("response-seven"), 200, true);
        int before = REQUESTS.get();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> body(submit(snapshot, "concurrent", actor)).get("data"));
            assertThat(exchange.started().await(3, TimeUnit.SECONDS)).isTrue();
            JsonNode second = body(submit(snapshot, "concurrent", actor)).get("data");
            assertThat(second.get("taskId")).isEqualTo(first.get(5, TimeUnit.SECONDS).get("taskId"));
        }
        assertThat(REQUESTS.get() - before).isEqualTo(1);
    }

    @Test
    void recordsKnownUpstreamDenialWithoutStoringBody() throws Exception {
        var request = uniqueRequest();
        var error = fixture("response-error-403");
        error.set("runId", request.get("runId"));
        long snapshot = snapshot(request, fixture("response-seven"), "admin");
        reply(request, error, 403, false);
        JsonNode task = body(submit(snapshot, "denial", actor("admin", "ADMIN"))).get("data");
        assertThat(task.get("status").asText()).isEqualTo("FAILED");
        assertThat(task.get("errorCode")).isEqualTo(error.get("code"));
        assertThat(task.get("result").isNull()).isTrue();
    }

    @Test
    void quarantinesAnUnboundHostResponse() throws Exception {
        var request = uniqueRequest();
        long snapshot = snapshot(request, fixture("response-seven"), "admin");
        reply(request, fixture("response-seven"), 200, false);
        JsonNode task = body(submit(snapshot, "mismatch", actor("admin", "ADMIN"))).get("data");
        assertThat(task.get("status").asText()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(task.get("errorCode").asText()).isEqualTo("RESEARCH_PROTOCOL_INVALID");
        assertThat(task.get("result").isNull()).isTrue();
    }

    @Test
    void rejectsNumericAndBindingViolationsBeyondTheSchema() throws Exception {
        var request = fixture("request-ten");
        var response = fixture("response-ten");
        var model = response.get("model").deepCopy();
        var binding = response.get("binding").deepCopy();
        assertThatThrownBy(() -> validate(request, response, model, binding, "f".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        ((ObjectNode) response.get("prediction")).put("adCif24m", 0.95);
        assertThatThrownBy(() -> validate(request, response, model, binding, response.get("requestSha256").asText()))
                .isInstanceOf(IllegalArgumentException.class);
        ((ObjectNode) request.get("features").get("apoe4_count")).put("value", 1.0);
        assertThatThrownBy(() -> protocol.validateRequest(request, model)).isInstanceOf(IllegalArgumentException.class);
    }

    private void validate(JsonNode request, JsonNode result, JsonNode model, JsonNode binding, String digest) {
        protocol.validateResponse(result, request, model, "CONSTRUCTED_VALIDATION", digest,
                binding.get("inputSha256").asText(), binding.get("trustedContextSha256").asText());
    }

    @Test
    void migratesLegacySnapshotAndMarksInvalidRequests() throws Exception {
        ObjectNode valid = fixture("request-seven");
        String validCode = "DEMO-MIG-" + UUID.randomUUID();
        valid.put("subjectCode", validCode);
        long validSubject = subject(validCode);
        jdbc.update("""
                INSERT INTO research_table_snapshot(subject_id,authorized_user_id,request_json,wire_sha256,
                    request_sha256,model_json,data_scope,input_sha256,context_sha256,host_sync_status)
                VALUES(?,?,?,?,?,?,?,?,?,'PENDING')
                """, validSubject, userId("admin"), valid.toString(), "0".repeat(64), "0".repeat(64),
                "{}", "CONSTRUCTED_VALIDATION", "0".repeat(64), "0".repeat(64));

        ObjectNode referenced = fixture("request-seven");
        String referencedCode = "DEMO-MIG-USED-" + UUID.randomUUID();
        referenced.put("subjectCode", referencedCode);
        long referencedSubject = subject(referencedCode);
        jdbc.update("""
                INSERT INTO research_table_snapshot(subject_id,authorized_user_id,request_json,wire_sha256,
                    request_sha256,model_json,data_scope,input_sha256,context_sha256,host_sync_status)
                VALUES(?,?,?,?,?,?,?,?,?,'PENDING')
                """, referencedSubject, userId("admin"), referenced.toString(), "0".repeat(64),
                "0".repeat(64), "{}", "CONSTRUCTED_VALIDATION", "0".repeat(64), "0".repeat(64));
        long referencedSnapshot = jdbc.queryForObject(
                "SELECT id FROM research_table_snapshot WHERE subject_id=?", Long.class, referencedSubject);
        jdbc.update("""
                INSERT INTO research_table_task(snapshot_id,run_id,status,created_at,updated_at)
                VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, referencedSnapshot, "run-legacy-referenced", "COMPLETED");

        ObjectNode invalid = fixture("request-seven");
        String invalidCode = "DEMO-MIG-BAD-" + UUID.randomUUID();
        invalid.put("subjectCode", invalidCode);
        invalid.putObject("features");
        long invalidSubject = subject(invalidCode);
        jdbc.update("""
                INSERT INTO research_table_snapshot(subject_id,authorized_user_id,request_json,wire_sha256,
                    request_sha256,model_json,data_scope,input_sha256,context_sha256,host_sync_status)
                VALUES(?,?,?,?,?,?,?,?,?, 'PENDING')
                """, invalidSubject, userId("admin"), invalid.toString(), "0".repeat(64), "0".repeat(64),
                "{}", "CONSTRUCTED_VALIDATION", "0".repeat(64), "0".repeat(64));

        ObjectNode controlled = fixture("request-seven");
        String controlledCode = "DEMO-MIG-CTRL-" + UUID.randomUUID();
        controlled.put("subjectCode", controlledCode);
        long controlledSubject = subject(controlledCode);
        jdbc.update("""
                INSERT INTO research_table_snapshot(subject_id,authorized_user_id,request_json,wire_sha256,
                    request_sha256,model_json,data_scope,input_sha256,context_sha256,host_sync_status)
                VALUES(?,?,?,?,?,?,?,?,?, 'PENDING')
                """, controlledSubject, userId("admin"), controlled.toString(), "0".repeat(64),
                "0".repeat(64), "{}", "CONTROLLED_RESEARCH", "0".repeat(64), "0".repeat(64));

        JsonNode result = body(mvc.perform(post("/api/research/table-assessment-tasks/snapshots/migrate")
                .with(authentication(actor("admin", "ADMIN"))))).get("data");
        assertEquals(1, result.get("migrated").asInt());
        assertEquals(1, result.get("failed").asInt());
        assertEquals(1, result.get("failedSnapshotIds").size());
        assertEquals(1, result.get("skipped").asInt());
        assertEquals(1, result.get("skippedSnapshotIds").size());
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT host_sync_status FROM research_table_snapshot WHERE subject_id=?", String.class, validSubject));
        assertNotNull(jdbc.queryForObject(
                "SELECT context_json FROM research_table_snapshot WHERE subject_id=?", String.class, validSubject));
        assertEquals("MIGRATION_FAILED", jdbc.queryForObject(
                "SELECT host_sync_status FROM research_table_snapshot WHERE subject_id=?", String.class, invalidSubject));
        assertEquals("MIGRATION_SKIPPED", jdbc.queryForObject(
                "SELECT host_sync_status FROM research_table_snapshot WHERE subject_id=?", String.class, referencedSubject));
        assertEquals("0".repeat(64), jdbc.queryForObject(
                "SELECT request_sha256 FROM research_table_snapshot WHERE subject_id=?", String.class, referencedSubject));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT host_sync_status FROM research_table_snapshot WHERE subject_id=?", String.class, controlledSubject));
        assertEquals("0".repeat(64), jdbc.queryForObject(
                "SELECT request_sha256 FROM research_table_snapshot WHERE subject_id=?", String.class, controlledSubject));
    }

    @Test
    void adminCannotReadOrReviewResearchTasks() throws Exception {
        long snapshot = snapshot(fixture("request-seven"), fixture("response-seven"), "researcher");
        jdbc.update("""
                INSERT INTO research_table_task(snapshot_id,run_id,status,created_at,updated_at)
                VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, snapshot, "run-admin-review", "COMPLETED");
        long taskId = jdbc.queryForObject(
                "SELECT id FROM research_table_task WHERE run_id='run-admin-review'", Long.class);

        mvc.perform(get(PATH + "/reviews?status=PENDING")
                .with(authentication(actor("admin", "ADMIN")))).andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/" + taskId + "/review")
                        .with(authentication(actor("admin", "ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRMED\",\"comment\":\"管理员不应能复核\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void retryWithoutConfiguredHostLeavesTaskRetryable() throws Exception {
        var request = fixture("request-seven");
        long snapshot = snapshot(request, fixture("response-seven"), "admin");
        var actor = actor("admin", "ADMIN");
        reply(request, fixture("response-seven"), 200, true);
        JsonNode failed = body(submit(snapshot, "retry-no-host", actor)).get("data");
        long taskId = failed.get("taskId").asLong();
        assertThat(failed.get("status").asText()).isEqualTo("OUTCOME_UNKNOWN");

        settings.saveResearchHost("", "", 120, false, userId("admin"));
        client.refresh();
        try {
            mvc.perform(post(PATH + "/" + taskId + "/retry").with(authentication(actor)))
                    .andExpect(status().isServiceUnavailable());
            var row = jdbc.queryForMap(
                    "SELECT status FROM research_table_task WHERE id=?", taskId);
            assertThat(row.get("status")).isEqualTo("OUTCOME_UNKNOWN");
        } finally {
            // Remove the override so the test profile's 600ms host configuration applies again.
            jdbc.update("DELETE FROM system_setting WHERE setting_key='research.host'");
            client.refresh();
        }
    }

    @Test
    void recoversResearchTaskAbandonedInRunningState() throws Exception {
        long snapshot = snapshot(fixture("request-seven"), fixture("response-seven"), "admin");
        jdbc.update("""
                INSERT INTO research_table_task(snapshot_id,run_id,status,execution_id,created_at,updated_at)
                VALUES(?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, snapshot, "run-orphaned", "RUNNING", UUID.randomUUID().toString());

        assertThat(recovery.recoverInterrupted()).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT status,error_code,execution_id FROM research_table_task WHERE run_id='run-orphaned'");
        assertThat(row.get("status")).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(row.get("error_code")).isEqualTo(ResearchTaskRecovery.INTERRUPTED_CODE);
        assertThat(row.get("execution_id")).isNull();
    }

    @Test
    void onlyOneExecutorClaimsARunningTask() throws Exception {
        long snapshot = snapshot(fixture("request-seven"), fixture("response-seven"), "admin");
        String executionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO research_table_task(snapshot_id,run_id,status,execution_id,created_at,updated_at)
                VALUES(?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, snapshot, "run-claim", "RUNNING", executionId);
        long taskId = jdbc.queryForObject(
                "SELECT id FROM research_table_task WHERE run_id='run-claim'", Long.class);

        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                return lease.claim(taskId, executionId, "executor-a");
            });
            var second = executor.submit(() -> {
                start.await();
                return lease.claim(taskId, executionId, "executor-b");
            });
            start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        var row = jdbc.queryForMap(
                "SELECT status,executor_id,lease_expires_at FROM research_table_task WHERE id=?", taskId);
        assertThat(row.get("status")).isEqualTo("RUNNING");
        assertThat(row.get("executor_id")).isIn("executor-a", "executor-b");
        assertThat(row.get("lease_expires_at")).isNotNull();
    }

    @Test
    void recoveryReclaimsOnlyExpiredLeases() throws Exception {
        long activeSnapshot = snapshot(fixture("request-seven"), fixture("response-seven"), "admin");
        long expiredSnapshot = snapshot(fixture("request-seven"), fixture("response-seven"), "admin");
        jdbc.update("""
                INSERT INTO research_table_task(snapshot_id,run_id,status,execution_id,executor_id,
                    lease_expires_at,created_at,updated_at)
                VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, activeSnapshot, "run-active", "RUNNING", UUID.randomUUID().toString(), "executor-live",
                Timestamp.from(Instant.now().plusSeconds(300)));
        jdbc.update("""
                INSERT INTO research_table_task(snapshot_id,run_id,status,execution_id,executor_id,
                    lease_expires_at,created_at,updated_at)
                VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, expiredSnapshot, "run-expired", "RUNNING", UUID.randomUUID().toString(), "executor-dead",
                Timestamp.from(Instant.now().minusSeconds(1)));

        assertThat(recovery.recoverInterrupted()).isEqualTo(1);
        var active = jdbc.queryForMap(
                "SELECT status,executor_id,lease_expires_at FROM research_table_task WHERE run_id='run-active'");
        assertThat(active.get("status")).isEqualTo("RUNNING");
        assertThat(active.get("executor_id")).isEqualTo("executor-live");
        assertThat(active.get("lease_expires_at")).isNotNull();

        var expired = jdbc.queryForMap("""
                SELECT status,error_code,execution_id,executor_id,lease_expires_at
                FROM research_table_task WHERE run_id='run-expired'
                """);
        assertThat(expired.get("status")).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(expired.get("error_code")).isEqualTo(ResearchTaskRecovery.INTERRUPTED_CODE);
        assertThat(expired.get("execution_id")).isNull();
        assertThat(expired.get("executor_id")).isNull();
        assertThat(expired.get("lease_expires_at")).isNull();
    }

    @Test
    void inFlightTaskAtFullRequestLeaseIsNotReclaimed() throws Exception {
        long snapshot = snapshot(fixture("request-seven"), fixture("response-seven"), "admin");
        jdbc.update("""
                INSERT INTO research_table_task(snapshot_id,run_id,status,execution_id,executor_id,
                    lease_expires_at,created_at,updated_at)
                VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, snapshot, "run-in-flight", "RUNNING", UUID.randomUUID().toString(), "executor-live",
                Timestamp.from(Instant.now().plusSeconds(ResearchTaskLease.MINIMUM_LEASE_SECONDS)));

        assertThat(recovery.recoverInterrupted()).isZero();
        var active = jdbc.queryForMap("""
                SELECT status,executor_id,lease_expires_at
                FROM research_table_task WHERE run_id='run-in-flight'
                """);
        assertThat(active.get("status")).isEqualTo("RUNNING");
        assertThat(active.get("executor_id")).isEqualTo("executor-live");
        assertThat(active.get("lease_expires_at")).isNotNull();
    }

    @Test
    void metricsSeparateResearchTasksByRole() throws Exception {
        long snapshot = snapshot(fixture("request-seven"), fixture("response-seven"), "admin");
        body(submit(snapshot, "metrics", actor("admin", "ADMIN")));

        JsonNode admin = body(mvc.perform(get(PATH + "/metrics")
                .with(authentication(actor("admin", "ADMIN"))))).get("data");
        assertThat(admin.get("tasksToday").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(admin.get("completed").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(admin.get("syncedSnapshots").asLong()).isGreaterThanOrEqualTo(1);

        JsonNode doctorWithoutGrant = body(mvc.perform(get(PATH + "/metrics")
                .with(authentication(actor("doctor", "DOCTOR"))))).get("data");
        assertThat(doctorWithoutGrant.get("tasksToday").asLong()).isZero();

        jdbc.update("""
                INSERT INTO subject_permission(subject_id,user_id,permission_type)
                SELECT subject_id,?,'VIEW' FROM research_table_snapshot WHERE id=?
                """, userId("doctor"), snapshot);
        JsonNode doctorWithGrant = body(mvc.perform(get(PATH + "/metrics")
                .with(authentication(actor("doctor", "DOCTOR"))))).get("data");
        assertThat(doctorWithGrant.get("tasksToday").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(doctorWithGrant.get("completed").asLong()).isGreaterThanOrEqualTo(1);

        JsonNode researcher = body(mvc.perform(get(PATH + "/metrics")
                .with(authentication(actor("researcher", "RESEARCHER"))))).get("data");
        assertThat(researcher.get("tasksToday").asLong()).isZero();
        assertThat(researcher.get("syncedSnapshots").asLong()).isZero();

        mvc.perform(get(PATH + "/metrics")
                .with(authentication(actor("patient", "PATIENT")))).andExpect(status().isForbidden());
    }

    private ResultActions submit(long snapshot, String key, UsernamePasswordAuthenticationToken actor) throws Exception {
        return mvc.perform(post(PATH).with(authentication(actor)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"snapshotId\":" + snapshot + "}"));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private long snapshot(JsonNode request, JsonNode example, String owner) throws Exception {
        JsonNode binding = example.get("binding");
        String input = binding.isNull() ? "0".repeat(64) : binding.get("inputSha256").asText();
        String context = binding.isNull() ? "0".repeat(64) : binding.get("trustedContextSha256").asText();
        return snapshot(request, example.get("model"), example.get("requestSha256").asText(), input, context, owner);
    }

    private long snapshot(JsonNode request, JsonNode model, String requestDigest, String input, String context,
                          String owner) throws Exception {
        String code = "DEMO-RT-" + UUID.randomUUID();
        jdbc.update("INSERT INTO subject(subject_code,alias,status,data_version,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",
                code, "构造档案", "ACTIVE", "demo-rt");
        long subject = jdbc.queryForObject("SELECT id FROM subject WHERE subject_code=?", Long.class, code);
        byte[] wire = bytes(request);
        jdbc.update("""
                INSERT INTO research_table_snapshot(subject_id,authorized_user_id,request_json,wire_sha256,
                    request_sha256,model_json,data_scope,input_sha256,context_sha256,host_sync_status)
                VALUES(?,?,?,?,?,?,?,?,?,'SYNCED')
                """, subject, userId(owner), request.toString(),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wire)),
                requestDigest, model.toString(),
                "CONSTRUCTED_VALIDATION", input, context);
        return jdbc.queryForObject("SELECT id FROM research_table_snapshot WHERE subject_id=?", Long.class, subject);
    }

    private long subject(String code) {
        jdbc.update("INSERT INTO subject(subject_code,alias,status,data_version,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",
                code, "登记测试档案", "ACTIVE", "demo-rt");
        return jdbc.queryForObject("SELECT id FROM subject WHERE subject_code=?", Long.class, code);
    }

    private void insertQuality(long subjectId, String status) {
        String dataVersion =
                jdbc.queryForObject("SELECT data_version FROM subject WHERE id=?", String.class, subjectId);
        jdbc.update("""
                INSERT INTO quality_check(subject_id,quality_status,issues,checked_at,rule_version,data_version,input_digest)
                VALUES(?,?,?,?,?,?,?)
                """, subjectId, status, "[]", new Timestamp(System.currentTimeMillis()),
                "qc-rules-demo-v2", dataVersion, qualityChecks.inputDigest(subjectId));
    }

    private long userId(String name) {
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, name);
    }

    private UsernamePasswordAuthenticationToken actor(String name, String... roles) {
        var principal = new AppUserPrincipal(userId(name), name, "", List.of(roles), true);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    private ObjectNode fixture(String name) throws IOException {
        return (ObjectNode) json.readTree(Files.readAllBytes(
                Path.of("../docs/contracts/examples/research-table-agent", name + ".json")));
    }

    private ObjectNode uniqueRequest() throws IOException {
        return fixture("request-seven").put("runId", "run-" + UUID.randomUUID());
    }

    private static byte[] bytes(JsonNode value) {
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Exchange reply(JsonNode request, JsonNode response, int status, boolean delay) {
        var exchange = new Exchange(bytes(request), bytes(response), status, delay,
                new CountDownLatch(1), new AtomicReference<>());
        EXCHANGE.set(exchange);
        return exchange;
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(HOST_THREADS);
            server.createContext("/api/research-table/v1/snapshots", http -> {
                try (http) {
                    SNAPSHOT_REQUESTS.incrementAndGet();
                    var mapper = new ObjectMapper();
                    JsonNode request = mapper.readTree(http.getRequestBody());
                    byte[] response = mapper.writeValueAsBytes(snapshotReceipt(request, SNAPSHOT_REPLY_MODE.get()));
                    http.getResponseHeaders().set("Content-Type", "application/json");
                    http.sendResponseHeaders(200, response.length);
                    http.getResponseBody().write(response);
                } catch (IOException disconnected) {
                    // The batch test only needs the status; a closed socket is not asserted here.
                }
            });
            server.createContext(ResearchTableProtocol.PATH, http -> {
                Exchange expected = EXCHANGE.get();
                REQUESTS.incrementAndGet();
                try (http) {
                    byte[] received = http.getRequestBody().readAllBytes();
                    if (!"POST".equals(http.getRequestMethod())
                            || !("Bearer " + TOKEN).equals(http.getRequestHeaders().getFirst("Authorization"))
                            || !Integer.toString(received.length).equals(http.getRequestHeaders().getFirst("Content-Length"))
                            || !java.util.Arrays.equals(received, expected.request())) {
                        expected.problem().set("The draft wire request changed");
                    }
                    expected.started().countDown();
                    if (expected.delay()) {
                        try {
                            Thread.sleep(1_500);
                        } catch (InterruptedException stop) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    http.getResponseHeaders().set("Content-Type", "application/json");
                    http.sendResponseHeaders(expected.status(), expected.response().length);
                    http.getResponseBody().write(expected.response());
                } catch (IOException disconnected) {
                    if (!expected.delay()) {
                        expected.problem().set("Fixture HTTP exchange failed");
                    }
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static ObjectNode snapshotReceipt(JsonNode request, String mode) {
        long id = request.path("snapshotId").asLong();
        String runId = request.path("runId").asText();
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("status", "SYNCED")
                .put("runId", mode.equals("WRONG_RUN") ? "run-other-snapshot" : runId)
                .put("snapshotId", mode.equals("WRONG_SNAPSHOT") ? id + 1 : id);
    }
}
