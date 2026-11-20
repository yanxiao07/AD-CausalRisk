package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.subject.InputVersions;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;

/** Enables ordinary archive rehearsal only in a disposable, designated synthetic database. */
@Component
final class SyntheticCareIdentity {
    private final boolean enabled;

    SyntheticCareIdentity(Environment environment, DataSource dataSource) throws SQLException {
        enabled = environment.acceptsProfiles(Profiles.of("synthetic-care"));
        if (enabled) {
            try (var connection = dataSource.getConnection()) {
                requireFixtureDatabase(connection.getMetaData().getURL());
            }
        }
    }

    boolean enabled() { return enabled; }

    static void requireFixtureDatabase(String url) {
        String database = url.split(";", 2)[0];
        if (!database.matches("jdbc:h2:mem:adcr_care_sandbox(?:_[A-Za-z0-9]+)?")) {
            throw new IllegalStateException("Synthetic care requires its designated in-memory fixture database");
        }
    }

    record Wire(String subjectCode, String dataVersion) {}

    Wire resolve(long subjectId, String code, String version, boolean doctorHandoff) {
        if (code.startsWith("DEMO-")) return new Wire(code, version);
        if (enabled && doctorHandoff && version.startsWith(InputVersions.CARE_PREFIX)) {
            return new Wire("DEMO-CARE-" + subjectId,
                    "demo-" + version.substring(InputVersions.CARE_PREFIX.length()));
        }
        return null;
    }
}
