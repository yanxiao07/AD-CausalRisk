package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.audit.AuditService;
import com.adcausalrisk.backend.common.*;
import com.adcausalrisk.backend.patient.PatientConsentService;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;

@Service
class ValidationCohortService {
    private static final int PAGE_SIZE = 20;
    private static final List<String> INPUTS = List.of("age_years", "education_years", "mmse", "cdr_sb",
            "cdr_global", "adas__total13", "faq__faqtotal");
    private static final Set<String> OUTCOMES = Set.of("AD", "OTHER_DEMENTIA", "NO_EVENT", "UNKNOWN");

    /**
     * 患者在最新一次表态中撤回「科研使用」后，这个档案不再出现在研究侧的队列与报告里。
     * 只认最新一行：重新授权后立刻恢复，历史行不会被当成当前状态。没有记录时视为未表态，
     * 沿用医院既有的伦理与书面授权流程。
     *
     * <p>拆成「条件」和「取反」两份常量，是为了让读取侧（{@link #COHORT}）与保存侧
     * （{@link #requireResearchUseStillGranted}）用同一段 SQL 判断，不会出现一边过滤、一边放行。
     */
    private static final String RESEARCH_USE_WITHDRAWN = """
            EXISTS (
                SELECT 1 FROM patient_consent_record consent
                WHERE consent.subject_id=s.id AND consent.research_use=FALSE
                  AND consent.id=(SELECT latest.id FROM patient_consent_record latest
                                  WHERE latest.subject_id=s.id
                                  ORDER BY latest.recorded_at DESC, latest.id DESC LIMIT 1)
            )
            """;
    private static final String CONSENT_ALLOWS_RESEARCH = "AND NOT " + RESEARCH_USE_WITHDRAWN;
    private static final String COHORT = """
            FROM validation_cohort_enrollment e JOIN subject s ON e.subject_id=s.id
            WHERE e.researcher_id=? AND s.data_version LIKE 'care-%'
            """ + CONSENT_ALLOWS_RESEARCH;
    private static final String CASE_SOURCE = """
            FROM validation_cohort_enrollment e
            JOIN subject s ON s.id=e.subject_id
            LEFT JOIN validation_follow_up f ON f.subject_id=s.id
            LEFT JOIN research_table_task t ON t.id=(
                SELECT COALESCE(MIN(CASE WHEN first_task.status='COMPLETED' THEN first_task.id END), MIN(first_task.id))
                FROM research_table_task first_task
                JOIN research_table_snapshot first_snapshot ON first_snapshot.id=first_task.snapshot_id
                WHERE first_snapshot.subject_id=s.id AND first_snapshot.doctor_handoff=TRUE)
            LEFT JOIN research_table_snapshot ss ON ss.id=t.snapshot_id
            WHERE e.researcher_id=? AND s.data_version LIKE 'care-%'
            """ + CONSENT_ALLOWS_RESEARCH;
    private static final String CASES = """
            SELECT e.id,e.project_code,t.status,t.result_json,ss.request_json,ss.model_json,
                ss.input_sha256,f.outcome,f.observed_date
            """ + CASE_SOURCE;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final AuditService audit;
    private final PatientConsentService consents;

