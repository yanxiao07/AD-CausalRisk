package com.adcausalrisk.backend.research;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
final class ResearchTableProtocol {
    static final String PATH = "/api/research-table/v1/assessments";
    static final int MAX_BYTES = 131_072;
    private static final double TOLERANCE = 1e-12;
    private static final List<String> IDENTITY = List.of("contractVersion", "runId", "modelKey",
            "subjectCode", "dataVersion", "indexDate", "availabilityPolicy", "predictionWindowMonths");

    private final ObjectMapper json;
    private final JsonNode contract;
    private final JsonSchema requestSchema;
    private final JsonSchema responseSchema;
    private final JsonSchema errorSchema;
    private final JsonSchema modelSchema;

    ResearchTableProtocol(ObjectMapper json) throws IOException {
        this.json = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        try (var stream = new ClassPathResource(
                "research-contract/research-table-agent.draft.openapi.json").getInputStream()) {
            contract = this.json.readTree(stream);
        }
        requestSchema = schema("AssessmentRequest");
        responseSchema = schema("AssessmentResponse");
        errorSchema = schema("ErrorResponse");
        modelSchema = schema("ModelIdentity");
    }

    private JsonSchema schema(String name) {
        String ref = "#/components/schemas/" + name;
        if (contract.at(ref.substring(1)).isMissingNode()) {
            throw new IllegalStateException("Missing research contract schema " + name);
        }
        ObjectNode root = json.createObjectNode().put("$ref", ref);
        root.set("components", contract.get("components"));
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root,
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
    }

    JsonNode parse(byte[] body) throws IOException {
        require(body.length <= MAX_BYTES);
        var decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body));
        return json.readTree(decoded.toString());
    }

    void validateRequest(JsonNode request, JsonNode model) {
        require(requestSchema.validate(request).isEmpty() && modelSchema.validate(model).isEmpty());
        require(request.get("predictionWindowMonths").isIntegralNumber());
        JsonNode features = request.get("features");
        List<String> expected = new ArrayList<>();
        model.get("features").forEach(name -> expected.add(name.textValue()));
        require(features.size() == expected.size() && expected.stream().allMatch(features::has));
        JsonNode apoe = features.path("apoe4_count").path("value");
        require(apoe.isMissingNode() || apoe.isNull() || apoe.isIntegralNumber());
    }

    String validateError(int httpStatus, JsonNode error, String runId) {
        require(errorSchema.validate(error).isEmpty());
        String code = error.get("code").textValue();
        require(contract.path("x-error-http-status").path(code).asInt(-1) == httpStatus);
        require(error.get("runId").isNull() || runId.equals(error.get("runId").textValue()));
        return code;
    }

    String validateResponse(JsonNode result, JsonNode request, JsonNode model,
                            String scope, String requestDigest, String inputDigest, String contextDigest) {
        require(responseSchema.validate(result).isEmpty());
        for (String name : IDENTITY) {
            require(Objects.equals(request.get(name), result.get(name)));
        }
        require(model.equals(result.get("model")) && scope.equals(result.get("dataScope").textValue())
                && requestDigest.equals(result.get("requestSha256").textValue()));

        JsonNode admission = result.get("admission");
        JsonNode featureOrder = model.get("features");
        require(model.equals(admission.get("model")) && featureOrder.equals(admission.get("featureOrder"))
                && request.get("availabilityPolicy").equals(admission.get("availabilityPolicy")));
        JsonNode missing = admission.get("missingFeatureCounts");
        require(missing.size() == featureOrder.size());
        for (JsonNode name : featureOrder) {
            String feature = name.textValue();
            int expected = request.get("features").get(feature).get("value").isNull() ? 1 : 0;
            require(missing.path(feature).isIntegralNumber() && missing.path(feature).asInt() == expected);
        }
        boolean blocked = "BLOCKED".equals(result.get("status").textValue());
        require(admission.get("recordCount").isIntegralNumber()
                && admission.get("blockedRecordCount").isIntegralNumber()
                && admission.get("blockedRecordCount").asInt() == (blocked ? 1 : 0));
        boolean hasBlockingIssue = false;
        for (JsonNode issue : admission.get("issues")) {
            hasBlockingIssue |= "BLOCK".equals(issue.get("severity").textValue());
            require(issue.get("count").isIntegralNumber());
            require(issue.get("feature").isNull() || request.get("features").has(issue.get("feature").textValue()));
        }
        require(blocked == hasBlockingIssue);
        JsonNode calls = result.get("computation");
        require(calls.get("directCifCalls").isIntegralNumber() && calls.get("explanationCifCalls").isIntegralNumber());
        if (blocked) {
            return "BLOCKED";
        }

        JsonNode binding = result.get("binding");
        require(inputDigest.equals(binding.get("inputSha256").textValue())
                && contextDigest.equals(binding.get("trustedContextSha256").textValue())
                && featureOrder.equals(binding.get("featureOrder"))
                && model.equals(binding.get("model"))
                && model.get("predictionPolicy").equals(binding.get("predictionPolicy"))
                && request.get("predictionWindowMonths").equals(binding.get("predictionWindowMonths"))
                && binding.get("causeOrder").equals(json.createArrayNode().add("AD").add("OTHER_DEMENTIA")));
        verifyExplanation(result.get("prediction"), result.get("explanation"), featureOrder);
        return "COMPLETED";
    }

    private void verifyExplanation(JsonNode prediction, JsonNode explanation, JsonNode featureOrder) {
        var expectedGroups = json.createArrayNode();
        int size = featureOrder.size();
        for (int i = 0; i < Math.min(size, 8); i++) {
            expectedGroups.add(featureOrder.get(i));
        }
        if (size == 10) {
            expectedGroups.add("bilateral_hippocampus");
        }
        require(expectedGroups.equals(explanation.get("groups")));
        JsonNode contributions = explanation.get("contributions").get(0);
        require(contributions.size() == expectedGroups.size());
        double[] point = {number(prediction.get("adCif24m")), number(prediction.get("otherDementiaCif24m"))};
        JsonNode reference = explanation.get("referenceCif").get(0);
        JsonNode explained = explanation.get("predictionCif").get(0);
        require(point[0] + point[1] <= 1 + TOLERANCE
                && number(reference.get(0)) + number(reference.get(1)) <= 1 + TOLERANCE
                && number(explained.get(0)) + number(explained.get(1)) <= 1 + TOLERANCE);
        double maxError = 0;
        for (int cause = 0; cause < point.length; cause++) {
            double total = number(reference.get(cause));
            for (JsonNode row : contributions) {
                total += number(row.get(cause));
            }
            double error = Math.abs(total - point[cause]);
            require(error <= TOLERANCE && Math.abs(number(explained.get(cause)) - point[cause]) <= TOLERANCE);
            maxError = Math.max(maxError, error);
        }
        require(Math.abs(number(explanation.get("reconstructionMaxError")) - maxError) <= TOLERANCE);
    }

    private static double number(JsonNode value) {
        require(value.isNumber() && Double.isFinite(value.doubleValue()));
        return value.doubleValue();
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("RESEARCH_PROTOCOL_INVALID");
        }
    }
}
