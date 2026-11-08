package com.adcausalrisk.backend.subject;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.dto.FeatureCreateRequest;
import com.adcausalrisk.backend.subject.dto.FeatureResultView;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * Writes one {@code subject_feature} row.
 *
 * <p>{@code subject_feature} has no JPA entity yet, so the insert is a direct JDBC statement; the
 * column set matches V1 exactly (feature_name check constraint, nullable feature_value, recorded_at
 * DATE, unit check constraint). The APOE / MRI rules from the contract's {@code if/then/else} are
 * applied here so the database CHECK never has to guess.
 */
@Service
public class FeatureService {

    private static final String APOE = "APOE_E4_COUNT";
    private static final Set<String> FEATURE_NAMES =
            Set.of(APOE, "MRI_HIPPOCAMPUS_L", "MRI_HIPPOCAMPUS_R");
    private static final String UNIT_COUNT = "count";
    private static final String UNIT_NORMALIZED = "normalized";

    private static final String INSERT_SQL =
            "INSERT INTO subject_feature (subject_id, feature_name, feature_value, recorded_at,"
                    + " unit) VALUES (?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final SubjectRepository subjectRepository;
    private final SubjectAccessPolicy accessPolicy;

    public FeatureService(
            JdbcTemplate jdbc,
            SubjectRepository subjectRepository,
            SubjectAccessPolicy accessPolicy) {
        this.jdbc = jdbc;
        this.subjectRepository = subjectRepository;
        this.accessPolicy = accessPolicy;
    }

    /** {@code POST /api/subjects/{subjectId}/features}. */
    @org.springframework.transaction.annotation.Transactional
    public FeatureResultView create(
            Long subjectId, FeatureCreateRequest request, AppUserPrincipal principal) {
        if (!accessPolicy.canCreate(principal) || !accessPolicy.canManage(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权录入特征");
        }
        if (!subjectRepository.existsById(subjectId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "档案不存在");
        }

        String featureName = request.featureName().trim();
        if (!FEATURE_NAMES.contains(featureName)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "featureName 仅支持 APOE_E4_COUNT / MRI_HIPPOCAMPUS_L / MRI_HIPPOCAMPUS_R");
        }
        String unit = request.unit().trim();
        LocalDate recordedAt = parseDate(request.recordedAt());
        BigDecimal value = request.featureValue();
        validateValue(featureName, unit, value);

        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement statement =
                            connection.prepareStatement(INSERT_SQL, new String[] {"id"});
                    statement.setLong(1, subjectId);
                    statement.setString(2, featureName);
                    if (value == null) {
                        statement.setNull(3, Types.DECIMAL);
                    } else {
                        statement.setBigDecimal(3, value);
                    }
                    statement.setObject(4, recordedAt);
                    statement.setString(5, unit);
                    return statement;
                },
                keys);

        Number generated = keys.getKey();
        if (generated == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "特征写入未返回主键");
        }
        String version = InputVersions.advance(jdbc, subjectId);
        jdbc.update(
                "UPDATE subject_feature SET data_version=?,source='manual' WHERE id=?",
                version,
                generated.longValue());
        return new FeatureResultView(
                generated.longValue(),
                subjectId,
                featureName,
                value == null ? null : value.doubleValue(),
                recordedAt,
                unit);
    }

    private static LocalDate parseDate(String recordedAt) {
        try {
            return LocalDate.parse(recordedAt.trim());
        } catch (DateTimeParseException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "recordedAt 不是有效日期");
        }
    }

    private static void validateValue(String featureName, String unit, BigDecimal value) {
        boolean apoe = APOE.equals(featureName);
        String expectedUnit = apoe ? UNIT_COUNT : UNIT_NORMALIZED;
        if (!expectedUnit.equals(unit)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, featureName + " 的 unit 必须是 " + expectedUnit);
        }
        if (value == null) {
            return;
        }
        if (apoe) {
            if (value.stripTrailingZeros().scale() > 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "APOE_E4_COUNT 必须是整数");
            }
            if (value.compareTo(BigDecimal.ZERO) < 0
                    || value.compareTo(BigDecimal.valueOf(2)) > 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "APOE_E4_COUNT 必须在 0-2 之间");
            }
            return;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, featureName + " 必须在 0-1 之间");
        }
    }
}
