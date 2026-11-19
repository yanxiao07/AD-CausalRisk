package com.adcausalrisk.backend.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchModelIdentityTest {

    @Test
    void keepsConfiguredTrustedDigestInsteadOfAppendingTheDefault() {
        String configured = "b".repeat(64);
        ResearchModelIdentity identity = new ResearchModelIdentity(new ObjectMapper(), "",
                configured,
                "c".repeat(64),
                "d".repeat(64),
                "e".repeat(64),
                "f".repeat(64),
                "1".repeat(64));

        assertThat(identity.identity().get("manifestSha256").asText()).isEqualTo(configured);
        assertThat(identity.identity().get("artifactSha256").asText()).isEqualTo("c".repeat(64));
        assertThat(identity.identity().get("trainingPlanSha256").asText()).isEqualTo("d".repeat(64));
    }

    @Test
    void declaresDefaultsInsideThePlaceholderAndUsesTheCanonicalLockDigest()
            throws NoSuchMethodException {
        var constructor = ResearchModelIdentity.class.getDeclaredConstructor(
                ObjectMapper.class, String.class, String.class, String.class,
                String.class, String.class, String.class, String.class);
        var parameters = constructor.getParameters();

        String trusted = parameters[2].getAnnotation(org.springframework.beans.factory.annotation.Value.class).value();
        String lock = parameters[6].getAnnotation(org.springframework.beans.factory.annotation.Value.class).value();

        assertThat(trusted).isEqualTo(
                "${research.model.trusted-manifest-sha256:46ad244fe612306cc95a2a457c44ac234361db5ab70184c2ee2226527883a511}");
        assertThat(lock).isEqualTo(
                "${research.model.software-lock-sha256:29236beb6079c25aee004819b5b6c2188517872ae06d49ca320b6cb2ae0db66c}");
    }
}
