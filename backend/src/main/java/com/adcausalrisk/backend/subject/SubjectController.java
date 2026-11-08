package com.adcausalrisk.backend.subject;

import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.dto.FeatureCreateRequest;
import com.adcausalrisk.backend.subject.dto.FeatureResultView;
import com.adcausalrisk.backend.subject.dto.SubjectCreateRequest;
import com.adcausalrisk.backend.subject.dto.SubjectDetailView;
import com.adcausalrisk.backend.subject.dto.SubjectView;

import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Maps the three contract endpoints of the subject module: paged list, create, and single-subject
 * detail.
 *
 * <p>This controller only bridges HTTP to {@link SubjectService} - no access control lives here.
 * The Controller guarantees the caller is authenticated (Spring Security's {@code authenticated()}
 * rule in {@code SecurityConfig}), while every fine-grained decision (role-based create permission,
 * per-subject visibility) is made inside the service, on top of {@link SubjectAccessPolicy}. The
 * principal is extracted from the injected {@link Authentication} and handed straight over.
 */
@RestController
@RequestMapping("/api/subjects")
public class SubjectController {

    private final SubjectService subjectService;
    private final FeatureService featureService;
    private final com.adcausalrisk.backend.common.BusinessQueries queries;

    public SubjectController(
            SubjectService subjectService,
            FeatureService featureService,
            com.adcausalrisk.backend.common.BusinessQueries queries) {
        this.subjectService = subjectService;
        this.featureService = featureService;
        this.queries = queries;
    }

    /**
     * {@code GET /api/subjects} - paged subject list. Both query parameters are optional: {@code
     * page} defaults to 1, {@code pageSize} to 20. Values outside the {@code [1, 100]} range are
     * clamped by the service (the contract describes 1 as the minimum and 100 as the maximum).
     */
    @GetMapping
    public Envelope<?> list(
            @RequestParam java.util.Map<String, String> filters, Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(queries.list("subjects", filters, principal));
    }

    /**
     * {@code POST /api/subjects} - create a new subject. Only {@code ADMIN} and {@code RESEARCHER}
     * may call this endpoint; the service layer enforces that and returns 403 for others.
     * {@code @Valid} triggers the DTO's constraint annotations (subjectCode pattern, notBlank)
     * before the request body reaches the service.
     */
    @PostMapping
    public Envelope<SubjectView> create(
            @Valid @RequestBody SubjectCreateRequest request, Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(subjectService.createSubject(request, principal));
    }

    /**
     * {@code GET /api/subjects/{subjectId}} - full detail of a single subject. Two distinct 4xx
     * responses are possible: 400 when the subject id does not exist in the table, and 403 when it
     * does exist but the caller is not permitted to see it (DOCTOR / PATIENT without a matching
     * subject_permission row).
     */
    @GetMapping("/{subjectId}")
    public Envelope<SubjectDetailView> detail(
            @PathVariable Long subjectId, Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(subjectService.getSubjectDetail(subjectId, principal));
    }

    /**
     * {@code PATCH /api/subjects/{subjectId}} - rename / archive a subject.
     *
     * <p>The contract reuses the {@code SubjectCreate} body, so {@code subjectCode} must be sent;
     * the service rejects a value that differs from the stored one instead of renaming the
     * identifier other modules key on.
     */
    @PatchMapping("/{subjectId}")
    public Envelope<SubjectView> update(
            @PathVariable Long subjectId,
            @Valid @RequestBody SubjectCreateRequest request,
            Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(subjectService.updateSubject(subjectId, request, principal));
    }

    /** {@code POST /api/subjects/{subjectId}/features} - one feature row per call. */
    @PostMapping("/{subjectId}/features")
    public Envelope<FeatureResultView> createFeature(
            @PathVariable Long subjectId,
            @Valid @RequestBody FeatureCreateRequest request,
            Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(featureService.create(subjectId, request, principal));
    }
}
