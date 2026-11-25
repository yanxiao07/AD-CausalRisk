package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PatientFollowUpSelfServiceController {

    private final PatientFollowUpSelfService selfService;
    private final PatientDoctorController followUpView;

    public PatientFollowUpSelfServiceController(PatientFollowUpSelfService selfService,
                                                PatientDoctorController followUpView) {
        this.selfService = selfService;
        this.followUpView = followUpView;
    }

    public record CancelRequest(@NotNull @Min(0) Long appointmentRevision) {}

    /**
     * 患者取消本人复诊。返回取消后的复诊列表，前端不必再发一次 GET 就能刷新页面。
     */
    @PostMapping("/api/patients/me/follow-up/{id}/cancel")
    public Envelope<?> cancel(@PathVariable long id,
                              @Valid @RequestBody CancelRequest request,
                              Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        selfService.cancelOwnAppointment(id, request.appointmentRevision(), principal);
        return followUpView.followup(authentication);
    }
}
