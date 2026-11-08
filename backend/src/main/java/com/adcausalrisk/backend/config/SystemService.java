package com.adcausalrisk.backend.config;

import com.adcausalrisk.backend.agent.RestAgentClient;
import com.adcausalrisk.backend.common.*;
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
    private final RestAgentClient agent;
    private final String commit;

    public SystemService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            RestAgentClient agent,
            @Value("${build.commit:UNKNOWN}") String commit) {
        this.jdbc = jdbc;
        this.json = json;
        this.agent = agent;
        this.commit = commit;
    }

    public Map<String, Object> info(AppUserPrincipal user) {
        BusinessQueries.admin(user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appVersion", "0.1.0");
        result.put("gitCommit", commit);
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("contractVersion", "course-srs-v3.2-business-20261108");
        try {
            long start = System.nanoTime();
            result.put(
                    "python",
                    Map.of(
                            "health",
                            agent.health(),
                            "latencyMs",
                            (System.nanoTime() - start) / 1000000,
                            "available",
                            true));
        } catch (Exception unavailable) {
            result.put("python", Map.of("available", false, "message", "算法服务暂不可用"));
        }
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

    public void model(String version, boolean enabled, AppUserPrincipal user) {
        BusinessQueries.admin(user);
        if (jdbc.update(
                        "UPDATE model_configuration SET enabled=?,updated_at=CURRENT_TIMESTAMP"
                                + " WHERE model_version=?",
                        enabled,
                        version)
                == 0) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "仅可配置已安装模型");
    }
}
