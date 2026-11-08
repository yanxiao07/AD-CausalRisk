package com.adcausalrisk.backend.importjob;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.importjob.dto.ImportJobView;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Maps the contract's three import endpoints.
 *
 * <p>Like the other modules this controller only bridges HTTP to {@link ImportJobService}; the role
 * check, ownership check and the whole preview/commit state machine live in the service. Two
 * failure modes of a multipart endpoint are handled centrally by {@code GlobalExceptionHandler}: a
 * missing {@code Idempotency-Key} header and a missing {@code file} part both become 400, and an
 * oversized upload does too (the contract declares no 413).
 *
 * <p>{@code consumes} is pinned to {@code multipart/form-data} so a client posting JSON gets a 415
 * instead of a confusing missing-part error.
 */
@RestController
@RequestMapping("/api/import-jobs")
public class ImportJobController {

    private final ImportJobService importJobService;

    public ImportJobController(ImportJobService importJobService) {
        this.importJobService = importJobService;
    }

    /** {@code POST /api/import-jobs} - validate a CSV and return a preview job. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Envelope<ImportJobView> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestPart("file") MultipartFile file,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String mapping,
            Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(
                importJobService.previewMapped(principal, idempotencyKey, bytesOf(file), mapping));
    }

    /** {@code GET /api/import-jobs/{jobId}} - current state of one job. */
    @GetMapping("/{jobId}")
    public Envelope<ImportJobView> get(@PathVariable Long jobId, Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(importJobService.get(jobId, principal));
    }

    /** {@code POST /api/import-jobs/{jobId}/commit} - insert the rows the preview accepted. */
    @PostMapping("/{jobId}/commit")
    public Envelope<ImportJobView> commit(@PathVariable Long jobId, Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return Envelope.success(importJobService.commit(jobId, principal));
    }

    private static byte[] bytesOf(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法读取上传文件");
        }
    }
}
