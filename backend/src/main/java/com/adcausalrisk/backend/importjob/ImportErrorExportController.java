package com.adcausalrisk.backend.importjob;

import com.adcausalrisk.backend.security.AppUserPrincipal;

import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class ImportErrorExportController {
    private final ImportJobService service;

    public ImportErrorExportController(ImportJobService service) {
        this.service = service;
    }

    private static String cell(String value) {
        String safe = value == null ? "" : value;
        if (safe.matches("(?s)^[=+@\\t\\r-].*")) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    @GetMapping("/api/import-jobs/{id}/errors.csv")
    public ResponseEntity<byte[]> errors(@PathVariable long id, Authentication auth) {
        var job = service.get(id, (AppUserPrincipal) auth.getPrincipal());
        StringBuilder csv = new StringBuilder("\uFEFF行号,字段,错误\r\n");
        for (var issue : job.errors())
            csv.append(issue.rowNumber())
                    .append(',')
                    .append(cell(issue.field()))
                    .append(',')
                    .append(cell(issue.message()))
                    .append("\r\n");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"import-" + id + "-errors.csv\"")
                .body(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
