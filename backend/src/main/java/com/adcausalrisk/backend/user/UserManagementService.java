package com.adcausalrisk.backend.user;

import com.adcausalrisk.backend.common.*;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class UserManagementService {
    private final JdbcTemplate jdbc;
    private final BusinessQueries queries;
    private final PasswordEncoder passwords;
    private static final Set<String> ROLES = Set.of("ADMIN", "DOCTOR", "RESEARCHER", "PATIENT");

    public UserManagementService(
            JdbcTemplate jdbc, BusinessQueries queries, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.queries = queries;
        this.passwords = passwords;
    }

    public PageResult<Map<String, Object>> list(
            Map<String, String> filter, AppUserPrincipal actor) {
        var page = queries.list("users", filter, actor);
        for (var user : page.items())
            user.put(
                    "roles",
                    jdbc.queryForList(
                            "SELECT r.role_code FROM sys_role r JOIN user_role ur ON"
                                    + " ur.role_id=r.id WHERE ur.user_id=? ORDER BY r.role_code",
                            String.class,
                            user.get("id")));
        return page;
    }

    private static String text(Map<String, Object> body, String key, int limit) {
        Object value = body.get(key);
        String text = value == null ? null : value.toString().trim();
        if (text != null && text.length() > limit)
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, key + "长度超限");
        return text;
    }

    @Transactional
    public long save(Long id, Map<String, Object> body, AppUserPrincipal actor) {
        BusinessQueries.admin(actor);
        Set<String> allowed =
                Set.of(
                        "username",
                        "password",
                        "status",
                        "roles",
                        "displayName",
                        "department",
                        "phone",
                        "officeHours",
                        "dataScope");
        if (!allowed.containsAll(body.keySet()))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "存在不支持的账号字段");
        List<String> roles = null;
        if (body.containsKey("roles")) {
            if (!(body.get("roles") instanceof List<?> values) || values.isEmpty())
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "至少选择一个角色");
            if (values.stream().anyMatch(v -> !(v instanceof String)))
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "角色须为字符串");
            roles = values.stream().map(Object::toString).distinct().toList();
            if (!ROLES.containsAll(roles) || roles.contains("PATIENT") && roles.size() > 1)
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "患者账号须独立使用PATIENT角色");
        }
        String password = text(body, "password", 100), status = text(body, "status", 20);
        String scope = text(body, "dataScope", 20);
        if (scope != null && !Set.of("ALL", "ASSIGNED").contains(scope))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "数据范围仅支持ALL/ASSIGNED");
        if (password != null && password.length() < 6)
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "密码至少6位");
        if (status != null && !Set.of("ACTIVE", "DISABLED").contains(status))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "账号状态无效");
        if (id != null
                && id == actor.userId()
                && ("DISABLED".equals(status) || roles != null && !roles.contains("ADMIN")))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不能停用或移除自己的管理员角色");
        if (id == null) {
            String username = text(body, "username", 50);
            if (username == null
                    || !username.matches("[A-Za-z0-9_-]{2,50}")
                    || password == null
                    || roles == null)
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "新账号需要用户名、密码和角色");
            var keys = new GeneratedKeyHolder();
            String hash = passwords.encode(password);
            jdbc.update(
                    connection -> {
                        var statement =
                                connection.prepareStatement(
                                        "INSERT INTO"
                                            + " sys_user(username,password_hash,status,created_at)"
                                            + " VALUES(?,?,?,?)",
                                        new String[] {"id"});
                        statement.setString(1, username);
                        statement.setString(2, hash);
                        statement.setString(3, "ACTIVE");
                        statement.setTimestamp(4, Timestamp.from(Instant.now()));
                        return statement;
                    },
                    keys);
            id = Objects.requireNonNull(keys.getKey()).longValue();
            jdbc.update("UPDATE sys_user SET data_scope='ASSIGNED' WHERE id=?", id);
        } else {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=?", Integer.class, id)
                    == 0) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "账号不存在");
            if (body.containsKey("username"))
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "账号名不可修改");
            if (roles != null
                    && roles.contains("PATIENT")
                    && (jdbc.queryForObject(
                                            "SELECT COUNT(DISTINCT subject_id) FROM"
                                                    + " subject_permission WHERE user_id=?",
                                            Integer.class,
                                            id)
                                    > 1
                            || jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM subject_permission WHERE"
                                                + " user_id=? AND permission_type<>'SUMMARY_READ'",
                                            Integer.class,
                                            id)
                                    > 0))
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "改为患者前须撤销多余档案和原始资料授权");
            if (password != null)
                jdbc.update(
                        "UPDATE sys_user SET password_hash=?,auth_epoch=auth_epoch+1 WHERE id=?",
                        passwords.encode(password),
                        id);
        }
        if (status != null) jdbc.update("UPDATE sys_user SET status=? WHERE id=?", status, id);
        if (scope != null) jdbc.update("UPDATE sys_user SET data_scope=? WHERE id=?", scope, id);
        if (roles != null) {
            jdbc.update("DELETE FROM user_role WHERE user_id=?", id);
            for (String role : roles)
                jdbc.update(
                        "INSERT INTO user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE"
                                + " role_code=?",
                        id,
                        role);
        }
        Map<String, String> columns =
                Map.of(
                        "displayName",
                        "display_name",
                        "department",
                        "department",
                        "phone",
                        "contact_phone",
                        "officeHours",
                        "office_hours");
        for (var entry : columns.entrySet())
            if (body.containsKey(entry.getKey()))
                jdbc.update(
                        "UPDATE sys_user SET " + entry.getValue() + "=? WHERE id=?",
                        text(
                                body,
                                entry.getKey(),
                                entry.getKey().equals("phone")
                                        ? 50
                                        : entry.getKey().equals("officeHours") ? 200 : 100),
                        id);
        return id;
    }

    public List<Map<String, Object>> permissions(long subject, AppUserPrincipal actor) {
        BusinessQueries.admin(actor);
        return queries.rows(
                "SELECT p.*,u.username FROM subject_permission p JOIN sys_user u ON u.id=p.user_id"
                        + " WHERE p.subject_id=? ORDER BY p.id",
                subject);
    }

    @Transactional
    public void grant(long subject, long user, String permission, AppUserPrincipal actor) {
        BusinessQueries.admin(actor);
        if (permission == null || !Set.of("VIEW", "MANAGE", "SUMMARY_READ").contains(permission))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "授权类型无效");
        var roles =
                jdbc.queryForList(
                        "SELECT r.role_code FROM sys_role r JOIN user_role ur ON ur.role_id=r.id"
                                + " WHERE ur.user_id=?",
                        String.class,
                        user);
        if (roles.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "账号不存在");
        if (roles.contains("PATIENT") && !permission.equals("SUMMARY_READ"))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "患者只可授予摘要读取权限");
        if (roles.contains("PATIENT")
                && jdbc.queryForObject(
                                "SELECT COUNT(*) FROM subject_permission WHERE user_id=? AND"
                                        + " subject_id<>?",
                                Integer.class,
                                user,
                                subject)
                        > 0)
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "患者只能绑定一个本人档案");
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM subject_permission WHERE user_id=? AND subject_id=?"
                                + " AND permission_type=?",
                        Integer.class,
                        user,
                        subject,
                        permission)
                == 0)
            jdbc.update(
                    "INSERT INTO subject_permission(subject_id,user_id,permission_type)"
                            + " VALUES(?,?,?)",
                    subject,
                    user,
                    permission);
    }

    public void revoke(long subject, long permission, AppUserPrincipal actor) {
        BusinessQueries.admin(actor);
        jdbc.update(
                "DELETE FROM subject_permission WHERE id=? AND subject_id=?", permission, subject);
    }
}
