package com.adcausalrisk.backend.patient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 记忆卡片的可选语义抽取后端（PaddleNLP UIE）。
 *
 * <p>规则抽取（{@link MemoCardExtractor}）只认少数句式，句子写法一变就漏抽。语义模型按字
 * 段从原文里找时间、地点、物品，能把规则漏掉的句子补上；问句模板仍由 Java 侧组装，模型只
 * 负责「找出字段」，不负责措辞，避免生成无法核对的内容。
 *
 * <p>这一层是纯增量：未配置地址、服务未启动、超时、返回格式不对，一律返回空列表，调用方
 * 退回规则抽取。卡片生成不能因为一个可选模型不可用而失败。
 *
 * <p>超时故意压得很短（连接 1 秒、读取 2 秒）：患者点「预览」是同步请求，模型慢就该放弃，
 * 而不是让患者等在那里。也没有重试——重试只会把最坏耗时翻倍。
 */
public class MemoSemanticClient {

    private static final Logger log = LoggerFactory.getLogger(MemoSemanticClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_SPANS = 8;

    /** 模型给出的一个字段片段。label 是字段类型（TIME/PLACE/ITEM/…），text 是原文片段。 */
    public record Span(String label, String text) {}

    private final RestClient restClient;
    private final boolean enabled;

    /**
     * @param baseUrl 语义服务地址；为空表示不启用，此时 {@link #spans(String)} 恒返回空列表
     */
    public MemoSemanticClient(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            this.restClient = null;
            this.enabled = false;
            return;
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        this.restClient = RestClient.builder().baseUrl(baseUrl.trim()).requestFactory(factory).build();
        this.enabled = true;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 抽取字段片段。任何失败都返回空列表，由调用方退回规则抽取。 */
    public List<Span> spans(String text) {
        if (!enabled || text == null || text.isBlank()) {
            return List.of();
        }
        try {
            byte[] body = OBJECT_MAPPER.writeValueAsBytes(java.util.Map.of("text", text));
            String raw =
                    restClient
                            .post()
                            .uri("/api/memo/v1/extract")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(String.class);
            return parse(raw);
        } catch (Exception failure) {
            // 可选增强失败不影响出卡：记录后交给规则抽取兜底。
            log.debug("语义抽取不可用，退回规则抽取：{}", failure.toString());
            return List.of();
        }
    }

    List<Span> parse(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(raw);
            JsonNode spans = root.path("spans");
            if (!spans.isArray()) return List.of();
            List<Span> result = new ArrayList<>();
            for (JsonNode node : spans) {
                String label = node.path("label").asText("").trim();
                String text = node.path("text").asText("").trim();
                if (label.isEmpty() || text.isEmpty()) continue;
                result.add(new Span(label, text));
                if (result.size() >= MAX_SPANS) break;
            }
            return result;
        } catch (Exception malformed) {
            log.debug("语义抽取返回无法解析，按无结果处理：{}", malformed.toString());
            return List.of();
        }
    }
}