package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.contract.ContractSchemas;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:research_table_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResearchTableAssessmentTest {
    private static final String PATH = "/api/research/table-assessment-tasks";
    private static final String TOKEN = "constructed-test-token";
    private static final String LIVE_MANIFEST = System.getProperty("research.table.it.manifest");
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicReference<Exchange> EXCHANGE = new AtomicReference<>();
    private static final ExecutorService HOST_THREADS = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer SERVER = startServer();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired ResearchTableProtocol protocol;

    private record Exchange(byte[] request, byte[] response, int status, boolean delay,
                            CountDownLatch started, AtomicReference<String> problem) {}

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add("research.table.base-url", () -> "http://127.0.0.1:"
                + (LIVE_MANIFEST == null ? SERVER.getAddress().getPort() : liveManifest().get("port").asInt()));
        properties.add("research.table.bearer-token",
                () -> LIVE_MANIFEST == null ? TOKEN : System.getenv("RESEARCH_TABLE_TEST_TOKEN"));
        properties.add("research.table.read-timeout", () -> LIVE_MANIFEST == null ? "600ms" : "30s");
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
        HOST_THREADS.close();
    }

    @BeforeEach
    void clearResearchTasks() {
        jdbc.update("DELETE FROM idempotency_record WHERE operation='RESEARCH_TABLE_ASSESSMENT'");
        jdbc.update("DELETE FROM research_table_task");
        jdbc.update("DELETE FROM research_table_snapshot");
    }

    @Test
    @EnabledIfSystemProperty(named = "research.table.it.manifest", matches = ".+")
    void callsIndependentPythonResearchHost() throws Exception {
        var actor = actor("admin", "ADMIN");
        JsonNode cases = liveManifest().get("cases");
        for (String label : List.of("seven", "ten", "blocked")) {
            JsonNode binding = cases.get(label);
            JsonNode request = binding.get("request");
            long snapshot = snapshot(request, binding.get("model"), binding.get("requestSha256").textValue(),
                    binding.get("inputSha256").textValue(), binding.get("contextSha256").textValue(), "admin");
            JsonNode created = body(submit(snapshot, "live-" + label, actor));
            assertThat(ContractSchemas.validateResponse(created, PATH, "post", 200)).isEmpty();
            JsonNode task = created.get("data");
            boolean blocked = label.equals("blocked");
            assertThat(task.get("status").textValue()).isEqualTo(blocked ? "BLOCKED" : "COMPLETED");
            JsonNode response = task.get("result");
            JsonNode fixture = fixture("response-" + label);
            for (String field : List.of("prediction", "explanation", "admission", "computation")) {
                assertThat(response.get(field)).as("%s %s", label, field).isEqualTo(fixture.get(field));
            }
            long taskId = task.get("taskId").longValue();
            assertThat(body(submit(snapshot, "live-" + label, actor)).get("data")).isEqualTo(task);
            assertThat(body(mvc.perform(get(PATH + "/" + taskId).with(authentication(actor)))).get("data"))
                    .isEqualTo(task);
            mvc.perform(get(PATH + "/" + taskId).with(authentication(actor("researcher", "RESEARCHER"))))
                    .andExpect(status().isForbidden());
            submit(snapshot, "different-" + label, actor).andExpect(status().isConflict());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM research_table_task", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM risk_assessment", Integer.class)).isZero();
    }

    private static JsonNode liveManifest() {
        try {
            return new ObjectMapper().readTree(Files.readAllBytes(Path.of(LIVE_MANIFEST)));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read constructed Python host manifest", failure);
        }
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
                    request_sha256,model_json,data_scope,input_sha256,context_sha256) VALUES(?,?,?,?,?,?,?,?,?)
                """, subject, userId(owner), request.toString(),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wire)),
                requestDigest, model.toString(),
                "CONSTRUCTED_VALIDATION", input, context);
        return jdbc.queryForObject("SELECT id FROM research_table_snapshot WHERE subject_id=?", Long.class, subject);
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
}
