package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.assessment.RiskAssessmentRepository;
import com.adcausalrisk.backend.assessment.entity.RiskAssessment;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.followup.FollowUpPlanRepository;
import com.adcausalrisk.backend.followup.entity.FollowUpPlan;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectAccessPolicy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 健康时间轴：把访视、量表、医生结论、复诊安排按时间倒序排成一条时间线。
 *
 * <p>所有内容都来自患者已绑定的档案，并且沿用 {@link PatientSummaryService} 的边界：
 * 这里不返回原始风险分数、模型权重、因果图或内部备注，只返回患者能看懂的门诊事实与
 * 医生已复核的结论措辞。
 */
@Service
public class PatientTimelineService {

    /** 时间线只给最近的一段，避免把多年的档案一次性铺满手机屏幕。 */
    private static final int MAX_EVENTS = 100;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final PatientSummaryService summary;
    private final RiskAssessmentRepository assessmentRepository;
    private final FollowUpPlanRepository followUpRepository;
    private final SubjectAccessPolicy accessPolicy;

    public PatientTimelineService(JdbcTemplate jdbc,
                                  PatientSummaryService summary,
                                  RiskAssessmentRepository assessmentRepository,
                                  FollowUpPlanRepository followUpRepository,
                                  SubjectAccessPolicy accessPolicy) {
        this.jdbc = jdbc;
        this.summary = summary;
        this.assessmentRepository = assessmentRepository;
        this.followUpRepository = followUpRepository;
        this.accessPolicy = accessPolicy;
    }

    public record TimelineValue(String label, String value) {}

    public record TimelineEvent(
            String type,
            Instant occurredAt,
            String title,
            String detail,
            String status,
            List<TimelineValue> values) {}

    public record TimelineView(
            boolean bound,
            Long subjectId,
            String subjectCode,
            String alias,
            List<TimelineEvent> events) {}

