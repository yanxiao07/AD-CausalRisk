package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectPermissionRepository;
import com.adcausalrisk.backend.subject.SubjectRepository;
import com.adcausalrisk.backend.subject.entity.Subject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Patient-facing research validation summary.
 *
 * <p>The research model output contains uncalibrated CIFs, model identity, explanation weights and
 * a graph. None of those may reach a patient. This service keeps the derivation on the server and
 * exposes only a qualitative sentence, a state and the fixed research-use limitation.
 *
 * <p>Only a doctor-confirmed result is shown as a conclusion. A pending, rejected or failed task
 * produces a plain-language status instead of falling back to an older result.
 */
@Service
public class PatientResearchSummaryService {

    private static final Logger log = LoggerFactory.getLogger(PatientResearchSummaryService.class);

    /** Qualitative bands for the research CIF; deliberately coarse and not clinically calibrated. */
    private static final double CIF_MEDIUM_THRESHOLD = 0.10;
    private static final double CIF_HIGH_THRESHOLD = 0.20;

    private static final String NOTICE = "研究验证用途，非临床诊断。";

    private static final List<String> LIMITATIONS =
            List.of(
                    "结论来自研究模型，未经临床校准，不代表个人真实患病概率。",
                    "具体诊疗请以医生面诊为准；如有不适请及时就医。");

    private static final String LATEST_TASK_SQL =
            """
            SELECT t.id,t.status,t.review_status,t.reviewed_at,t.result_json,t.created_at,
                   s.subject_id,sub.subject_code,sub.alias
              FROM research_table_task t
              JOIN research_table_snapshot s ON s.id=t.snapshot_id
              JOIN subject sub ON sub.id=s.subject_id
             WHERE s.subject_id=?
             ORDER BY t.created_at DESC,t.id DESC
             LIMIT 1
            """;

    private final SubjectPermissionRepository permissionRepository;
    private final SubjectRepository subjectRepository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PatientResearchSummaryService(
            SubjectPermissionRepository permissionRepository,
            SubjectRepository subjectRepository,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.permissionRepository = permissionRepository;
        this.subjectRepository = subjectRepository;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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
            List<String> limitations) {}

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
                    List.of("如需绑定，请联系您的主治医生。"));
        }

        ResearchCandidate selected = null;
        for (Long subjectId : boundSubjects) {
            ResearchCandidate candidate = latestTask(subjectId);
            if (candidate == null) {
                continue;
            }
            if (selected == null
                    || candidate.createdAt().isAfter(selected.createdAt())
                    || (candidate.createdAt().equals(selected.createdAt())
                            && candidate.taskId() > selected.taskId())) {
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
                    LIMITATIONS);
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
            case "CONFIRMED" -> confirmedView(task);
            default -> view(task, "PENDING", "研究验证结果正在等待医生复核确认。");
        };
    }

    private ResearchSummaryView confirmedView(ResearchCandidate task) {
        JsonNode result = parse(task.resultJson());
        Double cif = researchCif(result);
        if (cif == null || !predictionAllowed(result)) {
            // The review itself is complete. Keep the reviewed state and explain the limitation
            // instead of presenting the task as an unreviewed/unavailable result.
            return view(
                    task,
                    "CONFIRMED",
                    "医生已复核本次研究验证结果；当前输入尚不足以形成个人风险提示，"
                            + "本次结论不用于临床决策，请按医嘱随访。");
        }
        String band =
                cif >= CIF_HIGH_THRESHOLD
                        ? "偏高"
                        : cif >= CIF_MEDIUM_THRESHOLD ? "中等" : "较低";
        return view(
                task,
                "CONFIRMED",
                "医生已复核研究验证结果：模型提示痴呆相关风险信号" + band + "。建议咨询医生，结合面诊进一步评估。");
    }

    private ResearchSummaryView view(ResearchCandidate task, String state, String summary) {
        return new ResearchSummaryView(
                task.subjectId(),
                task.subjectCode(),
                task.alias(),
                state,
                summary,
                NOTICE,
                true,
                LIMITATIONS);
    }

    private ResearchCandidate latestTask(Long subjectId) {
        List<ResearchCandidate> rows =
                jdbc.query(
                        LATEST_TASK_SQL,
                        (rs, rowNum) ->
                                new ResearchCandidate(
                                        rs.getLong("id"),
                                        rs.getLong("subject_id"),
                                        rs.getString("subject_code"),
                                        rs.getString("alias"),
                                        rs.getString("status"),
                                        rs.getString("review_status"),
                                        instant(rs.getTimestamp("reviewed_at")),
                                        instant(rs.getTimestamp("created_at")),
                                        rs.getString("result_json")),
                        subjectId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception failure) {
            log.warn("研究验证结果 JSON 无法解析，患者摘要降级为通用文案");
            return null;
        }
    }

    private boolean predictionAllowed(JsonNode result) {
        return result != null
                && result.path("admission").path("predictionAllowed").asBoolean(false);
    }

    private Double researchCif(JsonNode result) {
        if (result == null) {
            return null;
        }
        Double ad = number(result.path("prediction").path("adCif24m"));
        Double other = number(result.path("prediction").path("otherDementiaCif24m"));
        if (ad == null) {
            return other;
        }
        if (other == null) {
            return ad;
        }
        return Math.max(ad, other);
    }

    private Double number(JsonNode node) {
        return node != null && node.isNumber() ? node.asDouble() : null;
    }

    private record ResearchCandidate(
            long taskId,
            Long subjectId,
            String subjectCode,
            String alias,
            String status,
            String reviewStatus,
            Instant reviewedAt,
            Instant createdAt,
            String resultJson) {}
}
