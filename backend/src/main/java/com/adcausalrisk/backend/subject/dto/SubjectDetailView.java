package com.adcausalrisk.backend.subject.dto;

import java.util.List;

/**
 * Response shape for {@code GET /api/subjects/{subjectId}} - the frozen contract schema {@code
 * SubjectDetail}.
 *
 * <p>Both collections are required by the contract and must be present in every response. {@code
 * visits} is populated from the {@code visit} table, ordered by visit date and then by id (the
 * contract does not prescribe an order for that array). Both collections are populated only when
 * the caller may read raw observations on this subject.
 */
public record SubjectDetailView(
        SubjectView subject, List<VisitResultView> visits, List<FeatureResultView> features) {}
