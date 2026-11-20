package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.qualitycheck.CurrentArchiveQuality;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

@Service
class DoctorAssessmentRequests {
    private static final int PAGE_SIZE = 20;
    private static final String CONTROLLED = "CONTROLLED_RESEARCH";
    private static final String CONSTRUCTED = "CONSTRUCTED_VALIDATION";
    private static final String REQUESTS = """
            FROM controlled_assessment_request a JOIN subject s ON s.id=a.subject_id
            LEFT JOIN research_table_task t ON t.snapshot_id=a.snapshot_id
            """;
    private static final String LATEST = """
             NOT EXISTS (SELECT 1 FROM controlled_assessment_request newer
                         WHERE newer.subject_id=a.subject_id AND newer.id>a.id)
            """;
    private static final String ROW = """
            SELECT a.id,a.subject_id,a.quality_check_id,a.snapshot_id,s.subject_code,
                   s.alias,t.id AS task_id,t.status,t.review_status
            """;

    record Status(String dataScope, String qualityStatus, String state, Long requestId, Long taskId) {}
    record QueueItem(long id, long subjectId, String subjectCode, String alias, String state,
                     Long taskId, boolean canExecute) {}
    record Queue(List<QueueItem> items, long total, int page, int pageSize) {}
    private record Archive(boolean active, boolean controlled) {}
    private record Requested(long id, long subjectId, long qualityId, Long snapshotId, String subjectCode,
                             String alias, Long taskId, String status, String reviewStatus) {}
    private record Candidate(long id, String wire, String sync, Long taskId,
                             String status, String reviewStatus) {}
    private record Dispatch(long snapshotId, long subjectId, long qualityId) {}

    private final JdbcTemplate jdbc;
    private final CurrentArchiveQuality quality;
    private final ResearchTableAssessmentService assessments;
    private final ControlledResearchCompletion permission;
    private final ResearchTableClient client;
    private final ResearchTableProtocol protocol;
    private final TransactionTemplate transaction;

    DoctorAssessmentRequests(JdbcTemplate jdbc, CurrentArchiveQuality quality,
            ResearchTableAssessmentService assessments, ControlledResearchCompletion permission,
            ResearchTableProtocol protocol, ResearchTableClient client, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.quality = quality;
        this.assessments = assessments;
        this.permission = permission;
        this.protocol = protocol;
        this.client = client;
        this.transaction = new TransactionTemplate(manager);
    }

    Status view(long subjectId, AppUserPrincipal actor) {
        requireDoctor(subjectId, actor);
        return view(subjectId, actor, archive(subjectId));
    }

    private Status view(long subjectId, AppUserPrincipal actor, Archive archive) {
        var gate = quality.forSubject(subjectId);
        String qualityStatus = archive.active() ? gate.status() : "ARCHIVED";
        if (archive.controlled()) {
            var row = latestRequest(subjectId);
            if (row != null) {
                boolean current = archive.active() && gate.ready() && Objects.equals(gate.id(), row.qualityId());
                if (!current && archive.active() && gate.ready()) {
                    return new Status(CONTROLLED, qualityStatus, "READY", null, null);
                }
                String state = row.taskId() != null ? taskState(row.status(), row.reviewStatus())
                        : current ? "RESEARCH_REVIEW" : "NEEDS_QUALITY";
                return new Status(CONTROLLED, qualityStatus, state, row.id(), row.taskId());
            }
            return new Status(CONTROLLED, qualityStatus, archive.active() && gate.ready() ? "READY" : "NEEDS_QUALITY",
                    null, null);
        }
        if (!archive.active() || !gate.ready()) {
            return new Status(CONSTRUCTED, qualityStatus, "NEEDS_QUALITY", null, null);
        }
        Candidate snapshot = fixture(subjectId, gate, actor.userId());
        if (snapshot == null) return new Status(CONSTRUCTED, qualityStatus, "NEEDS_SNAPSHOT", null, null);
        String state = snapshot.taskId() != null ? taskState(snapshot.status(), snapshot.reviewStatus())
                : "SYNCED".equals(snapshot.sync()) ? "READY" : "NEEDS_SYNC";
        return new Status(CONSTRUCTED, qualityStatus, state, null, snapshot.taskId());
    }

