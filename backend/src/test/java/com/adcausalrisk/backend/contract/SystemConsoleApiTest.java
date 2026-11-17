package com.adcausalrisk.backend.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The system console is the only place where an administrator can change runtime configuration.
 * These tests exercise the real controller, persistence and OpenAPI validation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SystemConsoleApiTest {

    private static final String DEMO_PASSWORD = "DemoPass!2026";

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Test
    void defaultsAreReadableAndValidatedAgainstTheContract() throws Exception {
        MockHttpSession admin = login("admin");

        assertEnvelope(admin, "/api/system/research-host", "get");
        assertEnvelope(admin, "/api/system/assessment-policy", "get");
        assertEnvelope(admin, "/api/system/retention", "get");
    }

    @Test
    void researchHostCanBeConfiguredWithoutEchoingTheToken() throws Exception {
        MockHttpSession admin = login("admin");

        MvcResult saved =
                mockMvc.perform(
                                put("/api/system/research-host")
                                        .session(admin)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "baseUrl",
                                                                "http://127.0.0.1:8091",
                                                                "bearerToken",
                                                                "demo-bearer-token",
                                                                "readTimeoutSeconds",
                                                                90,
                                                                "enabled",
                                                                true))))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode body = bodyOf(saved);
        assertThat(ContractSchemas.validateResponse(
                                body, "/api/system/research-host", "put", 200))
                .isEmpty();
        JsonNode data = body.get("data");
        assertThat(data.get("baseUrl").asText()).isEqualTo("http://127.0.0.1:8091");
        assertThat(data.get("bearerTokenConfigured").asBoolean()).isTrue();
        assertThat(data.get("configured").asBoolean()).isTrue();
        assertThat(data.get("readTimeoutSeconds").asInt()).isEqualTo(90);
        assertThat(saved.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("demo-bearer-token");

        // A blank token on the next update keeps the stored token.
        MvcResult kept =
                mockMvc.perform(
                                put("/api/system/research-host")
                                        .session(admin)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "baseUrl",
                                                                "http://127.0.0.1:8091",
                                                                "bearerToken",
                                                                "",
                                                                "readTimeoutSeconds",
                                                                120,
                                                                "enabled",
                                                                true))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(bodyOf(kept).get("data").get("bearerTokenConfigured").asBoolean()).isTrue();
        assertThat(bodyOf(kept).get("data").get("readTimeoutSeconds").asInt()).isEqualTo(120);
    }

    @Test
    void invalidResearchHostIsRejected() throws Exception {
        MockHttpSession admin = login("admin");

        mockMvc.perform(
                        put("/api/system/research-host")
                                .session(admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "baseUrl",
                                                        "https://example.com/table",
                                                        "bearerToken",
                                                        "demo-bearer-token",
                                                        "readTimeoutSeconds",
                                                        120,
                                                        "enabled",
                                                        true))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void assessmentPolicyCanBeToggled() throws Exception {
        MockHttpSession admin = login("admin");

        MvcResult saved =
                mockMvc.perform(
                                put("/api/system/assessment-policy")
                                        .session(admin)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of("testRuleEnabled", false))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(ContractSchemas.validateResponse(
                                bodyOf(saved), "/api/system/assessment-policy", "put", 200))
                .isEmpty();
        assertThat(bodyOf(saved).get("data").get("testRuleEnabled").asBoolean()).isFalse();

        MvcResult read =
                mockMvc.perform(get("/api/system/assessment-policy").session(admin))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(bodyOf(read).get("data").get("testRuleEnabled").asBoolean()).isFalse();
    }

    @Test
    void retentionMustStayWithinTheDocumentedRange() throws Exception {
        MockHttpSession admin = login("admin");

        MvcResult saved =
                mockMvc.perform(
                                put("/api/system/retention")
                                        .session(admin)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of("auditLogDays", 90))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(ContractSchemas.validateResponse(
                                bodyOf(saved), "/api/system/retention", "put", 200))
                .isEmpty();
        assertThat(bodyOf(saved).get("data").get("auditLogDays").asInt()).isEqualTo(90);

        mockMvc.perform(
                        put("/api/system/retention")
                                .session(admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("auditLogDays", 5))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void researcherCannotReadOrChangeTheConsole() throws Exception {
        MockHttpSession researcher = login("research_doctor");

        mockMvc.perform(get("/api/system/research-host").session(researcher))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/system/assessment-policy").session(researcher))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/system/retention").session(researcher))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        put("/api/system/retention")
                                .session(researcher)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("auditLogDays", 90))))
                .andExpect(status().isForbidden());
    }

    private void assertEnvelope(MockHttpSession session, String path, String method)
            throws Exception {
        MvcResult result =
                mockMvc.perform(get(path).session(session)).andExpect(status().isOk()).andReturn();
        JsonNode body = bodyOf(result);
        assertThat(ContractSchemas.validateResponse(body, path, method, 200)).isEmpty();
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result =
                mockMvc.perform(loginRequest(username, DEMO_PASSWORD))
                        .andExpect(status().isOk())
                        .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private RequestBuilder loginRequest(String username, String password) throws Exception {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password)));
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
