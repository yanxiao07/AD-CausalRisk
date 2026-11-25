package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PatientTimelineController {

    private final PatientTimelineService timelineService;

    public PatientTimelineController(PatientTimelineService timelineService) {
        this.timelineService = timelineService;
    }

    @GetMapping("/api/patients/me/timeline")
    public Envelope<PatientTimelineService.TimelineView> myTimeline(Authentication auth) {
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        return Envelope.success(timelineService.myTimeline(principal));
    }
}