    Status request(long subjectId, AppUserPrincipal actor) {
        requireDoctor(subjectId, actor);
        Archive archive = archive(subjectId);
        if (archive.controlled()) {
            transaction.executeWithoutResult(ignored -> {
                lockArchive(subjectId);
                requireDoctor(subjectId, actor);
                var gate = quality.forSubject(subjectId);
                if (!archive(subjectId).active() || !gate.ready()) throw needsQuality();
                var previous = latestRequest(subjectId);
                if (previous != null && Objects.equals(previous.qualityId(), gate.id())) return;
                jdbc.update("""
                        INSERT INTO controlled_assessment_request(subject_id,requested_by,quality_check_id,requested_at)
                        VALUES(?,?,?,CURRENT_TIMESTAMP)
                        """, subjectId, actor.userId(), gate.id());
            });
        } else {
            var gate = quality.forSubject(subjectId);
            if (!archive.active() || !gate.ready()) throw needsQuality();
            var snapshot = fixture(subjectId, gate, actor.userId());
            if (snapshot == null) throw conflict("请先为当前访视保存测试模型资料");
            if (snapshot.taskId() == null) assessments.submitDoctorFixture(snapshot.id(), subjectId, actor);
        }
        return view(subjectId, actor);
    }

    Queue queue(int page, AppUserPrincipal actor) {
        requireResearcher(actor);
        int currentPage = Math.max(1, page);
        String visible = REQUESTS + " WHERE " + LATEST + """
                AND EXISTS (SELECT 1 FROM subject_permission p WHERE p.subject_id=a.subject_id AND p.user_id=?
                            AND p.permission_type IN ('VIEW','MANAGE'))
                """;
        long total = jdbc.queryForObject("SELECT COUNT(*) " + visible, Long.class, actor.userId());
        var rows = jdbc.query(ROW + visible + " ORDER BY a.id DESC LIMIT ? OFFSET ?", this::readRequest,
                actor.userId(), PAGE_SIZE, (long) (currentPage - 1) * PAGE_SIZE);
        return new Queue(rows.stream().map(row -> {
            var gate = quality.forSubject(row.subjectId());
            boolean current = gate.ready() && Objects.equals(gate.id(), row.qualityId());
            boolean ready = row.taskId() == null && current && approvedSnapshot(row, actor.userId(), gate) != null;
            String state = row.taskId() != null ? taskState(row.status(), row.reviewStatus())
                    : current ? "RESEARCH_REVIEW" : "NEEDS_QUALITY";
            return new QueueItem(row.id(), row.subjectId(), row.subjectCode(), row.alias(), state, row.taskId(), ready);
        }).toList(), total, currentPage, PAGE_SIZE);
    }

    QueueItem execute(long id, AppUserPrincipal actor) {
        requireResearcher(actor);
        Dispatch dispatch = transaction.execute(ignored -> {
            Requested row = requested(id);
            lockArchive(row.subjectId());
            if (!hasGrant(row.subjectId(), actor.userId(), false)) throw denied();
            row = requested(id);
            var gate = quality.forSubject(row.subjectId());
            if (!gate.ready() || !Objects.equals(gate.id(), row.qualityId())) throw needsQuality();
            Long snapshotId = approvedSnapshot(row, actor.userId(), gate);
            if (snapshotId == null) throw conflict("请先核实来源并登记获执行许可的受控资料");
            return new Dispatch(snapshotId, row.subjectId(), row.qualityId());
        });
        Requested row = requested(id);
        if (row.taskId() == null) {
            assessments.submitControlledRequest(dispatch.snapshotId(), dispatch.subjectId(),
                    new ResearchTableAssessmentService.ControlledDispatch(id, dispatch.qualityId()), actor);
            row = requested(id);
        }
        return new QueueItem(row.id(), row.subjectId(), row.subjectCode(), row.alias(),
                taskState(row.status(), row.reviewStatus()), row.taskId(), false);
    }

