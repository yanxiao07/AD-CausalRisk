package com.adcausalrisk.backend.subject;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** Advance the business input identity in the same transaction as a source mutation. */
public final class InputVersions {
    private InputVersions() {}

    public static String advance(JdbcTemplate jdbc, long subjectId) {
        String version = "demo-" + UUID.randomUUID();
        jdbc.update("UPDATE subject SET data_version=? WHERE id=?", version, subjectId);
        return version;
    }
}
