package com.adcausalrisk.backend.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for {@code POST /api/agent/v1/runs}, mirroring AgentRequest in
 * contracts/python-agent.openapi.json ({@code additionalProperties: false}).
 *
 * <p>Java never parses this type - it only serialises it - so there is no Jackson
 * unknown-field handling here. Nulls are omitted: {@code mmse}/{@code moca}/{@code cdrSb}
 * and {@code featureValue} are optional in the contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRequest(
        String contractVersion,
        String runId,
        String subjectCode,
        String dataVersion,
        String modelVersion,
        int predictionWindowMonths,
        List<Visit> visits,
        List<Feature> features) {

    /** Contract const: the only accepted contract version. */
    public static final String CONTRACT_VERSION = "deepagent-v1";

    /** Contract const: the only accepted model version for this demo. */
    public static final String MODEL_VERSION = "deepagent-causal-warning-v1";

    public static final String FEATURE_APOE_E4_COUNT = "APOE_E4_COUNT";
    public static final String FEATURE_MRI_HIPPOCAMPUS_LEFT = "MRI_HIPPOCAMPUS_L";
    public static final String FEATURE_MRI_HIPPOCAMPUS_RIGHT = "MRI_HIPPOCAMPUS_R";

    public static final String UNIT_COUNT = "count";
    public static final String UNIT_NORMALIZED = "normalized";

    public static final int MIN_WINDOW_MONTHS = 6;
    public static final int MAX_WINDOW_MONTHS = 60;

    public AgentRequest {
        if (predictionWindowMonths < MIN_WINDOW_MONTHS || predictionWindowMonths > MAX_WINDOW_MONTHS) {
            throw new IllegalArgumentException(
                    "predictionWindowMonths 必须在 " + MIN_WINDOW_MONTHS + "-" + MAX_WINDOW_MONTHS + " 之间");
        }
        visits = visits == null ? List.of() : List.copyOf(visits);
        features = features == null ? List.of() : List.copyOf(features);
    }

    public static AgentRequest of(
            String runId,
            String subjectCode,
            String dataVersion,
            int predictionWindowMonths,
            List<Visit> visits,
            List<Feature> features) {
        return new AgentRequest(
                CONTRACT_VERSION,
                runId,
                subjectCode,
                dataVersion,
                MODEL_VERSION,
                predictionWindowMonths,
                visits,
                features);
    }

    /** One visit row. Only {@code visitCode} and {@code visitDate} are required by the contract. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Visit(String visitCode, String visitDate, BigDecimal mmse, BigDecimal moca, BigDecimal cdrSb) {
    }

    /** One feature row. Only {@code featureName}, {@code recordedAt} and {@code unit} are required. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Feature(String featureName, BigDecimal featureValue, String recordedAt, String unit) {

        /**
         * APOE ε4 count is an integer in the shared contract, but the column is DECIMAL, so a
         * database value of {@code 1.000000} would serialise as {@code 1.000000} and be rejected
         * by Python. Only this feature is normalised, and only by dropping trailing zeros:
         * nothing is rounded, no missing value is invented, and MRI decimals keep full precision
         * ({@code 1.500000} stays {@code 1.5}, which Python correctly rejects as a count).
         */
        public Feature {
            if (FEATURE_APOE_E4_COUNT.equals(featureName) && featureValue != null) {
                featureValue = featureValue.stripTrailingZeros();
            }
        }
    }
}