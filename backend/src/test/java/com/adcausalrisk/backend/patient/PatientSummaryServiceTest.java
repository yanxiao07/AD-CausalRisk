package com.adcausalrisk.backend.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.adcausalrisk.backend.assessment.RiskAssessmentRepository;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.followup.FollowUpPlanRepository;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectPermissionRepository;
import com.adcausalrisk.backend.subject.SubjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit guarantees of the patient summary that are awkward to arrange through the database:
 * the unbound account path and the role gate.
 */
class PatientSummaryServiceTest {

    private final SubjectPermissionRepository permissionRepository = mock(SubjectPermissionRepository.class);
    private final SubjectRepository subjectRepository = mock(SubjectRepository.class);
    private final RiskAssessmentRepository assessmentRepository = mock(RiskAssessmentRepository.class);
    private final FollowUpPlanRepository followUpRepository = mock(FollowUpPlanRepository.class);

    private PatientSummaryService service;

    @BeforeEach
    void setUp() {
        service = new PatientSummaryService(
                permissionRepository, subjectRepository, assessmentRepository, followUpRepository,
                new ObjectMapper());
    }

    @Test
    void unboundPatientGetsGuidanceInsteadOfAnError() {
        when(permissionRepository.findDistinctSubjectIdByUserId(7L)).thenReturn(List.of());

        PatientSummaryService.PatientSummaryView view =
                service.currentSummary(new AppUserPrincipal(7L, "patient", "", List.of("PATIENT"), true));

        assertThat(view.subjectId()).isNull();
        assertThat(view.hasAssessment()).isFalse();
        assertThat(view.assessmentId()).isNull();
        assertThat(view.summary()).contains("绑定");
        assertThat(view.limitations()).isNotEmpty();
        // No subject means no clinical lookup at all.
        verify(assessmentRepository, never()).findFirstBySubjectIdOrderByIdDesc(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void nonPatientIsRefused() {
        AppUserPrincipal doctor = new AppUserPrincipal(4L, "doctor", "", List.of("DOCTOR"), true);

        assertThatThrownBy(() -> service.currentSummary(doctor))
                .isInstanceOf(BusinessException.class)
                .satisfies(failure -> assertThat(((BusinessException) failure).errorCode())
                        .isEqualTo(ErrorCode.ACCESS_DENIED));
        verify(permissionRepository, never()).findDistinctSubjectIdByUserId(org.mockito.ArgumentMatchers.anyLong());
    }
}
