package com.adcausalrisk.backend.patient;

import static org.assertj.core.api.Assertions.assertThat;
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
 * 记忆卡片的字段抽取回归测试。
 *
 * <p>这组用例原本是「规则基线」：抽取只有三条写死的整句句式时，它们断言的是退化行为
 * （整句兜底）。字段抽取落地后，同一批虚构备忘改为断言期望结果——问句按字段走模板，
 * 答案只放字段值，卡片带原文出处。以后再动抽取逻辑，这里就是防回退的闸门。
 *
 * <p>刻意保留一条「问句不误拆」用例：原文本身在提问时不能当成陈述句去拆答案，否则会把
 * 「我的血压计在哪里」拆出答非所问的卡片。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PatientMemoFieldExtractionTest {

    private static final String DEMO_PASSWORD = "DemoPass!2026";
    private static final String MEMOS = "/api/patients/me/memos";
    private static final String WHOLE_SENTENCE_QUESTION = "我记下的这件事是什么？";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void colonSentenceSplitsIntoTitleAndAnswer() throws Exception {
        JsonNode drafts = preview(createMemo("复诊时间：11月30日上午"));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).path("frontText").asText()).isEqualTo("复诊时间？");
        assertThat(drafts.get(0).path("backText").asText()).isEqualTo("11月30日上午");
    }

    @Test
    void placeStatementYieldsWhereQuestionWithTheVerbKept() throws Exception {
        JsonNode drafts = preview(createMemo("钥匙搁在玄关柜上"));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).path("frontText").asText()).isEqualTo("钥匙搁在哪里？");
        assertThat(drafts.get(0).path("backText").asText()).isEqualTo("玄关柜上");
    }

    /**
     * 「放在」在旧实现里漏抽，因为旧后缀枚举只认「在」。现在动词与「在」一起作为分隔点。
     */
    @Test
    void placeVerbOutsideOldSuffixListStillYieldsWhereQuestion() throws Exception {
        JsonNode drafts = preview(createMemo("血压计放在客厅电视柜下面"));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).path("frontText").asText()).isEqualTo("血压计放在哪里？");
        assertThat(drafts.get(0).path("backText").asText()).isEqualTo("客厅电视柜下面");
    }

    @Test
    void timePrefixIsLiftedOutOfTheAction() throws Exception {
        JsonNode drafts = preview(createMemo("下周三上午九点去社区医院抽血"));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).path("frontText").asText()).isEqualTo("什么时候去社区医院抽血？");
        assertThat(drafts.get(0).path("backText").asText()).isEqualTo("下周三上午九点");
    }

    @Test
    void itemListYieldsWhatToBringQuestion() throws Exception {
        JsonNode drafts = preview(createMemo("复诊要带医保卡、病历本和化验单"));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).path("frontText").asText()).isEqualTo("复诊要带什么？");
        assertThat(drafts.get(0).path("backText").asText()).isEqualTo("医保卡、病历本和化验单");
    }

    /**
     * 否定必须单独成类：问句带「避免」语义，患者复习时才不会把「不要空腹吃」记成「空腹吃」。
     */
    @Test
    void negationKeepsItsPolarityInTheQuestion() throws Exception {
        JsonNode drafts = preview(createMemo("不要空腹吃这个药"));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).path("frontText").asText()).isEqualTo("我要避免做什么？");
        assertThat(drafts.get(0).path("backText").asText()).isEqualTo("空腹吃这个药");
    }

    @Test
    void reminderIsTreatedAsSomethingToDoRatherThanNegation() throws Exception {
        JsonNode drafts = preview(createMemo("别忘记带伞"));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).path("frontText").asText()).isEqualTo("要记得做什么？");
        assertThat(drafts.get(0).path("backText").asText()).isEqualTo("带伞");
    }

    @Test
    void twoActionsInOneSentenceBecomeTwoCards() throws Exception {
        JsonNode drafts = preview(createMemo("明天上午去药房取药，顺便把血压记录下来"));

        assertThat(drafts).hasSize(2);
        assertThat(drafts.get(0).path("frontText").asText()).isEqualTo("什么时候去药房取药？");
        assertThat(drafts.get(0).path("backText").asText()).isEqualTo("明天上午");
        assertThat(drafts.get(1).path("frontText").asText()).isEqualTo("另外还要做什么？");
        assertThat(drafts.get(1).path("backText").asText()).isEqualTo("把血压记录下来");
    }

    @Test
    void frequencyMovesTheQuantifierIntoTheAnswer() throws Exception {
        JsonNode drafts = preview(createMemo("每天早晚各量一次血压"));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).path("frontText").asText()).isEqualTo("量血压的频率是什么？");
        assertThat(drafts.get(0).path("backText").asText()).isEqualTo("每天早晚各一次");
    }

    @Test
    void conditionalSentenceSeparatesConditionFromAction() throws Exception {
        JsonNode drafts = preview(createMemo("如果头晕就马上坐下休息"));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).path("frontText").asText()).isEqualTo("头晕时该怎么做？");
        assertThat(drafts.get(0).path("backText").asText()).isEqualTo("马上坐下休息");
    }

    /**
     * 评审给的样例：带逗号、没有「就/则」的条件句。按逗号切开会把「不要去公园」变成
     * 无条件的嘱咐，患者复习时会把「下雨才别去」记成「永远别去」。这里钉住预览结果。
     */
    @Test
    void conditionalSentenceWithoutThenStillKeepsItsConditionInThePreview() throws Exception {
        JsonNode drafts = preview(createMemo("如果下雨，不要去公园"));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).path("frontText").asText()).isEqualTo("下雨时该怎么做？");
        assertThat(drafts.get(0).path("backText").asText()).isEqualTo("不要去公园");
    }

    /** 评审给的样例：条件用「的话」收尾时，预览里同样不能只剩动作。 */
    @Test
    void conditionalSentenceEndingWithDeHuaStillKeepsItsConditionInThePreview() throws Exception {
        JsonNode drafts = preview(createMemo("下雨的话，不要去公园"));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).path("frontText").asText()).isEqualTo("下雨时该怎么做？");
        assertThat(drafts.get(0).path("backText").asText()).isEqualTo("不要去公园");
    }

    /** 动作以「那」开头时不能把这个指代词当成连接词去掉，否则「那件外套」会变成「件外套」。 */
    @Test
    void actionStartingWithADemonstrativeKeepsItInThePreview() throws Exception {
        JsonNode drafts = preview(createMemo("下雨的话，那件外套要带上"));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).path("frontText").asText()).isEqualTo("下雨时该怎么做？");
        assertThat(drafts.get(0).path("backText").asText()).isEqualTo("那件外套要带上");
    }

    /**
     * 连逗号都没有的条件句（「如果下雨不要去公园」）拆不出条件与动作，
     * 预览必须整句保留，不能退化成无条件的「我要避免做什么？→ 去公园」。
     */
    @Test
    void unsplittableConditionalSentenceKeepsTheWholeSentenceInThePreview() throws Exception {
        JsonNode drafts = preview(createMemo("如果下雨不要去公园"));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).path("frontText").asText()).isEqualTo(WHOLE_SENTENCE_QUESTION);
        assertThat(drafts.get(0).path("backText").asText()).isEqualTo("如果下雨不要去公园");
    }

    /** 评审给的样例：时间答案不能截断到整点，患者看到的必须是「9点30分」。 */
    @Test
    void previewKeepsTheMinutesOfAnAppointmentTime() throws Exception {
        JsonNode drafts = preview(createMemo("明天上午9点30分复诊"));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).path("frontText").asText()).isEqualTo("什么时候复诊？");
        assertThat(drafts.get(0).path("backText").asText()).isEqualTo("明天上午9点30分");
    }

    @Test
    void draftsCarryTheSourceSentenceForPatientReview() throws Exception {
        JsonNode drafts = preview(createMemo("下周三上午九点去社区医院抽血"));

        assertThat(drafts.get(0).path("sourceRef").asText())
                .as("预览要能回看这张卡来自哪句原文")
                .isEqualTo("下周三上午九点去社区医院抽血");
    }

    /**
     * 原文本身是疑问句时不能被当成陈述句拆开：拆出来的答案会答非所问。
     * 这种情况下整句兜底反而更诚实——患者一眼能看出卡片没抽好。
     */
    @Test
    void questionSentenceIsNotSplitIntoAStatementCard() throws Exception {
        JsonNode drafts = preview(createMemo("我的血压计在哪里"));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).path("frontText").asText()).isEqualTo(WHOLE_SENTENCE_QUESTION);
        assertThat(drafts.get(0).path("backText").asText()).isEqualTo("我的血压计在哪里");
    }

    @Test
    void everySentenceInAMixedMemoBecomesAStructuredCard() throws Exception {
        JsonNode drafts =
                preview(
                        createMemo(
                                "复诊时间：11月30日上午。要带医保卡和化验单。"
                                        + "血压计放在客厅电视柜下面。不要空腹吃这个药。"));

        assertThat(drafts).hasSize(4);
        assertThat(frontTexts(drafts))
                .containsExactly(
                        "复诊时间？",
                        "要带什么？",
                        "血压计放在哪里？",
                        "我要避免做什么？");
    }

    private List<String> frontTexts(JsonNode drafts) {
        return java.util.stream.StreamSupport.stream(drafts.spliterator(), false)
                .map(node -> node.path("frontText").asText())
                .toList();
    }

    private long createMemo(String content) throws Exception {
        MockHttpSession session = login("patient");
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

    private JsonNode preview(long memoId) throws Exception {
        MockHttpSession session = login("patient");
        return bodyOf(
                        mockMvc.perform(post(MEMOS + "/" + memoId + "/card-drafts").session(session))
                                .andExpect(status().isOk())
                                .andReturn())
                .path("data")
                .path("drafts");
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