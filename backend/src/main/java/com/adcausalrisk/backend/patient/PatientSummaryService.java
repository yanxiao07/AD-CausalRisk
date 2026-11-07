package com.adcausalrisk.backend.patient;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.adcausalrisk.backend.agent.AgentResult;
import com.adcausalrisk.backend.assessment.RiskAssessmentRepository;
import com.adcausalrisk.backend.assessment.entity.RiskAssessment;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.followup.FollowUpPlanRepository;
import com.adcausalrisk.backend.followup.entity.FollowUpPlan;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectPermissionRepository;
import com.adcausalrisk.backend.subject.SubjectRepository;
import com.adcausalrisk.backend.subject.entity.Subject;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds the patient-facing summary ({@code GET /api/patients/me/summary}).
 *
 * <p>This is the de-identified view: it never returns the raw {@code riskScore}, the causal graph,
 * the explanation contributions, or any internal trace. Only a plain-language conclusion, the next
 * follow-up and a short list of limitations are exposed - which is exactly what
 * {@code PatientAssessmentSummary} in the contract describes.
 */
@Service
public class PatientSummaryService {

    private static final Logger log = LoggerFactory.getLogger(PatientSummaryService.class);

    private static final DateTimeFormatter FOLLOW_UP_DATE =
            DateTimeFormatter.ofPattern("yyyy年M月d日").withZone(ZoneId.of("Asia/Shanghai"));

    private static final int MAX_EXTRA_LIMITATIONS = 2;
    private static final int MAX_LIMITATION_LENGTH = 120;

    private final SubjectPermissionRepository permissionRepository;
    private final SubjectRepository subjectRepository;
    private final RiskAssessmentRepository assessmentRepository;
    private final FollowUpPlanRepository followUpRepository;
    private final ObjectMapper objectMapper;

    public PatientSummaryService(
            SubjectPermissionRepository permissionRepository,
            SubjectRepository subjectRepository,
            RiskAssessmentRepository assessmentRepository,
            FollowUpPlanRepository followUpRepository,
            ObjectMapper objectMapper) {
        this.permissionRepository = permissionRepository;
        this.subjectRepository = subjectRepository;
        this.assessmentRepository = assessmentRepository;
        this.followUpRepository = followUpRepository;
        this.objectMapper = objectMapper;
    }

    /** De-identified summary; the only shape a PATIENT is allowed to receive. */
    public record PatientSummaryView(
            Long subjectId,
            String subjectCode,
            String alias,
            Long assessmentId,
            boolean hasAssessment,
            boolean pendingReview,
            String summary,
            String followUpSummary,
            List<String> limitations) {
    }

