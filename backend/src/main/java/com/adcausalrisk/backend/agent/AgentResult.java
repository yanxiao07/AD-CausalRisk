package com.adcausalrisk.backend.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Body of a successful {@code POST /api/agent/v1/runs} or {@code GET /api/agent/v1/runs/{runId}},
 * mirroring AgentResult in contracts/python-agent.openapi.json.
 *
 * <p>The 18 properties are all required. {@code observedAt} and {@code graphVersion} are the only
 * optional ones, hence {@link JsonInclude.Include#NON_NULL} on those records: a null must be
 * omitted rather than emitted, because the contract types them as non-nullable strings.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentResult(
        String runId,
        String currentStage,
        String dataVersion,
        Status status,
        String contractVersion,
        String workflowVersion,
        String modelVersion,
        QualityStatus qualityStatus,
        // BLOCKED results carry riskScore=null: the contract allows null but not a missing field,
        // so this one component overrides the record-level NON_NULL. Everything else keeps
        // omitting absent optionals instead of emitting nulls.
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal riskScore,
        RiskLevel riskLevel,
        List<String> warnings,
        Graph graph,
        List<Explanation> explanations,
        List<String> missingModalities,
        List<String> limitations,
        List<Stage> stages,
        String dataSource,
        boolean reviewRequired) {

    public static final String CONTRACT_VERSION = "deepagent-v1";
    public static final String WORKFLOW_VERSION = "causalrisk-workflow-v1";
    public static final String MODEL_VERSION = "deepagent-causal-warning-v1";
    public static final String DATA_SOURCE = "SYNTHETIC_DEMO";

    public static final String EVIDENCE_PRIOR = "PRIOR";
    public static final String EVIDENCE_PREDICTIVE_DEMO = "PREDICTIVE_DEMO";
    public static final String EDGE_DIRECTION = "CONDITIONAL";

    /** Only two values exist on the Python side; FAILED/PENDING are Java task states, not agent states. */
    public enum Status {
        COMPLETED,
        BLOCKED
    }

    public enum QualityStatus {
        PASS,
        WARN,
        BLOCKED
    }

    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        UNAVAILABLE
    }

    public enum NodeStatus {
        ACTIVE,
        MISSING
    }

    public enum Direction {
        UP,
        DOWN,
        NEUTRAL
    }

    public enum StageName {
        DATA_FUSION,
        CAUSAL_GRAPH,
        WARNING_MODEL,
        EXPLANATION
    }

    public enum StageStatus {
        COMPLETED,
        BLOCKED
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Graph(List<Node> nodes, List<Edge> edges, List<Route> paths, String graphVersion) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Node(String id, String label, NodeStatus status, String evidenceLevel, String observedAt) {
    }

    public record Edge(
            String from,
            String to,
            String note,
            NodeStatus status,
            String evidenceLevel,
            String direction) {
    }

    /** Graph path. Named Route so it does not collide with {@link java.nio.file.Path}. */
    public record Route(List<String> nodes, NodeStatus status, String evidenceLevel) {
    }

    public record Explanation(
            String feature,
            String label,
            String message,
            String source,
            String interpretation,
            Direction direction,
            BigDecimal contribution,
            String evidenceLevel) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Stage(StageName stage, StageStatus status, double durationMs, Map<String, Object> summary) {
    }
}