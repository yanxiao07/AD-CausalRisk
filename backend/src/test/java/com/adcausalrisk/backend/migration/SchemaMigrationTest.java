package com.adcausalrisk.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the real Flyway migrations on H2 (MySQL compatibility mode) and checks the
 * invariants the baseline schema is supposed to guarantee.
 *
 * <p>Runs on H2 only: this environment has no MySQL server and no Docker, so the
 * MySQL branch of V1/V2 stays unverified. See backend/README.md.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SchemaMigrationTest {

    /** Documented synthetic demo password. Must stay in sync with backend/README.md. */
    private static final String DEMO_PASSWORD = "DemoPass!2026";

    /**
     * Application tables. Starts from the 15 baseline tables (Design_Guide.md section 3)
     * and grows as follow-up migrations land new domains. V7 added {@code qc_audit_record}.
     */
    private static final List<String> BASELINE_TABLES = List.of(
            "sys_user", "sys_role", "user_role",
            "subject", "subject_permission",
            "visit", "subject_feature",
            "data_import_job", "quality_check",
            "assessment_task", "idempotency_record", "risk_assessment",
            "assessment_review", "follow_up_plan", "audit_log",
            "qc_audit_record");

    /** Flyway's own bookkeeping table; created by Flyway, not by V1. */
    private static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";

    /** Schemas that belong to the engine, not to this application. */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "information_schema", "pg_catalog", "mysql", "performance_schema", "sys");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void flywayAppliedAllMigrations() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
                String.class);

        // Every new migration file must be appended here; the list is deliberately explicit so a
        // gap, a re-ordering or a forgotten migration fails loudly instead of silently passing.
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
    }

    /**
     * V3 widens the three score columns to {@code DECIMAL(5,2)}. Nothing else in the suite looks
     * at the column type, so without this check a V3 that silently became a no-op on some engine
     * would leave every other test green while the original precision bug came back.
     */
    @Test
    void visitScoreColumnsKeepTwoDecimals() throws SQLException {
        for (String columnName : List.of("mmse", "moca", "cdr_sb")) {
            ColumnShape shape = columnShape("visit", columnName);

            assertThat(shape.typeName().toLowerCase(Locale.ROOT))
                    .as("%s must stay a numeric column", columnName).doesNotContain("char");
            assertThat(shape.precision()).as("%s precision", columnName).isEqualTo(5);
            assertThat(shape.scale()).as("%s scale", columnName).isEqualTo(2);
        }
    }

    /**
     * V4 keeps the submitted score as text in {@code raw_*}, so no scale is imposed on it. Those
     * columns must be wide text: a silently numeric or shorter column would either round the value
     * or reject the insert.
     */
    @Test
    void visitRawScoreColumnsAreWideText() throws SQLException {
        for (String columnName : List.of("raw_mmse", "raw_moca", "raw_cdr_sb")) {
            ColumnShape shape = columnShape("visit", columnName);

            assertThat(shape.typeName().toLowerCase(Locale.ROOT))
                    .as("%s must be a character column", columnName).contains("char");
            assertThat(shape.precision()).as("%s length", columnName).isEqualTo(64);
        }
    }

    /**
     * V5 and V6 add the storage an import job needs between preview and commit. A missing or
     * mis-typed column here would only surface as a failed INSERT at runtime, so the shape is
     * asserted up front.
     */
    @Test
    void dataImportJobHasPreviewStorageColumns() throws SQLException {
        ColumnShape importType = columnShape("data_import_job", "import_type");
        ColumnShape previewCsv = columnShape("data_import_job", "preview_csv");
        ColumnShape sourceDigest = columnShape("data_import_job", "source_digest");
        ColumnShape previewDigest = columnShape("data_import_job", "preview_digest");

        assertThat(importType.typeName().toLowerCase(Locale.ROOT))
                .as("import_type must be text").contains("char");
        assertThat(importType.precision()).as("import_type length").isEqualTo(20);

        // TEXT: H2 reports CHARACTER VARYING with a huge capacity in MODE=MySQL, MySQL reports
        // TEXT. Either way it must be a character column wide enough for the 30000-byte cap.
        assertThat(previewCsv.typeName().toLowerCase(Locale.ROOT))
                .as("preview_csv must be text").contains("char");
        assertThat(previewCsv.precision()).as("preview_csv capacity").isGreaterThanOrEqualTo(30_000);

        // A SHA-256 digest written as hex is exactly 64 characters.
        assertThat(sourceDigest.typeName().toLowerCase(Locale.ROOT))
                .as("source_digest must be text").contains("char");
        assertThat(sourceDigest.precision()).as("source_digest length").isEqualTo(64);

        assertThat(previewDigest.typeName().toLowerCase(Locale.ROOT))
                .as("preview_digest must be text").contains("char");
        assertThat(previewDigest.precision()).as("preview_digest length").isEqualTo(64);
    }

    /** JDBC type name, precision/length (COLUMN_SIZE) and scale (DECIMAL_DIGITS) of one column. */
    private record ColumnShape(String typeName, int precision, int scale) {
    }

    private ColumnShape columnShape(String tableName, String columnName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             ResultSet columns = connection.getMetaData()
                     .getColumns(connection.getCatalog(), null, "%", "%")) {
            while (columns.next()) {
                String table = columns.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                String column = columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                if (tableName.equals(table) && columnName.equals(column)) {
                    return new ColumnShape(
                            columns.getString("TYPE_NAME"),
                            columns.getInt("COLUMN_SIZE"),
                            columns.getInt("DECIMAL_DIGITS"));
                }
            }
        }
        throw new IllegalStateException("column not found: " + tableName + "." + columnName);
    }

    @Test
    void baselineDefinesExactlyTheExpectedTables() throws SQLException {
        assertThat(BASELINE_TABLES).hasSize(16).doesNotHaveDuplicates();

        assertThat(applicationTables())
                .containsExactlyInAnyOrderElementsOf(
                        Stream.concat(BASELINE_TABLES.stream(), Stream.of(FLYWAY_HISTORY_TABLE)).toList());
    }

    @Test
    void seedCoversTheFourContractRoles() {
        List<String> roles = jdbc.queryForList("SELECT role_code FROM sys_role", String.class);

        assertThat(roles).containsExactlyInAnyOrder("ADMIN", "RESEARCHER", "DOCTOR", "PATIENT");
    }

    @Test
    void seedProvidesOneAccountPerRolePlusOneMultiRoleAccount() {
        List<String> grants = jdbc.query("""
                SELECT u.username, r.role_code
                FROM sys_user u
                JOIN user_role ur ON ur.user_id = u.id
                JOIN sys_role r ON r.id = ur.role_id
                """, (rs, rowNum) -> rs.getString(1) + ":" + rs.getString(2));

        assertThat(grants).containsExactlyInAnyOrder(
                "admin:ADMIN",
                "researcher:RESEARCHER",
                "doctor:DOCTOR",
                "patient:PATIENT",
                "research_doctor:RESEARCHER",
                "research_doctor:DOCTOR");
    }

    @Test
    void everySeededHashMatchesTheDocumentedDemoPassword() {
        List<String> hashes = jdbc.queryForList("SELECT password_hash FROM sys_user", String.class);

        assertThat(hashes).hasSize(5);

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        assertThat(hashes).allSatisfy(hash -> {
            assertThat(hash).matches("^\\$2[aby]\\$\\d{2}\\$.{53}$");
            assertThat(encoder.matches(DEMO_PASSWORD, hash))
                    .as("seeded hash must match the documented demo password")
                    .isTrue();
        });

        assertThat(new HashSet<>(hashes)).as("distinct BCrypt salts").hasSize(5);
    }

    @Test
    void usernameUniquenessIsEnforcedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO sys_user (username, password_hash, status, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                "admin", "$2a$10$duplicateProbe", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void foreignKeysAreEnforcedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO user_role (user_id, role_id) SELECT ?, id FROM sys_role WHERE role_code = 'ADMIN'",
                999_999L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void roleCodeCheckConstraintRejectsAnUnknownRole() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO sys_role (role_code) VALUES ('SUPERUSER')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void subjectRejectsADataVersionThatIsNotADemoVersion() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO subject (subject_code, alias, status, data_version, created_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                "DEMO-CHECK", "检查档案", "ACTIVE", "v1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void subjectAcceptsAValidDemoRowIncludingAChineseAlias() {
        int inserted = jdbc.update(
                "INSERT INTO subject (subject_code, alias, status, data_version, created_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                "DEMO-CHECK", "检查档案", "ACTIVE", "demo-v1");

        assertThat(inserted).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT alias FROM subject WHERE subject_code = ?", String.class, "DEMO-CHECK"))
                .isEqualTo("检查档案");
    }

    @Test
    void missingCognitiveScoresAreStoredAsNullAndNotAsZero() {
        jdbc.update(
                "INSERT INTO subject (subject_code, alias, status, data_version, created_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                "DEMO-MISSING", "缺失档案", "ACTIVE", "demo-v1");
        jdbc.update("""
                INSERT INTO visit (subject_id, visit_code, visit_date)
                SELECT id, ?, ? FROM subject WHERE subject_code = ?
                """, "BL", LocalDate.of(2025, 1, 1), "DEMO-MISSING");

        assertThat(jdbc.queryForObject("""
                SELECT v.mmse FROM visit v JOIN subject s ON s.id = v.subject_id WHERE s.subject_code = ?
                """, Double.class, "DEMO-MISSING")).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT v.moca FROM visit v JOIN subject s ON s.id = v.subject_id WHERE s.subject_code = ?
                """, Double.class, "DEMO-MISSING")).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT v.cdr_sb FROM visit v JOIN subject s ON s.id = v.subject_id WHERE s.subject_code = ?
                """, Double.class, "DEMO-MISSING")).isNull();
    }

    /** Every table in the application schema, excluding the engine's own schemas. */
    private List<String> applicationTables() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet tables = connection.getMetaData()
                     .getTables(connection.getCatalog(), null, "%", new String[] { "TABLE" })) {
            while (tables.next()) {
                String schema = tables.getString("TABLE_SCHEM");
                if (schema != null && SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                names.add(tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }
}