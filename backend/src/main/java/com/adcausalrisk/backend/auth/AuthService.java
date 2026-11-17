package com.adcausalrisk.backend.auth;

import com.adcausalrisk.backend.auth.dto.ChangePasswordRequest;
import com.adcausalrisk.backend.auth.dto.CurrentUserView;
import com.adcausalrisk.backend.auth.dto.LoginRequest;
import com.adcausalrisk.backend.auth.dto.MyProfileView;
import com.adcausalrisk.backend.auth.dto.UpdateProfileRequest;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.security.MenuCatalog;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

/**
 * Login, logout and "who am I".
 *
 * <p>Login is handled here rather than by Spring's form-login filter because the contract's {@code
 * POST /api/auth/login} takes a JSON body, which that filter cannot read. The session work the
 * filter would normally do - session-fixation protection and storing the security context - is
 * therefore done explicitly, using the two beans {@code SecurityConfig} provides.
 *
 * <p>BusinessAuditInterceptor writes the successful/failed login and logout audit events after
 * request completion; this service does not duplicate those events or store request credentials.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthenticationManager authenticationManager;

    private final SecurityContextRepository securityContextRepository;

    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public AuthService(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            PasswordEncoder passwords) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    public CurrentUserView login(
            LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        Authentication authentication;
        try {
            authentication =
                    authenticationManager.authenticate(
                            UsernamePasswordAuthenticationToken.unauthenticated(
                                    request.username(), request.password()));
        } catch (AuthenticationException exception) {
            // One refusal for every cause - unknown username, wrong password, disabled account -
            // so the caller cannot probe which usernames exist (FR-AUTH-01, "统一失败提示").
            // The cause stays in the server log.
            log.debug("login refused", exception);
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        // Session fixation protection. Spring's authentication filters apply this themselves,
        // but this login path bypasses them, so it is applied here.
        sessionAuthenticationStrategy.onAuthentication(authentication, httpRequest, httpResponse);

        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        // Store the principal without its password hash: the session keeps what later requests
        // need (id, username, roles) and nothing that resembles a credential.
        AppUserPrincipal sessionPrincipal = principal.withPasswordErased();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        sessionPrincipal, null, sessionPrincipal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);
        httpRequest
                .getSession()
                .setAttribute(
                        "AUTH_EPOCH",
                        jdbc.queryForObject(
                                "SELECT auth_epoch FROM sys_user WHERE id=?",
                                Long.class,
                                principal.userId()));

        log.info("login succeeded for account id={}", principal.userId());
        return viewOf(sessionPrincipal);
    }

    public void logout(HttpServletRequest httpRequest) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof AppUserPrincipal principal) {
            log.info("logout for account id={}", principal.userId());
        }

        SecurityContextHolder.clearContext();
        // Invalidating the session discards the stored security context, so the JSESSIONID the
        // caller still holds is worthless afterwards.
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    public CurrentUserView currentUser(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            // Defensive: SecurityConfig already requires authentication for /api/me.
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return viewOf(principal);
    }

    public MyProfileView profile(AppUserPrincipal principal) {
        return loadProfile(principal.userId());
    }

    public MyProfileView updateProfile(UpdateProfileRequest request, AppUserPrincipal principal) {
        if (request == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "缺少资料内容");
        if (request.displayName() != null) {
            String value = text(request.displayName(), 100, "显示名");
            jdbc.update("UPDATE sys_user SET display_name=? WHERE id=?", value, principal.userId());
        }
        if (request.department() != null) {
            String value = text(request.department(), 100, "科室");
            jdbc.update("UPDATE sys_user SET department=? WHERE id=?", value, principal.userId());
        }
        if (request.phone() != null) {
            String value = text(request.phone(), 50, "联系电话");
            jdbc.update(
                    "UPDATE sys_user SET contact_phone=? WHERE id=?",
                    value,
                    principal.userId());
        }
        if (request.officeHours() != null) {
            if (!principal.roleCodes().contains("DOCTOR"))
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "仅医生可维护门诊时间");
            String value = text(request.officeHours(), 200, "门诊时间");
            jdbc.update("UPDATE sys_user SET office_hours=? WHERE id=?", value, principal.userId());
        }
        return loadProfile(principal.userId());
    }

    public void changePassword(
            ChangePasswordRequest request,
            AppUserPrincipal principal,
            HttpServletRequest httpRequest) {
        if (request == null
                || isBlank(request.currentPassword())
                || isBlank(request.newPassword()))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请填写当前密码和新密码");
        if (request.newPassword().length() < 6 || request.newPassword().length() > 100)
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "新密码长度需为 6–100 位");
        String hash =
                jdbc.queryForObject(
                        "SELECT password_hash FROM sys_user WHERE id=?",
                        String.class,
                        principal.userId());
        if (hash == null || !passwords.matches(request.currentPassword(), hash))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "当前密码不正确");
        if (passwords.matches(request.newPassword(), hash))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "新密码不能与当前密码相同");
        jdbc.update(
                "UPDATE sys_user SET password_hash=?,auth_epoch=auth_epoch+1 WHERE id=?",
                passwords.encode(request.newPassword()),
                principal.userId());
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            Long epoch =
                    jdbc.queryForObject(
                            "SELECT auth_epoch FROM sys_user WHERE id=?",
                            Long.class,
                            principal.userId());
            session.setAttribute("AUTH_EPOCH", epoch);
        }
        log.info("password changed for account id={}", principal.userId());
    }

    private MyProfileView loadProfile(long userId) {
        return jdbc.queryForObject(
                "SELECT id,username,display_name,department,contact_phone,office_hours"
                        + " FROM sys_user WHERE id=?",
                (rs, rowNum) ->
                        new MyProfileView(
                                rs.getLong("id"),
                                rs.getString("username"),
                                rs.getString("display_name"),
                                rs.getString("department"),
                                rs.getString("contact_phone"),
                                rs.getString("office_hours")),
                userId);
    }

    private static String text(String value, int max, String label) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed != null && trimmed.length() > max)
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, label + "不能超过" + max + "个字符");
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static CurrentUserView viewOf(AppUserPrincipal principal) {
        return new CurrentUserView(
                principal.userId(),
                principal.username(),
                principal.roleCodes(),
                MenuCatalog.menusFor(principal.roleCodes()));
    }
}
