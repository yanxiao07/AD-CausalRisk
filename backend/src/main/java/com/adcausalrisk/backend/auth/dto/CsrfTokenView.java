package com.adcausalrisk.backend.auth.dto;

/**
 * The CSRF token a browser must echo on state-changing requests.
 *
 * <p>{@code headerName} is returned as well so the client never hard-codes a header name that
 * the server might have to change; {@code token} is the raw value stored by the cookie
 * repository. The matching cookie is written by {@code CookieCsrfTokenRepository}.
 */
public record CsrfTokenView(String token, String headerName) {}
