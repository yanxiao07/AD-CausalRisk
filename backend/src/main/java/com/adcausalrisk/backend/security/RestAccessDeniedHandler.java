package com.adcausalrisk.backend.security;

import com.adcausalrisk.backend.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Answers an authenticated but unauthorised request with HTTP 403 and the unified Envelope.
 *
 * <p>This is the refusal produced by the security filter chain itself (an unmatched rule, a missing
 * role). Object-level refusals decided inside a service use {@code
 * BusinessException(ACCESS_DENIED)} and the same status through {@code GlobalExceptionHandler}, so
 * both paths look identical to a caller.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(RestAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;
    private final com.adcausalrisk.backend.audit.AuditService audit;

    public RestAccessDeniedHandler(
            ObjectMapper objectMapper, com.adcausalrisk.backend.audit.AuditService audit) {
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {
        log.debug(
                "refusing unauthorised request to {}",
                request.getRequestURI(),
                accessDeniedException);
        SecurityEnvelopes.write(response, objectMapper, ErrorCode.ACCESS_DENIED);
        audit.recordQuietly(
                null, "ACCESS_DENIED", request.getMethod() + " " + request.getRequestURI());
    }
}
