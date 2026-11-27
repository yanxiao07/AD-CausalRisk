package com.adcausalrisk.backend.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks the rule that a card confirmation only publishes what the patient actually previewed.
 *
 * <p>Preview returns the version of the memo text it was computed from. If another window edits
 * the memo in between, the confirmation must be refused instead of quietly publishing cards
 * extracted from text the patient never saw.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PatientMemoCardDraftTest {

    private static final String DEMO_PASSWORD = "DemoPass!2026";
    private static final String MEMOS = "/api/patients/me/memos";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void previewCarriesTheMemoVersionAndSplitsEachColonSentenceIntoItsOwnCard() throws Exception {
        MockHttpSession session = login("patient");
        long memoId = createMemo(session, "周三下棋地点：社区活动室。棋盒放在哪里：蓝色帆布袋。");

        JsonNode preview = preview(session, memoId);
        assertThat(preview.path("memoVersion").asText()).isNotEmpty();
        assertThat(preview.path("drafts")).hasSize(2);
        assertThat(preview.path("drafts").get(0).path("frontText").asText()).isEqualTo("周三下棋地点？");
        assertThat(preview.path("drafts").get(0).path("backText").asText()).isEqualTo("社区活动室");
        assertThat(preview.path("drafts").get(1).path("backText").asText()).isEqualTo("蓝色帆布袋");
    }

    @Test
    void confirmingAStalePreviewIsRefusedAndNothingIsPublished() throws Exception {
        MockHttpSession session = login("patient");
        long memoId = createMemo(session, "周三下棋地点：社区活动室。棋盒放在哪里：蓝色帆布袋。");
        String staleVersion = preview(session, memoId).path("memoVersion").asText();

        editMemo(session, memoId, "周三下棋地点：社区活动室。棋盒放在哪里：红色纸箱。");

        JsonNode refused =
                bodyOf(
                        mockMvc.perform(
                                        post(MEMOS + "/" + memoId + "/generate-cards")
                                                .session(session)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(json(Map.of("memoVersion", staleVersion))))
                                .andExpect(status().isBadRequest())
                                .andReturn());
        assertThat(refused.path("errorCode").asText()).isEqualTo("MEMO_VERSION_MISMATCH");
        assertThat(cards(session, memoId)).isEmpty();

        JsonNode fresh = preview(session, memoId);
        JsonNode generated =
                bodyOf(
                                mockMvc.perform(
                                                post(MEMOS + "/" + memoId + "/generate-cards")
                                                        .session(session)
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                json(
                                                                        Map.of(
                                                                                "memoVersion",
                                                                                fresh.path("memoVersion")
                                                                                        .asText()))))
                                        .andExpect(status().isOk())
                                        .andReturn())
                        .path("data");
        assertThat(generated).hasSize(2);
        assertThat(generated.get(1).path("backText").asText()).isEqualTo("红色纸箱");
    }

    /**
     * 评审给出的时序：预览时语义服务可用，确认时不可用。原文没改，但确认若重新抽取，
     * 保存下来的就是规则兜底的整句，患者看过的「周五」被换掉了。
     * 这里提交的草稿故意与规则抽取结果不同：确认必须原样发布预览草稿，而不是重新抽。
     */
    @Test
    void confirmationPublishesWhatThePatientPreviewedInsteadOfReExtracting() throws Exception {
        MockHttpSession session = login("patient");
        long memoId = createMemo(session, "周五去趟银行");

        JsonNode generated =
                bodyOf(
                                mockMvc.perform(
                                                post(MEMOS + "/" + memoId + "/generate-cards")
                                                        .session(session)
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                json(
                                                                        Map.of(
                                                                                "drafts",
                                                                                List.of(
                                                                                        Map.of(
                                                                                                "frontText",
                                                                                                "这件事什么时候要做？",
                                                                                                "backText",
                                                                                                "周五",
                                                                                                "sourceRef",
                                                                                                "周五去趟银行"))))))
                                        .andExpect(status().isOk())
                                        .andReturn())
                        .path("data");

        assertThat(generated).hasSize(1);
        assertThat(generated.get(0).path("backText").asText()).isEqualTo("周五");
        assertThat(cards(session, memoId).get(0).path("backText").asText()).isEqualTo("周五");
    }

    /** 出处不在原文里的草稿必须挡住，否则确认接口可以拿来发布与这段备忘无关的卡片。 */
    @Test
    void draftsWhoseSourceIsNotInTheMemoAreRefused() throws Exception {
        MockHttpSession session = login("patient");
        long memoId = createMemo(session, "周五去趟银行");

        JsonNode refused =
                bodyOf(
                                mockMvc.perform(
                                                post(MEMOS + "/" + memoId + "/generate-cards")
                                                        .session(session)
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                json(
                                                                        Map.of(
                                                                                "drafts",
                                                                                List.of(
                                                                                        Map.of(
                                                                                                "frontText",
                                                                                                "这件事什么时候要做？",
                                                                                                "backText",
                                                                                                "下周三",
                                                                                                "sourceRef",
                                                                                                "下周三去公园"))))))
                                        .andExpect(status().isBadRequest())
                                        .andReturn());
        assertThat(refused.path("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(cards(session, memoId)).isEmpty();
    }

    @Test
    void omittingTheVersionKeepsTheOriginalCallersWorking() throws Exception {
        MockHttpSession session = login("patient");
        long memoId = createMemo(session, "我要每天吃降压药。复诊时间：11月30日上午。");

        JsonNode generated =
                bodyOf(
                                mockMvc.perform(
                                                post(MEMOS + "/" + memoId + "/generate-cards")
                                                        .session(session))
                                        .andExpect(status().isOk())
                                        .andReturn())
                        .path("data");
        assertThat(generated).hasSize(2);
    }

    private long createMemo(MockHttpSession session, String content) throws Exception {
        return bodyOf(
                        mockMvc.perform(
                                        post(MEMOS)
                                                .session(session)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(json(Map.of("content", content))))
                                .andExpect(status().isOk())
                                .andReturn())
                .path("data")
                .path("id")
                .asLong();
    }

    private void editMemo(MockHttpSession session, long memoId, String content) throws Exception {
        mockMvc.perform(
                        patch(MEMOS + "/" + memoId)
                                .session(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("content", content))))
                .andExpect(status().isOk());
    }

    private JsonNode preview(MockHttpSession session, long memoId) throws Exception {
        return bodyOf(
                        mockMvc.perform(post(MEMOS + "/" + memoId + "/card-drafts").session(session))
                                .andExpect(status().isOk())
                                .andReturn())
                .path("data");
    }

    private JsonNode cards(MockHttpSession session, long memoId) throws Exception {
        return bodyOf(
                        mockMvc.perform(get(MEMOS + "/" + memoId + "/cards").session(session))
                                .andExpect(status().isOk())
                                .andReturn())
                .path("data");
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json(
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

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}