    public PatientSummaryView currentSummary(AppUserPrincipal principal) {
        if (!principal.roleCodes().contains("PATIENT")) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "仅患者本人可查看该摘要");
        }

        List<Long> boundSubjects = permissionRepository.findDistinctSubjectIdByUserId(principal.userId());
        if (boundSubjects.isEmpty()) {
            return new PatientSummaryView(
                    null, null, null, null, false, false,
                    "您的账号还没有绑定健康档案，绑定后这里会显示医生复核过的结论。",
                    "绑定档案后，这里会显示医生安排的复诊时间。",
                    List.of("如需绑定，请联系您的主治医生。"));
        }

        // A patient normally holds exactly one subject. If a misconfiguration grants several,
        // prefer the one with the most recent result (that is the report the patient cares about),
        // then fall back to the smallest id so the answer stays deterministic.
        Long subjectId = pickSubject(boundSubjects);
        Subject subject = subjectRepository.findById(subjectId).orElse(null);
        RiskAssessment latest = assessmentRepository.findFirstBySubjectIdOrderByIdDesc(subjectId).orElse(null);
        AgentResult result = latest == null ? null : parse(latest);

        return new PatientSummaryView(
                subjectId,
                subject == null ? null : subject.getSubjectCode(),
                subject == null ? null : subject.getAlias(),
                latest == null ? null : latest.getId(),
                latest != null,
                latest != null && latest.getReviewStatus() == RiskAssessment.ReviewStatus.PENDING,
                buildSummary(latest, result),
                buildFollowUpSummary(subjectId),
                buildLimitations(result));
    }

    /**
     * A patient normally holds exactly one subject. If a misconfiguration grants several, prefer
     * the one with the most recent result (that is the report the patient cares about), then fall
     * back to the smallest id so the answer stays deterministic.
     *
     * <p>Deliberately no {@code Map.entry(id, null)}: {@link java.util.Map#entry} rejects null
     * values, and a bound subject that simply has no assessment yet would then throw NPE and turn
     * a normal "no report yet" page into a 500.
     */
    private Long pickSubject(List<Long> boundSubjects) {
        Long newestSubjectId = null;
        Instant newestAt = null;
        for (Long id : boundSubjects) {
            RiskAssessment latest = assessmentRepository.findFirstBySubjectIdOrderByIdDesc(id).orElse(null);
            if (latest == null || latest.getCreatedAt() == null) {
                continue;
            }
            if (newestAt == null || latest.getCreatedAt().isAfter(newestAt)) {
                newestSubjectId = id;
                newestAt = latest.getCreatedAt();
            }
        }
        if (newestSubjectId != null) {
            return newestSubjectId;
        }
        return boundSubjects.stream().min(Long::compareTo).orElseThrow();
    }

    private AgentResult parse(RiskAssessment assessment) {        try {
            return objectMapper.readValue(assessment.getAgentResultJson(), AgentResult.class);
        } catch (Exception failure) {
            log.warn("评估 {} 的 agentResult 无法解析，患者摘要降级为通用文案", assessment.getId());
            return null;
        }
    }

    private String buildSummary(RiskAssessment latest, AgentResult result) {
        if (latest == null) {
            return "您的档案还没有风险评估记录，医生完成评估后会在这里显示结论。";
        }
        RiskAssessment.ReviewStatus status = latest.getReviewStatus();
        if (status == RiskAssessment.ReviewStatus.PENDING) {
            return "您的风险评估已完成，正在等待医生复核确认。";
        }
        if (status == RiskAssessment.ReviewStatus.NEEDS_DATA) {
            return "医生希望补充一些资料后再确认结论，请留意复诊通知。";
        }
        if (status == RiskAssessment.ReviewStatus.REJECTED) {
            return "本次评估未通过医生复核，请联系医生了解下一步安排。";
        }

        String level = result == null || result.riskLevel() == null ? null : result.riskLevel().name();
        if ("UNAVAILABLE".equals(level)) {
            return "医生已确认本次评估：由于数据不足，暂未给出风险结论。";
        }
        return switch (level == null ? "" : level) {
            case "LOW" -> "医生已确认本次评估：目前风险较低，建议按计划随访。";
            case "MEDIUM" -> "医生已确认本次评估：风险中等，建议关注记忆与日常能力变化，并按期复诊。";
            case "HIGH" -> "医生已确认本次评估：风险偏高，建议尽快与医生沟通并安排专项随访。";
            default -> "医生已确认本次评估，具体结论请以门诊沟通为准。";
        };
    }

    private String buildFollowUpSummary(Long subjectId) {
        Instant next = followUpRepository.findBySubjectIdOrderByScheduledAtAscIdAsc(subjectId).stream()
                .filter(plan -> plan.getStatus() == FollowUpPlan.Status.PLANNED)
                .map(FollowUpPlan::getScheduledAt)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (next == null) {
            return "目前没有已安排的复诊。";
        }
        return "已为您安排 " + FOLLOW_UP_DATE.format(next) + " 复诊。";
    }

    private List<String> buildLimitations(AgentResult result) {
        List<String> limitations = new ArrayList<>();
        limitations.add("本摘要为面向患者的通俗说明，不构成诊断，具体诊疗请遵医嘱。");
        if (result == null || result.limitations() == null) {
            return limitations;
        }
        result.limitations().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .limit(MAX_EXTRA_LIMITATIONS)
                .forEach(text -> limitations.add(
                        text.length() > MAX_LIMITATION_LENGTH
                                ? text.substring(0, MAX_LIMITATION_LENGTH) + "…"
                                : text));
        return List.copyOf(limitations);
    }
}
