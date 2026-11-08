package com.adcausalrisk.backend.audit;

import com.adcausalrisk.backend.common.Envelope;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.*;

/**
 * Capture only object IDs and stable state codes; never serialize a clinical response into audit.
 */
@ControllerAdvice
public class AuditResponseMetadata implements ResponseBodyAdvice<Object> {
    private static final List<String> FIELDS =
            List.of(
                    "id",
                    "subjectId",
                    "taskId",
                    "assessmentId",
                    "jobId",
                    "status",
                    "qualityStatus",
                    "errorCode",
                    "decision");

    public boolean supports(
            MethodParameter type, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    public Object beforeBodyWrite(
            Object body,
            MethodParameter type,
            MediaType media,
            Class<? extends HttpMessageConverter<?>> converter,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body instanceof Envelope<?> envelope
                && request instanceof ServletServerHttpRequest servlet) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            Object value = envelope.data();
            if (value != null)
                for (String field : FIELDS) {
                    try {
                        Object item =
                                value instanceof Map<?, ?> map
                                        ? map.get(field)
                                        : value.getClass().isRecord()
                                                ? Arrays.stream(
                                                                value.getClass()
                                                                        .getRecordComponents())
                                                        .filter(c -> c.getName().equals(field))
                                                        .findFirst()
                                                        .map(
                                                                c -> {
                                                                    try {
                                                                        return c.getAccessor()
                                                                                .invoke(value);
                                                                    } catch (
                                                                            ReflectiveOperationException
                                                                                    failure) {
                                                                        return null;
                                                                    }
                                                                })
                                                        .orElse(null)
                                                : null;
                        if (item instanceof Number number && field.endsWith("Id")
                                || item instanceof Number && field.equals("id")
                                || item instanceof String text
                                        && text.matches("[A-Z][A-Z0-9_]{0,63}"))
                            metadata.put(field, item);
                    } catch (RuntimeException ignored) {
                        /* Metadata failure must not break the business response. */
                    }
                }
            if (envelope.errorCode() != null) metadata.put("errorCode", envelope.errorCode());
            servlet.getServletRequest().setAttribute("auditObjects", metadata.toString());
        }
        return body;
    }
}
