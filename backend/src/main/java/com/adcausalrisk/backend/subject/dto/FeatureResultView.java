package com.adcausalrisk.backend.subject.dto;

import java.time.LocalDate;

/**
 * Response item shape for {@code FeatureResult}. The frozen contract requires this type as the
 * element of {@code SubjectDetail.features}, which must always be an array (empty or populated).
 *
 * <p>All six fields match the contract:
 *
 * <pre>id, subjectId, featureName (enum), featureValue (number|null),
 * recordedAt (date), unit (enum)</pre>
 *
 * <p>Feature creation and subject detail return persisted feature values. Source, missingness and
 * version metadata are available through the separate feature-sources endpoint.
 */
public record FeatureResultView(
        Long id,
        Long subjectId,
        String featureName,
        Double featureValue,
        LocalDate recordedAt,
        String unit) {}
