package com.adcausalrisk.backend.config;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Reads and writes the small set of runtime knobs exposed by the system console.
 *
 * <p>The database is the source of truth once an administrator saves a value. Until then the
 * deployment properties remain the defaults, so an existing installation keeps working without a
 * data migration.
 */
@Service
public class SystemSettingService {

    private static final Logger log = LoggerFactory.getLogger(SystemSettingService.class);

    static final String KEY_RESEARCH_HOST = "research.host";
    static final String KEY_ASSESSMENT_POLICY = "assessment.policy";
    static final String KEY_RETENTION_POLICY = "retention.policy";

    static final int MIN_RESEARCH_TIMEOUT_SECONDS = 1;
    static final int MAX_RESEARCH_TIMEOUT_SECONDS = 600;
    static final int MIN_AUDIT_RETENTION_DAYS = 30;
    static final int MAX_AUDIT_RETENTION_DAYS = 3650;
    static final int DEFAULT_AUDIT_RETENTION_DAYS = 365;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final boolean defaultTestRuleEnabled;
    private final String defaultResearchBaseUrl;
    private final String defaultResearchToken;
    private final int defaultResearchTimeoutSeconds;

    public SystemSettingService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            @Value("${assessment.test-rule-enabled:false}") boolean defaultTestRuleEnabled,
            @Value("${research.table.base-url:}") String defaultResearchBaseUrl,
            @Value("${research.table.bearer-token:}") String defaultResearchToken,
            @Value("${research.table.read-timeout:120s}") Duration defaultResearchTimeout) {
        this.jdbc = jdbc;
        this.json = json;
        this.defaultTestRuleEnabled = defaultTestRuleEnabled;
        this.defaultResearchBaseUrl = defaultResearchBaseUrl == null ? "" : defaultResearchBaseUrl.trim();
        this.defaultResearchToken = defaultResearchToken == null ? "" : defaultResearchToken.trim();
        long roundedSeconds = (defaultResearchTimeout.toMillis() + 999) / 1000;
        this.defaultResearchTimeoutSeconds =
                (int)
                        Math.min(
                                MAX_RESEARCH_TIMEOUT_SECONDS,
                                Math.max(MIN_RESEARCH_TIMEOUT_SECONDS, roundedSeconds));
    }

    public record ResearchHost(
            String baseUrl,
            String token,
            int readTimeoutSeconds,
            boolean enabled,
            Instant updatedAt,
            Long updatedBy) {

        public boolean configured() {
            return enabled && !baseUrl.isBlank() && !token.isBlank();
        }

        /** Keeps the token out of logs and API responses. */
        public ResearchHost withoutToken() {
            return new ResearchHost(
                    baseUrl, "", readTimeoutSeconds, enabled, updatedAt, updatedBy);
        }
    }

    public record AssessmentPolicy(
            boolean testRuleEnabled, Instant updatedAt, Long updatedBy) {}

    public record RetentionPolicy(int auditLogDays, Instant updatedAt, Long updatedBy) {}

    private record StoredSetting(String valueJson, Long updatedBy, Instant updatedAt) {}

    public ResearchHost researchHost() {
        StoredSetting stored = read(KEY_RESEARCH_HOST);
        if (stored == null) {
            return new ResearchHost(
                    defaultResearchBaseUrl,
                    defaultResearchToken,
                    defaultResearchTimeoutSeconds,
                    true,
                    null,
                    null);
        }
        try {
            JsonNode node = json.readTree(stored.valueJson());
            String baseUrl = node.has("baseUrl")
                    ? node.path("baseUrl").asText("")
                    : defaultResearchBaseUrl;
            String token = node.has("bearerToken")
                    ? node.path("bearerToken").asText("")
                    : defaultResearchToken;
            int timeout = node.path("readTimeoutSeconds").asInt(defaultResearchTimeoutSeconds);
            boolean enabled = node.path("enabled").asBoolean(true);
            return new ResearchHost(
                    baseUrl,
                    token,
                    clamp(timeout, MIN_RESEARCH_TIMEOUT_SECONDS, MAX_RESEARCH_TIMEOUT_SECONDS),
                    enabled,
                    stored.updatedAt(),
                    stored.updatedBy());
        } catch (Exception unreadable) {
            log.warn("系统设置 {} 无法解析，使用部署默认值", KEY_RESEARCH_HOST, unreadable);
            return new ResearchHost(
                    defaultResearchBaseUrl,
                    defaultResearchToken,
                    defaultResearchTimeoutSeconds,
                    true,
                    stored.updatedAt(),
                    stored.updatedBy());
        }
    }

    @Transactional
    public ResearchHost saveResearchHost(
            String baseUrl,
            String bearerToken,
            int readTimeoutSeconds,
            boolean enabled,
            Long actorId) {
        ResearchHost current = researchHost();
        String normalizedBaseUrl = baseUrl == null ? "" : baseUrl.trim();
        String token =
                bearerToken == null || bearerToken.isBlank()
                        ? current.token()
                        : bearerToken.trim();
        if (normalizedBaseUrl.isBlank()) {
            token = "";
        } else {
            validateHost(normalizedBaseUrl, token, readTimeoutSeconds);
        }
        write(
                KEY_RESEARCH_HOST,
                new ResearchHostValue(
                        normalizedBaseUrl,
                        normalizedBaseUrl.isBlank() ? "" : token,
                        readTimeoutSeconds,
                        enabled),
                actorId);
        return researchHost();
    }

    private record ResearchHostValue(
            String baseUrl, String bearerToken, int readTimeoutSeconds, boolean enabled) {}

    public AssessmentPolicy assessmentPolicy() {
        StoredSetting stored = read(KEY_ASSESSMENT_POLICY);
        if (stored == null) {
            return new AssessmentPolicy(defaultTestRuleEnabled, null, null);
        }
        try {
            JsonNode node = json.readTree(stored.valueJson());
            return new AssessmentPolicy(
                    node.path("testRuleEnabled").asBoolean(defaultTestRuleEnabled),
                    stored.updatedAt(),
                    stored.updatedBy());
        } catch (Exception unreadable) {
            log.warn("系统设置 {} 无法解析，使用部署默认值", KEY_ASSESSMENT_POLICY, unreadable);
            return new AssessmentPolicy(
                    defaultTestRuleEnabled, stored.updatedAt(), stored.updatedBy());
        }
    }

    public boolean testRuleEnabled() {
        return assessmentPolicy().testRuleEnabled();
    }

    @Transactional
    public AssessmentPolicy saveAssessmentPolicy(boolean enabled, Long actorId) {
        write(KEY_ASSESSMENT_POLICY, new AssessmentPolicyValue(enabled), actorId);
        return assessmentPolicy();
    }

    private record AssessmentPolicyValue(boolean testRuleEnabled) {}

    public RetentionPolicy retentionPolicy() {
        StoredSetting stored = read(KEY_RETENTION_POLICY);
        if (stored == null) {
            return new RetentionPolicy(DEFAULT_AUDIT_RETENTION_DAYS, null, null);
        }
        try {
            JsonNode node = json.readTree(stored.valueJson());
            int days =
                    clamp(
                            node.path("auditLogDays").asInt(DEFAULT_AUDIT_RETENTION_DAYS),
                            MIN_AUDIT_RETENTION_DAYS,
                            MAX_AUDIT_RETENTION_DAYS);
            return new RetentionPolicy(days, stored.updatedAt(), stored.updatedBy());
        } catch (Exception unreadable) {
            log.warn("系统设置 {} 无法解析，使用部署默认值", KEY_RETENTION_POLICY, unreadable);
            return new RetentionPolicy(
                    DEFAULT_AUDIT_RETENTION_DAYS, stored.updatedAt(), stored.updatedBy());
        }
    }

    public int auditRetentionDays() {
        return retentionPolicy().auditLogDays();
    }

    @Transactional
    public RetentionPolicy saveRetentionPolicy(int auditLogDays, Long actorId) {
        if (auditLogDays < MIN_AUDIT_RETENTION_DAYS
                || auditLogDays > MAX_AUDIT_RETENTION_DAYS) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "审计日志保留天数必须在 "
                            + MIN_AUDIT_RETENTION_DAYS
                            + "-"
                            + MAX_AUDIT_RETENTION_DAYS
                            + " 之间");
        }
        write(KEY_RETENTION_POLICY, new RetentionPolicyValue(auditLogDays), actorId);
        return retentionPolicy();
    }

    private record RetentionPolicyValue(int auditLogDays) {}

    private static void validateHost(String baseUrl, String token, int readTimeoutSeconds) {
        if (readTimeoutSeconds < MIN_RESEARCH_TIMEOUT_SECONDS
                || readTimeoutSeconds > MAX_RESEARCH_TIMEOUT_SECONDS) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "研究宿主读取超时必须在 "
                            + MIN_RESEARCH_TIMEOUT_SECONDS
                            + "-"
                            + MAX_RESEARCH_TIMEOUT_SECONDS
                            + " 秒之间");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "研究宿主地址格式无效");
        }
        boolean loopback =
                Set.of("127.0.0.1", "localhost", "[::1]")
                        .contains(Objects.toString(uri.getHost(), ""));
        if (!("https".equals(uri.getScheme())
                        || ("http".equals(uri.getScheme()) && loopback))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !Set.of("", "/").contains(uri.getRawPath())) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "研究宿主仅支持 HTTPS，或环回地址上的 HTTP；地址不能带路径、查询或凭据");
        }
        if (token == null || token.isBlank() || !token.matches("[A-Za-z0-9._~+/-]+=*")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "研究宿主 Bearer Token 格式无效");
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private StoredSetting read(String key) {
        try {
            List<StoredSetting> rows =
                    jdbc.query(
                            "SELECT value_json,updated_by,updated_at FROM system_setting"
                                    + " WHERE setting_key=?",
                            (rs, index) -> {
                                java.sql.Timestamp updatedAt = rs.getTimestamp(3);
                                long actor = rs.getLong(2);
                                Long updatedBy = rs.wasNull() ? null : actor;
                                return new StoredSetting(
                                        rs.getString(1),
                                        updatedBy,
                                        updatedAt == null ? null : updatedAt.toInstant());
                            },
                            key);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (DataAccessException unavailable) {
            // During startup Flyway may not have created the table yet. The deployment
            // properties are the documented fallback.
            log.debug("系统设置表暂不可读，使用部署默认值: {}", unavailable.getMessage());
            return null;
        }
    }

    private void write(String key, Object value, Long actorId) {
        String encoded;
        try {
            encoded = json.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "系统设置无法序列化");
        }
        int changed =
                jdbc.update(
                        "UPDATE system_setting SET value_json=?,updated_by=?,updated_at=CURRENT_TIMESTAMP"
                                + " WHERE setting_key=?",
                        encoded,
                        actorId,
                        key);
        if (changed == 0) {
            jdbc.update(
                    "INSERT INTO system_setting(setting_key,value_json,updated_by,updated_at)"
                            + " VALUES(?,?,?,CURRENT_TIMESTAMP)",
                    key,
                    encoded,
                    actorId);
        }
    }
}
