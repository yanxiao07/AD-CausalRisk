package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.audit.AuditService;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 患者授权中心：记录「是否允许科研使用」「是否允许随访联系」，以及患者随时撤回的决定。
 *
 * <p>授权表是只追加的：每次变更插入一行，当前状态取最新一行。撤回不需要 UPDATE 覆盖历史，
 * 「谁在什么时候把授权改成了什么」这条线索不会被后来的写入抹掉。
 *
 * <p>研究侧在「新增验证队列授权」「录入科研随访」以及「读取队列与报告」三处读本表的最新一行：
 * 患者明确关闭「科研使用」后不再新增队列授权，也不再出现在研究侧的读取结果里；
 * 明确关闭「随访联系」后不再录入科研随访。已入组的记录本身保留，退出由管理员显式撤销。
 * 未表态（没有记录）时沿用医院既有的伦理与授权流程，不在这里替代它。
 * 临床诊疗需要的复诊安排不受这两个开关影响。
 */
@Service
public class PatientConsentService {

    private final JdbcTemplate jdbc;
    private final PatientSummaryService summary;
    private final AuditService audit;

    public PatientConsentService(JdbcTemplate jdbc, PatientSummaryService summary, AuditService audit) {
        this.jdbc = jdbc;
        this.summary = summary;
        this.audit = audit;
    }

    /** 一条授权记录；列表按时间倒序，第一条就是当前生效的授权。 */
    public record ConsentRecord(boolean researchUse, boolean followUpContact, Instant recordedAt) {}

    /**
     * 给研究侧判断用的当前决定。{@code decided=false} 表示患者从未表态，此时研究侧不应把
     * 「没有记录」当成「已经拒绝」，否则会静默阻断医院既有的授权流程。
     */
    public record ConsentDecision(boolean decided, boolean researchUse, boolean followUpContact,
                                  Instant recordedAt) {}

    /**
     * {@code decided=false} 表示患者从未表态，此时 {@code researchUse}/{@code followUpContact}
     * 只是占位值，前端应显示「未设置」而不是「不允许」。
     */
    public record ConsentView(
            boolean bound,
            Long subjectId,
            String subjectCode,
            String alias,
            boolean decided,
            boolean researchUse,
            boolean followUpContact,
            Instant updatedAt,
            List<ConsentRecord> history) {}

    private static void requirePatient(AppUserPrincipal principal) {
        if (!principal.roleCodes().contains("PATIENT")) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "仅患者本人可管理授权");
        }
    }

    @Transactional(readOnly = true)
    public ConsentView currentConsent(AppUserPrincipal principal) {
        requirePatient(principal);
        Long subjectId = summary.subjectForPatient(principal);
        if (subjectId == null) {
            return new ConsentView(false, null, null, null, false, false, false, null, List.of());
        }
        return view(subjectId);
    }

    /**
     * 记录一次授权变更。撤回与授予走同一条路径：前端把开关的最终状态提交上来，这里追加一行，
     * 因此「撤回」也是一条可追溯的记录，而不是把旧行删掉。
     */
    @Transactional
    public ConsentView saveConsent(AppUserPrincipal principal, boolean researchUse, boolean followUpContact) {
        requirePatient(principal);
        Long subjectId = summary.subjectForPatient(principal);
        if (subjectId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "账号还没有绑定健康档案，暂时无法设置授权");
        }
        // 与报告保存共用同一把行锁：报告侧在写入前锁住 subject 行并复核最新表态，
        // 撤回也必须先拿到同一行锁。否则撤回可以落在「复核之后、写入之前」，
        // 接口照样返回 200，报告里还带着这位患者的数据。
        lockSubject(subjectId);
        jdbc.update(
                "INSERT INTO patient_consent_record"
                        + " (subject_id, user_id, research_use, follow_up_contact, recorded_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                subjectId,
                principal.userId(),
                researchUse,
                followUpContact,
                Timestamp.from(Instant.now()));
        audit.recordQuietly(
                principal.userId(),
                "PATIENT_CONSENT",
                "患者更新授权 subjectId=" + subjectId
                        + " 科研使用=" + (researchUse ? "允许" : "不允许")
                        + " 随访联系=" + (followUpContact ? "允许" : "不允许"));
        return view(subjectId);
    }

    /** 取档案行锁。研究侧保存验证报告时用的是同一句 SQL，两边因此互斥。 */
    private void lockSubject(Long subjectId) {
        jdbc.queryForList("SELECT id FROM subject WHERE id=? FOR UPDATE", Long.class, subjectId);
    }

    /**
     * 研究侧的执行入口：读这个档案最新一次患者表态，不按账号过滤。
     * 同一档案如果换过绑定账号，最新一次表态仍然代表患者当前的意愿。
     */
    @Transactional(readOnly = true)
    public ConsentDecision decisionOf(Long subjectId) {
        return jdbc.query(
                "SELECT research_use, follow_up_contact, recorded_at FROM patient_consent_record"
                        + " WHERE subject_id = ? ORDER BY recorded_at DESC, id DESC LIMIT 1",
                rs -> rs.next()
                        ? new ConsentDecision(
                                true,
                                rs.getBoolean(1),
                                rs.getBoolean(2),
                                rs.getTimestamp(3).toInstant())
                        : new ConsentDecision(false, false, false, null),
                subjectId);
    }

    /**
     * 页面展示与执行判定读同一份数据：都按档案取最新一行，不按账号过滤。
     * 这样患者换过绑定账号后，页面上的「已撤回」不会和研究侧的拦截结论互相矛盾。
     */
    private ConsentView view(Long subjectId) {
        String[] subject = jdbc.query(
                "SELECT subject_code, alias FROM subject WHERE id = ?",
                rs -> rs.next() ? new String[] {rs.getString(1), rs.getString(2)} : null,
                subjectId);
        List<ConsentRecord> history = jdbc.query(
                "SELECT research_use, follow_up_contact, recorded_at FROM patient_consent_record"
                        + " WHERE subject_id = ? ORDER BY recorded_at DESC, id DESC",
                (rs, rowNum) -> new ConsentRecord(
                        rs.getBoolean(1),
                        rs.getBoolean(2),
                        rs.getTimestamp(3).toInstant()),
                subjectId);
        ConsentRecord latest = history.isEmpty() ? null : history.get(0);
        return new ConsentView(
                true,
                subjectId,
                subject == null ? null : subject[0],
                subject == null ? null : subject[1],
                latest != null,
                latest != null && latest.researchUse(),
                latest != null && latest.followUpContact(),
                latest == null ? null : latest.recordedAt(),
                history);
    }
}