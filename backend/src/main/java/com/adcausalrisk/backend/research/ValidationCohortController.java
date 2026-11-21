package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class ValidationCohortController {
    private final ValidationCohortService service;
    private final ValidationReportService reports;

    public ValidationCohortController(ValidationCohortService service, ValidationReportService reports) {
        this.service = service;
        this.reports = reports;
    }

    @GetMapping("/api/research/validation-cohort/report")
    public Envelope<?> latestReport(@RequestParam String project, @RequestParam String artifactSha256,
                                    @AuthenticationPrincipal AppUserPrincipal actor) {
        return Envelope.success(reports.latest(actor, new ValidationReportService.Scope(project, artifactSha256)));
    }

    @PostMapping("/api/research/validation-cohort/report")
    public Envelope<?> runReport(@RequestBody ValidationReportService.Scope scope,
                                 @AuthenticationPrincipal AppUserPrincipal actor) {
        return Envelope.success(reports.run(actor, scope));
    }

    @GetMapping("/api/research/validation-cohort")
    public Envelope<?> cohort(@RequestParam(defaultValue = "1") int page, @AuthenticationPrincipal AppUserPrincipal actor) {
        return Envelope.success(service.cohort(actor, page));
    }

    @GetMapping("/api/research/validation-cohort/scopes")
    public Envelope<?> scopes(@AuthenticationPrincipal AppUserPrincipal actor) {
        return Envelope.success(service.scopes(actor));
    }

    @PutMapping("/api/research/validation-cohort/grant")
    public Envelope<?> grant(@RequestBody ValidationCohortService.Grant request, @AuthenticationPrincipal AppUserPrincipal actor) {
        service.grant(actor, request);
        return Envelope.success(null);
    }

    @DeleteMapping("/api/research/validation-cohort/grant")
    public Envelope<?> revoke(@RequestParam String project, @RequestParam String subjectCode,
                              @RequestParam String researcher, @AuthenticationPrincipal AppUserPrincipal actor) {
        service.revoke(actor, new ValidationCohortService.Grant(project, subjectCode, researcher, null));
        return Envelope.success(null);
    }

    @GetMapping("/api/subjects/{id}/validation-follow-up")
    public Envelope<?> followUp(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal actor) {
        return Envelope.success(service.followUp(actor, id));
    }

    @PutMapping("/api/subjects/{id}/validation-follow-up")
    public Envelope<?> saveFollowUp(@PathVariable long id, @RequestBody ValidationCohortService.FollowUp request,
                                    @AuthenticationPrincipal AppUserPrincipal actor) {
        return Envelope.success(service.saveFollowUp(actor, id, request));
    }
}
