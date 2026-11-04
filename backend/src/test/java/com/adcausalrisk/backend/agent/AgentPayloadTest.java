package com.adcausalrisk.backend.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Wire-format guarantees of the request/result payloads.
 *
 * <p>These two rules are interoperability fixes rather than style: Python rejects an APOE count
 * that arrives as {@code 1.000000}, and a BLOCKED result must carry {@code "riskScore": null}
 * because the contract allows null but not a missing field. Both are easy to regress by touching
 * a record annotation, so they are pinned here.
 */
class AgentPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String featureJson(String name, String value) throws Exception {
        AgentRequest.Feature feature = new AgentRequest.Feature(
                name, value == null ? null : new BigDecimal(value), "2026-01-01", "count");
        return objectMapper.writeValueAsString(feature);
    }

    @Test
    void apoeCountIsSerialisedAsAnInteger() throws Exception {
        assertThat(featureJson(AgentRequest.FEATURE_APOE_E4_COUNT, "0.000000")).contains("\"featureValue\":0");
        assertThat(featureJson(AgentRequest.FEATURE_APOE_E4_COUNT, "1.000000")).contains("\"featureValue\":1");
        assertThat(featureJson(AgentRequest.FEATURE_APOE_E4_COUNT, "2.000000")).contains("\"featureValue\":2");
    }

    @Test
    void apoeFractionalValueIsNotRoundedIntoALegalCount() throws Exception {
        // 1.5 is not a legal count; it must reach Python as 1.5 so it is rejected, not silently
        // turned into 1 or 2 by the client.
        assertThat(featureJson(AgentRequest.FEATURE_APOE_E4_COUNT, "1.500000"))
                .contains("\"featureValue\":1.5");
    }

    @Test
    void mriFeatureKeepsItsDecimalPrecision() throws Exception {
        assertThat(featureJson(AgentRequest.FEATURE_MRI_HIPPOCAMPUS_LEFT, "1.500000"))
                .contains("\"featureValue\":1.500000");
        assertThat(featureJson(AgentRequest.FEATURE_MRI_HIPPOCAMPUS_LEFT, "2.2500"))
                .contains("\"featureValue\":2.2500");
    }

    @Test
    void missingFeatureValueIsOmittedRatherThanNull() throws Exception {
        assertThat(featureJson(AgentRequest.FEATURE_APOE_E4_COUNT, null))
                .doesNotContain("featureValue");
    }

    @Test
    void blockedResultKeepsExplicitNullRiskScore() throws Exception {
        AgentResult blocked = new AgentResult(
                "run-1",
                "EXPLANATION",
                "demo-1",
                AgentResult.Status.BLOCKED,
                AgentResult.CONTRACT_VERSION,
                AgentResult.WORKFLOW_VERSION,
                AgentResult.MODEL_VERSION,
                AgentResult.QualityStatus.BLOCKED,
                null,
                AgentResult.RiskLevel.UNAVAILABLE,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                AgentResult.DATA_SOURCE,
                true);

        String json = objectMapper.writeValueAsString(blocked);

        assertThat(json).contains("\"riskScore\":null");
        assertThat(json).contains("\"riskLevel\":\"UNAVAILABLE\"");
        // record-level NON_NULL still applies to the genuinely optional fields
        assertThat(json).doesNotContain("\"graph\":null");
    }

    @Test
    void completedResultStillCarriesItsNumericScore() throws Exception {
        AgentResult completed = new AgentResult(
                "run-2",
                "EXPLANATION",
                "demo-1",
                AgentResult.Status.COMPLETED,
                AgentResult.CONTRACT_VERSION,
                AgentResult.WORKFLOW_VERSION,
                AgentResult.MODEL_VERSION,
                AgentResult.QualityStatus.PASS,
                new BigDecimal("0.8304"),
                AgentResult.RiskLevel.HIGH,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                AgentResult.DATA_SOURCE,
                true);

        String json = objectMapper.writeValueAsString(completed);

        assertThat(json).contains("\"riskScore\":0.8304");
        assertThat(json).doesNotContain("\"riskScore\":null");
    }
}
