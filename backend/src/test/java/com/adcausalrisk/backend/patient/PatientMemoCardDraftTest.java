package com.adcausalrisk.backend.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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

    @Autowired
    private ToggleSemanticClient semantic;

    /**
     * 模拟评审描述的时序：本地语义服务桩在预览时成功、确认时不可用。
     * 不可用时返回空列表，与真实客户端遇到 503 后兜底的行为一致。
     */
    static class ToggleSemanticClient extends MemoSemanticClient {
        private final AtomicBoolean available = new AtomicBoolean(true);

        ToggleSemanticClient() {
            super("");
        }

        void failNow() {
            available.set(false);
        }

        void restore() {
            available.set(true);
        }

        @Override
        public List<Span> spans(String text) {
            if (!available.get() || text == null) return List.of();
            return text.contains("周五") ? List.of(new Span("TIME", "周五")) : List.of();
        }
    }

    @TestConfiguration
    static class SemanticToggleConfig {
        @Bean
        @Primary
        ToggleSemanticClient toggleSemanticClient() {
            return new ToggleSemanticClient();
        }
    }

    @Test
    void previewCarriesTheMemoVersionAndSplitsEachColonSentenceIntoItsOwnCard() throws Exception {
        MockHttpSession session = login("patient");
        long memoId = createMemo(session, "周三下棋地点：社区活动室。棋盒放在哪里：蓝色帆布袋。");

        JsonNode preview = preview(session, memoId);
        assertThat(preview.path("previewId").asText()).isNotEmpty();
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
        JsonNode stale = preview(session, memoId);

        editMemo(session, memoId, "周三下棋地点：社区活动室。棋盒放在哪里：红色纸箱。");

        JsonNode refused =
                bodyOf(
                        mockMvc.perform(
                                        post(MEMOS + "/" + memoId + "/generate-cards")
                                                .session(session)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json(
                                                                Map.of(
                                                                        "previewId",
                                                                        stale.path("previewId").asText(),
                                                                        "memoVersion",
                                                                        stale.path("memoVersion").asText()))))
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
                                                                                "previewId",
                                                                                fresh.path("previewId").asText(),
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
     * 这里先让桩服务在预览时给出「周五」，再让它不可用，然后按预览草稿确认：
     * 保存下来的必须仍是「周五」，而不是重新抽取得到的整句「周五去趟银行」。
     */
    @Test
    void confirmationPublishesWhatThePatientPreviewedInsteadOfReExtracting() throws Exception {
        semantic.restore();
        MockHttpSession session = login("patient");
        long memoId = createMemo(session, "周五去趟银行");

        JsonNode preview = preview(session, memoId);
        assertThat(preview.path("drafts")).hasSize(1);
        assertThat(preview.path("drafts").get(0).path("backText").asText()).isEqualTo("周五");

        semantic.failNow();

        List<Map<String, Object>> drafts = new ArrayList<>();
        preview.path("drafts")
                .forEach(
                        draft ->
                                drafts.add(
                                        Map.of(
                                                "frontText", draft.path("frontText").asText(),
                                                "backText", draft.path("backText").asText(),
                                                "sourceRef", draft.path("sourceRef").asText())));

        JsonNode generated =
                bodyOf(
                                mockMvc.perform(
                                                post(MEMOS + "/" + memoId + "/generate-cards")
                                                        .session(session)
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                json(
                                                                        Map.of(
                                                                                "previewId",
                                                                                preview.path("previewId").asText(),
                                                                                "memoVersion",
                                                                                preview.path("memoVersion")
                                                                                        .asText(),
                                                                                "drafts",
                                                                                drafts))))
                                        .andExpect(status().isOk())
                                        .andReturn())
                        .path("data");

        assertThat(generated).hasSize(1);
        assertThat(generated.get(0).path("backText").asText()).isEqualTo("周五");
        assertThat(cards(session, memoId).get(0).path("backText").asText()).isEqualTo("周五");
        assertThat(new MemoCardExtractor(semantic).extract("周五去趟银行").get(0).backText())
                .as("确认时若重新抽取，语义服务不可用只能退回整句兜底——正是评审看到的那张卡")
                .isEqualTo("周五去趟银行");
    }

    /**
     * 评审给出的篡改时序：保留原文版本、保留能在原文里找到的出处，只把预览答案从「周五」
     * 换成「下周一去公园」，确认接口曾经照样保存成功。现在草稿由服务端在预览时绑定，
     * 确认只发布那一批，提交的文字与绑定批次不一致必须拒绝，队列里也不能出现被改过的卡。
     */
    @Test
    void tamperedDraftsAreRefusedEvenWhenTheVersionAndTheSourceStillMatch() throws Exception {
        MockHttpSession session = login("patient");
        long memoId = createMemo(session, "周五去趟银行");
        JsonNode preview = preview(session, memoId);

        JsonNode refused =
                bodyOf(
                                mockMvc.perform(
                                                post(MEMOS + "/" + memoId + "/generate-cards")
                                                        .session(session)
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                json(
                                                                        Map.of(
                                                                                "previewId",
                                                                                preview.path("previewId").asText(),
                                                                                "memoVersion",
                                                                                preview.path("memoVersion")
                                                                                        .asText(),
                                                                                "drafts",
                                                                                List.of(
                                                                                        draft(
                                                                                                "这件事什么时候要做？",
                                                                                                "下周一去公园",
                                                                                                "周五去趟银行"))))))
                                        .andExpect(status().isBadRequest())
                                        .andReturn());
        assertThat(refused.path("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(cards(session, memoId)).isEmpty();

        JsonNode generated =
                bodyOf(
                                mockMvc.perform(
                                                post(MEMOS + "/" + memoId + "/generate-cards")
                                                        .session(session)
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                json(
                                                                        Map.of(
                                                                                "previewId",
                                                                                preview.path("previewId").asText(),
                                                                                "memoVersion",
                                                                                preview.path("memoVersion")
                                                                                        .asText()))))
                                        .andExpect(status().isOk())
                                        .andReturn())
                        .path("data");
        assertThat(generated).hasSize(1);
        assertThat(generated.get(0).path("backText").asText())
                .as("发布的是服务端绑定的那批草稿，不是客户端提交的文字")
                .isEqualTo(preview.path("drafts").get(0).path("backText").asText());
    }

    /** 只提交草稿、不带预览标识：确认接口不接受客户端自带的卡片文字，必须拒绝。 */
    @Test
    void draftsWithoutAPreviewIdAreRefused() throws Exception {
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
                                                                                        draft(
                                                                                                "这件事什么时候要做？",
                                                                                                "周五",
                                                                                                "周五去趟银行"))))))
                                        .andExpect(status().isBadRequest())
                                        .andReturn());
        assertThat(refused.path("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(cards(session, memoId)).isEmpty();
    }

    /** 预览标识必须属于当前备忘：拿别的备忘的预览批次往这条备忘里发卡要被拒绝。 */
    @Test
    void aPreviewIdFromAnotherMemoIsRefused() throws Exception {
        MockHttpSession session = login("patient");
        long first = createMemo(session, "周五去趟银行");
        long second = createMemo(session, "棋盒放在哪里：蓝色帆布袋。");
        JsonNode foreign = preview(session, second);

        JsonNode refused =
                bodyOf(
                                mockMvc.perform(
                                                post(MEMOS + "/" + first + "/generate-cards")
                                                        .session(session)
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                json(
                                                                        Map.of(
                                                                                "previewId",
                                                                                foreign.path("previewId")
                                                                                        .asText()))))
                                        .andExpect(status().isBadRequest())
                                        .andReturn());
        assertThat(refused.path("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(cards(session, first)).isEmpty();
    }

    /** 原文被改之后，旧的预览批次随之失效；即使客户端还留着 previewId，也不能再发布。 */
    @Test
    void staleVersionIsRefusedEvenWhenDraftsAreSubmitted() throws Exception {
        MockHttpSession session = login("patient");
        long memoId = createMemo(session, "周五去趟银行");
        JsonNode preview = preview(session, memoId);

        editMemo(session, memoId, "下周一去趟银行");

        JsonNode refused =
                bodyOf(
                                mockMvc.perform(
                                                post(MEMOS + "/" + memoId + "/generate-cards")
                                                        .session(session)
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                json(
                                                                        Map.of(
                                                                                "previewId",
                                                                                preview.path("previewId").asText(),
                                                                                "memoVersion",
                                                                                preview.path("memoVersion")
                                                                                        .asText(),
                                                                                "drafts",
                                                                                List.of(
                                                                                        draft(
                                                                                                "这件事什么时候要做？",
                                                                                                "周五",
                                                                                                "周五去趟银行"))))))
                                        .andExpect(status().isBadRequest())
                                        .andReturn());
        assertThat(refused.path("errorCode").asText()).isEqualTo("MEMO_VERSION_MISMATCH");
        assertThat(cards(session, memoId)).isEmpty();
    }

    /** 一次确认最多 20 张：超出上限直接拒绝，避免一次请求把复习队列灌满。 */
    @Test
    void draftsBeyondTheLimitAreRefused() throws Exception {
        MockHttpSession session = login("patient");
        long memoId = createMemo(session, "周五去趟银行");
        JsonNode preview = preview(session, memoId);

        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            tooMany.add(draft("这件事什么时候要做？", "周五", "周五去趟银行"));
        }

        JsonNode refused =
                bodyOf(
                                mockMvc.perform(
                                                post(MEMOS + "/" + memoId + "/generate-cards")
                                                        .session(session)
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                json(
                                                                        Map.of(
                                                                                "previewId",
                                                                                preview.path("previewId").asText(),
                                                                                "drafts",
                                                                                tooMany))))
                                        .andExpect(status().isBadRequest())
                                        .andReturn());
        assertThat(refused.path("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(cards(session, memoId)).isEmpty();
    }

    /** 正反面为空的草稿不能写进复习队列，否则患者复习时会看到一张空白卡。 */
    @Test
    void draftWithABlankFaceIsRefused() throws Exception {
        MockHttpSession session = login("patient");
        long memoId = createMemo(session, "周五去趟银行");
        JsonNode preview = preview(session, memoId);

        JsonNode refused =
                bodyOf(
                                mockMvc.perform(
                                                post(MEMOS + "/" + memoId + "/generate-cards")
                                                        .session(session)
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                json(
                                                                        Map.of(
                                                                                "previewId",
                                                                                preview.path("previewId").asText(),
                                                                                "drafts",
                                                                                List.of(
                                                                                        draft(
                                                                                                "这件事什么时候要做？",
                                                                                                "  ",
                                                                                                "周五去趟银行"))))))
                                        .andExpect(status().isBadRequest())
                                        .andReturn());
        assertThat(refused.path("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(cards(session, memoId)).isEmpty();
    }

    /** 确认是替换而不是追加：重复确认同一批草稿，队列里仍然只有一张卡。 */
    @Test
    void confirmingTheSameDraftsTwiceReplacesInsteadOfDuplicating() throws Exception {
        MockHttpSession session = login("patient");
        long memoId = createMemo(session, "周五去趟银行");
        JsonNode preview = preview(session, memoId);
        Map<String, Object> request =
                Map.of(
                        "previewId",
                        preview.path("previewId").asText(),
                        "memoVersion",
                        preview.path("memoVersion").asText(),
                        "drafts",
                        List.of(draft("这件事什么时候要做？", "周五", "周五去趟银行")));

        for (int attempt = 0; attempt < 2; attempt++) {
            JsonNode generated =
                    bodyOf(
                                    mockMvc.perform(
                                                    post(MEMOS + "/" + memoId + "/generate-cards")
                                                            .session(session)
                                                            .contentType(MediaType.APPLICATION_JSON)
                                                            .content(json(request)))
                                            .andExpect(status().isOk())
                                            .andReturn())
                            .path("data");
            assertThat(generated).hasSize(1);
        }
        assertThat(cards(session, memoId)).hasSize(1);
    }

    private static Map<String, Object> draft(String front, String back, String source) {
        return Map.of("frontText", front, "backText", back, "sourceRef", source);
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