    private Long approvedSnapshot(Requested row, long owner, CurrentArchiveQuality.Gate gate) {
        var approved = permission.forSubject(row.subjectId());
        if (approved == null || !permission.permits(client.configuredAssessmentEndpoint(), approved)
                || (row.snapshotId() != null && row.snapshotId() != approved.snapshotId())) return null;
        return jdbc.query("""
                SELECT id,request_json FROM research_table_snapshot
                WHERE id=? AND subject_id=? AND authorized_user_id=? AND data_scope='CONTROLLED_RESEARCH'
                AND host_sync_status='SYNCED' AND request_sha256=? AND input_sha256=? AND context_sha256=?
                """, (result, index) -> {
                    try {
                        var request = protocol.parse(result.getString(2).getBytes(StandardCharsets.UTF_8));
                        return approved.runId().equals(request.path("runId").textValue())
                                && row.subjectCode().equals(request.path("subjectCode").textValue())
                                && "RETROSPECTIVE_SNAPSHOT".equals(request.path("availabilityPolicy").textValue())
                                && gate.matchesVisit(request)
                                && protocol.digest(request).equals(approved.requestSha256()) ? result.getLong(1) : null;
                    } catch (IOException | IllegalArgumentException invalid) {
                        return null;
                    }
                }, approved.snapshotId(), approved.subjectId(), owner, approved.requestSha256(),
                approved.inputSha256(), approved.contextSha256()).stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    private Candidate fixture(long subjectId, CurrentArchiveQuality.Gate gate, long actor) {
        // Only the latest registered usable inputs are eligible. A stale newer snapshot is never silently skipped.
        var candidate = jdbc.query("""
                SELECT s.id,s.request_json,s.host_sync_status,t.id,t.status,t.review_status
                FROM research_table_snapshot s LEFT JOIN research_table_task t ON t.snapshot_id=s.id
                WHERE s.subject_id=? AND s.doctor_handoff=TRUE AND s.data_scope='CONSTRUCTED_VALIDATION'
                  AND (s.authorized_user_id=? OR t.id IS NOT NULL)
                ORDER BY s.id DESC LIMIT 1
                """, (row, index) -> new Candidate(row.getLong(1), row.getString(2), row.getString(3),
                row.getObject(4, Long.class), row.getString(5), row.getString(6)), subjectId, actor)
                .stream().findFirst().orElse(null);
        if (candidate == null) return null;
        try {
            return gate.matchesVisit(protocol.parse(candidate.wire().getBytes(StandardCharsets.UTF_8))) ? candidate : null;
        } catch (IOException | IllegalArgumentException invalid) {
            return null;
        }
    }

    private Archive archive(long subjectId) {
        return jdbc.query("""
                SELECT s.subject_code,s.status,CASE WHEN EXISTS (SELECT 1 FROM research_table_snapshot a
                    WHERE a.subject_id=s.id AND a.data_scope='CONTROLLED_RESEARCH') THEN 1 ELSE 0 END
                FROM subject s WHERE s.id=?
                """, (row, i) -> new Archive("ACTIVE".equals(row.getString(2)),
                !row.getString(1).startsWith("DEMO-") || row.getInt(3) == 1), subjectId)
                .stream().findFirst().orElseThrow(DoctorAssessmentRequests::denied);
    }

    private Requested latestRequest(long subjectId) {
        return jdbc.query(ROW + REQUESTS + " WHERE a.subject_id=? ORDER BY a.id DESC LIMIT 1", this::readRequest, subjectId)
                .stream().findFirst().orElse(null);
    }

    private Requested requested(long id) {
        return jdbc.query(ROW + REQUESTS + " WHERE a.id=? AND " + LATEST, this::readRequest, id)
                .stream().findFirst().orElseThrow(DoctorAssessmentRequests::denied);
    }

    private Requested readRequest(ResultSet row, int ignored) throws SQLException {
        return new Requested(row.getLong(1), row.getLong(2), row.getLong(3), row.getObject(4, Long.class),
                row.getString(5), row.getString(6), row.getObject(7, Long.class), row.getString(8), row.getString(9));
    }

    private void lockArchive(long subjectId) {
        jdbc.queryForObject("SELECT id FROM subject WHERE id=? FOR UPDATE", Long.class, subjectId);
    }

    private boolean hasGrant(long subjectId, long userId, boolean manage) {
        return !jdbc.queryForList("SELECT user_id FROM subject_permission WHERE subject_id=? AND user_id=? AND "
                + (manage ? "permission_type='MANAGE'" : "permission_type IN ('VIEW','MANAGE')"),
                Long.class, subjectId, userId).isEmpty();
    }

    private void requireDoctor(long subjectId, AppUserPrincipal actor) {
        if (!actor.roleCodes().contains("DOCTOR") || actor.roleCodes().contains("PATIENT")
                || !hasGrant(subjectId, actor.userId(), true)) throw denied();
    }

    private static void requireResearcher(AppUserPrincipal actor) {
        if (!actor.roleCodes().contains("RESEARCHER") || actor.roleCodes().contains("PATIENT")) throw denied();
    }

    private static String taskState(String status, String reviewStatus) {
        if ("COMPLETED".equals(status)) return "PENDING".equals(reviewStatus) ? "REVIEW_PENDING" : "REVIEWED";
        return status;
    }

    private static BusinessException needsQuality() {
        return conflict("请补齐最近访视的 MMSE 和 CDR-SB，并对当前资料重新质控");
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.TASK_STATE_CONFLICT, message);
    }

    private static BusinessException denied() {
        return new BusinessException(ErrorCode.ACCESS_DENIED);
    }
}
