package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DoctorAssessmentRequestController {
    private final DoctorAssessmentRequests requests;

    DoctorAssessmentRequestController(DoctorAssessmentRequests requests) {
        this.requests = requests;
    }

    @GetMapping("/api/subjects/{subjectId}/assessment-request")
    Envelope<?> view(@PathVariable long subjectId, @AuthenticationPrincipal AppUserPrincipal actor) {
        return Envelope.success(requests.view(subjectId, actor));
    }

    @PostMapping("/api/subjects/{subjectId}/assessment-request")
    Envelope<?> create(@PathVariable long subjectId, @AuthenticationPrincipal AppUserPrincipal actor) {
        return Envelope.success(requests.request(subjectId, actor));
    }

    @GetMapping("/api/research/assessment-requests")
    Envelope<?> queue(@RequestParam(defaultValue = "1") int page, @AuthenticationPrincipal AppUserPrincipal actor) {
        return Envelope.success(requests.queue(page, actor));
    }

    @PostMapping("/api/research/assessment-requests/{id}/execute")
    Envelope<?> execute(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal actor) {
        return Envelope.success(requests.execute(id, actor));
    }
}
