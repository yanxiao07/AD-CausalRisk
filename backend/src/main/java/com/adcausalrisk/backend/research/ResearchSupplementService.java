package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.audit.AuditService;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.common.PageResult;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ResearchSupplementService {
    private static final String SELECT = """
            SELECT r.*,s.id original_snapshot_id,s.subject_id,s.data_scope,
                   sub.subject_code,sub.alias,assignee.display_name,assignee.username
            """;
    private static final String FROM = """
            FROM research_supplement_request r
            JOIN research_table_task t ON t.id=r.original_task_id
            JOIN research_table_snapshot s ON s.id=t.snapshot_id
            JOIN subject sub ON sub.id=s.subject_id
            LEFT JOIN sys_user assignee ON assignee.id=r.assigned_to
            """;
    private static final String PERMISSION = """
            EXISTS (SELECT 1 FROM subject_permission p WHERE p.subject_id=s.subject_id
                    AND p.user_id=? AND p.permission_type IN ('VIEW','MANAGE'))
            """;

    private final JdbcTemplate jdbc;
    private final ResearchTableAssessmentService assessments;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final Clock clock;

    ResearchSupplementService(JdbcTemplate jdbc, ResearchTableAssessmentService assessments,
                              ObjectMapper mapper, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.assessments = assessments;
        this.mapper = mapper;
        this.audit = audit;
        this.clock = clock;
    }

    record Supplement(long id, long originalTaskId, long originalSnapshotId, long subjectId,
                      String subjectCode, String subjectAlias, String dataScope, long requestedBy,
                      String note, JsonNode issues, String status, Long assignedTo, String assigneeName,
                      Long replacementTaskId, Instant createdAt, Instant updatedAt) {}

    PageResult<Supplement> list(Long originalTaskId, String dataScope, int page, int pageSize,
                                AppUserPrincipal principal) {
        requireParticipant(principal);
        if ((originalTaskId != null && originalTaskId <= 0) || page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "补件编号或分页参数无效");
        }
        if (dataScope != null && !Set.of("CONTROLLED_RESEARCH", "CONSTRUCTED_VALIDATION").contains(dataScope)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "无效的数据来源");
        }
        StringBuilder where = new StringBuilder(" WHERE ").append(PERMISSION);
        List<Object> params = new ArrayList<>();
        params.add(principal.userId());
        if (originalTaskId != null) {
            where.append(" AND r.original_task_id=?");
            params.add(originalTaskId);
        }
        if (dataScope != null) {
            where.append(" AND s.data_scope=?");
            params.add(dataScope);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) " + FROM + where, Long.class, params.toArray());
        params.add(pageSize);
        params.add((long) (page - 1) * pageSize);
        var items = jdbc.query(SELECT + FROM + where + """
                 ORDER BY CASE r.status WHEN 'RETURNED' THEN 1 ELSE 0 END,r.id DESC
                 LIMIT ? OFFSET ?
                """, this::map, params.toArray());
        return new PageResult<>(items, page, pageSize, total);
    }

    @Transactional
    public Supplement create(long taskId, String note, AppUserPrincipal principal) {
        requireRole(principal, "DOCTOR");
        String text = note == null ? "" : note.strip();
        if (taskId <= 0 || text.isEmpty() || text.length() > 1000) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择原任务并填写1至1000字的补件原因");
        }
        var task = assessments.report(taskId, principal);
        jdbc.queryForObject("SELECT id FROM research_table_task WHERE id=? FOR UPDATE", Long.class, taskId);
        var previous = jdbc.query(SELECT + FROM + " WHERE r.original_task_id=?", this::map, taskId)
                .stream().findFirst();
        if (previous.isPresent()) {
            var existing = previous.get();
            if (existing.requestedBy() == principal.userId() && existing.note().equals(text)) return existing;
            throw conflict("原任务已有补件单");
        }
        if (!"BLOCKED".equals(task.status())
                && !("COMPLETED".equals(task.status()) && "NEEDS_DATA".equals(task.reviewStatus()))) {
            throw conflict("仅资料核验阻断或已复核为需补充资料的任务可创建补件单");
        }
        var issues = mapper.createArrayNode();
        if ("BLOCKED".equals(task.status()) && task.result() != null) {
            for (JsonNode issue : task.result().path("admission").path("issues")) {
                if (issues.size() == 100) break;
                var entry = issues.addObject();
                entry.put("feature", issue.path("feature").asText(""));
                entry.put("code", issue.path("code").asText(""));
                entry.put("severity", issue.path("severity").asText(""));
            }
        }
        var now = Timestamp.from(clock.instant());
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO research_supplement_request
                      (original_task_id,requested_by,note,issues_json,created_at,updated_at)
                    VALUES(?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, taskId);
            statement.setLong(2, principal.userId());
            statement.setString(3, text);
            statement.setString(4, issues.toString());
            statement.setTimestamp(5, now);
            statement.setTimestamp(6, now);
            return statement;
        }, keys);
        long id = keys.getKey().longValue();
        record(principal, "RESEARCH_SUPPLEMENT_CREATED", id);
        return visible(id, principal);
    }

    @Transactional
    public Supplement change(long id, String action, Long replacementTaskId, AppUserPrincipal principal) {
        requireParticipant(principal);
        if (id <= 0 || action == null || !Set.of("CLAIM", "RELEASE", "RETURN").contains(action)
                || ("RETURN".equals(action) ? replacementTaskId == null || replacementTaskId <= 0
                                           : replacementTaskId != null)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "补件动作或新任务编号无效");
        }
        visible(id, principal);
        jdbc.queryForObject("SELECT id FROM research_supplement_request WHERE id=? FOR UPDATE", Long.class, id);
        var current = visible(id, principal);
        Timestamp now = Timestamp.from(clock.instant());
        switch (action) {
            case "CLAIM" -> {
                requireRole(principal, "RESEARCHER");
                if ("CLAIMED".equals(current.status()) && assignedTo(current, principal)) return current;
                if (!"OPEN".equals(current.status())) throw conflict("补件单当前无法领取");
                jdbc.update("""
                        UPDATE research_supplement_request SET status='CLAIMED',assigned_to=?,updated_at=?
                        WHERE id=?
                        """, principal.userId(), now, id);
            }
            case "RELEASE" -> {
                if ("OPEN".equals(current.status())) return current;
                if (!principal.roleCodes().contains("DOCTOR") && !assignedTo(current, principal)) {
                    throw new BusinessException(ErrorCode.ACCESS_DENIED);
                }
                if (!"CLAIMED".equals(current.status())) throw conflict("补件单已回传");
                jdbc.update("""
                        UPDATE research_supplement_request SET status='OPEN',assigned_to=NULL,updated_at=?
                        WHERE id=?
                        """, now, id);
            }
            case "RETURN" -> {
                requireRole(principal, "RESEARCHER");
                if (!assignedTo(current, principal)) throw new BusinessException(ErrorCode.ACCESS_DENIED);
                if ("RETURNED".equals(current.status()) && replacementTaskId.equals(current.replacementTaskId())) {
                    return current;
                }
                if (!"CLAIMED".equals(current.status())) throw conflict("请先领取这张补件单");
                var task = assessments.report(replacementTaskId, principal);
                if (task.subjectId() != current.subjectId() || !current.dataScope().equals(task.dataScope())
                        || task.snapshotId() <= current.originalSnapshotId() || task.result() == null
                        || !Set.of("BLOCKED", "COMPLETED").contains(task.status())
                        || (task.createdAt() != null && task.createdAt().isBefore(current.createdAt()))) {
                    throw conflict("请选择该补件单创建后、同档案同资料类型、由新快照核验产生的任务");
                }
                var owners = jdbc.queryForList(
                        "SELECT authorized_user_id FROM research_table_snapshot WHERE id=?",
                        Long.class, task.snapshotId());
                if (!owners.contains(principal.userId())) throw new BusinessException(ErrorCode.ACCESS_DENIED);
                try {
                    jdbc.update("""
                            UPDATE research_supplement_request
                            SET status='RETURNED',replacement_task_id=?,updated_at=? WHERE id=?
                            """, replacementTaskId, now, id);
                } catch (DuplicateKeyException alreadyUsed) {
                    throw conflict("该任务已回传到其他补件单");
                }
            }
            default -> throw new IllegalStateException("Validated supplement action");
        }
        record(principal, "RESEARCH_SUPPLEMENT_" + action, id);
        return visible(id, principal);
    }

    private Supplement visible(long id, AppUserPrincipal principal) {
        return jdbc.query(SELECT + FROM + " WHERE r.id=? AND " + PERMISSION,
                        this::map, id, principal.userId()).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
    }

    private Supplement map(ResultSet row, int index) throws SQLException {
        JsonNode issues;
        try {
            issues = mapper.readTree(row.getString("issues_json"));
        } catch (JsonProcessingException ex) {
            throw new SQLException("Invalid stored supplement issues", ex);
        }
        String assignee = row.getString("display_name");
        return new Supplement(row.getLong("id"), row.getLong("original_task_id"),
                row.getLong("original_snapshot_id"), row.getLong("subject_id"),
                row.getString("subject_code"), row.getString("alias"), row.getString("data_scope"),
                row.getLong("requested_by"), row.getString("note"), issues, row.getString("status"),
                row.getObject("assigned_to", Long.class), assignee == null ? row.getString("username") : assignee,
                row.getObject("replacement_task_id", Long.class), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant());
    }

    private void record(AppUserPrincipal actor, String event, long id) {
        audit.recordQuietly(actor.userId(), event, "supplementId=" + id);
    }

    private static boolean assignedTo(Supplement item, AppUserPrincipal principal) {
        return item.assignedTo() != null && item.assignedTo() == principal.userId();
    }

    private static void requireParticipant(AppUserPrincipal principal) {
        if (principal.roleCodes().contains("PATIENT")
                || (!principal.roleCodes().contains("DOCTOR") && !principal.roleCodes().contains("RESEARCHER"))) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private static void requireRole(AppUserPrincipal principal, String role) {
        if (principal.roleCodes().contains("PATIENT") || !principal.roleCodes().contains(role)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.TASK_STATE_CONFLICT, message);
    }
}
