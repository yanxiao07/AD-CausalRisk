package com.adcausalrisk.backend.subject;

import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class ObservationController {
    private final ObservationService service;

    public ObservationController(ObservationService service) {
        this.service = service;
    }

    @PostMapping("/api/subjects/{id}/observations")
    public Envelope<?> create(
            @PathVariable long id,
            @RequestBody ObservationService.Input body,
            Authentication auth) {
        return Envelope.success(service.save(id, body, (AppUserPrincipal) auth.getPrincipal()));
    }
}
