package com.adcausalrisk.backend.common;

import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectAccessPolicy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.*;
import java.util.*;

/** Authorized database queries. Scope is applied before counts, filters and paging. */
@Service
public class BusinessQueries {
    private final JdbcTemplate jdbc;
    private final SubjectAccessPolicy policy;

    public BusinessQueries(JdbcTemplate jdbc, SubjectAccessPolicy policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    public String scope(String subjectColumn, AppUserPrincipal user, boolean raw) {
        if (raw && policy.hasFullRawScoreVisibility(user) || !raw && policy.hasFullVisibility(user))
            return "1=1";
        Set<Long> ids =
                raw ? policy.rawScoreVisibleSubjectIds(user) : policy.grantedSubjectIds(user);
        if (ids.isEmpty()) return "1=0";
        return subjectColumn
                + " IN ("
                + ids.stream()
                        .sorted()
                        .map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(","))
                + ")";
    }

    public static void admin(AppUserPrincipal user) {
        if (!user.roleCodes().contains("ADMIN") || user.roleCodes().contains("PATIENT"))
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "仅管理员可执行此操作");
    }

    public static String text(Map<String, String> filter, String key) {
        return filter.getOrDefault(key, "").trim();
    }

    private static int number(Map<String, String> filter, String key, int fallback) {
        try {
            return Integer.parseInt(filter.getOrDefault(key, String.valueOf(fallback)));
        } catch (NumberFormatException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, key + "不是有效整数");
        }
    }

    public static Timestamp time(String value, boolean end) {
        try {
            return Timestamp.from(
                    value.length() == 10
                            ? LocalDate.parse(value)
                                    .plusDays(end ? 1 : 0)
                                    .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                                    .toInstant()
                            : Instant.parse(value));
        } catch (Exception invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "日期须为YYYY-MM-DD或带时区时间");
        }
    }

    private static String camel(String value) {
        StringBuilder result = new StringBuilder();
        boolean upper = false;
        for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '_') {
                upper = true;
                continue;
            }
            result.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return result.toString();
    }

    public List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.query(
                sql,
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    var metadata = rs.getMetaData();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        Object value = rs.getObject(i);
                        if (value instanceof Timestamp timestamp)
                            value = timestamp.toInstant().toString();
                        else if (value instanceof java.sql.Date date)
                            value = date.toLocalDate().toString();
                        else if (value instanceof Clob clob)
                            value = clob.getSubString(1, Math.toIntExact(clob.length()));
                        row.put(camel(metadata.getColumnLabel(i)), value);
                    }
                    return row;
                },
                args);
    }

    public PageResult<Map<String, Object>> page(
            String from,
            String select,
            String where,
            List<Object> values,
            String order,
            Map<String, String> filter) {
        int page = Math.max(1, number(filter, "page", 1)),
                size = Math.max(1, Math.min(100, number(filter, "pageSize", 20)));
        long total =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM " + from + " WHERE " + where,
                                Long.class,
                                values.toArray()));
        var params = new ArrayList<>(values);
        params.add(size);
        params.add((long) (page - 1) * size);
        return new PageResult<>(
                rows(
                        "SELECT "
                                + select
                                + " FROM "
                                + from
                                + " WHERE "
                                + where
                                + " ORDER BY "
                                + order
                                + " LIMIT ? OFFSET ?",
                        params.toArray()),
                page,
                size,
                total);
    }

    public PageResult<Map<String, Object>> list(
            String kind, Map<String, String> filter, AppUserPrincipal user) {
        String from, select, id, date, where, order;
        List<Object> args = new ArrayList<>();
        switch (kind) {
            case "subjects" -> {
                from = "subject s";
                select = "s.*";
                id = "s.id";
                date = "s.created_at";
                where = scope(id, user, false);
                order = "s.id DESC";
            }
            case "assessments" -> {
                from = "risk_assessment r JOIN subject s ON s.id=r.subject_id";
                select =
                        "r.id assessment_id,r.subject_id,r.task_id,r.review_status,r.quality_status,r.risk_score,r.risk_level,r.created_at,s.subject_code,s.alias"
                            + " subject_alias";
                id = "r.subject_id";
                date = "r.created_at";
                where = scope(id, user, true);
                order = "r.id DESC";
            }
            case "tasks" -> {
                from =
                        "assessment_task t LEFT JOIN risk_assessment r ON r.task_id=t.id JOIN"
                                + " subject s ON s.id=t.subject_id";
                select =
                        "t.id task_id,t.subject_id,t.status,t.run_id,r.id"
                                + " assessment_id,t.error_code,t.error_message"
                                + " message,t.created_at,t.updated_at,s.subject_code,s.alias"
                                + " subject_alias";
                id = "t.subject_id";
                date = "t.created_at";
                where = scope(id, user, true);
                order = "t.id DESC";
            }
            case "followups" -> {
                if (user.roleCodes().contains("PATIENT"))
                    throw new BusinessException(ErrorCode.ACCESS_DENIED, "请使用本人摘要查看随访安排");
                from =
                        "follow_up_plan f JOIN subject s ON s.id=f.subject_id JOIN sys_user u ON"
                                + " u.id=f.owner_id";
                select = "f.*,s.subject_code,s.alias subject_alias,u.username owner_name";
                id = "f.subject_id";
                date = "f.scheduled_at";
                where = scope(id, user, true);
                order = "f.scheduled_at,f.id";
            }
            case "quality" -> {
                from = "quality_check q JOIN subject s ON s.id=q.subject_id";
                select = "q.*,s.subject_code,s.alias subject_alias";
                id = "q.subject_id";
                date = "q.checked_at";
                where = scope(id, user, true);
                order = "q.id DESC";
            }
            case "imports" -> {
                if (user.roleCodes().contains("PATIENT") || !policy.canCreate(user))
                    throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权查看导入任务");
                from = "data_import_job j";
                select =
                        "j.id job_id,j.status,j.data_version,j.total_rows,j.valid_rows,j.invalid_rows,j.created_at,j.created_by";
                id = "j.id";
                date = "j.created_at";
                where =
                        user.roleCodes().contains("ADMIN")
                                ? "1=1"
                                : "j.created_by=" + user.userId();
                order = "j.id DESC";
            }
            case "audit" -> {
                admin(user);
                from = "audit_log a";
                select =
                        "a.id,a.actor_id,a.event_type,a.trace_id,a.summary,a.occurred_at timestamp";
                id = "a.id";
                date = "a.occurred_at";
                where = "1=1";
                order = "a.occurred_at DESC,a.id DESC";
            }
            case "users" -> {
                admin(user);
                from = "sys_user u";
                select =
                        "u.id,u.username,u.status,u.data_scope,u.created_at,u.display_name,u.department,u.contact_phone"
                            + " phone,u.office_hours";
                id = "u.id";
                date = "u.created_at";
                where = "1=1";
                order = "u.id";
            }
            default -> throw new IllegalArgumentException("Unknown query kind");
        }
        if (!text(filter, "subjectId").isEmpty()
                && Set.of("assessments", "tasks", "followups", "quality").contains(kind)) {
            where += " AND " + id + "=?";
            args.add(number(filter, "subjectId", 0));
        }
        String statusColumn =
                switch (kind) {
                    case "subjects" -> "s.status";
                    case "tasks" -> "t.status";
                    case "followups" -> "f.status";
                    case "imports" -> "j.status";
                    case "users" -> "u.status";
                    case "quality" -> "q.quality_status";
                    default -> null;
                };
        if (statusColumn != null && !text(filter, "status").isEmpty()) {
            where += " AND " + statusColumn + "=?";
            args.add(text(filter, "status"));
        }
        if (kind.equals("assessments"))
            for (String key : List.of("reviewStatus", "riskLevel"))
                if (!text(filter, key).isEmpty()) {
                    where +=
                            " AND r."
                                    + (key.equals("reviewStatus") ? "review_status" : "risk_level")
                                    + "=?";
                    args.add(text(filter, key));
                }
        if (kind.equals("audit"))
            for (String key : List.of("eventType", "actorId", "traceId"))
                if (!text(filter, key).isEmpty()) {
                    where +=
                            " AND a."
                                    + switch (key) {
                                        case "eventType" -> "event_type";
                                        case "actorId" -> "actor_id";
                                        default -> "trace_id";
                                    }
                                    + "=?";
                    args.add(text(filter, key));
                }
        if (kind.equals("followups") && !text(filter, "due").isEmpty()) {
            String due = text(filter, "due");
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            where += " AND f.status='PLANNED'";
            if (due.equals("overdue")) {
                where += " AND f.scheduled_at<?";
                args.add(Timestamp.from(Instant.now()));
            } else if (due.equals("today") || due.equals("next7")) {
                where += " AND f.scheduled_at>=? AND f.scheduled_at<?";
                Instant now = Instant.now();
                args.add(due.equals("today") ? time(today.toString(), false) : Timestamp.from(now));
                args.add(
                        due.equals("today")
                                ? time(today.plusDays(1).toString(), false)
                                : Timestamp.from(now.plus(Duration.ofDays(7))));
            } else throw new BusinessException(ErrorCode.VALIDATION_ERROR, "随访时间窗口无效");
        }
        if (!text(filter, "from").isEmpty()) {
            where += " AND " + date + ">=?";
            args.add(time(text(filter, "from"), false));
        }
        if (!text(filter, "to").isEmpty()) {
            where += " AND " + date + "<?";
            args.add(time(text(filter, "to"), true));
        }
        if (!text(filter, "keyword").isEmpty()) {
            String column =
                    switch (kind) {
                        case "audit" -> "a.summary";
                        case "users" -> "u.username";
                        default -> kind.equals("imports") ? "j.data_version" : "s.subject_code";
                    };
            String kw =
                    "%"
                            + text(filter, "keyword")
                                    .toLowerCase(Locale.ROOT)
                                    .replace("!", "!!")
                                    .replace("%", "!%")
                                    .replace("_", "!_")
                            + "%";
            where += " AND (LOWER(" + column + ") LIKE ? ESCAPE '!'";
            args.add(kw);
            if (!Set.of("audit", "users", "imports").contains(kind)) {
                where += " OR LOWER(s.alias) LIKE ? ESCAPE '!'";
                args.add(kw);
            }
            where += ")";
        }
        if (!text(filter, "sort").isEmpty()) {
            String sort = text(filter, "sort");
            if (!Set.of("createdAsc", "createdDesc").contains(sort))
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "排序仅支持createdAsc/createdDesc");
            String unique =
                    switch (kind) {
                        case "tasks" -> "t.id";
                        case "assessments" -> "r.id";
                        case "followups" -> "f.id";
                        case "quality" -> "q.id";
                        case "imports" -> "j.id";
                        case "audit" -> "a.id";
                        default -> id;
                    };
            order = date + (sort.equals("createdAsc") ? " ASC" : " DESC") + "," + unique + " DESC";
        }
        return page(from, select, where, args, order, filter);
    }

    public List<Map<String, Object>> featureSources(long subjectId, AppUserPrincipal user) {
        if (!policy.canReadRawScores(user, subjectId))
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权查看原始资料");
        return rows(
                "SELECT * FROM subject_feature WHERE subject_id=? ORDER BY recorded_at,id",
                subjectId);
    }

    public Map<String, Boolean> capabilities(long id, AppUserPrincipal user) {
        if (!policy.canView(user, id))
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权访问档案");
        return Map.of(
                "readRaw",
                policy.canReadRawScores(user, id),
                "manage",
                policy.canManage(user, id),
                "record",
                policy.canCreate(user) && policy.canManage(user, id),
                "writeClinical",
                policy.canWriteClinical(user, id),
                "requestAssessment",
                policy.canRequestAssessment(user, id));
    }

    public Map<String, Object> dashboard(AppUserPrincipal user) {
        if (user.roleCodes().contains("PATIENT"))
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "请使用本人摘要");
        Map<String, Object> result = new LinkedHashMap<>();
        String raw = scope("subject_id", user, true), visible = scope("id", user, false);
        result.put(
                "subjects",
                jdbc.queryForObject("SELECT COUNT(*) FROM subject WHERE " + visible, Long.class));
        result.put(
                "pendingReview",
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM risk_assessment WHERE review_status='PENDING' AND "
                                + raw,
                        Long.class));
        result.put(
                "highRisk",
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM risk_assessment WHERE risk_level='HIGH' AND " + raw,
                        Long.class));
        var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var begin = time(today.toString(), false);
        var end = time(today.toString(), true);
        String followScope = scope("subject_id", user, false);
        result.put(
                "todayFollowups",
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM follow_up_plan WHERE status='PLANNED' AND"
                                + " scheduled_at>=? AND scheduled_at<? AND "
                                + followScope,
                        Long.class,
                        begin,
                        end));
        result.put(
                "overdueFollowups",
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM follow_up_plan WHERE status='PLANNED' AND"
                                + " scheduled_at<? AND "
                                + followScope,
                        Long.class,
                        Timestamp.from(Instant.now())));
        result.put("followupPreviewLimit", 100);
        result.put(
                "subjectStates",
                rows(
                        "SELECT status,COUNT(*) total FROM subject WHERE "
                                + visible
                                + " GROUP BY status"));
        result.put(
                "trend",
                rows(
                        "SELECT CAST(created_at AS DATE) date,COUNT(*) total FROM subject WHERE "
                                + visible
                                + " GROUP BY CAST(created_at AS DATE) ORDER BY date"));
        result.put(
                "followups",
                rows(
                        "SELECT f.*,s.alias subject_alias,s.subject_code FROM follow_up_plan f JOIN"
                                + " subject s ON s.id=f.subject_id WHERE f.status='PLANNED' AND "
                                + scope("f.subject_id", user, false)
                                + " ORDER BY f.scheduled_at,f.id LIMIT 100"));
        return result;
    }
}
