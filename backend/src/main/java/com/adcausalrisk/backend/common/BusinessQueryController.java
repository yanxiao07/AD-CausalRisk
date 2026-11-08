package com.adcausalrisk.backend.common;

import com.adcausalrisk.backend.security.AppUserPrincipal;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class BusinessQueryController {
    private final BusinessQueries queries;

    public BusinessQueryController(BusinessQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/api/follow-ups")
    public Envelope<?> followups(@RequestParam Map<String, String> filters, Authentication auth) {
        return Envelope.success(
                queries.list("followups", filters, (AppUserPrincipal) auth.getPrincipal()));
    }

    @GetMapping("/api/import-jobs")
    public Envelope<?> imports(@RequestParam Map<String, String> filters, Authentication auth) {
        return Envelope.success(
                queries.list("imports", filters, (AppUserPrincipal) auth.getPrincipal()));
    }

    @GetMapping("/api/audit-logs/page")
    public Envelope<?> audit(@RequestParam Map<String, String> filters, Authentication auth) {
        return Envelope.success(
                queries.list("audit", filters, (AppUserPrincipal) auth.getPrincipal()));
    }

    @GetMapping("/api/quality-checks")
    public Envelope<?> quality(@RequestParam Map<String, String> filters, Authentication auth) {
        return Envelope.success(
                queries.list("quality", filters, (AppUserPrincipal) auth.getPrincipal()));
    }

    @GetMapping("/api/dashboard/summary")
    public Envelope<?> dashboard(Authentication auth) {
        return Envelope.success(queries.dashboard((AppUserPrincipal) auth.getPrincipal()));
    }

    @GetMapping("/api/subjects/{id}/feature-sources")
    public Envelope<?> sources(@PathVariable long id, Authentication auth) {
        return Envelope.success(queries.featureSources(id, (AppUserPrincipal) auth.getPrincipal()));
    }

    @GetMapping("/api/subjects/{id}/visit-sources")
    public Envelope<?> visits(@PathVariable long id, Authentication auth) {
        queries.featureSources(id, (AppUserPrincipal) auth.getPrincipal());
        return Envelope.success(
                queries.rows(
                        "SELECT"
                            + " id,visit_code,visit_date,raw_mmse,raw_moca,raw_cdr_sb,source,missing_reason,data_version,import_job_id"
                            + " FROM visit WHERE subject_id=? ORDER BY visit_date,id",
                        id));
    }

    @GetMapping("/api/subjects/{id}/capabilities")
    public Envelope<?> capabilities(@PathVariable long id, Authentication auth) {
        return Envelope.success(queries.capabilities(id, (AppUserPrincipal) auth.getPrincipal()));
    }
}
