package com.adcausalrisk.backend.research;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/** Check the configured datasource before any datasource or Flyway beans are initialized. */
public final class SyntheticCareEnvironment implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.acceptsProfiles(Profiles.of("synthetic-care"))) {
            SyntheticCareIdentity.requireFixtureDatabase(environment.getProperty("spring.datasource.url", ""));
            if (!"127.0.0.1".equals(environment.getProperty("server.address"))) {
                throw new IllegalStateException("Synthetic care requires the local loopback address");
            }
        }
    }

    @Override
    public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
