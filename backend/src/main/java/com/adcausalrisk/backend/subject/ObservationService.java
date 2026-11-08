package com.adcausalrisk.backend.subject;

import com.adcausalrisk.backend.common.*;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.dto.*;
import com.adcausalrisk.backend.visit.*;
import com.adcausalrisk.backend.visit.dto.VisitCreateRequest;

import jakarta.validation.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class ObservationService {
    private final VisitService visits;
    private final FeatureService features;
    private final JdbcTemplate jdbc;
    private final Validator validator;

    public ObservationService(
            VisitService visits, FeatureService features, JdbcTemplate jdbc, Validator validator) {
        this.visits = visits;
        this.features = features;
        this.jdbc = jdbc;
        this.validator = validator;
    }

    public record Input(
            String kind,
            String visitCode,
            String visitDate,
            BigDecimal mmse,
            BigDecimal moca,
            BigDecimal cdrSb,
            String featureName,
            BigDecimal featureValue,
            String recordedAt,
            String unit,
            String source,
            String missingReason) {}

    private void validate(Object input) {
        var errors =
                validator.validate(
                        input,
                        jakarta.validation.groups.Default.class,
                        com.adcausalrisk.backend.validation.ContractGroup.class);
        if (!errors.isEmpty()) throw new ConstraintViolationException(errors);
    }

    @Transactional
    public Object save(long subjectId, Input input, AppUserPrincipal actor) {
        String source = input.source() == null ? "" : input.source().trim(),
                reason = input.missingReason() == null ? "" : input.missingReason().trim();
        if (source.isEmpty() || source.length() > 200 || reason.length() > 500)
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请填写来源（至多200字）和有效缺失说明");
        boolean visit = "visit".equals(input.kind());
        if (!visit && !"feature".equals(input.kind()))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "资料类型无效");
        boolean missing =
                visit
                        ? (input.mmse() == null || input.moca() == null || input.cdrSb() == null)
                        : input.featureValue() == null;
        if (missing && reason.isEmpty())
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "缺失项须填写原因，不能用0代替");
        Object saved;
        long recordId;
        if (visit) {
            var dto =
                    new VisitCreateRequest(
                            input.visitCode(),
                            input.visitDate(),
                            input.mmse(),
                            input.moca(),
                            input.cdrSb());
            validate(dto);
            var result = visits.createVisit(subjectId, dto, actor);
            saved = result;
            recordId = result.id();
        } else {
            var dto =
                    new FeatureCreateRequest(
                            input.featureName(),
                            input.featureValue(),
                            input.recordedAt(),
                            input.unit());
            validate(dto);
            var result = features.create(subjectId, dto, actor);
            saved = result;
            recordId = result.id();
        }
        String version =
                jdbc.queryForObject(
                        "SELECT data_version FROM subject WHERE id=?", String.class, subjectId);
        jdbc.update(
                "UPDATE "
                        + (visit ? "visit" : "subject_feature")
                        + " SET source=?,missing_reason=?,data_version=? WHERE id=? AND"
                        + " subject_id=?",
                source,
                reason,
                version,
                recordId,
                subjectId);
        return saved;
    }
}
