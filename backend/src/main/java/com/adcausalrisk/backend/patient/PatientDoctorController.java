package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.common.*;
import com.adcausalrisk.backend.followup.FollowUpService;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class PatientDoctorController {
    private final PatientSummaryService summary;
    private final BusinessQueries queries;
    private final FollowUpService followUps;

    public PatientDoctorController(PatientSummaryService summary, BusinessQueries queries, FollowUpService followUps) {
        this.summary = summary;
        this.queries = queries;
        this.followUps = followUps;
    }

    @GetMapping("/api/patients/me/doctor")
    public Envelope<?> doctor(Authentication auth) {
        var user = (AppUserPrincipal) auth.getPrincipal();
        var current = summary.currentSummary(user);
        if (current.subjectId() == null) return Envelope.success(null);
        var explicit = queries.rows("""
                SELECT pra.responsible_doctor_id
                FROM patient_account_request pra
                WHERE pra.subject_id=? AND pra.issued_user_id=? AND pra.responsible_doctor_id IS NOT NULL
                """, current.subjectId(), user.userId());
        if (!explicit.isEmpty()) {
            var contact = queries.rows("""
                    SELECT COALESCE(u.display_name,u.username) name,u.department,u.contact_phone phone,u.office_hours
                    FROM sys_user u WHERE u.id=? AND u.status='ACTIVE'
                    AND EXISTS (SELECT 1 FROM user_role ur JOIN sys_role r ON r.id=ur.role_id
                                WHERE ur.user_id=u.id AND r.role_code='DOCTOR')
                    AND EXISTS (SELECT 1 FROM subject_permission p WHERE p.user_id=u.id AND p.subject_id=?
                                AND p.permission_type IN ('VIEW','MANAGE'))
                    """, explicit.get(0).get("responsibleDoctorId"), current.subjectId());
            return Envelope.success(contact.isEmpty() ? null : contact.get(0));
        }
        var doctors =
                queries.rows(
                        "SELECT DISTINCT u.display_name name,u.department,u.contact_phone"
                            + " phone,u.office_hours FROM sys_user u JOIN user_role ur ON"
                            + " ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id JOIN"
                            + " subject_permission p ON p.user_id=u.id WHERE p.subject_id=? AND"
                            + " p.permission_type IN ('VIEW','MANAGE') AND r.role_code='DOCTOR' AND"
                            + " u.status='ACTIVE' AND u.display_name IS NOT NULL ORDER BY"
                            + " u.display_name",
                        current.subjectId());
        return Envelope.success(doctors.size() == 1 ? doctors.get(0) : null);
    }

    @GetMapping("/api/patients/me/follow-up")
    public Envelope<?> followup(Authentication auth) {
        var actor = (AppUserPrincipal) auth.getPrincipal();
        Long subjectId = summary.subjectForPatient(actor);
        if (subjectId == null) return Envelope.success(Map.of("bound", false, "appointments", List.of()));
        var now = java.sql.Timestamp.from(java.time.Instant.now());
        var recent = java.sql.Timestamp.from(now.toInstant().minus(90, java.time.temporal.ChronoUnit.DAYS));
        var appointments =
                queries.rows(
                        """
                        SELECT * FROM (
                        SELECT f.id,f.scheduled_at,f.purpose,f.visit_location,f.preparation,f.appointment_revision,
                               f.status,booking.response_status,
                               CASE WHEN f.status='PLANNED' AND f.scheduled_at>? THEN TRUE ELSE FALSE END AS can_respond,
                               CASE WHEN previous.response_status='RESCHEDULE_REQUESTED' THEN TRUE ELSE FALSE END AS rescheduled
                        FROM follow_up_plan f LEFT JOIN follow_up_response booking ON booking.follow_up_id=f.id
                            AND booking.appointment_revision=f.appointment_revision AND booking.patient_id=?
                        LEFT JOIN follow_up_response previous ON previous.follow_up_id=f.id
                            AND previous.appointment_revision=f.appointment_revision-1 AND previous.patient_id=?
                        WHERE f.subject_id=? AND f.scheduled_at>=?
                        ) patient_appointments
                        ORDER BY can_respond DESC,
                            CASE WHEN can_respond THEN scheduled_at END ASC,
                            scheduled_at DESC,id DESC LIMIT 100
                        """,
                        now, actor.userId(), actor.userId(), subjectId, recent);
        var past =
                queries.rows(
                        "SELECT COUNT(*) total FROM follow_up_plan WHERE subject_id=? AND"
                                + " status='PLANNED' AND scheduled_at<?",
                        subjectId,
                        now);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("bound", true);
        for (var appointment : appointments) {
            appointment.put("canRespond", readFlag(appointment.get("canRespond")));
            appointment.put("rescheduled", readFlag(appointment.get("rescheduled")));
        }
        response.put("next", appointments.stream().filter(row -> Boolean.TRUE.equals(row.get("canRespond"))).findFirst().orElse(null));
        response.put("appointments", appointments);
        response.put("overdueCount", past.get(0).get("total"));
        return Envelope.success(response);
    }

    private static boolean readFlag(Object flag) {
        return Boolean.TRUE.equals(flag) || flag instanceof Number number && number.intValue() == 1;
    }

    record RespondRequest(@NotNull @Min(0) Long appointmentRevision, @NotNull String action,
                          @Size(max = 280) String note) {}

    @PostMapping("/api/patients/me/follow-up/{id}/response")
    public Envelope<?> respond(@PathVariable long id, @Valid @RequestBody RespondRequest request,
                               Authentication authentication) {
        var actor = (AppUserPrincipal) authentication.getPrincipal();
        followUps.respond(id, request.appointmentRevision(), request.action(), request.note(),
                summary.subjectForPatient(actor), actor);
        return followup(authentication);
    }
}
