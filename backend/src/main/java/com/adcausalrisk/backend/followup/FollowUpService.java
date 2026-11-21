package com.adcausalrisk.backend.followup;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.followup.entity.FollowUpPlan;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectAccessPolicy;
import com.adcausalrisk.backend.subject.SubjectRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Creates, reschedules and closes follow-up plans, scoped to a subject the caller can see. */
@Service
public class FollowUpService {

    private static final int MAX_PURPOSE_LENGTH = 1000;

    private final FollowUpPlanRepository followUpRepository;
    private final SubjectRepository subjectRepository;
    private final SubjectAccessPolicy accessPolicy;
    private final TransactionTemplate writeTransaction;
    private final JdbcTemplate jdbc;

    public record BookingResponse(String status, String note, long patientId) {}

    public FollowUpService(
            FollowUpPlanRepository followUpRepository,
            SubjectRepository subjectRepository,
            SubjectAccessPolicy accessPolicy,
            PlatformTransactionManager transactionManager,
            JdbcTemplate jdbc) {
        this.followUpRepository = followUpRepository;
        this.subjectRepository = subjectRepository;
        this.accessPolicy = accessPolicy;
        this.jdbc = jdbc;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** {@code POST /api/follow-ups}. */
    public FollowUpPlan create(
            Long subjectId, Instant scheduledAt, String purpose, AppUserPrincipal principal) {
        return create(subjectId, scheduledAt, purpose, null, null, principal);
    }

    public FollowUpPlan create(Long subjectId, Instant scheduledAt, String purpose, String location,
                               String preparation, AppUserPrincipal principal) {
        if (scheduledAt == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "scheduledAt 不能为空");
        }
        String text = purpose == null ? "" : purpose.trim();
        if (text.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "purpose 不能为空");
        }
        if (text.length() > MAX_PURPOSE_LENGTH) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "purpose 长度不能超过 " + MAX_PURPOSE_LENGTH);
        }
        if (!subjectRepository.existsById(subjectId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "档案不存在");
        }
        requireWriteAccess(principal, subjectId);
        validateDetails(location, preparation);

        return writeTransaction.execute(status -> {
            var plan = new FollowUpPlan(subjectId, scheduledAt, text, principal.userId());
            plan.setBookingDetails(location, preparation);
            return followUpRepository.saveAndFlush(plan);
        });
    }

    /** {@code PATCH /api/follow-ups/{followUpId}} - reschedule, complete or cancel. */
    public FollowUpPlan update(
            Long followUpId,
            String status,
            Instant scheduledAt,
            String purpose,
            AppUserPrincipal principal) {
        return update(followUpId, status, scheduledAt, purpose, null, principal);
    }

    public FollowUpPlan update(
            Long followUpId,
            String status,
            Instant scheduledAt,
            String purpose,
            String notes,
            AppUserPrincipal principal) {
        return update(followUpId, status, scheduledAt, purpose, notes, null, null, null, principal);
    }

    public FollowUpPlan update(Long followUpId, String status, Instant scheduledAt, String purpose, String notes,
                               String location, String preparation, Long expectedRevision, AppUserPrincipal principal) {
        FollowUpPlan plan =
                followUpRepository
                        .findById(followUpId)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.VALIDATION_ERROR, "随访计划不存在"));
        requireWriteAccess(principal, plan.getSubjectId());

        FollowUpPlan.Status parsed = parseStatus(status);
        validateDetails(location, preparation);
        if (purpose != null && purpose.trim().length() > MAX_PURPOSE_LENGTH) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "purpose 长度不能超过 " + MAX_PURPOSE_LENGTH);
        }
        return writeTransaction.execute(
                transaction -> {
                    FollowUpPlan managed = followUpRepository.findForUpdate(followUpId).orElseThrow();
                    requireWriteAccess(principal, managed.getSubjectId());
                    if (expectedRevision != null) requireRevision(managed, expectedRevision);
                    long previousRevision = managed.getAppointmentRevision();
                    Instant previousTime = managed.getScheduledAt();
                    if (scheduledAt != null && !scheduledAt.equals(previousTime) && !scheduledAt.isAfter(Instant.now())) {
                        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "改期时间必须晚于当前时间");
                    }
                    managed.editBooking(scheduledAt, purpose, location, preparation);
                    if (previousRevision != managed.getAppointmentRevision() && previousTime.equals(managed.getScheduledAt())) {
                        BookingResponse previous = responseFor(managed.getId(), previousRevision);
                        if (previous != null && "RESCHEDULE_REQUESTED".equals(previous.status())) {
                            throw conflict("改期申请待处理。请设置新的复诊时间，或选择保留原安排");
                        }
                    }
                    managed.setNotes(notes);
                    if (parsed != null) {
                        managed.changeStatus(parsed);
                    }
                    return followUpRepository.saveAndFlush(managed);
                });
    }

    public BookingResponse getBookingResponse(FollowUpPlan plan) {
        return responseFor(plan.getId(), plan.getAppointmentRevision());
    }

    private BookingResponse responseFor(long id, long revision) {
        return jdbc.query("""
                SELECT response_status,response_note,patient_id FROM follow_up_response
                WHERE follow_up_id=? AND appointment_revision=?
                """, (row, index) -> new BookingResponse(row.getString(1), row.getString(2), row.getLong(3)),
                id, revision).stream().findFirst().orElse(null);
    }

    public void respond(long id, long revision, String action, String note, Long subjectId, AppUserPrincipal actor) {
        if (!actor.roleCodes().contains("PATIENT") || subjectId == null) throw denied();
        String status = switch (Objects.toString(action, "")) {
            case "CONFIRM" -> "CONFIRMED";
            case "REQUEST_RESCHEDULE" -> "RESCHEDULE_REQUESTED";
            default -> throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择确认复诊或申请改期");
        };
        String text = "RESCHEDULE_REQUESTED".equals(status) && note != null && !note.isBlank() ? note.trim() : null;
        if (text != null && text.length() > 280) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "改期说明不能超过280字");
        }
        writeTransaction.executeWithoutResult(ignored -> {
            FollowUpPlan plan = followUpRepository.findForUpdate(id).orElseThrow(FollowUpService::denied);
            if (!subjectId.equals(plan.getSubjectId()) || !accessPolicy.canView(actor, subjectId)) throw denied();
            requireRevision(plan, revision);
            if (plan.getStatus() != FollowUpPlan.Status.PLANNED || !plan.getScheduledAt().isAfter(Instant.now())) {
                throw conflict("本次复诊已结束，请联系医院核对安排");
            }
            BookingResponse existing = getBookingResponse(plan);
            if (existing == null) {
                jdbc.update("""
                        INSERT INTO follow_up_response(follow_up_id,appointment_revision,patient_id,response_status,response_note,updated_at)
                        VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)
                        """, id, revision, actor.userId(), status, text);
                return;
            }
            if (existing.patientId() != actor.userId()) throw denied();
            if (existing.status().equals(status) && Objects.equals(existing.note(), text)) return;
            if ("RESCHEDULE_REQUESTED".equals(existing.status())) throw conflict("改期申请正在处理，请等待医院回复");
            jdbc.update("""
                    UPDATE follow_up_response SET response_status=?,response_note=?,updated_at=CURRENT_TIMESTAMP
                    WHERE follow_up_id=? AND appointment_revision=?
                    """, status, text, id, revision);
        });
    }

    public void declineReschedule(long id, long revision, AppUserPrincipal actor) {
        writeTransaction.executeWithoutResult(ignored -> {
            FollowUpPlan plan = followUpRepository.findForUpdate(id).orElseThrow(FollowUpService::denied);
            requireWriteAccess(actor, plan.getSubjectId());
            requireRevision(plan, revision);
            if (plan.getStatus() != FollowUpPlan.Status.PLANNED) throw conflict("本次复诊已结束");
            BookingResponse response = getBookingResponse(plan);
            if (response == null) throw conflict("该复诊没有待处理的改期申请");
            if ("RESCHEDULE_DECLINED".equals(response.status())) return;
            if (!"RESCHEDULE_REQUESTED".equals(response.status())) throw conflict("该复诊没有待处理的改期申请");
            jdbc.update("""
                    UPDATE follow_up_response SET response_status='RESCHEDULE_DECLINED',updated_at=CURRENT_TIMESTAMP
                    WHERE follow_up_id=? AND appointment_revision=?
                    """, id, revision);
        });
    }

    private static void requireRevision(FollowUpPlan plan, long expected) {
        if (plan.getAppointmentRevision() != expected) throw conflict("复诊安排已更新，请刷新后查看新安排");
    }

    private static void validateDetails(String location, String preparation) {
        if ((location != null && location.length() > 255) || (preparation != null && preparation.length() > 1000)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "复诊地点或准备事项超过长度限制");
        }
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.TASK_STATE_CONFLICT, message);
    }

    private static BusinessException denied() {
        return new BusinessException(ErrorCode.ACCESS_DENIED, "无权操作该复诊");
    }

    /** {@code GET /api/subjects/{subjectId}/follow-ups}. */
    public List<FollowUpPlan> list(Long subjectId, AppUserPrincipal principal) {
        if (!subjectRepository.existsById(subjectId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "档案不存在");
        }
        requireSubjectAccess(principal, subjectId);
        return followUpRepository.findBySubjectIdOrderByScheduledAtAscIdAsc(subjectId);
    }

    private void requireSubjectAccess(AppUserPrincipal principal, Long subjectId) {
        if (!accessPolicy.canView(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "无权访问该档案");
        }
    }

    private void requireWriteAccess(AppUserPrincipal principal, Long subjectId) {
        if (!accessPolicy.canWriteClinical(principal, subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "仅授权医生或管理员可维护随访");
        }
    }

    private static FollowUpPlan.Status parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return FollowUpPlan.Status.valueOf(status.trim());
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "status 仅支持 PLANNED / COMPLETED / CANCELLED");
        }
    }
}
