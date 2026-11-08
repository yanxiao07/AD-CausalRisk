package com.adcausalrisk.backend.qc;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.common.PageResult;
import com.adcausalrisk.backend.qc.dto.QcListQueryRequest;
import com.adcausalrisk.backend.qc.dto.QcListView;
import com.adcausalrisk.backend.qc.dto.QcStatsView;
import com.adcausalrisk.backend.qc.dto.QcSubmitRequest;
import com.adcausalrisk.backend.qc.entity.QcAuditRecord;
import com.adcausalrisk.backend.qc.entity.QcAuditRecord.SourceType;
import com.adcausalrisk.backend.qc.entity.QcAuditRecord.Status;
import com.adcausalrisk.backend.qc.entity.QcAuditRecord.TargetType;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectAccessPolicy;
import com.adcausalrisk.backend.subject.SubjectRepository;
import com.adcausalrisk.backend.visit.VisitCreatedEvent;
import com.adcausalrisk.backend.visit.VisitRepository;
import com.adcausalrisk.backend.visit.entity.Visit;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Business-logic service for the QC audit module.
 *
 * <p>Four entry points map to the four UI actions the frozen contract exposes:
 *
 * <ol>
 *   <li>{@link #generateQcRecord} — called automatically after a visit or subject is saved; creates
 *       a PENDING row when simple validation rules fire.
 *   <li>{@link #getQcList} — paged list with optional subject/aliases/status filters.
 *   <li>{@link #submitAudit} — the auditor's APPROVED / REJECTED decision.
 *   <li>{@link #getQcStats} — dashboard counts per status bucket.
 * </ol>
 *
 * <p><b>Deliberate design choices</b> worth flagging up front:
 *
 * <ul>
 *   <li>No {@code @ManyToOne} navigations — {@link QcAuditRecord} only holds bare foreign-key
 *       {@code Long}s. The list joins names via {@link QcAuditRecordRepository#findJoined}.
 *   <li>No setter methods on the Entity — mutation goes through {@link
 *       QcAuditRecord#submitDecision} and {@link QcAuditRecord#updateComment}, which are
 *       atomically-tested business transitions.
 *   <li>REJECTED does not flip a flag on the Visit/Subject row — neither table has a QC status
 *       column today. Instead, a REJECTED audit row <em>is</em> the flag: the application's write
 *       paths (visit create, import commit, assessment submit) are expected to check {@code
 *       findTopByVisitIdOrderByCreatedAtDesc} and refuse to mutate until the REJECTED row is
 *       resolved. This avoids a schema migration just to store a flag we already store.
 * </ul>
 */
@Service
public class QcAuditService {

    private static final Logger log = LoggerFactory.getLogger(QcAuditService.class);

    /** MMSE valid range per clinical convention. */
    private static final double MMSE_MAX = 30.0;

    /** MoCA valid range. */
    private static final double MOCA_MAX = 30.0;

    /** CDR-SB valid range. */
    private static final double CDR_SB_MAX = 18.0;

    /** Pages beyond 100 rows are capped at 100 by {@link SubjectService} too. */
    private static final int MAX_PAGE_SIZE = 100;

    /** Roles allowed to submit audit decisions (APPROVED / REJECTED). */
    private static final Set<String> AUDITOR_ROLES = Set.of("ADMIN", "RESEARCHER");

    private final QcAuditRecordRepository qcAuditRecordRepository;
    private final VisitRepository visitRepository;
    private final SubjectRepository subjectRepository;
    private final SubjectAccessPolicy subjectAccessPolicy;
    private final ObjectMapper objectMapper;

    public QcAuditService(
            QcAuditRecordRepository qcAuditRecordRepository,
            VisitRepository visitRepository,
            SubjectRepository subjectRepository,
            SubjectAccessPolicy subjectAccessPolicy,
            ObjectMapper objectMapper) {
        this.qcAuditRecordRepository = qcAuditRecordRepository;
        this.visitRepository = visitRepository;
        this.subjectRepository = subjectRepository;
        this.subjectAccessPolicy = subjectAccessPolicy;
        this.objectMapper = objectMapper;
    }

    // ===================================================================
    //  0. AFTER_COMMIT listener — decoupled QC trigger
    // ===================================================================

    /**
     * Spring event listener that fires only after {@code VisitService.createVisit} has
     * <b>successfully committed</b> its transaction. The listener itself runs in a fresh {@link
     * Propagation#REQUIRES_NEW} transaction on a brand-new physical connection, so any QC failure
     * (FK violation, constraint, SQL error, anything) stays confined to this new transaction — the
     * visit row that was just created is already durably on disk and can never be rolled back by a
     * QC accident.
     *
     * <p>This replaces the earlier approach of calling {@link #generateQcRecord} directly inside
     * {@code VisitService} wrapped in a try/catch. That attempt failed because Spring/JPA still
     * marked the shared transaction {@code rollback-only} even when the exception was caught, or
     * raised {@code UnexpectedRollbackException} at commit time. AFTER_COMMIT + REQUIRES_NEW is the
     * canonical Spring pattern for "side-effect work that must never poison the primary
     * transaction".
     *
     * <p>Loads a fresh, attached {@link Visit} from its own session before delegating to {@link
     * #generateQcRecord}, because the entity reference that was saved in the publisher's session is
     * detached by the time this listener runs.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onVisitCreated(VisitCreatedEvent event) {
        log.info(
                "AFTER_COMMIT QC trigger for visit id={} operatorId={}",
                event.visitId(),
                event.operatorId());
        try {
            Visit visit =
                    visitRepository
                            .findById(event.visitId())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Visit "
                                                            + event.visitId()
                                                            + " disappeared between commit and QC"
                                                            + " listener — DB consistency issue"));
            generateQcRecord(visit, event.operatorId(), null);
        } catch (Exception qcFailure) {
            // REQUIRES_NEW: this catch is belt-and-suspenders. The transaction will
            // roll back regardless (it's the REQUIRES_NEW one, not the Visit's), but we
            // swallow here so an uncaught Spring event listener exception doesn't propagate
            // up through whatever scheduled/async mechanism fired the commit.
            log.error(
                    "QC listener failed for visit id={} — visit is safely committed, audit row NOT"
                            + " created",
                    event.visitId(),
                    qcFailure);
        }
    }

    // ===================================================================
    //  1. Generate QC records  (core logic — called by the listener above)
    // ===================================================================

    /**
     * Runs the simple built-in QC rules against a visit and inserts a PENDING row when any of them
     * fire.
     *
     * <p>The caller is expected to provide a transactional context. Today this is the {@link
     * #onVisitCreated} listener, which runs in {@code REQUIRES_NEW}. The method is public so import
     * pipelines and manual QC endpoints can also invoke it directly — those callers own their own
     * transaction boundaries.
     *
     * <p>The rules are intentionally simple — this is not the Python rule engine. It catches
     * obvious data-entry mistakes before a human ever opens the review queue. The {@code
     * operatorId} passed in is the person whose action triggered the check (e.g. the import
     * operator), not the person who will eventually review it.
     *
     * <p>Rules that currently fire:
     *
     * <ul>
     *   <li>{@code mmse} is present but exceeds 30 (or negative)
     *   <li>{@code moca} is present but exceeds 30 (or negative)
     *   <li>{@code cdr_sb} is present but exceeds 18 (or negative)
     *   <li>{@code mmse} <b>and</b> {@code moca} are both null — every AD visit should record at
     *       least one cognitive score
     *   <li>{@code visitDate} is after today — future dates are suspicious
     * </ul>
     *
     * <p>Idempotent: if a PENDING row already exists for this visit, we skip insertion.
     */
    public void generateQcRecord(Visit visit, Long operatorId, Long qualityCheckId) {
        BigDecimal mmse = originalScoreOf(visit.getRawMmse(), visit.getMmse());
        BigDecimal moca = originalScoreOf(visit.getRawMoca(), visit.getMoca());
        BigDecimal cdrSb = originalScoreOf(visit.getRawCdrSb(), visit.getCdrSb());

        log.info(
                "QC check triggered for visit id={} visitCode={} subjectId={} mmse={} moca={}"
                        + " cdrSb={} visitDate={}",
                visit.getId(),
                visit.getVisitCode(),
                visit.getSubjectId(),
                mmse,
                moca,
                cdrSb,
                visit.getVisitDate());

        List<String> anomalies = new ArrayList<>();

        if (mmse != null
                && (mmse.compareTo(BigDecimal.ZERO) < 0
                        || mmse.compareTo(BigDecimal.valueOf(MMSE_MAX)) > 0)) {
            anomalies.add("MMSE 得分 " + mmse + " 超出有效范围 0-" + MMSE_MAX);
        }
        if (moca != null
                && (moca.compareTo(BigDecimal.ZERO) < 0
                        || moca.compareTo(BigDecimal.valueOf(MOCA_MAX)) > 0)) {
            anomalies.add("MoCA 得分 " + moca + " 超出有效范围 0-" + MOCA_MAX);
        }
        if (cdrSb != null
                && (cdrSb.compareTo(BigDecimal.ZERO) < 0
                        || cdrSb.compareTo(BigDecimal.valueOf(CDR_SB_MAX)) > 0)) {
            anomalies.add("CDR-SB 得分 " + cdrSb + " 超出有效范围 0-" + CDR_SB_MAX);
        }
        if (mmse == null && moca == null) {
            anomalies.add("MMSE 和 MoCA 得分均缺失，至少应记录一项认知评估");
        }
        if (visit.getVisitDate() != null && visit.getVisitDate().isAfter(LocalDate.now())) {
            anomalies.add("访视日期 " + visit.getVisitDate() + " 晚于今日，请核对录入是否正确");
        }

        if (anomalies.isEmpty()) {
            log.info("QC passed clean for visit id={} — no rules fired", visit.getId());
            return;
        }

        log.info(
                "QC flagged {} anomaly(ies) for visit id={}: {}",
                anomalies.size(),
                visit.getId(),
                anomalies);

        // Idempotency guard — a PENDING row for this visit already covers it.
        if (qcAuditRecordRepository.existsByVisitIdAndVisitIdIsNotNull(visit.getId())) {
            log.debug(
                    "QC rule fired for visit {} but a PENDING row already exists, skipping",
                    visit.getId());
            return;
        }

        Instant now = Instant.now();
        QcAuditRecord record =
                new QcAuditRecord(
                        visit.getSubjectId(),
                        visit.getId(),
                        TargetType.VISIT,
                        qualityCheckId != null
                                ? SourceType.AUTO_QC_TRIGGER
                                : SourceType.MANUAL_REVIEW,
                        operatorId,
                        qualityCheckId,
                        toJson(anomalies),
                        now);

        qcAuditRecordRepository.save(record);
        log.info(
                "Created QC audit record id={} for visit id={}, {} anomaly(ies)",
                record.getId(),
                visit.getId(),
                anomalies.size());
    }

    // ===================================================================
    //  2. Paged list  (used by the QC dashboard / queue)
    // ===================================================================

    /**
     * One page of joined QC audit rows. Filters from {@link QcListQueryRequest} are optional;
     * {@code page} and {@code pageSize} are passed as raw ints from the controller because the
     * service owns the clamp-to-[1,100] business rule.
     *
     * <p><b>Row-level visibility</b> — {@link SubjectAccessPolicy} is consulted before the query
     * runs: ADMIN / RESEARCHER see every QC row; DOCTOR / PATIENT see only rows whose subject they
     * hold at least one grant on. Users with zero grants receive an empty page immediately, without
     * any repository call.
     */
    @Transactional(readOnly = true)
    public PageResult<QcListView> getQcList(
            QcListQueryRequest request, int page, int pageSize, AppUserPrincipal principal) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));

        Pageable pageable = PageRequest.of(safePage - 1, safePageSize);
        Status statusFilter = null;
        if (request.status() != null && !request.status().isBlank()) {
            try {
                statusFilter = Status.valueOf(request.status());
            } catch (IllegalArgumentException ignored) {
                statusFilter = null;
            }
        }

        Page<QcJoinedRow> paged;
        if (subjectAccessPolicy.hasFullVisibility(principal)) {
            paged =
                    qcAuditRecordRepository.findJoined(
                            request.subjectCode(), request.subjectAlias(), statusFilter, pageable);
        } else {
            Set<Long> grantedIds = subjectAccessPolicy.grantedSubjectIds(principal);
            if (grantedIds.isEmpty()) {
                paged = Page.empty(pageable);
            } else {
                paged =
                        qcAuditRecordRepository.findJoinedBySubjectIds(
                                grantedIds,
                                request.subjectCode(),
                                request.subjectAlias(),
                                statusFilter,
                                pageable);
            }
        }

        List<QcListView> items = paged.getContent().stream().map(this::toView).toList();

        items =
                items.stream()
                        .map(
                                view -> {
                                    var roles = principal.roleCodes();
                                    boolean isPatient = roles.contains("PATIENT");
                                    boolean auditor =
                                            roles.stream().anyMatch(AUDITOR_ROLES::contains);
                                    return (!isPatient
                                                    && auditor
                                                    && subjectAccessPolicy.canReadRawScores(
                                                            principal, view.subjectId()))
                                            ? view
                                            : maskSensitiveFields(view);
                                })
                        .toList();

        return new PageResult<>(items, safePage, safePageSize, paged.getTotalElements());
    }

    // ===================================================================
    //  3. Submit audit decision  (APPROVED / REJECTED)
    // ===================================================================

    /**
     * The auditor submits a decision on a PENDING row.
     *
     * <p>Flow:
     *
     * <ol>
     *   <li>Role check — only {@code ADMIN} and {@code RESEARCHER} may submit audit decisions.
     *       {@code DOCTOR} / {@code PATIENT} receive ACCESS_DENIED (403).
     *   <li>Load the row — 400 if missing (business resource, not 404).
     *   <li>Status must be PENDING; APPROVED/REJECTED rows cannot be re-decided.
     *   <li>Call {@link QcAuditRecord#submitDecision} which atomically sets the five mutating
     *       fields and throws if the preconditions are somehow violated again.
     *   <li>Persist and return the joined view.
     * </ol>
     *
     * <p><b>REJECTED & the "needs re-entry" flag</b> — as noted in the class javadoc, neither
     * {@code Visit} nor {@code Subject} carries a QC status column today. The REJECTED row itself
     * <em>is</em> the flag: callers that write clinical data (visit create, import commit,
     * assessment submit) must consult {@link VisitRepository} + this repository to refuse mutations
     * when the target visit has an unresolved REJECTED audit. Adding a dedicated {@code
     * needs_reentry} flag to {@code visit} is a separate schema migration — the flag would
     * duplicate what we already store.
     */
    @Transactional
    public QcListView submitAudit(Long id, QcSubmitRequest request, AppUserPrincipal principal) {
        if (principal.roleCodes().contains("PATIENT")
                || principal.roleCodes().stream().noneMatch(AUDITOR_ROLES::contains)) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED, "无质控审核权限，仅 ADMIN / RESEARCHER 可以提交审核结果");
        }

        var record =
                qcAuditRecordRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.VALIDATION_ERROR, "质控记录不存在: " + id));
        if (!subjectAccessPolicy.canManage(principal, record.getSubjectId()))
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权审核该档案的数据");

        int rows =
                qcAuditRecordRepository.updateDecision(
                        id,
                        QcAuditRecord.Status.valueOf(request.status()),
                        request.abnormalItems(),
                        request.auditComment(),
                        principal.userId(),
                        Instant.now());

        if (rows == 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "质控记录 " + id + " 已被其他审核员处理，不可重复提交");
        }

        log.info(
                "QC audit id={} submitted as {} by userId={} role={}",
                id,
                request.status(),
                principal.userId(),
                principal.roleCodes());

        return qcAuditRecordRepository
                .findJoinedById(id)
                .map(this::toView)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Joined row disappeared after update for id=" + id));
    }

    // ===================================================================
    //  4. Dashboard stats
    // ===================================================================

    /**
     * Aggregates row counts per status bucket. Always returns all three buckets — buckets with zero
     * rows default to 0 instead of being omitted, so the frontend never sees a missing key.
     *
     * <p><b>Row-level visibility</b> — same rule as {@link #getQcList}: ADMIN / RESEARCHER see
     * totals for the whole queue; DOCTOR / PATIENT see totals scoped to subjects they hold grants
     * on. Even summary counts leak information when a clinician should only see their own patients.
     */
    @Transactional(readOnly = true)
    public QcStatsView getQcStats(AppUserPrincipal principal) {
        List<Object[]> rows;
        if (subjectAccessPolicy.hasFullVisibility(principal)) {
            rows = qcAuditRecordRepository.groupByStatus();
        } else {
            Set<Long> grantedIds = subjectAccessPolicy.grantedSubjectIds(principal);
            rows =
                    grantedIds.isEmpty()
                            ? List.of()
                            : qcAuditRecordRepository.groupByStatusBySubjectIds(grantedIds);
        }

        Map<String, Long> counts =
                rows.stream()
                        .collect(
                                Collectors.toMap(
                                        arr -> ((Status) arr[0]).name(),
                                        arr -> (Long) arr[1],
                                        (a, b) -> a));

        return new QcStatsView(
                counts.getOrDefault(Status.PENDING.name(), 0L),
                counts.getOrDefault(Status.APPROVED.name(), 0L),
                counts.getOrDefault(Status.REJECTED.name(), 0L));
    }

    // ===================================================================
    //  Internal helpers
    // ===================================================================

    private QcListView toView(QcJoinedRow row) {
        return new QcListView(
                row.getId(),
                row.getSubjectId(),
                row.getVisitId(),
                row.getSubjectCode(),
                row.getSubjectAlias(),
                row.getVisitCode(),
                row.getTargetType(),
                row.getSourceType(),
                row.getStatus(),
                row.getAbnormalItems(),
                row.getAuditComment(),
                row.getAuditorName(),
                row.getAuditedAt(),
                row.getCreatedAt());
    }

    /**
     * Field-level masking for non-auditor roles ({@code DOCTOR}, {@code PATIENT}).
     *
     * <p>Subject-level filtering already restricts which rows they see, but even the rows they are
     * allowed to see must not leak clinical details or internal audit notes — the frozen contract
     * treats scores and comments as privileged clinical data that only ADMIN / RESEARCHER can
     * inspect. This mirrors how {@code SubjectApiTest} omits visit scores from the patient-facing
     * subject detail payload.
     *
     * <p>Fields nulled out: {@code abnormalItems}, {@code auditComment}. Fields preserved: {@code
     * status}, IDs, codes, timestamps, targetType/sourceType — enough for the frontend to render
     * audit-progress UI without exposing PHI.
     */
    private QcListView maskSensitiveFields(QcListView src) {
        return new QcListView(
                src.id(),
                src.subjectId(),
                src.visitId(),
                src.subjectCode(),
                src.subjectAlias(),
                src.visitCode(),
                src.targetType(),
                src.sourceType(),
                src.status(),
                null,
                null,
                src.auditorName(),
                src.auditedAt(),
                src.createdAt());
    }

    /**
     * Converts a list of anomaly strings to a JSON array for the TEXT column. Returns {@code null}
     * (not "[]") when the list is empty, so downstream code can distinguish "no anomalies" from
     * "has anomalies".
     */
    private String toJson(List<String> anomalies) {
        if (anomalies == null || anomalies.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(anomalies);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize anomalies to JSON, falling back to comma-joined text", e);
            return String.join(", ", anomalies);
        }
    }

    /**
     * Resolves the authoritative score for QC range checks.
     *
     * <p>{@code rawText} (from {@code raw_mmse}/{@code raw_moca}/{@code raw_cdr_sb}, added by
     * migration {@code V4}) is the exact submitted value — a raw {@code BigDecimal.toString()} that
     * preserves full precision. QC must use it, never the rounded DECIMAL column, otherwise {@code
     * 30.004} silently becomes {@code 30.00} and the out-of-range rule never fires.
     *
     * <p>When {@code rawText} is {@code null} (rows written before V4, backfilled from the
     * already-rounded DECIMAL columns), we fall back to {@code derived} so old rows still get
     * checked rather than treated as missing.
     */
    private static BigDecimal originalScoreOf(String rawText, BigDecimal derived) {
        if (rawText == null) {
            return derived;
        }
        try {
            return new BigDecimal(rawText.trim());
        } catch (NumberFormatException parseFailure) {
            log.warn(
                    "Could not parse raw score text '{}', falling back to derived column", rawText);
            return derived;
        }
    }
}
