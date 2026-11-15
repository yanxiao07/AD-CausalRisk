package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/research/table-assessment-tasks")
public class ResearchTableAssessmentController {
    private final ResearchTableAssessmentService service;
    private final ResearchTableProtocol protocol;

    ResearchTableAssessmentController(ResearchTableAssessmentService service, ResearchTableProtocol protocol) {
        this.service = service;
        this.protocol = protocol;
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
        if (request == null || !request.isObject() || request.size() != 1) {
            throw new BusinessException(ErrorCode.MALFORMED_JSON);
        }
        JsonNode id = request.path("snapshotId");
        if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "snapshotId 须为正整数");
        }
        return Envelope.success(service.submit(id.longValue(), key, (AppUserPrincipal) auth.getPrincipal()));
    }

    record SnapshotRegistration(long snapshotId, String hostSyncStatus) {}

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

    @GetMapping("/{id}")
    public Envelope<?> task(@PathVariable long id, Authentication auth) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return Envelope.success(service.find(id, (AppUserPrincipal) auth.getPrincipal()));
    }
}
