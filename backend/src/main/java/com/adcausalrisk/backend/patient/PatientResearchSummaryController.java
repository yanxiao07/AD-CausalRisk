package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.patient.PatientResearchSummaryService.ResearchSummaryView;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/patients/me/research-summary} - qualitative, de-identified research validation
 * result for the patient home page.
 *
 * <p>Only a PATIENT may call it. The service returns no CIF, model identity, weights or graph; the
 * response is deliberately narrower than the research report.
 */
@RestController
public class PatientResearchSummaryController {

    private final PatientResearchSummaryService service;

    public PatientResearchSummaryController(PatientResearchSummaryService service) {
        this.service = service;
    }

    @GetMapping("/api/patients/me/research-summary")
    public Envelope<ResearchSummaryView> myResearchSummary(Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(service.currentSummary(principal));
    }
}
