package com.adcausalrisk.backend.audit;

import com.adcausalrisk.backend.security.AppUserPrincipal;

import jakarta.servlet.http.*;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Audit metadata only: no passwords, tokens, uploaded text or internal exception bodies. */
@Component
public class BusinessAuditInterceptor implements HandlerInterceptor {
    private final AuditService audit;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public BusinessAuditInterceptor(
            AuditService audit,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            com.fasterxml.jackson.databind.ObjectMapper json) {
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
    }

    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal user) {
            request.setAttribute("auditActor", user.userId());
            var rows =
                    jdbc.queryForList(
                            "SELECT status,auth_epoch FROM sys_user WHERE id=?", user.userId());
            var session = request.getSession(false);
            long epoch =
                    session != null && session.getAttribute("AUTH_EPOCH") instanceof Number n
                            ? n.longValue()
                            : 0;
            if (rows.isEmpty()
                    || !rows.get(0).get("status").equals("ACTIVE")
                    || ((Number) rows.get(0).get("auth_epoch")).longValue() != epoch) {
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                try {
                    json.writeValue(
                            response.getWriter(),
                            com.adcausalrisk.backend.common.Envelope.failure(
                                    com.adcausalrisk.backend.common.ErrorCode.UNAUTHENTICATED,
                                    "账号状态已变更，请重新登录"));
                } catch (java.io.IOException ignored) {
                }
                audit.recordQuietly(user.userId(), "SESSION_REVOKED", "会话因账号状态或密码变更失效");
                return false;
            }
            var roles =
                    jdbc.queryForList(
                            "SELECT r.role_code FROM sys_role r JOIN user_role ur ON"
                                    + " ur.role_id=r.id WHERE ur.user_id=? ORDER BY r.role_code",
                            String.class,
                            user.userId());
            if (!roles.equals(user.roleCodes())) {
                var current = new AppUserPrincipal(user.userId(), user.username(), "", roles, true);
                SecurityContextHolder.getContext()
                        .setAuthentication(
                                new org.springframework.security.authentication
                                        .UsernamePasswordAuthenticationToken(
                                        current, null, current.getAuthorities()));
            }
        }
        return true;
    }

    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception failure) {
        String path = request.getRequestURI(), method = request.getMethod();
        if (!path.startsWith("/api/")) return;
        if (method.equals("GET") && !path.startsWith("/api/reports/") && response.getStatus() < 400)
            return;
        if (path.matches("/api/assessments/\\d+/review") && response.getStatus() < 400)
            return; // Existing review aspect owns this event.
        String event;
        if (response.getStatus() == 403) event = "ACCESS_DENIED";
        else if (path.endsWith("/auth/login"))
            event = response.getStatus() < 400 ? "LOGIN" : "LOGIN_FAILED";
        else if (path.endsWith("/auth/logout")) event = "LOGOUT";
        else if (path.contains("/import-jobs")) event = "DATA_IMPORT";
        else if (path.contains("quality-checks") || path.startsWith("/api/qc/"))
            event = "QUALITY_CHECK";
        else if (path.contains("assessment-tasks")) event = "ASSESSMENT_TASK";
        else if (path.contains("follow-ups")) event = "FOLLOW_UP";
        else if (path.startsWith("/api/reports/")) event = "REPORT_EXPORT";
        else if (path.contains("/permissions")) event = "SUBJECT_PERMISSION";
        else event = "BUSINESS_WRITE";
        var auth = SecurityContextHolder.getContext().getAuthentication();
        Long actor =
                auth != null && auth.getPrincipal() instanceof AppUserPrincipal user
                        ? user.userId()
                        : (Long) request.getAttribute("auditActor");
        audit.recordQuietly(
                actor,
                event,
                method
                        + " "
                        + path
                        + " HTTP "
                        + response.getStatus()
                        + " "
                        + java.util.Objects.toString(request.getAttribute("auditObjects"), ""));
    }
}
