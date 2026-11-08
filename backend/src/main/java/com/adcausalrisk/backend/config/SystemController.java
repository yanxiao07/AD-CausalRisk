package com.adcausalrisk.backend.config;

import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class SystemController {
    private final SystemService service;

    public SystemController(SystemService service) {
        this.service = service;
    }

    @GetMapping("/api/system/info")
    public Envelope<?> info(Authentication auth) {
        return Envelope.success(service.info((AppUserPrincipal) auth.getPrincipal()));
    }

    @GetMapping("/api/me/preferences")
    public Envelope<?> preferences(Authentication auth) {
        return Envelope.success(service.preferences((AppUserPrincipal) auth.getPrincipal()));
    }

    @PatchMapping("/api/me/preferences")
    public Envelope<?> save(@RequestBody Map<String, Object> body, Authentication auth) {
        return Envelope.success(service.preferences(body, (AppUserPrincipal) auth.getPrincipal()));
    }

    public record ModelUpdate(@jakarta.validation.constraints.NotNull Boolean enabled) {}

    @PatchMapping("/api/system/models/{version}")
    public Envelope<?> model(
            @PathVariable String version,
            @jakarta.validation.Valid @RequestBody ModelUpdate body,
            Authentication auth) {
        service.model(version, body.enabled(), (AppUserPrincipal) auth.getPrincipal());
        return Envelope.success(Map.of("saved", true));
    }
}