    ValidationCohortService(JdbcTemplate jdbc, ObjectMapper json, Clock clock, AuditService audit,
                            PatientConsentService consents) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.audit = audit;
        this.consents = consents;
    }

    record Grant(String project, String subjectCode, String researcher, String authorizationRef) {}
    record FollowUp(String outcome, LocalDate observedDate, String sourceRef) {}
    record Case(String caseCode, String project, String predictionStatus, LocalDate indexDate,
                LocalDate horizonDate, BigDecimal adRisk, String model, String artifactSha256,
                Boolean clinicalUse, String inputSha256, Map<String, BigDecimal> inputs,
                LocalDate observedDate, String outcome24m) {}
    record ScopeOption(String project, String artifactSha256, String model) {}
    private record Target(String project, long subjectId, long researcherId) {}

    @Transactional(readOnly = true)
    public PageResult<Case> cohort(AppUserPrincipal actor, int page) {
        requireRole(actor, "RESEARCHER");
        if (page < 1) throw invalid("页码须为正整数");
        long total = jdbc.queryForObject("SELECT COUNT(*) " + COHORT, Long.class, actor.userId());
        var cases = jdbc.query(CASES + """
                ORDER BY e.project_code,e.granted_at,e.id LIMIT ? OFFSET ?
                """, this::projectCase, actor.userId(), PAGE_SIZE, (long) (page - 1) * PAGE_SIZE);
        return new PageResult<>(cases, page, PAGE_SIZE, total);
    }

    List<Case> projectCases(AppUserPrincipal actor, String project, int limit) {
        requireRole(actor, "RESEARCHER");
        return jdbc.query(CASES + " AND e.project_code=? ORDER BY e.id LIMIT ?",
                this::projectCase, actor.userId(), project, limit);
    }

    public List<ScopeOption> scopes(AppUserPrincipal actor) {
        requireRole(actor, "RESEARCHER");
        return jdbc.query("SELECT DISTINCT e.project_code,ss.model_json " + CASE_SOURCE
                        + " AND t.status='COMPLETED' ORDER BY e.project_code",
                (row, i) -> {
                    JsonNode model = node(row.getString("model_json"));
                    return new ScopeOption(row.getString("project_code"), model.path("artifactSha256").asText(),
                            modelLabel(model));
                }, actor.userId()).stream().filter(option -> option.artifactSha256().matches("[a-f0-9]{64}")
                        && option.model() != null).distinct().toList();
    }

    /**
     * 当前仍然可以出现在报告里的入组记录。与 {@link #COHORT} 同一条授权条件：
     * 患者撤回科研使用后，这些档案即使入组记录还在，也不能再进入新的报告。
     */
    Set<String> projectGrants(AppUserPrincipal actor, String project) {
        requireRole(actor, "RESEARCHER");
        return new HashSet<>(jdbc.queryForList("SELECT e.id " + COHORT + " AND e.project_code=?",
                String.class, actor.userId(), project));
    }

    Set<String> lockProjectGrants(AppUserPrincipal actor, String project) {
        requireRole(actor, "RESEARCHER");
        return new HashSet<>(jdbc.queryForList("""
                SELECT id FROM validation_cohort_enrollment
                WHERE researcher_id=? AND project_code=? ORDER BY id FOR UPDATE
                """, String.class, actor.userId(), project));
    }

    /**
     * 保存报告前的最后一道复核：入组记录还在，不代表患者此刻仍然同意科研使用。
     * 请求是几秒前发出的，患者可能在报告算完之前撤回，所以必须在写入前按最新表态再查一次。
     * 任何一例已撤回都整体拒绝，不做「剔除后继续保存」——患者看到的队列已经是 0 例，
     * 再产出一份包含该病例的报告会自相矛盾。
     *
     * <p>光再查一次仍有窗口：撤回可以落在这次查询之后、报告写入之前，接口返回 200，
     * 报告里却带着已撤回患者的数据。所以复核前先锁住涉及档案的 {@code subject} 行
     * （与 {@link PatientConsentService#saveConsent} 加锁的是同一行），并一直持有到
     * 报告写入所在事务提交：撤回要么整体先完成（这里复核即拒绝），要么排到报告落库之后。
     */
    void requireResearchUseStillGranted(List<String> enrollmentIds) {
        if (enrollmentIds.isEmpty()) return;
        String placeholders = String.join(",", Collections.nCopies(enrollmentIds.size(), "?"));
        List<Long> subjectIds = lockSubjectsOf(enrollmentIds, placeholders);
        var withdrawn = jdbc.queryForList("""
                SELECT e.id FROM validation_cohort_enrollment e JOIN subject s ON s.id=e.subject_id
                WHERE e.id IN (""" + placeholders + ") AND " + RESEARCH_USE_WITHDRAWN,
                String.class, enrollmentIds.toArray());
        if (!withdrawn.isEmpty()) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "患者已撤回科研使用授权，不能保存包含该档案的验证报告");
        }
        if (subjectIds.isEmpty()) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    /**
     * 先按入组记录取出档案 id，再对档案行加锁。锁语句刻意不写在带 join 的查询里：
     * 只有单表 {@code FOR UPDATE} 才明确锁住 subject 行，也就锁住了这张表的授权表态。
     */
    private List<Long> lockSubjectsOf(List<String> enrollmentIds, String placeholders) {
        List<Long> subjectIds = jdbc.queryForList("""
                SELECT DISTINCT s.id FROM validation_cohort_enrollment e JOIN subject s ON s.id=e.subject_id
                WHERE e.id IN (""" + placeholders + ") ORDER BY s.id",
                Long.class, enrollmentIds.toArray());
        if (subjectIds.isEmpty()) return subjectIds;
        String subjectPlaceholders = String.join(",", Collections.nCopies(subjectIds.size(), "?"));
        jdbc.queryForList("SELECT id FROM subject WHERE id IN (" + subjectPlaceholders + ")"
                + " ORDER BY id FOR UPDATE", Long.class, subjectIds.toArray());
        return subjectIds;
    }

    @Transactional
    public void grant(AppUserPrincipal actor, Grant request) {
        requireRole(actor, "ADMIN");
        var target = target(request, true);
        requireResearchUse(target.subjectId(), "新增验证队列授权");
        String reference = required(request.authorizationRef(), 150);
        var existing = jdbc.queryForList("""
                SELECT id FROM validation_cohort_enrollment WHERE project_code=? AND subject_id=? AND researcher_id=?
                """, String.class, target.project(), target.subjectId(), target.researcherId());
        if (existing.isEmpty()) {
            jdbc.update("""
                    INSERT INTO validation_cohort_enrollment
                        (id,project_code,subject_id,researcher_id,authorization_ref,granted_by,granted_at)
                    VALUES (?,?,?,?,?,?,?)
                    """, UUID.randomUUID().toString(), target.project(), target.subjectId(), target.researcherId(),
                    reference, actor.userId(), now());
        } else {
            jdbc.update("""
                    UPDATE validation_cohort_enrollment SET authorization_ref=?,granted_by=? WHERE id=?
                    """, reference, actor.userId(), existing.getFirst());
        }
        audit.recordQuietly(actor.userId(), "VALIDATION_COHORT_GRANTED", auditTarget(target));
    }

    @Transactional
    public void revoke(AppUserPrincipal actor, Grant request) {
        requireRole(actor, "ADMIN");
        var target = target(request, false);
        int changes = jdbc.update("""
                DELETE FROM validation_cohort_enrollment WHERE project_code=? AND subject_id=? AND researcher_id=?
                """, target.project(), target.subjectId(), target.researcherId());
        if (changes > 0) audit.recordQuietly(actor.userId(), "VALIDATION_COHORT_REVOKED", auditTarget(target));
    }

    public FollowUp followUp(AppUserPrincipal actor, long subjectId) {
        requireDoctor(actor, subjectId, false);
        requireResearchUseForRead(subjectId);
        return jdbc.query("""
                SELECT outcome,observed_date,source_ref FROM validation_follow_up WHERE subject_id=?
                """, (row, ignored) -> new FollowUp(row.getString(1), date(row, 2), row.getString(3)), subjectId)
                .stream().findFirst().orElse(null);
    }

    @Transactional
    public FollowUp saveFollowUp(AppUserPrincipal actor, long subjectId, FollowUp request) {
        requireDoctor(actor, subjectId, true);
        if (request.outcome() == null || !OUTCOMES.contains(request.outcome())
                || request.observedDate() == null || request.observedDate().isAfter(LocalDate.now(clock))) {
            throw invalid("请选择有效结局并填写不晚于今天的记录日期");
        }
        String source = required(request.sourceRef(), 150);
        requireResearchUse(subjectId, "录入科研随访记录");
        requireFollowUpContact(subjectId);
        int updated = jdbc.update("""
                UPDATE validation_follow_up SET outcome=?,observed_date=?,source_ref=?,recorded_by=?,recorded_at=?
                WHERE subject_id=?
                """, request.outcome(), request.observedDate(), source, actor.userId(), now(), subjectId);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO validation_follow_up (outcome,observed_date,source_ref,recorded_by,recorded_at,subject_id)
                    VALUES (?,?,?,?,?,?)
                    """, request.outcome(), request.observedDate(), source, actor.userId(), now(), subjectId);
        }
        audit.recordQuietly(actor.userId(), "VALIDATION_FOLLOW_UP_RECORDED", "subjectId=" + subjectId);
        return new FollowUp(request.outcome(), request.observedDate(), source);
    }

    private Case projectCase(ResultSet row, int ignored) throws SQLException {
        String status = row.getString("status");
        LocalDate observedDate = row.getObject("observed_date", LocalDate.class);
        JsonNode request = node(row.getString("request_json"));
        JsonNode model = node(row.getString("model_json"));
        JsonNode result = node(row.getString("result_json"));
        LocalDate index = request.hasNonNull("indexDate") ? LocalDate.parse(request.path("indexDate").asText()) : null;
        LocalDate horizon = index == null ? null : index.plusMonths(24);
        Map<String, BigDecimal> inputs = new LinkedHashMap<>();
        for (String key : INPUTS) {
            JsonNode value = request.path("features").path(key).path("value");
            if (value.isNumber()) inputs.put(key, value.decimalValue());
        }
        JsonNode probability = result.path("prediction").path("adCif24m");
        BigDecimal risk = "COMPLETED".equals(status) && probability.isNumber() ? probability.decimalValue() : null;
        if (risk != null && (risk.signum() < 0 || risk.compareTo(BigDecimal.ONE) > 0)) risk = null;
        JsonNode clinical = result.path("clinicalUse");
        return new Case(row.getString("id"), row.getString("project_code"), status == null ? "PENDING" : status,
                index, horizon, risk, modelLabel(model), model.path("artifactSha256").asText(null),
                clinical.isBoolean() ? clinical.booleanValue() : null, row.getString("input_sha256"), inputs,
                observedDate, outcome24m(index, horizon, row.getString("outcome"), observedDate));
    }

    private static String modelLabel(JsonNode model) {
        String family = model.path("family").asText(null);
        return family == null ? null : family + " / " + model.path("specification").asText("");
    }

    static String outcome24m(LocalDate index, LocalDate horizon, String outcome, LocalDate date) {
        if (index == null || date == null || outcome == null || !date.isAfter(index) || "UNKNOWN".equals(outcome)) {
            return "UNKNOWN";
        }
        if (date.isAfter(horizon)) return "NO_EVENT";
        if ("NO_EVENT".equals(outcome) && date.isBefore(horizon)) return "UNKNOWN";
        return outcome;
    }

    /**
     * 患者明确关闭「科研使用」后不再新增队列授权、不再录入科研随访，读取侧由
     * CONSENT_ALLOWS_RESEARCH 一并过滤。未表态时放行，保持医院既有授权流程不变；
     * 已经入组的记录保留，退出由管理员显式撤销。
     */
    private void requireResearchUse(long subjectId, String action) {
        var consent = consents.decisionOf(subjectId);
        if (consent.decided() && !consent.researchUse()) {
            throw invalid("患者已于 " + consent.recordedAt() + " 撤回科研使用授权，不能" + action);
        }
    }

    /**
     * 科研随访属于科研使用：患者撤回科研使用后，医生连读取都不应看到既有结局。
     * 这里用 403 而不是 400，因为请求本身有效，只是没有读取该科研数据的授权。
     */
    private void requireResearchUseForRead(long subjectId) {
        var consent = consents.decisionOf(subjectId);
        if (consent.decided() && !consent.researchUse()) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED,
                    "患者已于 " + consent.recordedAt() + " 撤回科研使用授权，不能读取科研随访记录");
        }
    }

    /** 患者明确关闭「随访联系」后不再录入科研随访；临床复诊走 follow-up 模块，不受此开关影响。 */
    private void requireFollowUpContact(long subjectId) {
        var consent = consents.decisionOf(subjectId);
        if (consent.decided() && !consent.followUpContact()) {
            throw invalid("患者已于 " + consent.recordedAt() + " 撤回随访联系授权，不能录入科研随访记录");
        }
    }

    private Target target(Grant request, boolean active) {
        String project = required(request.project(), 50).toUpperCase(Locale.ROOT);
        if (!project.matches("[A-Z0-9][A-Z0-9_-]{0,49}")) throw invalid("项目编号仅支持字母、数字、短横线和下划线");
        var subjects = jdbc.queryForList("""
                SELECT id FROM subject WHERE subject_code=? AND data_version LIKE 'care-%' FOR UPDATE
                """, Long.class, required(request.subjectCode(), 50));
        if (subjects.isEmpty()) throw invalid("未找到普通患者档案");
        var users = jdbc.queryForList("SELECT id FROM sys_user WHERE username=?" + (active ? """
                 AND status='ACTIVE'
                 AND EXISTS (SELECT 1 FROM user_role ur JOIN sys_role r ON r.id=ur.role_id
                             WHERE ur.user_id=sys_user.id AND r.role_code='RESEARCHER')
                 AND NOT EXISTS (SELECT 1 FROM user_role ur JOIN sys_role r ON r.id=ur.role_id
                                 WHERE ur.user_id=sys_user.id AND r.role_code='PATIENT')
                """ : ""), Long.class, required(request.researcher(), 50));
        if (users.isEmpty()) throw invalid("未找到可用的研究员账号");
        return new Target(project, subjects.getFirst(), users.getFirst());
    }

    private void requireDoctor(AppUserPrincipal actor, long subjectId, boolean lock) {
        requireRole(actor, "DOCTOR");
        if (jdbc.queryForList("""
                SELECT s.id FROM subject s WHERE s.id=? AND s.data_version LIKE 'care-%'
                AND EXISTS (SELECT 1 FROM subject_permission p
                    WHERE p.subject_id=s.id AND p.user_id=? AND p.permission_type='MANAGE')
                AND NOT EXISTS (SELECT 1 FROM patient_account_request a WHERE a.subject_id=s.id
                    AND COALESCE(a.responsible_doctor_id,a.requested_by)<>?)
                """ + (lock ? " FOR UPDATE" : ""), Long.class, subjectId, actor.userId(), actor.userId()).isEmpty()) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private static void requireRole(AppUserPrincipal actor, String role) {
        if (actor.roleCodes().contains("PATIENT") || !actor.roleCodes().contains(role)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private JsonNode node(String value) throws SQLException {
        if (value == null) return json.missingNode();
        try {
            return json.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new SQLException("Invalid stored validation snapshot or report", error);
        }
    }

    private Timestamp now() { return Timestamp.from(clock.instant()); }

    private static LocalDate date(ResultSet row, int column) throws SQLException {
        return row.getObject(column, LocalDate.class);
    }

    private static String auditTarget(Target target) {
        return "project=" + target.project() + " subjectId=" + target.subjectId() + " researcherId=" + target.researcherId();
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.strip().length() > max) throw invalid("请填写有效的必填字段");
        return value.strip();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
}