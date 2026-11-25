package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.audit.AuditService;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.followup.FollowUpPlanRepository;
import com.adcausalrisk.backend.followup.entity.FollowUpPlan;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectAccessPolicy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 随访自助里患者自己可以做的动作。确认与申请改期已经由
 * {@link com.adcausalrisk.backend.followup.FollowUpService#respond} 提供，这里只补上「取消」：
 * 医生排好的复诊如果患者确实来不了，应当由患者本人取消，而不是让记录一直挂着过期。
 *
 * <p>取消必须带 {@code appointmentRevision}：患者看到的是某一版安排，如果医院在他提交之前
 * 又改了一次时间，就拒绝这次取消，避免取消掉一个他从没见过的新安排。
 *
 * <p>取消会覆盖同一版本的回执：患者先确认、后取消时，医生端只应看到「已取消」，
 * 而不是「已取消」旁边还挂着一句「患者已确认」。回执表按 (复诊, 版本) 唯一，
 * 覆盖写正是让同一版安排只保留最新一次表态。
 */
@Service
public class PatientFollowUpSelfService {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private final FollowUpPlanRepository followUpRepository;
    private final PatientSummaryService summary;
    private final SubjectAccessPolicy accessPolicy;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public PatientFollowUpSelfService(FollowUpPlanRepository followUpRepository,
                                      PatientSummaryService summary,
                                      SubjectAccessPolicy accessPolicy,
                                      AuditService audit,
                                      JdbcTemplate jdbc) {
        this.followUpRepository = followUpRepository;
        this.summary = summary;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /**
     * 取消本人复诊。只允许取消「还没到时间、且仍是待复诊」的安排：已经完成的记录取消没有意义，
     * 已经过期的安排应当联系医院核对，而不是由患者单方面抹掉。
     */
    @Transactional
    public void cancelOwnAppointment(long followUpId, long appointmentRevision, AppUserPrincipal principal) {
        if (!principal.roleCodes().contains("PATIENT")) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "仅患者本人可取消复诊");
        }
        Long subjectId = summary.subjectForPatient(principal);
        if (subjectId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "账号还没有绑定健康档案，无法取消复诊");
        }

        FollowUpPlan plan = followUpRepository.findForUpdate(followUpId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "复诊安排不存在"));
        if (!subjectId.equals(plan.getSubjectId()) || !accessPolicy.canView(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权操作该复诊");
        }
        if (plan.getAppointmentRevision() != appointmentRevision) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "复诊安排已更新，请刷新后查看新安排");
        }
        if (plan.getStatus() != FollowUpPlan.Status.PLANNED) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "本次复诊已结束，无法取消");
        }
        if (plan.getScheduledAt() == null || !plan.getScheduledAt().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "本次复诊时间已过，请联系医院核对安排");
        }

        String when = WHEN.format(plan.getScheduledAt());
        replaceResponseWithCancellation(followUpId, appointmentRevision, principal.userId());
        plan.changeStatus(FollowUpPlan.Status.CANCELLED);
        followUpRepository.saveAndFlush(plan);
        audit.recordQuietly(
                principal.userId(),
                "PATIENT_FOLLOW_UP_CANCEL",
                "患者取消复诊 " + when + " subjectId=" + subjectId);
    }

    /**
     * 把同一版本的回执覆盖成「已取消」。先改后插：患者确认过就覆盖那一行，从未表态则补一行，
     * 这样医生端无论哪种情况都不会读到「已取消 + 患者已确认」这种自相矛盾的组合。
     * 改期说明一并清空——安排已经取消，再留着「那天上午要上班」会被误读成仍在等医院处理。
     */
    private void replaceResponseWithCancellation(long followUpId, long appointmentRevision, long patientId) {
        int updated = jdbc.update("""
                UPDATE follow_up_response SET response_status='CANCELLED',response_note=NULL,updated_at=CURRENT_TIMESTAMP
                WHERE follow_up_id=? AND appointment_revision=? AND patient_id=?
                """, followUpId, appointmentRevision, patientId);
        if (updated > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO follow_up_response(follow_up_id,appointment_revision,patient_id,response_status,response_note,updated_at)
                VALUES(?,?,?,'CANCELLED',NULL,CURRENT_TIMESTAMP)
                """, followUpId, appointmentRevision, patientId);
    }
}
