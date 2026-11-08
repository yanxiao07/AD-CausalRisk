package com.adcausalrisk.backend.auth;

import com.adcausalrisk.backend.auth.dto.CurrentUserView;
import com.adcausalrisk.backend.auth.dto.LoginRequest;
import com.adcausalrisk.backend.common.Envelope;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three authentication operations of the frozen contract: {@code POST /api/auth/login}, {@code
 * POST /api/auth/logout} and {@code GET /api/me}.
 *
 * <p>The controller only maps HTTP and DTOs; every rule lives in {@link AuthService}.
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/login")
    public Envelope<CurrentUserView> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        return Envelope.success(authService.login(request, httpRequest, httpResponse));
    }

    @PostMapping("/auth/logout")
    public Envelope<Void> logout(HttpServletRequest httpRequest) {
        authService.logout(httpRequest);
        // The contract's logout response is a bare Envelope: there is no payload to return.
        return Envelope.success(null);
    }

    @GetMapping({"/me", "/auth/me"})
    public Envelope<CurrentUserView> me(Authentication authentication) {
        return Envelope.success(authService.currentUser(authentication));
    }
}
