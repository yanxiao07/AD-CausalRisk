package com.adcausalrisk.backend.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.subject.SubjectRepository;
import com.adcausalrisk.backend.subject.entity.Subject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 报告保存前的授权复核。
 *
 * <p>复现评审给出的时序：请求发出时患者仍授权，报告算完之前患者撤回，于是入组记录还在、
 * 最新表态却已经拒绝。此时若只核对入组记录，报告会照常落库，而队列页面已经显示 0 例。
 * 这里直接钉住保存侧的第二道检查，避免再靠「队列为空」这种间接现象发现回归。
 */
@SpringBootTest
@ActiveProfiles("test")
class ValidationReportConsentGuardTest {

    private static final String SUBJECT_CODE = "REPORT-GUARD-001";
    private static final String PROJECT = "REPORT-GUARD";

    @Autowired private ValidationCohortService cohort;

    @Autowired private SubjectRepository subjectRepository;

    @Autowired private JdbcTemplate jdbc;

    private Long subjectId;
    private Long patientId;
    private Long researcherId;
    private Long adminId;
    private String enrollmentId;

    @BeforeEach
    void setUp() {
        Subject subject =
                subjectRepository.save(
                        new Subject(
                                SUBJECT_CODE,
                                "报告授权复核测试档案",
                                Subject.Status.ACTIVE,
                                "care-demo-report-guard",
                                Instant.now()));
        subjectId = subject.getId();
        patientId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'patient'", Long.class);
        researcherId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'researcher'", Long.class);
        adminId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
        enrollmentId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO validation_cohort_enrollment"
                        + " (id, project_code, subject_id, researcher_id, authorization_ref, granted_by,"
                        + " granted_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                enrollmentId,
                PROJECT,
                subjectId,
                researcherId,
                "ETHICS-2026-001",
                adminId,
                Timestamp.from(Instant.now()));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM validation_cohort_enrollment WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM patient_consent_record WHERE subject_id = ?", subjectId);
        jdbc.update("DELETE FROM subject WHERE id = ?", subjectId);
    }

    /** 撤回发生在报告算完之前：入组记录仍在，但保存必须被拒绝，不能落一份含该病例的报告。 */
    @Test
    void withdrawalBeforeSaveRefusesTheReport() {
        recordConsent(true, Instant.now().minusSeconds(3600));
        recordConsent(false, Instant.now());

        assertThatThrownBy(() -> cohort.requireResearchUseStillGranted(List.of(enrollmentId)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("撤回科研使用授权")
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(ErrorCode.ACCESS_DENIED));
    }

    /** 重新授权后最新表态生效，历史撤回行不再拦保存。 */
    @Test
    void reauthorizationLiftsTheBlock() {
        recordConsent(false, Instant.now().minusSeconds(3600));
        recordConsent(true, Instant.now());

        cohort.requireResearchUseStillGranted(List.of(enrollmentId));
    }

    /** 从未表态沿用医院既有的伦理与书面授权流程，不能被静默阻断。 */
    @Test
    void undecidedPatientKeepsTheExistingFlow() {
        cohort.requireResearchUseStillGranted(List.of(enrollmentId));
    }

    /** 与本次报告无关的入组记录不受影响：只核对本次报告选中的病例。 */
    @Test
    void otherWithdrawnEnrollmentsDoNotBlockThisReport() {
        Subject other =
                subjectRepository.save(
                        new Subject(
                                "REPORT-GUARD-002",
                                "另一位撤回授权的档案",
                                Subject.Status.ACTIVE,
                                "care-demo-report-guard-2",
                                Instant.now()));
        String otherEnrollment = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO validation_cohort_enrollment"
                        + " (id, project_code, subject_id, researcher_id, authorization_ref, granted_by,"
                        + " granted_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                otherEnrollment,
                PROJECT,
                other.getId(),
                researcherId,
                "ETHICS-2026-001",
                adminId,
                Timestamp.from(Instant.now()));
        jdbc.update(
                "INSERT INTO patient_consent_record (subject_id, user_id, research_use, follow_up_contact,"
                        + " recorded_at) VALUES (?, ?, FALSE, TRUE, ?)",
                other.getId(),
                patientId,
                Timestamp.from(Instant.now()));
        try {
            cohort.requireResearchUseStillGranted(List.of(enrollmentId));
        } finally {
            jdbc.update("DELETE FROM validation_cohort_enrollment WHERE subject_id = ?", other.getId());
            jdbc.update("DELETE FROM patient_consent_record WHERE subject_id = ?", other.getId());
            jdbc.update("DELETE FROM subject WHERE id = ?", other.getId());
        }
    }

    private void recordConsent(boolean researchUse, Instant at) {
        jdbc.update(
                "INSERT INTO patient_consent_record (subject_id, user_id, research_use, follow_up_contact,"
                        + " recorded_at) VALUES (?, ?, ?, TRUE, ?)",
                subjectId,
                patientId,
                researchUse,
                Timestamp.from(at));
    }
}
