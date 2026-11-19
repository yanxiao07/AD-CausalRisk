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
import java.net.URI;
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
    static final int CONNECT_TIMEOUT_SECONDS = 2;

    record Reply(int httpStatus, JsonNode body) {}

    static boolean reachableStatus(int status) {
        return status < 500 || status == 501;
    }

    /** Admin-facing liveness result; never contains the bearer token or response body. */
    public record Health(
            boolean configured,
            boolean enabled,
            boolean available,
            Integer httpStatus,
            long latencyMs,
            String baseUrl,
            String message) {}

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
        factory.setConnectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS));
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

    /**
     * Probes the configured research host without calling a business endpoint.
     *
     * <p>A 4xx response still proves the HTTP service is listening. The research host deliberately
     * implements only POST business routes, so a GET to {@code /} returns HTTP 501; that is a
     * reachable listener, not an outage. Other 5xx responses remain unavailable. The token is never
     * sent here.
     */
    public Health health() {
        SystemSettingService.ResearchHost host = settings.researchHost();
        if (!host.enabled()) {
            return new Health(host.configured(), false, false, null, 0, host.baseUrl(), "研究宿主已停用");
        }
        if (!host.configured()) {
            return new Health(false, true, false, null, 0, host.baseUrl(), "研究宿主配置不完整");
        }

        long started = System.nanoTime();
        HttpURLConnection connection = null;
        try {
            URI probe = URI.create(host.baseUrl()).resolve("/");
            connection = (HttpURLConnection) probe.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            boolean available = reachableStatus(status);
            return new Health(
                    true,
                    true,
                    available,
                    status,
                    latencyMs,
                    host.baseUrl(),
                    !available
                            ? "研究宿主返回 HTTP " + status
                            : status == 501 ? "研究宿主在线（根路径不支持 GET）" : "研究宿主可达");
        } catch (IOException | RuntimeException unreachable) {
            long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            log.warn("Research host health probe failed: {}", unreachable.toString());
            return new Health(true, true, false, null, latencyMs, host.baseUrl(), "研究宿主不可达");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
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
