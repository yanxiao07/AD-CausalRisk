package com.adcausalrisk.backend.patient;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

/**
 * 抽取器与语义侧的配合规则，纯单元测试，不起 Spring。
 *
 * <p>核心约束只有一条：规则优先，语义只补规则漏掉的句子。模型把字段配错时，最坏结果是
 * 少一张卡；绝不能把规则已经抽对的问答改坏。
 */
class MemoCardExtractorTest {

    private static MemoSemanticClient stub(List<MemoSemanticClient.Span> spans) {
        return new MemoSemanticClient("") {
            @Override
            public List<Span> spans(String text) {
                return spans;
            }
        };
    }

    @Test
    void rulesDecideFirstEvenWhenSemanticBackendHasAnAnswer() {
        MemoCardExtractor extractor =
                new MemoCardExtractor(stub(List.of(new MemoSemanticClient.Span("PLACE", "玄关柜上"))));

        List<MemoCardExtractor.Draft> drafts = extractor.extract("钥匙搁在玄关柜上");

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).frontText()).isEqualTo("钥匙搁在哪里？");
        assertThat(drafts.get(0).backText()).isEqualTo("玄关柜上");
    }

    @Test
    void semanticSpansFillInSentencesTheRulesMiss() {
        MemoCardExtractor extractor =
                new MemoCardExtractor(stub(List.of(new MemoSemanticClient.Span("TIME", "周五"))));

        List<MemoCardExtractor.Draft> drafts = extractor.extract("周五去趟银行");

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).frontText()).isEqualTo("这件事什么时候要做？");
        assertThat(drafts.get(0).backText()).isEqualTo("周五");
        assertThat(drafts.get(0).sourceRef()).isEqualTo("周五去趟银行");
    }

    @Test
    void disabledSemanticBackendLeavesRulesAndFallbackUntouched() {
        MemoCardExtractor extractor = new MemoCardExtractor(new MemoSemanticClient(""));

        List<MemoCardExtractor.Draft> drafts = extractor.extract("今天天气不错");

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).frontText()).isEqualTo(MemoCardExtractor.WHOLE_SENTENCE_QUESTION);
        assertThat(drafts.get(0).backText()).isEqualTo("今天天气不错");
    }

    @Test
    void semanticClientReturnsNothingWhenNoUrlIsConfigured() {
        assertThat(new MemoSemanticClient("").enabled()).isFalse();
        assertThat(new MemoSemanticClient("").spans("明天上午去药房取药")).isEmpty();
    }

    @Test
    void malformedSemanticResponseIsIgnoredInsteadOfThrowing() {
        MemoSemanticClient client = new MemoSemanticClient("http://127.0.0.1:1");

        assertThat(client.parse("not json")).isEmpty();
        assertThat(client.parse("{\"spans\":{}}")).isEmpty();
        assertThat(client.parse("{\"spans\":[{\"label\":\"TIME\",\"text\":\"明天\"}]}"))
                .containsExactly(new MemoSemanticClient.Span("TIME", "明天"));
    }

    @Test
    void blankMemoYieldsNoCards() {
        MemoCardExtractor extractor = new MemoCardExtractor(new MemoSemanticClient(""));

        assertThat(extractor.extract("   ")).isEmpty();
        assertThat(extractor.extract(null)).isEmpty();
    }

    /**
     * uie-nano 实测会把疑问句里的「哪里」当成地点。这类片段必须被丢掉，
     * 否则卡片会变成「地点在哪里？→ 哪里」——比整句兜底更糟。
     */
    @Test
    void semanticSpanThatIsAQuestionWordIsRejected() {
        MemoCardExtractor extractor =
                new MemoCardExtractor(stub(List.of(new MemoSemanticClient.Span("PLACE", "哪里"))));

        List<MemoCardExtractor.Draft> drafts = extractor.extract("我的血压计在哪里");

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).frontText()).isEqualTo(MemoCardExtractor.WHOLE_SENTENCE_QUESTION);
        assertThat(drafts.get(0).backText()).isEqualTo("我的血压计在哪里");
    }

    @Test
    void semanticSpanAbsentFromTheSentenceIsRejected() {
        MemoCardExtractor extractor =
                new MemoCardExtractor(stub(List.of(new MemoSemanticClient.Span("ITEM", "钥匙"))));

        List<MemoCardExtractor.Draft> drafts = extractor.extract("今天天气不错");

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).frontText()).isEqualTo(MemoCardExtractor.WHOLE_SENTENCE_QUESTION);
    }

    /**
     * 模型把「血压」标成 CONDITION 时，三道基础校验都拦不住（它在原文里、不是疑问词、
     * 也不长）。这类错配患者读卡片时看不出问题，所以条件/否定还要求原句真的带线索词。
     */
    @Test
    void semanticSpanWithAWrongLabelCueIsRejected() {
        MemoCardExtractor extractor =
                new MemoCardExtractor(stub(List.of(new MemoSemanticClient.Span("CONDITION", "血压"))));

        List<MemoCardExtractor.Draft> drafts = extractor.extract("血压有点高");

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).frontText()).isEqualTo(MemoCardExtractor.WHOLE_SENTENCE_QUESTION);
        assertThat(drafts.get(0).backText()).isEqualTo("血压有点高");
    }

    /** 线索词在时照旧生效，否则这条防线会把规则漏掉的真条件句也一起挡掉。 */
    @Test
    void semanticSpanKeepsWorkingWhenTheCueIsPresent() {
        MemoCardExtractor extractor =
                new MemoCardExtractor(stub(List.of(new MemoSemanticClient.Span("CONDITION", "发烧"))));

        List<MemoCardExtractor.Draft> drafts = extractor.extract("万一发烧就吃退烧药");

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).frontText()).isEqualTo("什么情况下要这样做？");
        assertThat(drafts.get(0).backText()).isEqualTo("发烧");
    }

    @Test
    void overlongSemanticSpanIsRejected() {
        String tooLong = "很".repeat(41);
        MemoCardExtractor extractor =
                new MemoCardExtractor(stub(List.of(new MemoSemanticClient.Span("TIME", tooLong))));

        List<MemoCardExtractor.Draft> drafts = extractor.extract(tooLong + "出门");

        assertThat(drafts.get(0).frontText()).isEqualTo(MemoCardExtractor.WHOLE_SENTENCE_QUESTION);
    }

    /**
     * 与 uie-nano 对照用的同一组虚构备忘，钉住「新规则能结构化到哪一步」。
     *
     * <p>数字来自 scripts/compare_memo_extraction.py 的同组样例：旧的三条整句正则在 12 条里
     * 只覆盖 3 条，字段抽取覆盖 11 条，剩下的那条原文本身是疑问句，按设计退回整句兜底。
     * 改抽取逻辑时这个数字若变化，说明覆盖面动了，必须重新对照模型一侧再下结论。
     */
    @Test
    void fieldRulesCoverElevenOfTwelveBaselineMemos() {
        MemoCardExtractor extractor = new MemoCardExtractor(new MemoSemanticClient(""));
        List<String> memos =
                List.of(
                        "复诊时间：11月30日上午",
                        "钥匙搁在玄关柜上",
                        "下周三上午九点去社区医院抽血",
                        "血压计放在客厅电视柜下面",
                        "复诊要带医保卡、病历本和化验单",
                        "不要空腹吃这个药",
                        "明天上午去药房取药，顺便把血压记录下来",
                        "每天早晚各量一次血压",
                        "如果头晕就马上坐下休息",
                        "别忘记带伞",
                        "我要每天吃降压药",
                        "我的血压计在哪里");

        long structured =
                memos.stream()
                        .filter(
                                memo ->
                                        extractor.extract(memo).stream()
                                                .anyMatch(
                                                        draft ->
                                                                !MemoCardExtractor
                                                                        .WHOLE_SENTENCE_QUESTION
                                                                        .equals(draft.frontText())))
                        .count();

        assertThat(structured).as("12 条基线备忘里应覆盖 11 条，只有疑问句那条兜底").isEqualTo(11);
    }

    /**
     * 语义侧真正走一次 HTTP：前面几个用例都用桩替代了客户端，这里要确认请求体、路径和
     * 返回解析真的和 Python 服务对得上。否则「语义兜底」只是纸面接通。
     */
    @Test
    void semanticClientReadsSpansFromTheHttpContract() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> received = new AtomicReference<>();
        server.createContext(
                "/api/memo/v1/extract",
                exchange -> {
                    try (exchange) {
                        received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        byte[] reply =
                                "{\"spans\":[{\"label\":\"TIME\",\"text\":\"周五\"}]}"
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                        exchange.sendResponseHeaders(200, reply.length);
                        exchange.getResponseBody().write(reply);
                    } catch (IOException disconnected) {
                        // 断言失败时客户端可能提前断开，这里不掩盖真正的断言结果。
                    }
                });
        server.start();
        try {
            MemoSemanticClient client =
                    new MemoSemanticClient("http://127.0.0.1:" + server.getAddress().getPort());
            assertThat(client.spans("周五去趟银行"))
                    .containsExactly(new MemoSemanticClient.Span("TIME", "周五"));
            assertThat(received.get()).contains("周五去趟银行");
        } finally {
            server.stop(0);
        }
    }

    /**
     * 服务不可达时整条链路必须退化成规则抽取，不能抛异常也不能少出卡。
     * 端口先占用再释放，保证测的是一个真的没人监听的地址。
     */
    @Test
    void unavailableSemanticServiceDegradesToRules() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        MemoCardExtractor extractor =
                new MemoCardExtractor(new MemoSemanticClient("http://127.0.0.1:" + port));

        List<MemoCardExtractor.Draft> drafts = extractor.extract("周五去趟银行");

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).frontText()).isEqualTo(MemoCardExtractor.WHOLE_SENTENCE_QUESTION);
        assertThat(drafts.get(0).backText()).isEqualTo("周五去趟银行");
    }
}