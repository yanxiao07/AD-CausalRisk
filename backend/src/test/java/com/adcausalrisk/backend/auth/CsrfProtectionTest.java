package com.adcausalrisk.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.adcausalrisk.backend.contract.ContractSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Enables CSRF for one test context even though the shared test profile disables it for the
 * hundreds of pre-existing MVC tests. This exercises the real {@code CsrfFilter}, the cookie
 * repository, the public token endpoint and the login exemption together.
 */
@SpringBootTest(properties = "app.security.csrf-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CsrfProtectionTest {

    private static final String PASSWORD = "DemoPass!2026";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void stateChangingRequestWithoutTokenIsRejectedAndWithTokenSucceeds() throws Exception {
        MvcResult login =
                mvc.perform(
                                post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"username":"doctor","password":"%s"}
                                                """
                                                        .formatted(PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();

        // The session cookie alone is not enough: logout is a state-changing POST.
        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isForbidden());

        MvcResult csrfResult =
                mvc.perform(get("/api/auth/csrf").session(session))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode body = json.readTree(csrfResult.getResponse().getContentAsByteArray());
        assertThat(ContractSchemas.validateResponse(body, "/api/auth/csrf", "get", 200)).isEmpty();
        String token = body.get("data").get("token").asText();
        assertThat(body.get("data").get("headerName").asText()).isEqualTo("X-CSRF-TOKEN");
        assertThat(token).isNotBlank();

        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();

        mvc.perform(
                        post("/api/auth/logout")
                                .session(session)
                                .cookie(csrfCookie)
                                .header("X-CSRF-TOKEN", token))
                .andExpect(status().isOk());
    }
}
