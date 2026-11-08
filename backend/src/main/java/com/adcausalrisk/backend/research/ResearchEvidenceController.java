package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class ResearchEvidenceController {
    private final ResearchEvidenceService evidence;

    public ResearchEvidenceController(ResearchEvidenceService evidence) {
        this.evidence = evidence;
    }

    @GetMapping("/api/research/evidence")
    public Envelope<?> catalog(Authentication auth) {
        return Envelope.success(evidence.catalog((AppUserPrincipal) auth.getPrincipal()));
    }

    @GetMapping("/api/research/evidence/{key}")
    public Envelope<?> report(@PathVariable String key, Authentication auth) {
        return Envelope.success(evidence.evidence(key, (AppUserPrincipal) auth.getPrincipal()));
    }
}
