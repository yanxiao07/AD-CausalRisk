package com.adcausalrisk.backend.user;

import com.adcausalrisk.backend.audit.AuditService;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.BusinessQueries;
import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.qualitycheck.CurrentArchiveQuality;
import com.adcausalrisk.backend.qualitycheck.CurrentArchiveQuality.Gate;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/patient-account-requests")
public class PatientAccountRequestController {
    private final JdbcTemplate jdbc;
    private final CurrentArchiveQuality archiveQuality;
    private final UserManagementService users;
    private final AuditService audit;

    public PatientAccountRequestController(JdbcTemplate jdbc, CurrentArchiveQuality archiveQuality,
            UserManagementService users, AuditService audit) {
        this.jdbc = jdbc;
        this.archiveQuality = archiveQuality;
        this.users = users;
        this.audit = audit;
    }

    record AccountApplication(long subjectId) {}
    record Issuance(String username, String password, boolean identityVerified) {}
    record DoctorHandoff(long doctorId, boolean identityVerified) {}
    record AccountRequest(long id, long subjectId, String subjectCode, String alias,
                          String doctor, String status, String qualityStatus, boolean canIssue) {}
    record OnboardingStatus(String accountStatus, String qualityStatus, Long qualityCheckId,
                            boolean canApply, boolean resubmissionRequired) {}
    private record Request(long id, long subjectId, long doctorId, Long qualityId, Long issuedUserId) {}

    @GetMapping("/status")
    public Envelope<OnboardingStatus> status(@RequestParam long subjectId, Authentication authentication) {
        var actor = (AppUserPrincipal) authentication.getPrincipal();
        requireDoctor(actor, subjectId);
        return Envelope.success(onboardingStatus(subjectId, actor.userId()));
    }

