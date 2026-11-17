package com.adcausalrisk.backend.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Upload, activate and roll back a model release through the real console API. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ModelRepositoryApiTest {

    private static final String DEMO_PASSWORD = "DemoPass!2026";

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Test
    void adminCanUploadActivateAndRollBackAModelRelease() throws Exception {
        MockHttpSession admin = login("admin");
        String version = "ui-test-" + UUID.randomUUID();
        byte[] artifact =
                ("fake-artifact-" + version).getBytes(StandardCharsets.UTF_8);
        String artifactSha = sha256(artifact);
        MockMultipartFile manifest =
                new MockMultipartFile(
                        "manifest",
                        "manifest.json",
                        MediaType.APPLICATION_JSON_VALUE,
                        objectMapper.writeValueAsBytes(
                                Map.of(
                                        "modelVersion", version,
                                        "family", "logistic",
                                        "features", 7)));
        MockMultipartFile artifactFile =
                new MockMultipartFile(
                        "artifact", "model.bin", MediaType.APPLICATION_OCTET_STREAM_VALUE, artifact);

        MvcResult uploaded =
                mockMvc.perform(
                                multipart("/api/system/models")
                                        .file(manifest)
                                        .file(artifactFile)
                                        .param("artifactSha256", artifactSha)
                                        .session(admin))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode uploadedBody = bodyOf(uploaded);
        assertThat(ContractSchemas.validateResponse(
                                uploadedBody, "/api/system/models", "post", 200))
                .isEmpty();
        JsonNode uploadedData = uploadedBody.get("data");
        assertThat(uploadedData.get("modelVersion").asText()).isEqualTo(version);
        assertThat(uploadedData.get("registered").asBoolean()).isTrue();
        assertThat(uploadedData.get("artifactSha256").asText()).isEqualTo(artifactSha);
        assertThat(uploadedData.get("enabled").asBoolean()).isFalse();

        mockMvc.perform(
                        patch("/api/system/models/{version}", version)
                                .session(admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isOk());

        MvcResult list = mockMvc.perform(get("/api/system/models").session(admin))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(ContractSchemas.validateResponse(
                                bodyOf(list), "/api/system/models", "get", 200))
                .isEmpty();
        JsonNode active = findModel(bodyOf(list), version);
        assertThat(active).isNotNull();
        assertThat(active.get("enabled").asBoolean()).isTrue();

        mockMvc.perform(
                        post("/api/system/models/{version}/rollback", "deepagent-causal-warning-v1")
                                .session(admin))
                .andExpect(status().isOk());

        MvcResult afterRollback = mockMvc.perform(get("/api/system/models").session(admin))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rolledBack = findModel(bodyOf(afterRollback), version);
        assertThat(rolledBack).isNotNull();
        assertThat(rolledBack.get("enabled").asBoolean()).isFalse();
        assertThat(findModel(bodyOf(afterRollback), "deepagent-causal-warning-v1")
                        .get("enabled")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void uploadRejectsAMismatchedShaAndDuplicates() throws Exception {
        MockHttpSession admin = login("admin");
        String version = "mismatch-" + UUID.randomUUID();
        MockMultipartFile manifest =
                new MockMultipartFile(
                        "manifest",
                        "manifest.json",
                        MediaType.APPLICATION_JSON_VALUE,
                        objectMapper.writeValueAsBytes(Map.of("modelVersion", version)));

        mockMvc.perform(
                        multipart("/api/system/models")
                                .file(manifest)
                                .file(new MockMultipartFile(
                                        "artifact",
                                        "model.bin",
                                        MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                        "abc".getBytes(StandardCharsets.UTF_8)))
                                .param("artifactSha256", "0".repeat(64))
                                .session(admin))
                .andExpect(status().isBadRequest());

        String sha = sha256("same".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile artifact =
                new MockMultipartFile(
                        "artifact",
                        "model.bin",
                        MediaType.APPLICATION_OCTET_STREAM_VALUE,
                        "same".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(
                        multipart("/api/system/models")
                                .file(manifest)
                                .file(artifact)
                                .param("artifactSha256", sha)
                                .session(admin))
                .andExpect(status().isOk());
        mockMvc.perform(
                        multipart("/api/system/models")
                                .file(manifest)
                                .file(artifact)
                                .param("artifactSha256", sha)
                                .session(admin))
                .andExpect(status().isConflict());
    }

    @Test
    void nonAdminCannotReadOrChangeTheRepository() throws Exception {
        MockHttpSession researcher = login("research_doctor");

        mockMvc.perform(get("/api/system/models").session(researcher))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        post("/api/system/models/{version}/rollback", "deepagent-causal-warning-v1")
                                .session(researcher))
                .andExpect(status().isForbidden());
    }

    private JsonNode findModel(JsonNode envelope, String version) {
        for (JsonNode row : envelope.get("data")) {
            if (version.equals(row.get("modelVersion").asText())) return row;
        }
        return null;
    }

    private String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
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
