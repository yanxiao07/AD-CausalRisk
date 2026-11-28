package com.adcausalrisk.backend.research;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
final class ResearchTableProtocol {
    static final String PATH = "/api/research-table/v1/assessments";
    static final int MAX_BYTES = 131_072;
    private static final double TOLERANCE = 1e-12;
    private static final List<String> IDENTITY = List.of("contractVersion", "runId", "modelKey",
            "subjectCode", "dataVersion", "indexDate", "availabilityPolicy", "predictionWindowMonths");
    private static final List<String> SEVEN_FEATURES = List.of(
            "age_years", "education_years", "mmse", "cdr_sb", "cdr_global", "adas__total13", "faq__faqtotal");
    private static final List<String> INSTRUMENT_FEATURES = List.of(
            "mmse", "cdr_sb", "cdr_global", "adas__total13", "faq__faqtotal");
    // Trained-model business input ranges shared by manual, CSV and execution paths.
    // Constructed arithmetic fixtures are a separate, test-only model family.
    // Null remains missing; invalid observations must never be clipped or imputed.
    private static final Map<String, BigDecimal> FEATURE_MAXIMUM = Map.of(
            "age_years", new BigDecimal("120"), "education_years", new BigDecimal("40"),
            "mmse", new BigDecimal("30"), "cdr_sb", new BigDecimal("18"),
            "cdr_global", new BigDecimal("3"), "adas__total13", new BigDecimal("85"),
            "faq__faqtotal", new BigDecimal("30"));

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

    /**
     * Python 研究运行时用 canonical JSON（键排序、无空白）计算摘要；受控登记必须复现同一规则，
     * 否则已登记快照在 Java 侧回读时 request/input/context 摘要无法与 Python 结果对齐。
     */
    String digest(JsonNode value) {
        return sha256(canonicalBytes(value));
    }

    byte[] canonicalBytes(JsonNode value) {
        StringBuilder out = new StringBuilder();
        canonical(value, out);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 本地受控登记使用的离线准入核心。字段名与 research_runtime.examples.research_table_http
     * 的 core 保持一致，供 input_sha256 使用；真实宿主仍以自身 trusted context 为准。
     */
    JsonNode admissionCore(JsonNode request, JsonNode model) {
        ObjectNode core = json.createObjectNode();
        core.put("schemaVersion", "research-real-input-admission-v1");
        core.put("purpose", "OFFLINE_RESEARCH_ADMISSION");
        core.put("dataVersion", request.path("dataVersion").asText());
        core.put("indexDate", request.path("indexDate").asText());
        core.put("availabilityPolicy", request.path("availabilityPolicy").asText());
        core.put("predictionWindowMonths", 24);
        core.set("model", model);
        ArrayNode records = core.putArray("records");
        ObjectNode record = records.addObject();
        record.put("subjectCode", request.path("subjectCode").asText());
        record.set("features", request.get("features"));
        return core;
    }

    /**
     * The trusted source mapping of the delivered seven-variable model. It is a
     * property of the model package, not of the incoming request; its digest must
     * equal {@code model.sourceMappingSha256} (2be25c0e...).
     */
    ObjectNode sourceMappings() {
        var features = json.createArrayNode();
        SEVEN_FEATURES.forEach(features::add);
        return sourceMappings(features);
    }

    ObjectNode sourceMappings(JsonNode features) {
        ObjectNode mappings = json.createObjectNode();
        for (JsonNode name : features) {
            String feature = name.textValue();
            ObjectNode row = mappings.putObject(feature);
            boolean instrument = INSTRUMENT_FEATURES.contains(feature);
            row.put("sourceId", "CONSTRUCTED-SRC-" + feature);
            row.put("dataset", "CONSTRUCTED");
            row.put("table", "CONSTRUCTED_TABLE");
            row.put("field", feature);
            row.put("release", "constructed-v1");
            if (instrument) {
                row.put("language", "en");
                row.put("instrumentVersion", "constructed-v1");
            } else {
                row.putNull("language");
                row.putNull("instrumentVersion");
            }
            if (feature.startsWith("mri_")) row.put("processingVersion", "constructed-fs4.3");
            else row.putNull("processingVersion");
        }
        return mappings;
    }

    /**
     * Trusted execution context for a controlled snapshot. The mapping and the
     * evidence catalog are built from server-owned constants and the normalized
     * request; they are never copied from a caller-supplied context.
     */
    JsonNode registrationContext(JsonNode request, JsonNode model) {
        ObjectNode context = json.createObjectNode();
        context.put("schemaVersion", "research-input-context-v1");
        context.put("dataVersion", request.path("dataVersion").asText());
        context.put("indexDate", request.path("indexDate").asText());
        context.put("availabilityPolicy", request.path("availabilityPolicy").asText());
        context.set("model", model);
        context.set("sourceMappings", sourceMappings(model.path("features")));
        ObjectNode evidence = context.putObject("evidence");
        JsonNode features = request.path("features");
        for (JsonNode name : model.path("features")) {
            String feature = name.textValue();
            JsonNode observation = features.get(feature);
            if (observation == null || !observation.isObject()) {
                throw new IllegalArgumentException("Missing normalized feature " + feature);
            }
            String ref = observation.path("evidenceRef").asText("");
            if (ref.isBlank()) {
                throw new IllegalArgumentException("Missing evidenceRef for " + feature);
            }
            ObjectNode row = evidence.putObject(ref);
            row.put("subjectCode", request.path("subjectCode").asText());
            row.put("feature", feature);
            row.set("observation", observation.deepCopy());
        }
        return context;
    }

    private void canonical(JsonNode value, StringBuilder out) {
        if (value == null || value.isNull()) {
            out.append("null");
            return;
        }
        if (value.isObject()) {
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(quote(names.get(i))).append(':');
                canonical(value.get(names.get(i)), out);
            }
            out.append('}');
            return;
        }
        if (value.isArray()) {
            out.append('[');
            for (int i = 0; i < value.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                canonical(value.get(i), out);
            }
            out.append(']');
            return;
        }
        if (value.isTextual()) {
            out.append(quote(value.textValue()));
            return;
        }
        out.append(value.toString());
    }

    private String quote(String text) {
        try {
            return json.writeValueAsString(text);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    void validateRequest(JsonNode request, JsonNode model) {
        require(requestSchema.validate(request).isEmpty() && modelSchema.validate(model).isEmpty());
        require(request.get("predictionWindowMonths").isIntegralNumber());
        JsonNode features = request.get("features");
        List<String> expected = new ArrayList<>();
        model.get("features").forEach(name -> expected.add(name.textValue()));
        require(features.size() == expected.size() && expected.stream().allMatch(features::has));
        if (request.path("modelKey").asText().startsWith("trained-")) {
            for (var bound : FEATURE_MAXIMUM.entrySet()) {
                JsonNode value = features.path(bound.getKey()).path("value");
                if (value.isMissingNode() || value.isNull()) continue;
                require(value.isNumber() && Double.isFinite(value.doubleValue())
                        && value.decimalValue().signum() >= 0
                        && value.decimalValue().compareTo(bound.getValue()) <= 0);
            }
        }
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
