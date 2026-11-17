package com.adcausalrisk.backend.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Builds the model identity written into a controlled snapshot. The identity is
 * owned by the server: callers may select a model by key, but may never supply
 * hashes or a role themselves.
 */
@Component
class ResearchModelIdentity {
    static final String MODEL_KEY = "trained-seven-logistic";
    private static final Logger log = LoggerFactory.getLogger(ResearchModelIdentity.class);
    private static final List<String> FEATURES = List.of(
            "age_years", "education_years", "mmse", "cdr_sb", "cdr_global", "adas__total13", "faq__faqtotal");

    private final ObjectMapper json;
    private final String manifestPath;
    private final String trustedManifestSha256;
    private final String artifactSha256;
    private final String trainingPlanSha256;
    private final String runtimeSourceSha256;
    private final String softwareLockSha256;
    private final String sourceMappingSha256;

    ResearchModelIdentity(ObjectMapper json,
            @Value("${research.model.manifest-path:}") String manifestPath,
            @Value("${research.model.trusted-manifest-sha256:46ad244fe612306cc95a2a457c44ac234361db5ab70184c2ee2226527883a511}")
            String trustedManifestSha256,
            @Value("${research.model.artifact-sha256:66cfc25386ef9d90c9a6572ac13f673269426fce277d6604e7d1a355ff0a1fd5}")
            String artifactSha256,
            @Value("${research.model.training-plan-sha256:47ae229df74070f56165ece9b828a1aebcbf1b316b2005439e3f76fa89b0d54e}")
            String trainingPlanSha256,
            @Value("${research.model.runtime-source-sha256:f69db2e64f0810d6abb18516e3e845067f69cad9c8422c4c09be5e4329b1450e}")
            String runtimeSourceSha256,
            @Value("${research.model.software-lock-sha256:29236beb6079c25aee004819b5b6c2188517872ae06d49ca320b6cb2ae0db66c}")
            String softwareLockSha256,
            @Value("${research.model.source-mapping-sha256:2be25c0e3176806765a4350185191640144a2370d6951d62c66a4fdcdfac488f}")
            String sourceMappingSha256) {
        this.json = json;
        this.manifestPath = manifestPath;
        this.trustedManifestSha256 = trustedManifestSha256;
        this.artifactSha256 = artifactSha256;
        this.trainingPlanSha256 = trainingPlanSha256;
        this.runtimeSourceSha256 = runtimeSourceSha256;
        this.softwareLockSha256 = softwareLockSha256;
        this.sourceMappingSha256 = sourceMappingSha256;
    }

    ObjectNode identity() {
        ObjectNode model = JsonNodeFactory.instance.objectNode();
        model.put("family", "logistic");
        model.put("specification", "seven");
        model.put("role", "FULL_COHORT_SEVEN_VARIABLE_RESEARCH_MODEL");
        ArrayNode features = model.putArray("features");
        FEATURES.forEach(features::add);
        model.put("predictionPolicy", "raw_uncalibrated_24month_CIF");
        model.putNull("graphProtocolSha256");
        model.put("runtimeSourceSha256", runtimeSourceSha256);
        model.put("softwareLockSha256", softwareLockSha256);
        model.put("sourceMappingSha256", sourceMappingSha256);

        Manifest manifest = loadManifest();
        model.put("manifestSha256", manifest.manifestSha256());
        model.put("artifactSha256", manifest.artifactSha256());
        model.put("trainingPlanSha256", manifest.trainingPlanSha256());
        return model;
    }

    private Manifest loadManifest() {
        if (manifestPath == null || manifestPath.isBlank()) {
            return new Manifest(trustedManifestSha256, artifactSha256, trainingPlanSha256);
        }
        try {
            byte[] bytes = Files.readAllBytes(Path.of(manifestPath));
            String actual = sha256(bytes);
            if (!trustedManifestSha256.isBlank() && !trustedManifestSha256.equalsIgnoreCase(actual)) {
                throw new IllegalStateException("Model manifest SHA-256 does not match the trusted digest");
            }
            JsonNode manifest = json.readTree(bytes);
            String artifact = artifactSha256;
            for (JsonNode entry : manifest.path("final_models")) {
                if ("logistic".equals(entry.path("family").asText())) {
                    String candidate = entry.path("artifact").path("sha256").asText("");
                    if (!candidate.isBlank()) {
                        artifact = candidate;
                    }
                }
            }
            String plan = manifest.path("plan_sha256").asText(trainingPlanSha256);
            return new Manifest(actual, artifact, plan);
        } catch (IOException | IllegalArgumentException invalid) {
            log.warn("Research model manifest is unavailable at {}; using the configured delivery identity",
                    manifestPath);
            return new Manifest(trustedManifestSha256, artifactSha256, trainingPlanSha256);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Manifest(String manifestSha256, String artifactSha256, String trainingPlanSha256) {}
}
