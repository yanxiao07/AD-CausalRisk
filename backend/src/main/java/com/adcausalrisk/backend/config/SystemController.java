package com.adcausalrisk.backend.config;

import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.model.ModelRepositoryService;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
public class SystemController {
    private final SystemService service;
    private final ModelRepositoryService modelRepository;

    public SystemController(SystemService service, ModelRepositoryService modelRepository) {
        this.service = service;
        this.modelRepository = modelRepository;
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

    @GetMapping("/api/system/models")
    public Envelope<?> models(Authentication auth) {
        return Envelope.success(
                modelRepository.list((AppUserPrincipal) auth.getPrincipal()));
    }

    @PostMapping(value = "/api/system/models", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Envelope<?> uploadModel(
            @RequestPart("manifest") MultipartFile manifest,
            @RequestPart("artifact") MultipartFile artifact,
            @RequestParam("artifactSha256") String artifactSha256,
            Authentication auth) {
        return Envelope.success(
                modelRepository.upload(
                        manifest,
                        artifact,
                        artifactSha256,
                        (AppUserPrincipal) auth.getPrincipal()));
    }

    @PostMapping("/api/system/models/{version}/rollback")
    public Envelope<?> rollbackModel(
            @PathVariable String version, Authentication auth) {
        return Envelope.success(
                modelRepository.rollback(version, (AppUserPrincipal) auth.getPrincipal()));
    }

    @PatchMapping("/api/system/models/{version}")
    public Envelope<?> model(
            @PathVariable String version,
            @jakarta.validation.Valid @RequestBody ModelUpdate body,
            Authentication auth) {
        modelRepository.setEnabled(
                version, body.enabled(), (AppUserPrincipal) auth.getPrincipal());
        return Envelope.success(Map.of("saved", true));
    }

    public record ResearchHostUpdate(
            String baseUrl, String bearerToken, Integer readTimeoutSeconds, Boolean enabled) {}

    @GetMapping("/api/system/research-host")
    public Envelope<?> researchHost(Authentication auth) {
        return Envelope.success(service.researchHost((AppUserPrincipal) auth.getPrincipal()));
    }

    @PutMapping("/api/system/research-host")
    public Envelope<?> saveResearchHost(
            @RequestBody ResearchHostUpdate body, Authentication auth) {
        return Envelope.success(
                service.saveResearchHost(
                        body.baseUrl(),
                        body.bearerToken(),
                        body.readTimeoutSeconds(),
                        body.enabled(),
                        (AppUserPrincipal) auth.getPrincipal()));
    }

    public record AssessmentPolicyUpdate(Boolean testRuleEnabled) {}

    @GetMapping("/api/system/assessment-policy")
    public Envelope<?> assessmentPolicy(Authentication auth) {
        return Envelope.success(
                service.assessmentPolicy((AppUserPrincipal) auth.getPrincipal()));
    }

    @PutMapping("/api/system/assessment-policy")
    public Envelope<?> saveAssessmentPolicy(
            @RequestBody AssessmentPolicyUpdate body, Authentication auth) {
        return Envelope.success(
                service.saveAssessmentPolicy(
                        body.testRuleEnabled(), (AppUserPrincipal) auth.getPrincipal()));
    }

    public record RetentionUpdate(Integer auditLogDays) {}

    @GetMapping("/api/system/retention")
    public Envelope<?> retention(Authentication auth) {
        return Envelope.success(service.retention((AppUserPrincipal) auth.getPrincipal()));
    }

    @PutMapping("/api/system/retention")
    public Envelope<?> saveRetention(@RequestBody RetentionUpdate body, Authentication auth) {
        return Envelope.success(
                service.saveRetention(
                        body.auditLogDays(), (AppUserPrincipal) auth.getPrincipal()));
    }
}
