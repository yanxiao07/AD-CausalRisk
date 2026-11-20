package com.adcausalrisk.backend.config;

import com.adcausalrisk.backend.audit.AuditService;
import com.adcausalrisk.backend.common.*;
import com.adcausalrisk.backend.research.ResearchTableClient;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class SystemService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String commit;
    private final SystemSettingService settings;
    private final AuditService audit;
    private final ResearchTableClient researchClient;

    public SystemService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            @Value("${build.commit:UNKNOWN}") String commit,
            SystemSettingService settings,
            AuditService audit,
            ResearchTableClient researchClient) {
        this.jdbc = jdbc;
        this.json = json;
        this.commit = commit;
        this.settings = settings;
        this.audit = audit;
        this.researchClient = researchClient;
    }

    public Map<String, Object> info(AppUserPrincipal user) {
        BusinessQueries.admin(user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appVersion", "0.1.0");
        result.put("gitCommit", commit);
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("contractVersion", "business-v1.0-20261115");
        result.put("researchHost", researchClient.health());
        result.put(
                "models",
                jdbc.queryForList(
                        "SELECT model_version,enabled,updated_at FROM model_configuration ORDER BY"
                                + " model_version"));
        return result;
    }

    public Map<String, Object> preferences(AppUserPrincipal user) {
        var rows =
                jdbc.queryForList(
                        "SELECT preferences_json FROM user_preferences WHERE user_id=?",
                        String.class,
                        user.userId());
        if (rows.isEmpty())
            return Map.of("density", "comfortable", "fontSize", 16, "notifications", true);
        try {
            return json.readValue(
                    rows.get(0),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception invalid) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "偏好配置不可读取");
        }
    }

    @Transactional
    public Map<String, Object> preferences(Map<String, Object> body, AppUserPrincipal user) {
        if (!Set.of("density", "fontSize", "notifications").containsAll(body.keySet()))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "偏好字段无效");
        var values = new LinkedHashMap<>(preferences(user));
        values.putAll(body);
        if (!(values.get("density") instanceof String density)
                || !Set.of("comfortable", "compact").contains(density)
                || !(values.get("fontSize") instanceof Number size)
                || size.intValue() < 14
                || size.intValue() > 24
                || size.doubleValue() != size.intValue()
                || !(values.get("notifications") instanceof Boolean))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "偏好值无效");
        try {
            String encoded = json.writeValueAsString(values);
            int changed =
                    jdbc.update(
                            "UPDATE user_preferences SET"
                                    + " preferences_json=?,updated_at=CURRENT_TIMESTAMP WHERE"
                                    + " user_id=?",
                            encoded,
                            user.userId());
            if (changed == 0)
                jdbc.update(
                        "INSERT INTO user_preferences(user_id,preferences_json,updated_at)"
                                + " VALUES(?,?,CURRENT_TIMESTAMP)",
                        user.userId(),
                        encoded);
            return values;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "配置格式无效");
        }
    }

    public Map<String, Object> researchHost(AppUserPrincipal user) {
        BusinessQueries.admin(user);
        return researchHostView(settings.researchHost());
    }

    public ResearchTableClient.ModelStatus researchModels(AppUserPrincipal user) {
        BusinessQueries.admin(user);
        return researchClient.loadedModels();
    }

    @Transactional
    public Map<String, Object> saveResearchHost(
            String baseUrl,
            String bearerToken,
            Integer readTimeoutSeconds,
            Boolean enabled,
            AppUserPrincipal user) {
        BusinessQueries.admin(user);
        SystemSettingService.ResearchHost current = settings.researchHost();
        String nextBaseUrl = baseUrl == null ? current.baseUrl() : baseUrl;
        int timeout =
                readTimeoutSeconds == null ? current.readTimeoutSeconds() : readTimeoutSeconds;
        boolean nextEnabled = enabled == null ? current.enabled() : enabled;
        if (nextEnabled && (nextBaseUrl == null || nextBaseUrl.isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "启用研究宿主前请填写地址");
        }
        settings.saveResearchHost(
                nextBaseUrl, bearerToken, timeout, nextEnabled, user.userId());
        researchClient.refresh();
        audit.recordQuietly(
                user.userId(), "SYSTEM_RESEARCH_HOST_UPDATED", "更新研究宿主配置");
        return researchHostView(settings.researchHost());
    }

    public Map<String, Object> assessmentPolicy(AppUserPrincipal user) {
        BusinessQueries.admin(user);
        return assessmentPolicyView(settings.assessmentPolicy());
    }

    @Transactional
    public Map<String, Object> saveAssessmentPolicy(
            Boolean testRuleEnabled, AppUserPrincipal user) {
        BusinessQueries.admin(user);
        if (testRuleEnabled == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "testRuleEnabled 不能为空");
        }
        SystemSettingService.AssessmentPolicy saved =
                settings.saveAssessmentPolicy(testRuleEnabled, user.userId());
        audit.recordQuietly(
                user.userId(),
                "SYSTEM_ASSESSMENT_POLICY_UPDATED",
                testRuleEnabled ? "开启旧规则评估通道" : "关闭旧规则评估通道");
        return assessmentPolicyView(saved);
    }

    public Map<String, Object> retention(AppUserPrincipal user) {
        BusinessQueries.admin(user);
        return retentionView(settings.retentionPolicy());
    }

    @Transactional
    public Map<String, Object> saveRetention(Integer auditLogDays, AppUserPrincipal user) {
        BusinessQueries.admin(user);
        if (auditLogDays == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "审计保留天数不能为空");
        }
        SystemSettingService.RetentionPolicy saved =
                settings.saveRetentionPolicy(auditLogDays, user.userId());
        audit.recordQuietly(
                user.userId(),
                "SYSTEM_RETENTION_POLICY_UPDATED",
                "审计日志保留 " + saved.auditLogDays() + " 天");
        return retentionView(saved);
    }

    private static Map<String, Object> researchHostView(
            SystemSettingService.ResearchHost host) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("baseUrl", host.baseUrl());
        view.put("bearerTokenConfigured", host.token() != null && !host.token().isBlank());
        view.put("readTimeoutSeconds", host.readTimeoutSeconds());
        view.put("enabled", host.enabled());
        view.put("configured", host.configured());
        view.put("updatedAt", host.updatedAt() == null ? null : host.updatedAt().toString());
        view.put("updatedBy", host.updatedBy());
        return view;
    }

    private static Map<String, Object> assessmentPolicyView(
            SystemSettingService.AssessmentPolicy policy) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("testRuleEnabled", policy.testRuleEnabled());
        view.put("updatedAt", policy.updatedAt() == null ? null : policy.updatedAt().toString());
        view.put("updatedBy", policy.updatedBy());
        return view;
    }

    private static Map<String, Object> retentionView(
            SystemSettingService.RetentionPolicy policy) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("auditLogDays", policy.auditLogDays());
        view.put("updatedAt", policy.updatedAt() == null ? null : policy.updatedAt().toString());
        view.put("updatedBy", policy.updatedBy());
        return view;
    }
}
