package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectPermissionRepository;
import com.adcausalrisk.backend.subject.SubjectRepository;
import com.adcausalrisk.backend.subject.entity.Subject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Patient-facing research validation summary.
 *
 * <p>The research model output contains uncalibrated CIFs, model identity, explanation weights and
 * a graph. The patient sees progress, the research-use limitation and any separately published doctor message.
 *
 * <p>Review updates progress. Publication controls whether a doctor's message is visible.
 */
@Service
public class PatientResearchSummaryService {

    private static final String NOTICE = "研究验证用途，非临床诊断。";

    private static final List<String> LIMITATIONS =
            List.of(
                    "结论来自研究模型，未经临床校准，不代表个人真实患病概率。",
                    "具体诊疗请以医生面诊为准；如有不适请及时就医。");

    private static final String LATEST_TASK_SQL =
            """
            SELECT t.id,t.status,t.review_status,t.created_at,p.message AS published_message,
                   s.subject_id,s.data_scope,sub.subject_code,sub.alias
              FROM research_table_task t
              JOIN research_table_snapshot s ON s.id=t.snapshot_id
              JOIN subject sub ON sub.id=s.subject_id
              LEFT JOIN patient_research_publication p ON p.task_id=t.id AND p.action='PUBLISHED'
                AND p.id=(SELECT MAX(p2.id) FROM patient_research_publication p2 WHERE p2.task_id=t.id)
             WHERE s.subject_id=?
               AND (s.data_scope='CONTROLLED_RESEARCH'
                    OR (s.data_scope='CONSTRUCTED_VALIDATION' AND sub.subject_code LIKE 'DEMO-%'
                        AND NOT EXISTS (SELECT 1 FROM research_table_snapshot controlled
                                        WHERE controlled.subject_id=s.subject_id
                                          AND controlled.data_scope='CONTROLLED_RESEARCH')))
             ORDER BY t.created_at DESC,t.id DESC
             LIMIT 1
            """;

    private final SubjectPermissionRepository permissionRepository;
    private final SubjectRepository subjectRepository;
    private final JdbcTemplate jdbc;

    public PatientResearchSummaryService(
            SubjectPermissionRepository permissionRepository,
            SubjectRepository subjectRepository,
            JdbcTemplate jdbc) {
        this.permissionRepository = permissionRepository;
        this.subjectRepository = subjectRepository;
        this.jdbc = jdbc;
    }

    /** De-identified shape: no task id, no result JSON, no model/CIF fields. */
    public record ResearchSummaryView(
            Long subjectId,
            String subjectCode,
            String alias,
            String state,
            String summary,
            String notice,
            boolean researchUseOnly,
            List<String> limitations,
            String publishedMessage) {}

    public ResearchSummaryView currentSummary(AppUserPrincipal principal) {
        if (!principal.roleCodes().contains("PATIENT")) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "仅患者本人可查看该摘要");
        }

        List<Long> boundSubjects =
                permissionRepository.findDistinctSubjectIdByUserId(principal.userId());
        if (boundSubjects.isEmpty()) {
            return new ResearchSummaryView(
                    null,
                    null,
                    null,
                    "UNBOUND",
                    "您的账号还没有绑定健康档案，绑定后这里会显示医生复核过的研究验证结果。",
                    NOTICE,
                    true,
                    List.of("如需绑定，请联系您的主治医生。"), null);
        }

        ResearchCandidate selected = null;
        for (Long subjectId : boundSubjects) {
            ResearchCandidate candidate = latestTask(subjectId);
            if (candidate == null) {
                continue;
            }
            if (selected == null || candidate.isPreferredTo(selected)) {
                selected = candidate;
            }
        }

        if (selected == null) {
            Long subjectId = boundSubjects.stream().min(Long::compareTo).orElseThrow();
            Subject subject = subjectRepository.findById(subjectId).orElse(null);
            return new ResearchSummaryView(
                    subjectId,
                    subject == null ? null : subject.getSubjectCode(),
                    subject == null ? null : subject.getAlias(),
                    "NO_TASK",
                    "目前还没有可展示的研究验证结果，医生完成研究评估后会在这里更新。",
                    NOTICE,
                    true,
                    LIMITATIONS, null);
        }

        return toView(selected);
    }

    private ResearchSummaryView toView(ResearchCandidate task) {
        String state;
        String summary;
        switch (task.status()) {
            case "RUNNING" -> {
                state = "IN_PROGRESS";
                summary = "研究验证正在生成，完成后需要医生复核才会显示结论。";
            }
            case "COMPLETED" -> {
                return completedView(task);
            }
            default -> {
                state = "UNAVAILABLE";
                summary = "本次研究验证暂时无法生成可展示的结论，请联系医生了解下一步安排。";
            }
        }
        return view(task, state, summary);
    }

    private ResearchSummaryView completedView(ResearchCandidate task) {
        String review = task.reviewStatus() == null ? "PENDING" : task.reviewStatus();
        return switch (review) {
            case "PENDING" ->
                    view(task, "PENDING", "研究验证结果已生成，正在等待医生复核确认。");
            case "NEEDS_DATA" ->
                    view(task, "NEEDS_DATA", "医生希望补充一些资料后再确认研究验证结果，请留意复诊通知。");
            case "REJECTED" ->
                    view(task, "REJECTED", "本次研究验证结果未通过医生复核，请联系医生了解下一步安排。");
            case "CONFIRMED" -> view(task, "CONFIRMED", "医生已完成复核。请查看医生发布的说明和复诊安排。");
            default -> view(task, "PENDING", "研究验证结果正在等待医生复核确认。");
        };
    }

    private ResearchSummaryView view(ResearchCandidate task, String state, String summary) {
        return new ResearchSummaryView(
                task.subjectId(),
                task.subjectCode(),
                task.alias(),
                state,
                summary,
                task.controlled() ? NOTICE : "测试样例。" + NOTICE,
                true,
                LIMITATIONS,
                "CONFIRMED".equals(state) ? task.publishedMessage() : null);
    }

    private ResearchCandidate latestTask(Long subjectId) {
        List<ResearchCandidate> rows =
                jdbc.query(
                        LATEST_TASK_SQL,
                        (rs, rowNum) ->
                                new ResearchCandidate(
                                        rs.getLong("id"),
                                        rs.getLong("subject_id"),
                                        "CONTROLLED_RESEARCH".equals(rs.getString("data_scope")),
                                        rs.getString("subject_code"),
                                        rs.getString("alias"),
                                        rs.getString("status"),
                                        rs.getString("review_status"),
                                        instant(rs.getTimestamp("created_at")),
                                        rs.getString("published_message")),
                        subjectId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }

    private record ResearchCandidate(
            long taskId,
            Long subjectId,
            boolean controlled,
            String subjectCode,
            String alias,
            String status,
            String reviewStatus,
            Instant createdAt,
            String publishedMessage) {
        boolean isPreferredTo(ResearchCandidate other) {
            if (controlled != other.controlled) return controlled;
            return createdAt.isAfter(other.createdAt)
                    || (createdAt.equals(other.createdAt) && taskId > other.taskId);
        }
    }
}
