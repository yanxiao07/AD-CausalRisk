package com.adcausalrisk.backend.visit;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.dto.VisitResultView;
import com.adcausalrisk.backend.visit.dto.VisitCreateRequest;

import jakarta.validation.Valid;

/**
 * Maps the single visit operation the frozen contract exposes:
 * {@code POST /api/subjects/{subjectId}/visits}.
 *
 * <p>There is intentionally no {@code GET} here. The contract declares no visit collection
 * endpoint; visits are read through {@code GET /api/subjects/{subjectId}} as
 * {@code SubjectDetail.visits}, which {@code SubjectService} populates.
 *
 * <p>As in {@code SubjectController}, this class only bridges HTTP to {@link VisitService}:
 * the caller's authentication is guaranteed by {@code SecurityConfig}'s
 * {@code authenticated()} rule, and every role or object decision is made in the service.
 */
@RestController
@RequestMapping("/api/subjects/{subjectId}/visits")
public class VisitController {

    private final VisitService visitService;

    public VisitController(VisitService visitService) {
        this.visitService = visitService;
    }

    /**
     * {@code POST /api/subjects/{subjectId}/visits} - record one visit.
     *
     * <p>{@code @Valid} applies the DTO constraints (visit code length, date presence, score
     * bounds and scale) before the body reaches the service, so the service only has to deal
     * with business rules.
     */
    @PostMapping
    public Envelope<VisitResultView> create(@PathVariable Long subjectId,
                                            @Valid @RequestBody VisitCreateRequest request,
                                            Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(visitService.createVisit(subjectId, request, principal));
    }
}
