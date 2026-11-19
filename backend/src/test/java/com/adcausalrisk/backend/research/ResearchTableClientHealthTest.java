package com.adcausalrisk.backend.research;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The research host implements POST-only routes; its GET / 501 still proves it is listening. */
class ResearchTableClientHealthTest {

    @Test
    void root501IsReachableButOtherServerErrorsAreNot() {
        assertThat(ResearchTableClient.reachableStatus(200)).isTrue();
        assertThat(ResearchTableClient.reachableStatus(401)).isTrue();
        assertThat(ResearchTableClient.reachableStatus(501)).isTrue();
        assertThat(ResearchTableClient.reachableStatus(502)).isFalse();
        assertThat(ResearchTableClient.reachableStatus(500)).isFalse();
    }
}
