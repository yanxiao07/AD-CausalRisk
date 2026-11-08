package com.adcausalrisk.backend.report;

import com.adcausalrisk.backend.security.AppUserPrincipal;

import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReportExportController {
    private final ReportExportService service;

    public ReportExportController(ReportExportService service) {
        this.service = service;
    }

    @GetMapping("/api/reports/{id}/download")
    public ResponseEntity<byte[]> download(
            @PathVariable long id,
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(defaultValue = "research") String audience,
            Authentication auth) {
        var file = service.export(id, format, audience, (AppUserPrincipal) auth.getPrincipal());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.filename() + "\"")
                .body(file.bytes());
    }
}
