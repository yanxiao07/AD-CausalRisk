package com.adcausalrisk.backend.subject;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** Advance the business input identity in the same transaction as a source mutation. */
public final class InputVersions {
    public static final String CARE_PREFIX = "care-";

    private InputVersions() {}

    public static String advance(JdbcTemplate jdbc, long subjectId) {
        String prior = jdbc.queryForObject("SELECT data_version FROM subject WHERE id=? FOR UPDATE",
                String.class, subjectId);
        String version = (prior.startsWith(CARE_PREFIX) ? CARE_PREFIX : "demo-") + UUID.randomUUID();
        jdbc.update("UPDATE subject SET data_version=? WHERE id=?", version, subjectId);
        return version;
    }
}
