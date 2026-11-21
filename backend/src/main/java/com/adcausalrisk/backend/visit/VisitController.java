package com.adcausalrisk.backend.visit;

import java.util.List;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.dto.VisitResultView;
import com.adcausalrisk.backend.validation.ContractGroup;
import com.adcausalrisk.backend.visit.dto.VisitCorrectionRequest;
import com.adcausalrisk.backend.visit.dto.VisitCreateRequest;
import com.adcausalrisk.backend.visit.dto.VisitVersionView;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.groups.Default;

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
 *
 * <h3>QC-tolerant override</h3>
 * Spring's {@code @Valid} is intentionally <b>not</b> used here — validation is driven
 * manually so this endpoint can switch validation groups based on a caller-supplied header:
 *
 * <ul>
 *   <li><b>No {@code X-Allow-OOR} header</b> (or {@code false}):
 *       validates with {@link Default} + {@link ContractGroup}. The latter contains the
 *       clinical {@code @DecimalMax} ceilings (MMSE 30, MoCA 30, CDR-SB 18), so callers that
 *       do not opt in (notably the Python analytics side) keep the frozen contract verbatim
 *       and receive 400 on out-of-range scores.</li>
 *   <li><b>{@code X-Allow-OOR: true}</b>:
 *       validates with {@link Default} only — the clinical ceilings are skipped, but shape
 *       constraints (not-null, date pattern, {@code @DecimalMin("0")}) still fire. Out-of-range
 *       values pass through and the QC layer in {@link VisitService} catches them with a
 *       PENDING QC record instead of a 400.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/subjects/{subjectId}/visits")
public class VisitController {

    private final VisitService visitService;
    private final VisitCorrectionService correctionService;
    private final Validator validator;

    public VisitController(
            VisitService visitService,
            VisitCorrectionService correctionService,
            Validator validator) {
        this.visitService = visitService;
        this.correctionService = correctionService;
        this.validator = validator;
    }

    /**
     * {@code POST /api/subjects/{subjectId}/visits} - record one visit.
     *
     * <p>{@code X-Allow-OOR} defaults to {@code false} so the frozen contract is preserved
     * for callers that do not explicitly opt in.
     */
    @PostMapping
    public Envelope<VisitResultView> create(@PathVariable Long subjectId,
                                            @RequestHeader(value = "X-Allow-OOR", required = false, defaultValue = "false")
                                            boolean allowOor,
                                            @RequestBody VisitCreateRequest request,
                                            Authentication authentication) {
        validate(request, allowOor);

        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(visitService.createVisit(subjectId, request, principal));
    }

    /**
     * {@code PATCH /api/subjects/{subjectId}/visits/{visitId}} - append a corrected version.
     *
     * <p>The previous row stays in the table with its original values; only its
     * {@code is_current} flag is cleared. The response is the new effective version.
     */
    @PatchMapping("/{visitId}")
    public Envelope<VisitResultView> correct(@PathVariable Long subjectId,
                                             @PathVariable Long visitId,
                                             @Valid @RequestBody VisitCorrectionRequest request,
                                             Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(correctionService.correct(subjectId, visitId, request, principal));
    }

    /** {@code GET .../visits/{visitId}/history} - original plus every appended correction. */
    @GetMapping("/{visitId}/history")
    public Envelope<List<VisitVersionView>> history(@PathVariable Long subjectId,
                                                    @PathVariable Long visitId,
                                                    Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(correctionService.history(subjectId, visitId, principal));
    }

    /**
     * Runs Jakarta Validation against the request with the correct group set for
     * this caller, and throws {@link ConstraintViolationException} when any
     * constraint fires - so the existing {@code GlobalExceptionHandler} produces
     * the exact same 400 Envelope as Spring's built-in {@code @Valid} would.
     */
    private void validate(VisitCreateRequest request, boolean allowOor) {
        Class<?>[] groups = allowOor
                ? new Class<?>[]{Default.class}
                : new Class<?>[]{Default.class, ContractGroup.class};

        Set<ConstraintViolation<VisitCreateRequest>> violations = validator.validate(request, groups);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }
}