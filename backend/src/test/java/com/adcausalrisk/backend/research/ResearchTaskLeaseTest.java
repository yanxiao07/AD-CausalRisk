package com.adcausalrisk.backend.research;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchTaskLeaseTest {

    @Test
    void rejectsLeaseThatIsNotLongerThanTheHostReadTimeout() {
        assertThatThrownBy(() -> new ResearchTaskLease(
                new JdbcTemplate(), Clock.systemUTC(), "local", 600L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("greater than the research host read timeout");
    }

    @Test
    void acceptsLeaseLongerThanTheHostReadTimeout() {
        ResearchTaskLease lease = new ResearchTaskLease(
                new JdbcTemplate(), Clock.systemUTC(), "local", 900L);
        assertThat(lease.executorId()).isEqualTo("local");
        assertThat(lease.expiresAt()).isNotNull();
    }
}
