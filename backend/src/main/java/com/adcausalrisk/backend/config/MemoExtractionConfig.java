package com.adcausalrisk.backend.config;

import com.adcausalrisk.backend.patient.MemoSemanticClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 记忆卡片语义抽取的可选接线。
 *
 * <p>默认空地址即关闭：卡片抽取完全走 Java 规则，不依赖任何 Python 运行时，也不影响
 * {@code requirements.txt} 里「运行期只用标准库」的约定。需要提升漏抽时才显式配置
 * {@code memo.extraction.base-url} 指向 {@code python_service.memo_server}。
 */
@Configuration(proxyBeanMethods = false)
public class MemoExtractionConfig {

    @Bean
    MemoSemanticClient memoSemanticClient(
            @Value("${memo.extraction.base-url:}") String baseUrl) {
        return new MemoSemanticClient(baseUrl);
    }
}