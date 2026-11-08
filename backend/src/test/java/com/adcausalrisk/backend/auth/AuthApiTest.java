package com.adcausalrisk.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.adcausalrisk.backend.contract.ContractSchemas;
import com.adcausalrisk.backend.security.MenuCatalog;
import com.adcausalrisk.backend.user.SysRole;
import com.adcausalrisk.backend.user.SysRoleRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * End-to-end tests for FR-AUTH-01: the real {@code SecurityFilterChain}, the real BCrypt hashes
 * seeded by {@code V2__seed_synthetic_accounts.sql}, and responses validated against {@code
 * contracts/java-business.target.openapi.json}.
 *
 * <p>Nothing is mocked. Sessions are established by actually calling {@code POST /api/auth/login}
 * and reused for the follow-up requests, which is what a browser does with the {@code JSESSIONID}
 * cookie.
 *
 * <p>These tests cover authentication and the role menu boundary. BusinessCloseoutTest separately
 * covers account administration, object scope, session revocation and audit persistence.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthApiTest {

    /** Documented synthetic demo password; kept in sync with V2 and backend/README.md. */
    private static final String DEMO_PASSWORD = "DemoPass!2026";

    private static final List<String> ENVELOPE_PROPERTIES =
            List.of("code", "message", "data", "errorCode", "traceId", "timestamp");

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private SysRoleRepository sysRoleRepository;

    @Test
    void loginReturnsTheCurrentUserTheContractDeclares() throws Exception {
        MvcResult result =
                mockMvc.perform(loginRequest("admin", DEMO_PASSWORD))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode body = bodyOf(result);

        assertThat(fieldNames(body)).containsExactlyElementsOf(ENVELOPE_PROPERTIES);
        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("errorCode").isNull()).isTrue();
        assertThat(body.get("data").get("username").asText()).isEqualTo("admin");
        assertThat(body.get("data").get("id").asLong()).isPositive();
        assertThat(rolesOf(body)).containsExactly("ADMIN");
        // Design_Guide section 1: a pure administrator does not acquire clinical/research menus.
        assertThat(menusOf(body)).containsExactly("audit", "system", "users");
        assertThat(ContractSchemas.validateResponse(body, "/api/auth/login", "post", 200))
                .isEmpty();
    }

    @Test
    void loginEstablishesASessionThatApiMeAccepts() throws Exception {
        MockHttpSession session = login("admin", DEMO_PASSWORD);

        MvcResult result =
                mockMvc.perform(get("/api/me").session(session))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode body = bodyOf(result);

        assertThat(body.get("data").get("username").asText()).isEqualTo("admin");
        assertThat(rolesOf(body)).containsExactly("ADMIN");
        assertThat(ContractSchemas.validateResponse(body, "/api/me", "get", 200)).isEmpty();
    }

    @Test
    void multiRoleAccountReceivesTheUnionOfItsRolesAndMenus() throws Exception {
        JsonNode data =
                bodyOf(
                                mockMvc.perform(loginRequest("research_doctor", DEMO_PASSWORD))
                                        .andExpect(status().isOk())
                                        .andReturn())
                        .get("data");

        // research_doctor holds RESEARCHER and DOCTOR (see V2); both roles and the union of
        // their menus must be reported, in the deterministic sorted order.
        assertThat(stringValues(data.get("roles"))).containsExactly("DOCTOR", "RESEARCHER");
        assertThat(stringValues(data.get("menus")))
                .containsExactly(
                        "followups",
                        "graph",
                        "imports",
                        "quality",
                        "reports",
                        "research",
                        "reviews",
                        "subjects",
                        "tasks");
    }

    @Test
    void unknownUserAndWrongPasswordProduceTheSameRefusal() throws Exception {
        JsonNode wrongPassword =
                bodyOf(
                        mockMvc.perform(loginRequest("admin", "definitely-not-the-password"))
                                .andExpect(status().isUnauthorized())
                                .andReturn());
        JsonNode unknownUser =
                bodyOf(
                        mockMvc.perform(loginRequest("no-such-account", DEMO_PASSWORD))
                                .andExpect(status().isUnauthorized())
                                .andReturn());

        assertThat(wrongPassword.get("errorCode").asText()).isEqualTo("AUTH_INVALID_CREDENTIALS");
        assertThat(unknownUser.get("errorCode").asText()).isEqualTo("AUTH_INVALID_CREDENTIALS");
        // FR-AUTH-01 "统一失败提示": the two cases must be indistinguishable to the caller.
        assertThat(wrongPassword.get("message").asText())
                .isEqualTo(unknownUser.get("message").asText());
        assertThat(wrongPassword.get("data").isNull()).isTrue();
        assertThat(ContractSchemas.validateResponse(wrongPassword, "/api/auth/login", "post", 401))
                .isEmpty();
    }

    @Test
    void missingLoginFieldsAreReportedAsAValidationError() throws Exception {
        JsonNode body =
                bodyOf(
                        mockMvc.perform(
                                        post("/api/auth/login")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{}"))
                                .andExpect(status().isBadRequest())
                                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("message").asText()).contains("username").contains("password");
    }

    @Test
    void loginRejectsPropertiesTheContractDoesNotDefine() throws Exception {
        JsonNode body =
                bodyOf(
                        mockMvc.perform(
                                        post("/api/auth/login")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"username\":\"admin\",\"password\":\""
                                                                + DEMO_PASSWORD
                                                                + "\",\"adMin\":true}"))
                                .andExpect(status().isBadRequest())
                                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("MALFORMED_JSON");
    }

    @Test
    void anonymousApiMeIsRefusedWithAnEnvelope() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized()).andReturn();

        JsonNode body = bodyOf(result);
        assertThat(fieldNames(body)).containsExactlyElementsOf(ENVELOPE_PROPERTIES);
        assertThat(body.get("errorCode").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(ContractSchemas.validateResponse(body, "/api/me", "get", 401)).isEmpty();
    }

    @Test
    void logoutInvalidatesTheSession() throws Exception {
        MockHttpSession session = login("admin", DEMO_PASSWORD);

        MvcResult result =
                mockMvc.perform(post("/api/auth/logout").session(session))
                        .andExpect(status().isOk())
                        .andReturn();

        assertThat(session.isInvalid()).as("logout must invalidate the session").isTrue();
        assertThat(fieldNames(bodyOf(result))).containsExactlyElementsOf(ENVELOPE_PROPERTIES);
        assertThat(
                        ContractSchemas.validateResponse(
                                bodyOf(result), "/api/auth/logout", "post", 200))
                .isEmpty();

        // The JSESSIONID the browser still holds no longer authenticates anything.
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutASessionIsRefused() throws Exception {
        JsonNode body =
                bodyOf(
                        mockMvc.perform(post("/api/auth/logout"))
                                .andExpect(status().isUnauthorized())
                                .andReturn());

        assertThat(body.get("errorCode").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void loginChangeTheSessionIdToPreventSessionFixation() throws Exception {
        MockHttpSession beforeLogin = new MockHttpSession();
        String originalSessionId = beforeLogin.getId();

        MvcResult result =
                mockMvc.perform(
                                post("/api/auth/login")
                                        .session(beforeLogin)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "username",
                                                                "admin",
                                                                "password",
                                                                DEMO_PASSWORD))))
                        .andExpect(status().isOk())
                        .andReturn();

        MockHttpSession afterLogin = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(afterLogin).isNotNull();
        assertThat(afterLogin.getId()).isNotEqualTo(originalSessionId);
    }

    @Test
    void seededRoleVocabularyMatchesTheFourContractRoles() {
        assertThat(sysRoleRepository.findAll().stream().map(SysRole::getRoleCode).sorted().toList())
                .containsExactly("ADMIN", "DOCTOR", "PATIENT", "RESEARCHER");
    }

    @Test
    void menusAreTheUnionOfTheHoldersRoles() {
        assertThat(MenuCatalog.menusFor(List.of("ADMIN")))
                .containsExactly("audit", "system", "users");
        assertThat(MenuCatalog.menusFor(List.of("ADMIN", "DOCTOR")))
                .containsExactly(
                        "audit",
                        "followups",
                        "graph",
                        "reports",
                        "reviews",
                        "subjects",
                        "system",
                        "users");
        assertThat(MenuCatalog.menusFor(List.of("PATIENT")))
                .containsExactly("followups", "summary");
        assertThat(MenuCatalog.menusFor(List.of("ADMIN", "PATIENT")))
                .containsExactly("followups", "summary");
        assertThat(MenuCatalog.menusFor(List.of("RESEARCHER", "DOCTOR")))
                .containsExactly(
                        "followups",
                        "graph",
                        "imports",
                        "quality",
                        "reports",
                        "research",
                        "reviews",
                        "subjects",
                        "tasks");
        assertThat(MenuCatalog.menusFor(List.of("NOT_A_CONTRACT_ROLE"))).isEmpty();
    }

    private RequestBuilder loginRequest(String username, String password) throws Exception {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password)));
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result =
                mockMvc.perform(loginRequest(username, password))
                        .andExpect(status().isOk())
                        .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).as("a successful login must establish a session").isNotNull();
        return session;
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> rolesOf(JsonNode body) {
        return stringValues(body.get("data").get("roles"));
    }

    private static List<String> menusOf(JsonNode body) {
        return stringValues(body.get("data").get("menus"));
    }

    private static List<String> stringValues(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        arrayNode.forEach(element -> values.add(element.asText()));
        return values;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
