package com.adcausalrisk.backend.subject;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;

import com.adcausalrisk.backend.common.PageResult;
import com.adcausalrisk.backend.subject.dto.SubjectCreateRequest;
import com.adcausalrisk.backend.subject.dto.SubjectDetailView;
import com.adcausalrisk.backend.subject.dto.SubjectView;
import com.adcausalrisk.backend.subject.dto.VisitResultView;
import com.adcausalrisk.backend.subject.entity.Subject;
import com.adcausalrisk.backend.subject.entity.Subject.Status;

/**
 * Hand-written Entity ↔ DTO converters for the subject module.
 *
 * <p>Kept as a plain static utility rather than a Spring bean so that callers
 * (Service, tests) can invoke it without DI. There is no MapStruct or other
 * mapping library in this project; the auth module follows the same pattern
 * with {@code AuthService.viewOf(...)}.
 */
public final class SubjectMapper {

    private SubjectMapper() {
        // utility class - no instances
    }

    // ===================================================================
    // Entity → DTO (outgoing)
    // ===================================================================

    /**
     * Maps a {@link Subject} entity to the flat {@link SubjectView} DTO that
     * appears in list items and as the top-level payload of POST /api/subjects.
     */
    public static SubjectView toView(Subject subject) {
        return new SubjectView(
                subject.getId(),
                subject.getSubjectCode(),
                subject.getAlias(),
                subject.getStatus().name(),
                subject.getDataVersion(),
                subject.getCreatedAt());
    }

    /**
     * Maps a {@link Subject} entity to {@link SubjectDetailView}.
     *
     * <p>{@code visits} is supplied by the caller, which owns the query and the ordering, so
     * that this class stays a pure Entity → DTO converter: it performs no data access and does
     * not depend on the visit entity.
     *
     * <p>{@code features} is still an empty array because the feature write endpoint has not
     * been implemented - the contract requires the key to be present (not omitted, not null).
     */
    public static SubjectDetailView toDetail(Subject subject, List<VisitResultView> visits) {
        return new SubjectDetailView(
                toView(subject),
                visits,
                List.of());
    }

    /**
     * Maps a Spring Data {@link Page} of {@link Subject} entities to the shared
     * {@link PageResult} wrapper that the contract's {@code PageSubject} schema
     * describes.
     */
    public static PageResult<SubjectView> toPage(Page<Subject> page) {
        List<SubjectView> items = page.getContent().stream()
                .map(SubjectMapper::toView)
                .toList();
        return new PageResult<>(items, page.getNumber() + 1, page.getSize(), page.getTotalElements());
    }

    // ===================================================================
    // DTO → Entity (incoming)
    // ===================================================================

    /**
     * Maps the incoming creation request to a fresh {@link Subject} entity. The
     * caller is responsible for supplying {@code dataVersion} (server-generated)
     * and {@code createdAt} (typically {@link Instant#now()}).
     *
     * <p>If the request omits {@code status}, it defaults to {@link Status#ACTIVE}.
     */
    public static Subject toEntity(SubjectCreateRequest request, String dataVersion, Instant createdAt) {
        Status status = request.status() == null || request.status().isBlank()
                ? Status.ACTIVE
                : Status.valueOf(request.status().toUpperCase());
        return new Subject(request.subjectCode(), request.alias(), status, dataVersion, createdAt);
    }
}