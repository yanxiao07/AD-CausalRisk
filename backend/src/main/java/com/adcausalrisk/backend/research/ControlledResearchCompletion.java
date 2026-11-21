package com.adcausalrisk.backend.research;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Operator configuration, independent of the request and of the host response. */
@Component
final class ControlledResearchCompletion {
    private static final int MAX_AUTHORIZATION_BYTES = 4096;
    private static final Set<String> CONSTRUCTED_REFS =
            Set.of("CONSTRUCTED-HTTP-PREDICT", "CONSTRUCTED-HTTP-EXPLAIN");
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]");

    record Binding(long snapshotId, long subjectId, String runId, String requestSha256,
                   String inputSha256, String contextSha256) {}

    private final URI endpoint;
    private final Binding binding;

    ControlledResearchCompletion(ResearchTableProtocol protocol,
            @Value("${research.table.controlled-completion-authorization-file:}") String authorizationFile,
            @Value("${research.table.controlled-completion-base-url:}") String baseUrl) {
        if (authorizationFile.isBlank() && baseUrl.isBlank()) {
            endpoint = null;
            binding = null;
            return;
        }
        try {
            URI base = URI.create(baseUrl);
            String host = base.getHost();
            if (host == null || !("https".equals(base.getScheme())
                    || ("http".equals(base.getScheme()) && LOOPBACK.contains(host)))
                    || base.getRawUserInfo() != null || base.getRawQuery() != null
                    || base.getRawFragment() != null || !Set.of("", "/").contains(base.getRawPath())) {
                throw new IllegalArgumentException();
            }
            endpoint = base.resolve(ResearchTableProtocol.PATH);
            Path path = Path.of(authorizationFile);
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException();
            }
            try (var stream = Files.newInputStream(path)) {
                byte[] bytes = stream.readNBytes(MAX_AUTHORIZATION_BYTES + 1);
                if (bytes.length > MAX_AUTHORIZATION_BYTES) {
                    throw new IllegalArgumentException();
                }
                JsonNode json = protocol.parse(bytes);
                if (json == null || !json.isObject() || json.size() != 8) {
                    throw new IllegalArgumentException();
                }
                validateReference(json.path("predictionAuthorizationRef"));
                validateReference(json.path("explanationAuthorizationRef"));
                binding = new Binding(id(json.path("snapshotId")), id(json.path("subjectId")),
                        text(json.path("runId"), "run-[A-Za-z0-9][A-Za-z0-9-]{0,63}"),
                        text(json.path("requestSha256"), "[0-9a-f]{64}"),
                        text(json.path("inputSha256"), "[0-9a-f]{64}"),
                        text(json.path("contextSha256"), "[0-9a-f]{64}"));
            }
        } catch (IOException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("CONTROLLED_COMPLETION_CONFIGURATION_INVALID");
        }
    }

    boolean permits(URI responseEndpoint, Binding snapshot) {
        return endpoint != null && endpoint.equals(responseEndpoint) && binding.equals(snapshot);
    }

    Binding forSubject(long subjectId) {
        return binding != null && binding.subjectId() == subjectId ? binding : null;
    }

    URI endpoint() {
        return endpoint;
    }

    private static long id(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() <= 0) {
            throw new IllegalArgumentException();
        }
        return node.longValue();
    }

    private static String text(JsonNode node, String pattern) {
        if (!node.isTextual() || !node.textValue().matches(pattern)) {
            throw new IllegalArgumentException();
        }
        return node.textValue();
    }

    private static void validateReference(JsonNode node) {
        if (CONSTRUCTED_REFS.contains(text(node, "[A-Za-z0-9][A-Za-z0-9_.:-]{0,79}"))) {
            throw new IllegalArgumentException();
        }
    }
}
