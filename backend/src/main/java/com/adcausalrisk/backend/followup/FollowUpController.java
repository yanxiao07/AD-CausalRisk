package com.adcausalrisk.backend.followup;

import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.followup.entity.FollowUpPlan;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
public class FollowUpController {

    private final FollowUpService followUpService;

    public FollowUpController(FollowUpService followUpService) {
        this.followUpService = followUpService;
    }

    /**
     * {@code FollowUp} in the contract; {@code status} is optional on create (defaults to PLANNED).
     */
    public record FollowUpCreateRequest(
            @NotNull @Min(1) Long subjectId,
            @NotNull Instant scheduledAt,
            @NotBlank @Size(max = 1000) String purpose,
            String status) {}

    /**
     * {@code PATCH} body: any subset of the mutable fields. {@code subjectId} is accepted and
     * ignored.
     */
    public record FollowUpUpdateRequest(
            String status,
            Instant scheduledAt,
            @Size(max = 1000) String purpose,
            Long subjectId,
            @Size(max = 1000) String notes) {}

    /** {@code FollowUpResult}. */
    public record FollowUpResult(
            long id,
            long subjectId,
            Instant scheduledAt,
            String purpose,
            String status,
            Long ownerId,
            String notes) {}

    @PostMapping("/api/follow-ups")
    public Envelope<FollowUpResult> create(
            @Valid @RequestBody FollowUpCreateRequest request, Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(
                view(
                        followUpService.create(
                                request.subjectId(),
                                request.scheduledAt(),
                                request.purpose(),
                                principal)));
    }

    @PatchMapping("/api/follow-ups/{followUpId}")
    public Envelope<FollowUpResult> update(
            @PathVariable Long followUpId,
            @RequestBody FollowUpUpdateRequest request,
            Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(
                view(
                        followUpService.update(
                                followUpId,
                                request.status(),
                                request.scheduledAt(),
                                request.purpose(),
                                request.notes(),
                                principal)));
    }

    @GetMapping("/api/subjects/{subjectId}/follow-ups")
    public Envelope<List<FollowUpResult>> list(
            @PathVariable Long subjectId, Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(
                followUpService.list(subjectId, principal).stream()
                        .map(
                                plan ->
                                        principal.roleCodes().contains("PATIENT")
                                                ? new FollowUpResult(
                                                        plan.getId(),
                                                        plan.getSubjectId(),
                                                        plan.getScheduledAt(),
                                                        plan.getPurpose(),
                                                        plan.getStatus().name(),
                                                        null,
                                                        null)
                                                : view(plan))
                        .toList());
    }

    private static FollowUpResult view(FollowUpPlan plan) {
        return new FollowUpResult(
                plan.getId(),
                plan.getSubjectId(),
                plan.getScheduledAt(),
                plan.getPurpose(),
                plan.getStatus().name(),
                plan.getOwnerId(),
                plan.getNotes());
    }
}
