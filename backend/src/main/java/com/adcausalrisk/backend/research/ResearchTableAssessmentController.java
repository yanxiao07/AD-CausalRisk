package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/research/table-assessment-tasks")
public class ResearchTableAssessmentController {
    private final ResearchTableAssessmentService service;
    private final ResearchTableProtocol protocol;
    private final ResearchReportExportService exports;

    ResearchTableAssessmentController(ResearchTableAssessmentService service, ResearchTableProtocol protocol,
                                      ResearchReportExportService exports) {
        this.service = service;
        this.protocol = protocol;
        this.exports = exports;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Envelope<?> submit(@RequestHeader("Idempotency-Key") String key,
                              @RequestBody byte[] body, Authentication auth) {
        JsonNode request;
        try {
            request = protocol.parse(body);
        } catch (IOException | IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.MALFORMED_JSON);
        }
        if (request == null || !request.isObject()
                || request.size() != (request.has("subjectId") ? 2 : 1) || !request.has("snapshotId")) {
            throw new BusinessException(ErrorCode.MALFORMED_JSON);
        }
        JsonNode id = request.path("snapshotId");
        if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "snapshotId 须为正整数");
        }
        JsonNode subject = request.get("subjectId");
        if (subject != null && (!subject.isIntegralNumber() || !subject.canConvertToLong() || subject.longValue() <= 0)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "subjectId 须为正整数");
        }
        return Envelope.success(service.submit(id.longValue(), subject == null ? null : subject.longValue(),
                key, (AppUserPrincipal) auth.getPrincipal()));
    }

    record SnapshotRegistration(long snapshotId, String hostSyncStatus) {}
    record DoctorIntake(long subjectId, String indexDate, JsonNode features) {}

    @GetMapping("/snapshots/doctor-intake")
    public Envelope<?> doctorIntakeHistory(@RequestParam long subjectId,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int pageSize,
                                           Authentication auth) {
        return Envelope.success(service.doctorIntakeHistory(subjectId, page, pageSize,
                (AppUserPrincipal) auth.getPrincipal()));
    }

    @PostMapping("/snapshots/doctor-intake")
    public Envelope<?> doctorIntake(@RequestBody DoctorIntake intake, Authentication auth) {
        if (intake.indexDate() == null || intake.features() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请输入评估日期和七项模型输入");
        }
        return Envelope.success(service.recordDoctorIntake(intake.subjectId(), intake.features(),
                intake.indexDate(), (AppUserPrincipal) auth.getPrincipal()));
    }

    @PostMapping(value = "/snapshots/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Envelope<?> register(@RequestBody byte[] body, Authentication auth) {
        JsonNode payload;
        try {
            payload = protocol.parse(body);
        } catch (IOException | IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.MALFORMED_JSON);
        }
        if (payload == null || !payload.isObject()) {
            throw new BusinessException(ErrorCode.MALFORMED_JSON);
        }
        JsonNode subject = payload.path("subjectId");
        if (!subject.isIntegralNumber() || !subject.canConvertToLong() || subject.longValue() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "subjectId 须为正整数");
        }
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        JsonNode request = payload.path("request");
        if (request.isObject()) {
            var registration = service.registerAndSync(subject.longValue(), request, principal);
            return Envelope.success(new SnapshotRegistration(
                    registration.snapshotId(), registration.hostSyncStatus()));
        }
        JsonNode features = payload.path("features");
        if (!features.isObject()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "请提供 features 七变量数据");
        }
        var registration = service.registerManualAndSync(
                subject.longValue(), features,
                text(payload, "indexDate"), text(payload, "dataVersion"), text(payload, "runId"),
                principal);
        return Envelope.success(new SnapshotRegistration(
                registration.snapshotId(), registration.hostSyncStatus()));
    }

    @PostMapping(value = "/snapshots/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Envelope<?> batch(@RequestBody byte[] body, Authentication auth) {
        JsonNode payload;
        try {
            payload = protocol.parse(body);
        } catch (IOException | IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.MALFORMED_JSON);
        }
        if (payload == null || !payload.isObject() || !payload.path("subjectIds").isArray()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "subjectIds 必须为数组");
        }
        java.util.List<Long> subjectIds = new java.util.ArrayList<>();
        payload.get("subjectIds").forEach(node -> {
            if (!node.isIntegralNumber() || !node.canConvertToLong()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "subjectIds 必须为正整数");
            }
            subjectIds.add(node.longValue());
        });
        boolean evaluate = !payload.has("evaluate") || payload.path("evaluate").asBoolean(true);
        return Envelope.success(service.batch(subjectIds, evaluate, (AppUserPrincipal) auth.getPrincipal()));
    }

    @PostMapping(value = "/snapshots/batch-csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Envelope<?> modelCsv(@RequestPart("file") MultipartFile file,
                                @RequestParam(defaultValue = "true") boolean preview,
                                Authentication auth) throws IOException {
        if (file.isEmpty() || file.getSize() > 30000) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请上传不超过 30,000 字节的 CSV");
        }
        return Envelope.success(service.modelCsv(file.getBytes(), preview, (AppUserPrincipal) auth.getPrincipal()));
    }

    @GetMapping("/summary")
    public Envelope<?> summary(@RequestParam(required = false) String dataScope, Authentication auth) {
        return Envelope.success(service.summary(reportDataScope(dataScope), (AppUserPrincipal) auth.getPrincipal()));
    }

    @GetMapping("/metrics")
    public Envelope<?> metrics(@RequestParam(required = false) String dataScope, Authentication auth) {
        return Envelope.success(service.metrics(reportDataScope(dataScope), (AppUserPrincipal) auth.getPrincipal()));
    }

    @PostMapping("/snapshots/migrate")
    public Envelope<?> migrateLegacySnapshots(Authentication auth) {
        return Envelope.success(service.migrateLegacySnapshots((AppUserPrincipal) auth.getPrincipal()));
    }

    @PostMapping("/snapshots/{id}/sync")
    public Envelope<?> sync(@PathVariable long id, Authentication auth) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        return Envelope.success(new SnapshotRegistration(id, service.syncToHost(id, principal)));
    }

    @PostMapping("/snapshots/{id}/re-register")
    public Envelope<?> reRegister(@PathVariable long id, Authentication auth) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        var registration = service.reRegister(id, principal);
        return Envelope.success(new SnapshotRegistration(
                registration.snapshotId(), registration.hostSyncStatus()));
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    @GetMapping("/snapshots")
    public Envelope<?> snapshots(@RequestParam long subjectId, Authentication auth) {
        if (subjectId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return Envelope.success(service.snapshots(subjectId, (AppUserPrincipal) auth.getPrincipal()));
    }

    @GetMapping("/intake-state")
    public Envelope<?> intakeState(@RequestParam long subjectId, Authentication auth) {
        return Envelope.success(java.util.Map.of("status",
                service.intakeState(subjectId, (AppUserPrincipal) auth.getPrincipal())));
    }

    @GetMapping("/history")
    public Envelope<?> history(@RequestParam long subjectId, Authentication auth) {
        if (subjectId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return Envelope.success(service.history(subjectId, (AppUserPrincipal) auth.getPrincipal()));
    }

    @GetMapping("/archive")
    public Envelope<?> archive(@RequestParam long subjectId, Authentication auth) {
        if (subjectId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return Envelope.success(service.archive(subjectId, (AppUserPrincipal) auth.getPrincipal()));
    }

    @PostMapping("/{id}/retry")
    public Envelope<?> retry(@PathVariable long id, Authentication auth) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return Envelope.success(service.retry(id, (AppUserPrincipal) auth.getPrincipal()));
    }

    record ReviewRequest(String decision, String comment) {}

    @GetMapping("/reports")
    public Envelope<?> reports(@RequestParam(defaultValue = "ALL") String status,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int pageSize,
                               @RequestParam(required = false) String dataScope,
                               @RequestParam(required = false) Long subjectId,
                               Authentication auth) {
        if (subjectId != null && subjectId <= 0) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        return Envelope.success(service.reports(status, page, pageSize,
                reportDataScope(dataScope), subjectId, (AppUserPrincipal) auth.getPrincipal()));
    }

    @GetMapping("/reviews")
    public Envelope<?> reviews(@RequestParam(defaultValue = "PENDING") String status,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int pageSize,
                               @RequestParam(required = false) String dataScope,
                               Authentication auth) {
        return Envelope.success(service.reviewQueue(status, page, pageSize,
                reportDataScope(dataScope), (AppUserPrincipal) auth.getPrincipal()));
    }

    private static String reportDataScope(String value) {
        if (value == null || "CONTROLLED_RESEARCH".equals(value) || "CONSTRUCTED_VALIDATION".equals(value)) return value;
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "无效的数据来源");
    }

    @PostMapping(value = "/{id}/review", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Envelope<?> review(@PathVariable long id, @RequestBody ReviewRequest request, Authentication auth) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return Envelope.success(service.review(id, request.decision(), request.comment(),
                (AppUserPrincipal) auth.getPrincipal()));
    }

    record PublicationRequest(String message) {}

    @GetMapping("/{id}/patient-publication")
    public Envelope<?> patientPublication(@PathVariable long id, Authentication auth) {
        return Envelope.success(service.publication(id, (AppUserPrincipal) auth.getPrincipal()));
    }

    @PutMapping("/{id}/patient-publication")
    public Envelope<?> publishToPatient(@PathVariable long id, @RequestBody PublicationRequest request, Authentication auth) {
        return Envelope.success(service.changePublication(id, request.message(), true, (AppUserPrincipal) auth.getPrincipal()));
    }

    @DeleteMapping("/{id}/patient-publication")
    public Envelope<?> withdrawPatientPublication(@PathVariable long id, Authentication auth) {
        return Envelope.success(service.changePublication(id, null, false, (AppUserPrincipal) auth.getPrincipal()));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable long id,
                                         @RequestParam(defaultValue = "pdf") String format,
                                         Authentication auth) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        var export = exports.export(id, format, (AppUserPrincipal) auth.getPrincipal());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(export.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + export.filename() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(export.bytes());
    }

    @GetMapping("/reports/{id}")
    public Envelope<?> report(@PathVariable long id, Authentication auth) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return Envelope.success(service.report(id, (AppUserPrincipal) auth.getPrincipal()));
    }

    @GetMapping("/{id}")
    public Envelope<?> task(@PathVariable long id, Authentication auth) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return Envelope.success(service.find(id, (AppUserPrincipal) auth.getPrincipal()));
    }
}
