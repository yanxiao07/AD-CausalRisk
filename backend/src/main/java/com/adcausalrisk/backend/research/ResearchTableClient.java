package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

@Component
final class ResearchTableClient {
    record Reply(int httpStatus, JsonNode body) {}

    private final ResearchTableProtocol protocol;
    private final RestClient client;
    private final String token;

    ResearchTableClient(RestClient.Builder builder, ResearchTableProtocol protocol,
                        @Value("${research.table.base-url:}") String baseUrl,
                        @Value("${research.table.bearer-token:}") String token,
                        @Value("${research.table.read-timeout:120s}") Duration timeout) {
        this.protocol = protocol;
        this.token = token;
        if (baseUrl.isBlank() && token.isBlank()) {
            client = null;
            return;
        }
        URI uri = URI.create(baseUrl);
        if (!("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                && Set.of("127.0.0.1", "localhost", "[::1]").contains(
                        Objects.toString(uri.getHost(), ""))))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !Set.of("", "/").contains(uri.getRawPath())
                || token.isBlank() || !token.matches("[A-Za-z0-9._~+/-]+=*")
                || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Invalid research table host configuration");
        }
        var factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(timeout);
        client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    void requireConfigured() {
        if (client == null) {
            throw new BusinessException(ErrorCode.RESEARCH_UNAVAILABLE);
        }
    }

    Reply assess(byte[] request) {
        requireConfigured();
        try {
            return client.post().uri(ResearchTableProtocol.PATH)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .contentLength(request.length).headers(headers -> headers.setBearerAuth(token))
                    .body(request).exchange((sent, received) -> {
                        int status = received.getStatusCode().value();
                        MediaType type = received.getHeaders().getContentType();
                        if (type == null || !MediaType.APPLICATION_JSON.includes(type)) {
                            throw new IllegalArgumentException("RESEARCH_PROTOCOL_INVALID");
                        }
                        byte[] payload = received.getBody().readNBytes(ResearchTableProtocol.MAX_BYTES + 1);
                        try {
                            return new Reply(status, protocol.parse(payload));
                        } catch (IOException invalid) {
                            throw new IllegalArgumentException("RESEARCH_PROTOCOL_INVALID");
                        }
                    });
        } catch (ResourceAccessException unavailable) {
            return null;
        } catch (RestClientException invalid) {
            if (invalid.contains(IOException.class)) {
                return null;
            }
            throw new IllegalArgumentException("RESEARCH_PROTOCOL_INVALID");
        }
    }
}
