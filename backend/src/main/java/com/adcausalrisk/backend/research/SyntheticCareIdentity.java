package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.subject.InputVersions;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

/** Enables ordinary archive rehearsal only in a designated local synthetic database. */
@Component
final class SyntheticCareIdentity {
    private static final String DESKTOP_PATH = "./../runtime/desktop/adcr_care_sandbox";
    private static final String DESKTOP_URL = "jdbc:h2:file:" + DESKTOP_PATH;
    private final boolean enabled;
    private final boolean predictionEnabled;

    SyntheticCareIdentity(Environment environment, DataSource dataSource) throws SQLException {
        enabled = environment.acceptsProfiles(Profiles.of("synthetic-care"));
        predictionEnabled = enabled && environment.getProperty("research.care.prediction-enabled", Boolean.class, true);
        if (enabled) {
            try (var connection = dataSource.getConnection()) {
                requireFixtureDatabase(connection.getMetaData().getURL());
            }
        }
    }

    boolean enabled() { return enabled; }

    boolean predictionEnabled() { return predictionEnabled; }

    static void requireFixtureDatabase(String url) {
        String database = url.split(";", 2)[0];
        if (DESKTOP_URL.equals(database)) {
            requireDesktopPath();
            return;
        }
        if (!database.matches("jdbc:h2:mem:adcr_care_sandbox(?:_[A-Za-z0-9]+)?")) {
            throw new IllegalStateException("Synthetic care requires its designated local fixture database");
        }
    }

    private static void requireDesktopPath() {
        try {
            Path storage = Path.of("").toRealPath().resolve(DESKTOP_PATH + ".mv.db").normalize();
            Path directory = storage.getParent();
            if (!directory.equals(directory.toRealPath())
                    || Files.isSymbolicLink(storage)
                    || (Files.exists(storage) && !storage.equals(storage.toRealPath()))) {
                throw new IllegalStateException("Synthetic care database path must not be redirected");
            }
        } catch (IOException invalid) {
            throw new IllegalStateException("Synthetic care requires an accessible desktop database directory", invalid);
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