    @Transactional(readOnly = true)
    public TimelineView myTimeline(AppUserPrincipal principal) {
        if (!principal.roleCodes().contains("PATIENT")) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "仅患者本人可查看健康时间轴");
        }
        Long subjectId = summary.subjectForPatient(principal);
        if (subjectId == null) {
            return new TimelineView(false, null, null, null, List.of());
        }

        String[] subject = jdbc.query(
                "SELECT subject_code, alias FROM subject WHERE id = ?",
                rs -> rs.next() ? new String[] {rs.getString(1), rs.getString(2)} : null,
                subjectId);

        List<TimelineEvent> events = new ArrayList<>();
        // 原始量表值与档案详情（SubjectDetail.visits）共用同一个判定：患者摘要只讲结论，
        // 不能因为换了页面（时间轴）就绕开原始量表权限。未通过时访视事件只保留日期与版本，
        // 不再携带 MMSE / MoCA / CDR-SB。
        boolean rawScores = accessPolicy.canReadRawScores(principal, subjectId);
        events.addAll(visitEvents(subjectId, rawScores));
        events.addAll(assessmentEvents(subjectId));
        events.addAll(followUpEvents(subjectId));
        events.sort(Comparator.comparing(TimelineEvent::occurredAt).reversed());
        if (events.size() > MAX_EVENTS) {
            events = new ArrayList<>(events.subList(0, MAX_EVENTS));
        }

        return new TimelineView(
                true,
                subjectId,
                subject == null ? null : subject[0],
                subject == null ? null : subject[1],
                events);
    }

    /**
     * 只取当前版本（{@code is_current = TRUE}）。被更正掉的旧版本不在时间线上重复出现，
     * 患者看到的「那次访视」永远只有一份，更正信息用状态标签说明。
     *
     * <p>{@code rawScores} 为 {@code false} 时连查询列都不取原始量表值，避免出现「查询了但
     * 拼装时忘了过滤」这类回退。
     */
    private List<TimelineEvent> visitEvents(Long subjectId, boolean rawScores) {
        String columns = rawScores
                ? "visit_code, visit_date, raw_mmse, raw_moca, raw_cdr_sb, mmse, moca, cdr_sb, version_no"
                : "visit_code, visit_date, NULL, NULL, NULL, NULL, NULL, NULL, version_no";
        return jdbc.query(
                "SELECT " + columns
                        + " FROM visit WHERE subject_id = ? AND is_current = TRUE"
                        + " ORDER BY visit_date DESC, id DESC",
                (rs, rowNum) -> {
                    List<TimelineValue> values = new ArrayList<>();
                    addValue(values, "MMSE", rs.getString(3), rs.getBigDecimal(6));
                    addValue(values, "MoCA", rs.getString(4), rs.getBigDecimal(7));
                    addValue(values, "CDR-SB", rs.getString(5), rs.getBigDecimal(8));
                    int versionNo = rs.getInt(9);
                    String detail = !rawScores
                            ? "本次访视已完成，量表结果以医生说明为准。"
                            : values.isEmpty() ? "本次访视没有记录量表结果。" : "本次访视的量表记录。";
                    return new TimelineEvent(
                            "VISIT",
                            atStartOfDay(rs.getDate(2).toLocalDate()),
                            "访视 " + rs.getString(1),
                            detail,
                            versionNo > 1 ? "已更正（第 " + versionNo + " 版）" : null,
                            values);
                },
                subjectId);
    }

    private List<TimelineEvent> assessmentEvents(Long subjectId) {
        List<TimelineEvent> events = new ArrayList<>();
        for (RiskAssessment assessment : assessmentRepository.findBySubjectIdOrderByCreatedAtDescIdDesc(subjectId)) {
            if (assessment.getCreatedAt() == null) {
                continue;
            }
            events.add(new TimelineEvent(
                    "ASSESSMENT",
                    assessment.getCreatedAt(),
                    "医生结论",
                    summary.conclusionOf(assessment),
                    reviewStatusText(assessment.getReviewStatus()),
                    List.of()));
        }
        return events;
    }

    private List<TimelineEvent> followUpEvents(Long subjectId) {
        List<TimelineEvent> events = new ArrayList<>();
        Instant now = Instant.now();
        for (FollowUpPlan plan : followUpRepository.findBySubjectIdOrderByScheduledAtAscIdAsc(subjectId)) {
            if (plan.getScheduledAt() == null) {
                continue;
            }
            StringBuilder detail = new StringBuilder(plan.getPurpose());
            if (plan.getVisitLocation() != null && !plan.getVisitLocation().isBlank()) {
                detail.append(" · 地点：").append(plan.getVisitLocation());
            }
            if (plan.getPreparation() != null && !plan.getPreparation().isBlank()) {
                detail.append(" · 准备：").append(plan.getPreparation());
            }
            events.add(new TimelineEvent(
                    "FOLLOW_UP",
                    plan.getScheduledAt(),
                    "复诊安排",
                    detail.toString(),
                    followUpStatusText(plan, now),
                    List.of()));
        }
        return events;
    }

    /**
     * 原始值是医生录入的原文（例如 {@code "27.5"}），优先展示；只有原始值缺失的老数据才退回
     * 数值列，避免把 {@code 27.50} 这种补零格式当成患者填错。
     */
    private static void addValue(List<TimelineValue> values, String label, String raw, BigDecimal numeric) {
        String text = raw != null && !raw.isBlank() ? raw.trim()
                : numeric == null ? null : numeric.stripTrailingZeros().toPlainString();
        if (text != null) {
            values.add(new TimelineValue(label, text));
        }
    }

    private static Instant atStartOfDay(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant();
    }

    private static String reviewStatusText(RiskAssessment.ReviewStatus status) {
        return switch (status) {
            case PENDING -> "待医生复核";
            case ACKNOWLEDGED -> "医生已复核";
            case NEEDS_DATA -> "需要补充资料";
            case REJECTED -> "未通过复核";
        };
    }

    private static String followUpStatusText(FollowUpPlan plan, Instant now) {
        return switch (plan.getStatus()) {
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
            case PLANNED -> plan.getScheduledAt().isBefore(now) ? "已过期未完成" : "待复诊";
        };
    }
}
