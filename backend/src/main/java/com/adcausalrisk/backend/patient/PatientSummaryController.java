package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.patient.PatientSummaryService.PatientSummaryView;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/patients/me/summary} - the de-identified summary the patient home page reads.
 *
 * <p>Only a PATIENT may call it (enforced in {@link PatientSummaryService}); any other role gets
 * 403 instead of a partially redacted clinical result.
 */
@RestController
public class PatientSummaryController {

    private final PatientSummaryService patientSummaryService;

    public PatientSummaryController(PatientSummaryService patientSummaryService) {
        this.patientSummaryService = patientSummaryService;
    }

    @GetMapping("/api/patients/me/summary")
    public Envelope<PatientSummaryView> mySummary(Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(patientSummaryService.currentSummary(principal));
    }
}
