package com.adcausalrisk.backend.research;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchTaskLeaseTest {

    @Test
    void rejectsLeaseThatOnlyCoversTheHostReadTimeout() {
        assertThatThrownBy(() -> new ResearchTaskLease(
                new JdbcTemplate(), Clock.systemUTC(), "local", 601L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must cover the full host request")
                .hasMessageContaining(String.valueOf(ResearchTaskLease.MINIMUM_LEASE_SECONDS));
    }

    @Test
    void rejectsLeaseWithoutTheResultWriteGrace() {
        assertThatThrownBy(() -> new ResearchTaskLease(
                new JdbcTemplate(), Clock.systemUTC(), "local",
                ResearchTaskLease.MINIMUM_LEASE_SECONDS - 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("result-write grace");
    }

    @Test
    void acceptsLeaseAtTheFullRequestBoundary() {
        ResearchTaskLease lease = new ResearchTaskLease(
                new JdbcTemplate(), Clock.systemUTC(), "local",
                ResearchTaskLease.MINIMUM_LEASE_SECONDS);
        assertThat(lease.executorId()).isEqualTo("local");
        assertThat(lease.expiresAt()).isNotNull();
    }

    @Test
    void acceptsLeaseWithHeadroom() {
        ResearchTaskLease lease = new ResearchTaskLease(
                new JdbcTemplate(), Clock.systemUTC(), "local", 900L);
        assertThat(lease.executorId()).isEqualTo("local");
        assertThat(lease.expiresAt()).isNotNull();
    }
}
