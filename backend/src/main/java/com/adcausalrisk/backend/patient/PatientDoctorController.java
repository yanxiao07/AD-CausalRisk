package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.common.*;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class PatientDoctorController {
    private final PatientSummaryService summary;
    private final BusinessQueries queries;

    public PatientDoctorController(PatientSummaryService summary, BusinessQueries queries) {
        this.summary = summary;
        this.queries = queries;
    }

    @GetMapping("/api/patients/me/doctor")
    public Envelope<?> doctor(Authentication auth) {
        var user = (AppUserPrincipal) auth.getPrincipal();
        var current = summary.currentSummary(user);
        if (current.subjectId() == null) return Envelope.success(null);
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
        return Envelope.success(doctors.isEmpty() ? null : doctors.get(0));
    }

    @GetMapping("/api/patients/me/follow-up")
    public Envelope<?> followup(Authentication auth) {
        var current = summary.currentSummary((AppUserPrincipal) auth.getPrincipal());
        if (current.subjectId() == null) return Envelope.success(Map.of("bound", false));
        var now = java.sql.Timestamp.from(java.time.Instant.now());
        var upcoming =
                queries.rows(
                        "SELECT scheduled_at,purpose FROM follow_up_plan WHERE subject_id=? AND"
                            + " status='PLANNED' AND scheduled_at>=? ORDER BY scheduled_at,id LIMIT"
                            + " 1",
                        current.subjectId(),
                        now);
        var past =
                queries.rows(
                        "SELECT COUNT(*) total FROM follow_up_plan WHERE subject_id=? AND"
                                + " status='PLANNED' AND scheduled_at<?",
                        current.subjectId(),
                        now);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("bound", true);
        response.put("next", upcoming.isEmpty() ? null : upcoming.get(0));
        response.put("overdueCount", past.get(0).get("total"));
        return Envelope.success(response);
    }
}