    @PostMapping
    @Transactional
    public Envelope<?> request(@RequestBody AccountApplication body, Authentication authentication) {
        var actor = (AppUserPrincipal) authentication.getPrincipal();
        long subjectId = body.subjectId();
        requireDoctor(actor, subjectId);
        requireUnboundSubject(subjectId);
        var gate = requireReady(subjectId);
        var previous = findRequest(subjectId);
        if (previous != null) {
            if (previous.doctorId() != actor.userId() || previous.issuedUserId() != null) {
                throw conflict("此档案已有其他申请或已绑定患者账号");
            }
            if (!Objects.equals(previous.qualityId(), gate.id())) {
                jdbc.update("UPDATE patient_account_request SET quality_check_id=? WHERE id=?",
                        gate.id(), previous.id());
                audit.recordQuietly(actor.userId(), "PATIENT_ACCOUNT_QUALITY_REFRESHED", "subjectId=" + subjectId);
            }
            return Envelope.success(Map.of("status", "PENDING"));
        }
        try {
            jdbc.update("""
                    INSERT INTO patient_account_request(subject_id, requested_by, quality_check_id, created_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    """, subjectId, actor.userId(), gate.id());
        } catch (DuplicateKeyException alreadyRequested) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "此档案已有账号申请");
        }
        audit.recordQuietly(actor.userId(), "PATIENT_ACCOUNT_REQUESTED", "subjectId=" + body.subjectId());
        return Envelope.success(Map.of("status", "PENDING"));
    }

    @GetMapping
    public Envelope<List<AccountRequest>> list(Authentication authentication) {
        BusinessQueries.admin((AppUserPrincipal) authentication.getPrincipal());
        var pending = jdbc.queryForList("""
                SELECT r.id,s.id AS subject_id,s.subject_code,s.alias,u.username,u.display_name,
                       r.issued_user_id,r.quality_check_id,u.id AS doctor_id
                  FROM patient_account_request r
                  JOIN subject s ON s.id=r.subject_id JOIN sys_user u ON u.id=COALESCE(r.responsible_doctor_id,r.requested_by)
                 ORDER BY r.id DESC
                """);
        return Envelope.success(pending.stream().map(row -> {
            long id = ((Number) row.get("id")).longValue();
            long subjectId = ((Number) row.get("subject_id")).longValue();
            boolean issued = row.get("issued_user_id") != null;
            var gate = issued ? new Gate(null, "ISSUED", null) : archiveQuality.forSubject(subjectId);
            boolean ready = !issued && gate.ready() && Objects.equals(gate.id(), row.get("quality_check_id"))
                    && !boundPatient(subjectId) && doctorAllotted(((Number) row.get("doctor_id")).longValue(), subjectId);
            String doctor = row.get("display_name") == null ? (String) row.get("username") : (String) row.get("display_name");
            return new AccountRequest(id, subjectId, (String) row.get("subject_code"), (String) row.get("alias"),
                    doctor, issued ? "ISSUED" : "PENDING", gate.status(), ready);
        }).toList());
    }

    @PostMapping("/{id}/issue")
    @Transactional
    public Envelope<?> issue(@PathVariable long id, @RequestBody Issuance body, Authentication authentication) {
        var admin = (AppUserPrincipal) authentication.getPrincipal();
        BusinessQueries.admin(admin);
        if (body.username() == null || body.password() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请输入账号及初始密码");
        }
        if (!body.identityVerified()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请先核对患者、档案和责任医生");
        long subjectId = jdbc.query("""
                SELECT subject_id FROM patient_account_request WHERE id=?
                """, (row, index) -> row.getLong(1), id).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "账号申请不存在"));
        requireUnboundSubject(subjectId);
        Request application = jdbc.query("""
                SELECT * FROM patient_account_request
                 WHERE id=? AND issued_user_id IS NULL FOR UPDATE
                """, this::mapRequest, id).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "账号申请不存在或已发放"));
        var gate = requireReady(subjectId);
        if (!Objects.equals(gate.id(), application.qualityId())) {
            throw conflict("资料或质控记录已更新，请申请医生核对后重新提交");
        }
        if (!doctorAllotted(application.doctorId(), subjectId)) {
            throw conflict("当前责任医生已失去档案管理权限，请先办理医生交接");
        }
        long userId;
        try {
            userId = users.issuePatient(subjectId, body.username(), body.password(), admin);
        } catch (DuplicateKeyException duplicate) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "账号名已存在");
        }
        jdbc.update("""
                UPDATE patient_account_request
                SET issued_user_id=?,responsible_doctor_id=?,issued_at=CURRENT_TIMESTAMP WHERE id=?
                """, userId, application.doctorId(), id);
        audit.recordQuietly(admin.userId(), "PATIENT_ACCOUNT_ISSUED", "subjectId=" + subjectId + " userId=" + userId);
        return Envelope.success(Map.of("status", "ISSUED", "userId", userId));
    }

    @PostMapping("/{id}/responsible-doctor")
    @Transactional
    public Envelope<?> handoff(@PathVariable long id, @RequestBody DoctorHandoff body, Authentication authentication) {
        var admin = (AppUserPrincipal) authentication.getPrincipal();
        BusinessQueries.admin(admin);
        if (!body.identityVerified()) throw conflict("请先核对档案和接手医生");
        Long subjectId = jdbc.query("SELECT subject_id FROM patient_account_request WHERE id=?",
                (row, index) -> row.getLong(1), id).stream().findFirst().orElseThrow(() -> conflict("账号申请不存在"));
        jdbc.queryForObject("SELECT id FROM subject WHERE id=? FOR UPDATE", Long.class, subjectId);
        Request application = findRequest(subjectId);
        if (application == null || !doctorAllotted(body.doctorId(), subjectId)) {
            throw conflict("接手医生须为已启用且具有本档案管理权限的医生");
        }
        if (application.doctorId() != body.doctorId()) {
            jdbc.update("UPDATE patient_account_request SET responsible_doctor_id=? WHERE id=?", body.doctorId(), id);
            audit.recordQuietly(admin.userId(), "PATIENT_ACCOUNT_DOCTOR_HANDOFF",
                    "subjectId=" + subjectId + " previousDoctorId=" + application.doctorId() + " doctorId=" + body.doctorId());
        }
        return Envelope.success(Map.of("status", application.issuedUserId() == null ? "PENDING" : "ISSUED"));
    }

    private OnboardingStatus onboardingStatus(long subjectId, long doctorId) {
        var request = findRequest(subjectId);
        if (boundPatient(subjectId)) return new OnboardingStatus("ISSUED", "ISSUED", null, false, false);
        var gate = archiveQuality.forSubject(subjectId);
        boolean pending = request != null;
        boolean stale = pending && (!gate.ready() || !Objects.equals(gate.id(), request.qualityId()));
        boolean canApply = gate.ready() && (!pending || (stale && request.doctorId() == doctorId));
        return new OnboardingStatus(pending ? "PENDING" : "NONE", gate.status(), gate.id(), canApply, stale);
    }

    private Request findRequest(long subjectId) {
        return jdbc.query("SELECT * FROM patient_account_request WHERE subject_id=?", this::mapRequest, subjectId)
                .stream().findFirst().orElse(null);
    }

    private Request mapRequest(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        Long doctorId = row.getObject("responsible_doctor_id", Long.class);
        return new Request(row.getLong("id"), row.getLong("subject_id"), doctorId == null ? row.getLong("requested_by") : doctorId,
                row.getObject("quality_check_id", Long.class), row.getObject("issued_user_id", Long.class));
    }

    private CurrentArchiveQuality.Gate requireReady(long subjectId) {
        var gate = archiveQuality.forSubject(subjectId);
        if (!gate.ready()) throw conflict("请完成当前档案质控并补齐影响评估的核心量表信息");
        return gate;
    }

    private void requireDoctor(AppUserPrincipal actor, long subjectId) {
        if (actor.roleCodes().contains("PATIENT") || !actor.roleCodes().contains("DOCTOR")
                || !doctorAllotted(actor.userId(), subjectId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private boolean doctorAllotted(long userId, long subjectId) {
        var matches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM subject_permission p JOIN sys_user u ON u.id=p.user_id
                JOIN user_role ur ON ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id
                WHERE p.subject_id=? AND u.id=? AND u.status='ACTIVE' AND r.role_code='DOCTOR'
                  AND p.permission_type='MANAGE'
                """, Long.class, subjectId, userId);
        return matches != null && matches > 0;
    }

    private void requireUnboundSubject(long subjectId) {
        if (jdbc.queryForList("SELECT id FROM subject WHERE id=? FOR UPDATE", Long.class, subjectId).isEmpty()) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (boundPatient(subjectId)) throw conflict("此档案已绑定患者账号");
    }

    private boolean boundPatient(long subjectId) {
        Integer bound = jdbc.queryForObject("""
                SELECT COUNT(*) FROM subject_permission sp
                JOIN user_role ur ON ur.user_id=sp.user_id JOIN sys_role r ON r.id=ur.role_id
                WHERE sp.subject_id=? AND r.role_code='PATIENT'
                """, Integer.class, subjectId);
        return bound != null && bound > 0;
    }

    private static BusinessException conflict(String note) {
        return new BusinessException(ErrorCode.TASK_STATE_CONFLICT, note);
    }
}
