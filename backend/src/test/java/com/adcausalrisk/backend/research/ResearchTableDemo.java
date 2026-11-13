package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.BackendApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static java.nio.file.StandardOpenOption.CREATE_NEW;

/** Local launcher on the test classpath. There is no writable registration endpoint. */
public final class ResearchTableDemo {
    private static final String OWNER = "researcher";
    private static final String SUBJECT = "DEMO-RESEARCH-TABLE";

    private ResearchTableDemo() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: ResearchTableDemo manifest.json handoff.json");
        }
        var json = new ObjectMapper();
        JsonNode manifest = json.readTree(Files.readAllBytes(Path.of(args[0])));
        int pythonPort = manifest.path("port").asInt();
        JsonNode cases = manifest.path("cases");
        boolean trained = cases.size() == 1 && ResearchTableAssessmentService.TRAINED_MODEL
                .equals(cases.path("seven").path("request").path("modelKey").asText());
        String token = System.getenv("RESEARCH_TABLE_TEST_TOKEN");
        if (pythonPort < 1 || pythonPort > 65535 || token == null || token.isBlank()) {
            throw new IllegalArgumentException("Expected the local constructed Python host");
        }

        var app = new SpringApplication(BackendApplication.class);
        app.setDefaultProperties(Map.of("research.table.bearer-token", token));
        var context = (ServletWebServerApplicationContext) app.run(
                "--spring.config.location=classpath:/application.yml",
                "--spring.profiles.active=test",
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.datasource.url=jdbc:h2:mem:research_demo_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--server.address=127.0.0.1", "--server.port=0",
                "--research.table.base-url=http://127.0.0.1:" + pythonPort,
                "--research.table.read-timeout=30s",
                "--research.table.test-fixtures-enabled=" + !trained,
                "--assessment.test-rule-enabled=false");
        try {
            var jdbc = context.getBean(JdbcTemplate.class);
            var protocol = context.getBean(ResearchTableProtocol.class);
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            var handoff = transaction.execute(status -> register(jdbc, protocol, cases,
                    context.getWebServer().getPort(), trained));
            Files.write(Path.of(args[1]), json.writerWithDefaultPrettyPrinter().writeValueAsBytes(handoff), CREATE_NEW);
        } catch (Exception error) {
            context.close();
            throw error;
        }
    }

    private static Map<String, Object> register(JdbcTemplate jdbc, ResearchTableProtocol protocol,
                                                 JsonNode cases, int port, boolean trained) {
        List<String> labels = trained ? List.of("seven") : List.of("seven", "ten", "blocked");
        if (cases.size() != labels.size()) {
            throw new IllegalArgumentException("Unexpected assessment examples");
        }
        jdbc.update("""
                INSERT INTO subject(subject_code,alias,status,data_version,created_at)
                VALUES(?,'Research table demo','ACTIVE','demo-research-table',CURRENT_TIMESTAMP)
                """, SUBJECT);
        long subject = jdbc.queryForObject("SELECT id FROM subject WHERE subject_code=?", Long.class, SUBJECT);
        long owner = user(jdbc, OWNER);
        jdbc.update("UPDATE sys_user SET data_scope='ASSIGNED' WHERE id=?", owner);
        jdbc.update("INSERT INTO subject_permission(subject_id,user_id,permission_type) VALUES(?,?,'VIEW')",
                subject, owner);
        jdbc.update("INSERT INTO subject_permission(subject_id,user_id,permission_type) VALUES(?,?,'SUMMARY_READ')",
                subject, user(jdbc, "patient"));

        var snapshots = new TreeMap<String, Long>();
        for (String label : labels) {
            JsonNode entry = cases.path(label);
            JsonNode request = entry.path("request");
            JsonNode model = entry.path("model");
            protocol.validateRequest(request, model);
            if (!"CONSTRUCTED-001".equals(request.path("subjectCode").asText())
                    || !((trained ? "run-trained-demo-" : "run-constructed-http-") + label)
                            .equals(request.path("runId").asText())) {
                throw new IllegalArgumentException("Unexpected constructed example");
            }
            var keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                    INSERT INTO research_table_snapshot(subject_id,authorized_user_id,request_json,wire_sha256,
                        request_sha256,model_json,data_scope,input_sha256,context_sha256)
                    VALUES(?,?,?,?,?,?,'CONSTRUCTED_VALIDATION',?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
                String wire = request.toString();
                statement.setLong(1, subject);
                statement.setLong(2, owner);
                statement.setString(3, wire);
                statement.setString(4, wireSha256(wire));
                statement.setString(5, digest(entry, "requestSha256"));
                statement.setString(6, model.toString());
                statement.setString(7, digest(entry, "inputSha256"));
                statement.setString(8, digest(entry, "contextSha256"));
                return statement;
            }, keys);
            snapshots.put(label, keys.getKey().longValue());
        }
        return Map.of("javaBaseUrl", "http://127.0.0.1:" + port, "subjectId", subject,
                "owner", OWNER, "snapshots", snapshots);
    }

    private static long user(JdbcTemplate jdbc, String username) {
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, username);
    }

    private static String digest(JsonNode entry, String field) {
        String value = entry.path(field).asText();
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Missing or invalid " + field);
        }
        return value;
    }

    private static String wireSha256(String request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(request.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
