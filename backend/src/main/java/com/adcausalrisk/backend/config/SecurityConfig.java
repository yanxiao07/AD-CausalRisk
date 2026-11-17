package com.adcausalrisk.backend.config;

import com.adcausalrisk.backend.security.RestAccessDeniedHandler;
import com.adcausalrisk.backend.security.RestAuthenticationEntryPoint;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Session-cookie security for the business API.
 *
 * <p>The browser authenticates with {@code POST /api/auth/login} and then carries the
 * {@code JSESSIONID} cookie; the contract's {@code securitySchemes.sessionCookie} is an
 * {@code apiKey} in a cookie, which is exactly what an HTTP session provides.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            @Value("${app.security.csrf-enabled:true}") boolean csrfEnabled)
            throws Exception {
        http
                .csrf(csrf -> {
                    if (!csrfEnabled) {
                        // The test profile disables CSRF so hundreds of pre-existing MVC tests
                        // do not each need a token. CsrfProtectionTest enables it explicitly and
                        // exercises the real filter chain, so production behaviour stays tested.
                        csrf.disable();
                        return;
                    }
                    CookieCsrfTokenRepository repository =
                            CookieCsrfTokenRepository.withHttpOnlyFalse();
                    repository.setCookieName("XSRF-TOKEN");
                    repository.setHeaderName("X-CSRF-TOKEN");
                    repository.setCookiePath("/");
                    csrf.csrfTokenRepository(repository)
                            // Accept the raw token from GET /api/auth/csrf in the header.
                            // Spring's default XOR handler expects a masked value instead.
                            .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                            // Login must succeed before a session can issue a CSRF token.
                            .ignoringRequestMatchers("/api/auth/login");
                })
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // The contract defines POST /api/auth/logout. Spring's own /logout would be a
                // second, contract-invisible way to end a session.
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        // The token itself is not a credential; it must be obtainable before login
                        // and after a page reload when only the session cookie survives.
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        // Container error dispatch. Without this, a failed request could answer
                        // 403 while trying to render its own error.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // Nothing else is exposed. An authenticated caller that reaches a
                        // non-/api path is refused by RestAccessDeniedHandler (403).
                        .anyRequest().denyAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /** BCrypt, as promised by FR-AUTH-01 ("密码哈希"). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Exposes the {@code AuthenticationManager} built from the {@code UserDetailsService} and
     * {@code PasswordEncoder} beans above, so the login controller can authenticate a JSON
     * body (the built-in form-login filter cannot read a JSON request body).
     */
    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * Spring Security reads both beans as extension points: the configured
     * {@code SecurityContextHolderFilter} uses this repository, and session management uses this
     * strategy - which is why the explicit login path can apply session-fixation protection
     * without reimplementing it.
     */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }
}
