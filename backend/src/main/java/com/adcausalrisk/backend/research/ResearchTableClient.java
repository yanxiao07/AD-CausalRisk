package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.config.SystemSettingService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;

/**
 * Calls the controlled research table host.
 *
 * <p>The host and token can be changed from the system console, so the HTTP client is rebuilt
 * lazily from {@link SystemSettingService} instead of being fixed at construction time.
 */
@Component
public final class ResearchTableClient {

    private static final Logger log = LoggerFactory.getLogger(ResearchTableClient.class);

    record Reply(int httpStatus, JsonNode body) {}

    private record Runtime(RestClient client, String token) {}

    private final RestClient.Builder builder;
    private final ResearchTableProtocol protocol;
    private final SystemSettingService settings;

    private volatile Runtime runtime;

    public ResearchTableClient(
            RestClient.Builder builder,
            ResearchTableProtocol protocol,
            SystemSettingService settings) {
        this.builder = builder;
        this.protocol = protocol;
        this.settings = settings;
    }

    /** Rebuilds the cached client from the current system setting. */
    public void refresh() {
        SystemSettingService.ResearchHost host = settings.researchHost();
        if (!host.configured()) {
            runtime = null;
            return;
        }
        var factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method)
                    throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(host.readTimeoutSeconds()));
        runtime =
                new Runtime(
                        builder.baseUrl(host.baseUrl()).requestFactory(factory).build(),
                        host.token());
    }

    private Runtime runtime() {
        Runtime current = runtime;
        if (current == null) {
            refresh();
            current = runtime;
        }
        return current;
    }

    void requireConfigured() {
        if (runtime() == null) {
            throw new BusinessException(ErrorCode.RESEARCH_UNAVAILABLE);
        }
    }

    Reply registerSnapshot(byte[] bundle) {
        Runtime current = runtime();
        if (current == null) {
            throw new BusinessException(ErrorCode.RESEARCH_UNAVAILABLE);
        }
        try {
            return current
                    .client()
                    .post()
                    .uri("/api/research-table/v1/snapshots")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .contentLength(bundle.length)
                    .headers(headers -> headers.setBearerAuth(current.token()))
                    .body(bundle)
                    .exchange(
                            (sent, received) -> {
                                int status = received.getStatusCode().value();
                                MediaType type = received.getHeaders().getContentType();
                                if (type == null || !MediaType.APPLICATION_JSON.includes(type)) {
                                    throw new IllegalArgumentException("RESEARCH_PROTOCOL_INVALID");
                                }
                                byte[] payload =
                                        received.getBody()
                                                .readNBytes(ResearchTableProtocol.MAX_BYTES + 1);
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

    Reply assess(byte[] request) {
        Runtime current = runtime();
        if (current == null) {
            throw new BusinessException(ErrorCode.RESEARCH_UNAVAILABLE);
        }
        try {
            return current
                    .client()
                    .post()
                    .uri(ResearchTableProtocol.PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .contentLength(request.length)
                    .headers(headers -> headers.setBearerAuth(current.token()))
                    .body(request)
                    .exchange(
                            (sent, received) -> {
                                int status = received.getStatusCode().value();
                                MediaType type = received.getHeaders().getContentType();
                                if (type == null || !MediaType.APPLICATION_JSON.includes(type)) {
                                    throw new IllegalArgumentException("RESEARCH_PROTOCOL_INVALID");
                                }
                                byte[] payload =
                                        received.getBody()
                                                .readNBytes(ResearchTableProtocol.MAX_BYTES + 1);
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
