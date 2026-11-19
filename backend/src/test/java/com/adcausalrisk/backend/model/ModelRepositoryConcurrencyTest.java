package com.adcausalrisk.backend.model;

import com.adcausalrisk.backend.security.AppUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two administrators can enable different model versions at the same time. The switch must be
 * serialized in one transaction; otherwise both versions can end up enabled.
 */
@SpringBootTest
@ActiveProfiles("test")
class ModelRepositoryConcurrencyTest {

    private static final String VERSION_A = "concurrency-model-a";
    private static final String VERSION_B = "concurrency-model-b";

    @Autowired private ModelRepositoryService service;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void restoreSeedModel() {
        jdbc.update("DELETE FROM model_configuration WHERE model_version IN (?,?)", VERSION_A, VERSION_B);
        jdbc.update("UPDATE model_configuration SET enabled=TRUE WHERE model_version='deepagent-causal-warning-v1'");
        jdbc.update("UPDATE model_configuration SET enabled=FALSE WHERE model_version<>'deepagent-causal-warning-v1'");
    }

    @Test
    void concurrentActivationLeavesExactlyOneEnabledVersion() throws Exception {
        jdbc.update(
                "INSERT INTO model_configuration(model_version,enabled,updated_at)"
                        + " VALUES(?,FALSE,CURRENT_TIMESTAMP)",
                VERSION_A);
        jdbc.update(
                "INSERT INTO model_configuration(model_version,enabled,updated_at)"
                        + " VALUES(?,FALSE,CURRENT_TIMESTAMP)",
                VERSION_B);
        long adminId = jdbc.queryForObject(
                "SELECT id FROM sys_user WHERE username='admin'", Long.class);
        AppUserPrincipal admin = new AppUserPrincipal(
                adminId, "admin", "", List.of("ADMIN"), true);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> enable(VERSION_A, admin, ready, start));
            Future<?> b = pool.submit(() -> enable(VERSION_B, admin, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);

            List<String> enabled = jdbc.queryForList(
                    "SELECT model_version FROM model_configuration WHERE enabled=TRUE", String.class);
            assertThat(enabled).hasSize(1);
            assertThat(enabled.get(0)).isIn(VERSION_A, VERSION_B);
        } finally {
            pool.shutdownNow();
        }
    }

    private void enable(String version, AppUserPrincipal admin,
                        CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await(5, TimeUnit.SECONDS);
            service.setEnabled(version, true, admin);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (Exception error) {
            throw new IllegalStateException("enable failed for " + version, error);
        }
    }
}
