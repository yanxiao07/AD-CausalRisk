package com.adcausalrisk.backend.config;

import com.adcausalrisk.backend.agent.RestAgentClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Wiring for the Python agent client.
 *
 * <p>Blocking {@link RestClient} on purpose: the Python workflow is synchronous and the backend is
 * a servlet application, so pulling in WebFlux would add a second concurrency model for nothing.
 *
 * <p>The read timeout must exceed the worst-case workflow duration, because a client-side timeout
 * does not cancel the run on the Python side. Defaults: 2s to connect, 120s to read.
 */
@Configuration(proxyBeanMethods = false)
public class AgentClientConfig {

    @Bean
    @Qualifier("agentRestClient")
    RestClient agentRestClient(
            RestClient.Builder builder,
            @Value("${python.agent.base-url:http://127.0.0.1:8091}") String baseUrl,
            @Value("${python.agent.connect-timeout:2s}") Duration connectTimeout,
            @Value("${python.agent.read-timeout:120s}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        // Python requires an explicit Content-Length. Without buffering, Spring can stream the
        // body with chunked transfer-encoding and the Python endpoint answers 400 before the
        // workflow starts. Buffering keeps the (small, bounded) JSON body in memory so it is sent
        // with a fixed length; the connect/read timeouts above are preserved unchanged.
        BufferingClientHttpRequestFactory bufferedRequestFactory =
                new BufferingClientHttpRequestFactory(requestFactory);
        return builder
                .baseUrl(baseUrl)
                .requestFactory(bufferedRequestFactory)
                .build();
    }

    @Bean
    RestAgentClient agentClient(@Qualifier("agentRestClient") RestClient agentRestClient) {
        return new RestAgentClient(agentRestClient);
    }
}