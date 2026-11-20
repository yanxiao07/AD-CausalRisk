package com.adcausalrisk.backend.visit;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.adcausalrisk.backend.subject.dto.VisitResultView;
import com.adcausalrisk.backend.visit.dto.VisitCreateRequest;
import com.adcausalrisk.backend.visit.entity.Visit;

/**
 * Hand-written Entity ↔ DTO converters for the visit module, following the same style as
 * {@link com.adcausalrisk.backend.subject.SubjectMapper}: a plain static utility rather than
 * a Spring bean, and no mapping library anywhere in this project.
 */
public final class VisitMapper {

    /** Clinical validity ceilings shared with the QC engine and CSV importer. */
    private static final BigDecimal MMSE_CEIL = new BigDecimal("30");
    private static final BigDecimal MOCA_CEIL = new BigDecimal("30");
    private static final BigDecimal CDR_SB_CEIL = new BigDecimal("18");

    private VisitMapper() {
        // utility class - no instances
    }

    /**
     * Builds a fresh entity for one subject. {@code id} is owned by the database, and
     * {@code subjectId} comes from the request path rather than the body.
     *
     * <p>{@code visitDate} arrives already parsed: the request carries the raw text so that
     * {@code @Pattern} can enforce the contract's {@code ^\d{4}-\d{2}-\d{2}$}, and the service
     * owns turning it into a date (and reporting an impossible one as a 400). Parsing here would
     * hide that failure inside a mapper.
     *
     * <p>Scores are passed through <b>unchanged</b>. The entity derives both stored forms from
     * them - the raw text and the rounded {@code DECIMAL(5,2)} display column - so this mapper
     * must not pre-round or normalise anything.
     */
    public static Visit toEntity(Long subjectId, VisitCreateRequest request, LocalDate visitDate) {
        return new Visit(
                subjectId,
                request.visitCode(),
                visitDate,
                request.mmse(),
                request.moca(),
                request.cdrSb(),
                request.modelInputs());
    }

    /**
     * Maps a {@link Visit} to the contract's {@code VisitResult} shape, which is also the
     * element type of {@code SubjectDetail.visits}.
     *
     * <p>Scores come from the {@code raw_*} text columns, which hold the value exactly as it was
     * submitted, so a client that sent 27.555 reads back 27.555 - not the 27.56 kept in the
     * derived DECIMAL column. Rows written before migration {@code V4} have no raw value and fall
     * back to that derived column rather than reporting {@code null}. A {@code NULL} score stays
     * JSON null; it is never reported as 0.
     *
     * <h3>P1 Python contract mask</h3>
     * Any score that exceeds the clinical validity ceiling (MMSE &gt; 30, MoCA &gt; 30,
     * CDR-SB &gt; 18) is masked to {@code null} before the DTO is returned. This covers
     * both {@code POST /api/subjects/{id}/visits} responses and
     * {@code GET /api/subjects/{id} → SubjectDetail.visits}. Out-of-range values exist in the
     * database because the QC-tolerant {@code X-Allow-OOR: true} path and CSV import both skip
     * the {@code @DecimalMax} DTO guard — but the frozen Python contract forbids callers from
     * ever receiving them back over a normal GET/POST response. QC operators see the raw values
     * through a separate path ({@code QcListView}) that is explicitly gated behind role checks.
     */
    public static VisitResultView toResultView(Visit visit) {
        return new VisitResultView(
                visit.getId(),
                visit.getSubjectId(),
                visit.getVisitCode(),
                visit.getVisitDate(),
                maskOutOfRange(exactScore(visit.getRawMmse(), visit.getMmse()), MMSE_CEIL),
                maskOutOfRange(exactScore(visit.getRawMoca(), visit.getMoca()), MOCA_CEIL),
                maskOutOfRange(exactScore(visit.getRawCdrSb(), visit.getCdrSb()), CDR_SB_CEIL),
                visit.getModelInputs());
    }

    /**
     * Returns {@code raw} when it is within [0, {@code ceil}], otherwise {@code null}.
     * {@code null} input stays {@code null} — a missing score is never promoted to out-of-range.
     */
    private static BigDecimal maskOutOfRange(BigDecimal value, BigDecimal ceil) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(ceil) > 0) {
            return null;
        }
        return value;
    }

    /** Raw text first (authoritative), then the derived DECIMAL column, then {@code null}. */
    private static BigDecimal exactScore(String raw, BigDecimal derived) {
        return raw != null ? new BigDecimal(raw) : derived;
    }
}
