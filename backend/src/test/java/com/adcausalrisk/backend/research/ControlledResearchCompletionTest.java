package com.adcausalrisk.backend.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlledResearchCompletionTest {
    private static final String BASE_URL = "http://127.0.0.1:17301";
    private static final String REQUEST = "a".repeat(64);
    private static final String INPUT = "b".repeat(64);
    private static final String CONTEXT = "c".repeat(64);
    private static final String RUN = "run-example-test";

    @TempDir Path directory;

    @Test
    void pinsTheActualResponseEndpointAndEveryPartOfTheFrozenRun() throws Exception {
        var protocol = new ResearchTableProtocol(new ObjectMapper());
        var approval = new ControlledResearchCompletion(protocol, grant().toString(), BASE_URL);
        var endpoint = URI.create(BASE_URL + ResearchTableProtocol.PATH);
        var expected = new ControlledResearchCompletion.Binding(7, 51, RUN, REQUEST, INPUT, CONTEXT);
        assertThat(approval.permits(endpoint, expected)).isTrue();
        assertThat(approval.permits(URI.create("http://127.0.0.1:17302" + ResearchTableProtocol.PATH), expected)).isFalse();
        assertThat(approval.permits(null, expected)).isFalse();
        assertThat(new ControlledResearchCompletion(protocol, "", "").permits(endpoint, expected)).isFalse();
        for (var changed : new ControlledResearchCompletion.Binding[] {
                new ControlledResearchCompletion.Binding(8, 51, RUN, REQUEST, INPUT, CONTEXT),
                new ControlledResearchCompletion.Binding(7, 52, RUN, REQUEST, INPUT, CONTEXT),
                new ControlledResearchCompletion.Binding(7, 51, "run-changed", REQUEST, INPUT, CONTEXT),
                new ControlledResearchCompletion.Binding(7, 51, RUN, CONTEXT, INPUT, CONTEXT),
                new ControlledResearchCompletion.Binding(7, 51, RUN, REQUEST, CONTEXT, CONTEXT),
                new ControlledResearchCompletion.Binding(7, 51, RUN, REQUEST, INPUT, INPUT)}) {
            assertThat(approval.permits(endpoint, changed)).isFalse();
        }
    }

    @Test
    void refusesHalfConfiguredNonLoopbackHttpAndTestPermissionAtStartup() throws Exception {
        var protocol = new ResearchTableProtocol(new ObjectMapper());
        Path path = grant();
        for (String url : new String[] {"", "http://example.test", BASE_URL + "/arbitrary"}) {
            assertThatThrownBy(() -> new ControlledResearchCompletion(protocol, path.toString(), url))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("CONTROLLED_COMPLETION_CONFIGURATION_INVALID");
        }
        Files.writeString(path, Files.readString(path).replace("LAB-EXPLAIN", "CONSTRUCTED-HTTP-EXPLAIN"));
        assertThatThrownBy(() -> new ControlledResearchCompletion(protocol, path.toString(), BASE_URL))
                .hasMessage("CONTROLLED_COMPLETION_CONFIGURATION_INVALID");
    }

    private Path grant() throws Exception {
        return Files.writeString(directory.resolve("grant.json"), """
                {"snapshotId":7,"subjectId":51,"runId":"%s","requestSha256":"%s",
                 "inputSha256":"%s","contextSha256":"%s","predictionAuthorizationRef":"LAB-PREDICT",
                 "explanationAuthorizationRef":"LAB-EXPLAIN"}
                """.formatted(RUN, REQUEST, INPUT, CONTEXT));
    }
}
