package com.adcausalrisk.backend.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.patient.PatientConsentService;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectRepository;
import com.adcausalrisk.backend.subject.entity.Subject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Autowired private PatientConsentService consents;

    @Autowired private SubjectRepository subjectRepository;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private PlatformTransactionManager transactionManager;

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
        jdbc.update("DELETE FROM subject_permission WHERE subject_id = ?", subjectId);
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

    /**
     * 多例报告里只要有一例撤回就整体拒绝：不能「剔除后继续保存」，否则患者看到队列已归零，
     * 却仍然收到一份统计里含该病例的报告。
     */
    @Test
    void oneWithdrawalRefusesTheWholeMultiCaseReport() {
        recordConsent(true, Instant.now().minusSeconds(3600));
        Subject second =
                subjectRepository.save(
                        new Subject(
                                "REPORT-GUARD-003",
                                "另一位仍在授权中的档案",
                                Subject.Status.ACTIVE,
                                "care-demo-report-guard-3",
                                Instant.now()));
        String secondEnrollment = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO validation_cohort_enrollment"
                        + " (id, project_code, subject_id, researcher_id, authorization_ref, granted_by,"
                        + " granted_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                secondEnrollment,
                PROJECT,
                second.getId(),
                researcherId,
                "ETHICS-2026-001",
                adminId,
                Timestamp.from(Instant.now()));
        jdbc.update(
                "INSERT INTO patient_consent_record (subject_id, user_id, research_use, follow_up_contact,"
                        + " recorded_at) VALUES (?, ?, FALSE, TRUE, ?)",
                subjectId,
                patientId,
                Timestamp.from(Instant.now()));
        try {
            assertThatThrownBy(
                            () ->
                                    cohort.requireResearchUseStillGranted(
                                            List.of(enrollmentId, secondEnrollment)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                            .isEqualTo(ErrorCode.ACCESS_DENIED));
        } finally {
            jdbc.update("DELETE FROM validation_cohort_enrollment WHERE subject_id = ?", second.getId());
            jdbc.update("DELETE FROM patient_consent_record WHERE subject_id = ?", second.getId());
            jdbc.update("DELETE FROM subject WHERE id = ?", second.getId());
        }
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

    /**
     * 评审给出的第二个时序：撤回卡在「最后一次复核之后、报告写入之前」。
     *
     * <p>只再查一次解决不了这个窗口——查询和写入之间撤回照样能提交，接口返回 200，
     * 报告里带着已撤回患者的数据。这里钉住修复方式：报告侧复核时锁住档案行并持有到
     * 事务提交，患者的撤回走同一行锁，因此只能整体排在报告落库之前或之后。
     */
    @Test
    void withdrawalCannotCommitBetweenTheRecheckAndTheReportWrite() throws Exception {
        recordConsent(true, Instant.now().minusSeconds(3600));
        List<Map<String, Object>> displacedBindings = bindPatientToThisSubject();
        AppUserPrincipal patient =
                new AppUserPrincipal(patientId, "patient", "", List.of("PATIENT"), true);

        TransactionTemplate reportTransaction = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch reportHoldsTheLock = new CountDownLatch(1);
        CountDownLatch withdrawalAttempted = new CountDownLatch(1);
        AtomicBoolean withdrawalCommitted = new AtomicBoolean(false);
        try {
            Future<?> reportSide =
                    pool.submit(
                            () ->
                                    reportTransaction.executeWithoutResult(
                                            state -> {
                                                cohort.requireResearchUseStillGranted(
                                                        List.of(enrollmentId));
                                                reportHoldsTheLock.countDown();
                                                awaitQuietly(withdrawalAttempted);
                                                sleepQuietly();
                                                assertThat(withdrawalCommitted)
                                                        .as("报告持锁期间撤回不能提交，否则报告里会留下已撤回患者的数据")
                                                        .isFalse();
                                            }));
            assertThat(reportHoldsTheLock.await(10, TimeUnit.SECONDS))
                    .as("报告侧必须走到复核这一步")
                    .isTrue();

            Future<?> withdrawalSide =
                    pool.submit(
                            () -> {
                                withdrawalAttempted.countDown();
                                consents.saveConsent(patient, false, true);
                                withdrawalCommitted.set(true);
                            });

            reportSide.get(10, TimeUnit.SECONDS);
            withdrawalSide.get(10, TimeUnit.SECONDS);

            assertThat(withdrawalCommitted).as("报告提交后撤回才能落地").isTrue();
            assertThat(latestResearchUse()).as("撤回记录已经写入").isFalse();
        } finally {
            pool.shutdownNow();
            restorePatientBindings(displacedBindings);
        }
    }

    /**
     * 评审在 H2 的 REPEATABLE READ 下复现的时序：报告事务先建立快照，之后患者撤回并提交，
     * 报告再取档案锁、复核授权。普通 SELECT 读到的仍是快照里的旧值，过期授权就被放行。
     * 复核必须用加锁读（读最新已提交版本），否则「先锁后查」只是顺序对了、数据还是旧的。
     */
    @Test
    void recheckReadsTheWithdrawalThatCommittedAfterTheSnapshotWasTaken() throws Exception {
        recordConsent(true, Instant.now().minusSeconds(3600));
        List<Map<String, Object>> displacedBindings = bindPatientToThisSubject();
        AppUserPrincipal patient =
                new AppUserPrincipal(patientId, "patient", "", List.of("PATIENT"), true);

        TransactionTemplate reportTransaction = new TransactionTemplate(transactionManager);
        reportTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch snapshotTaken = new CountDownLatch(1);
        CountDownLatch withdrawalCommitted = new CountDownLatch(1);
        try {
            Future<?> reportSide =
                    pool.submit(
                            () ->
                                    reportTransaction.executeWithoutResult(
                                            state -> {
                                                jdbc.queryForList(
                                                        "SELECT research_use FROM patient_consent_record"
                                                                + " WHERE subject_id = ?",
                                                        Boolean.class,
                                                        subjectId);
                                                snapshotTaken.countDown();
                                                awaitQuietly(withdrawalCommitted);
                                                assertThatThrownBy(
                                                                () ->
                                                                        cohort.requireResearchUseStillGranted(
                                                                                List.of(enrollmentId)))
                                                        .as("撤回已提交，复核必须按最新表态拒绝")
                                                        .isInstanceOf(BusinessException.class)
                                                        .satisfies(
                                                                error ->
                                                                        assertThat(
                                                                                        ((BusinessException) error)
                                                                                                .errorCode())
                                                                                .isEqualTo(
                                                                                        ErrorCode
                                                                                                .ACCESS_DENIED));
                                            }));
            assertThat(snapshotTaken.await(10, TimeUnit.SECONDS))
                    .as("报告侧要先建立本事务的快照")
                    .isTrue();

            Future<?> withdrawalSide =
                    pool.submit(
                            () -> {
                                consents.saveConsent(patient, false, true);
                                withdrawalCommitted.countDown();
                            });

            reportSide.get(10, TimeUnit.SECONDS);
            withdrawalSide.get(10, TimeUnit.SECONDS);
            assertThat(latestResearchUse()).as("撤回记录已经写入").isFalse();
        } finally {
            pool.shutdownNow();
            restorePatientBindings(displacedBindings);
        }
    }

    /**
     * 让 patient 账号只绑定本测试档案。{@link PatientConsentService#saveConsent} 按「最近一次
     * 有结果的档案」解析 subject，若这个账号还绑着别的档案，撤回可能落到别的档案上，
     * 测试就会时对时错。返回被暂时挪走的绑定，供测试结束还原。
     */
    private List<Map<String, Object>> bindPatientToThisSubject() {
        List<Map<String, Object>> displaced =
                jdbc.queryForList(
                        "SELECT subject_id, permission_type FROM subject_permission WHERE user_id = ?",
                        patientId);
        jdbc.update("DELETE FROM subject_permission WHERE user_id = ?", patientId);
        jdbc.update(
                "INSERT INTO subject_permission (subject_id, user_id, permission_type) VALUES (?, ?, 'VIEW')",
                subjectId,
                patientId);
        return displaced;
    }

    private void restorePatientBindings(List<Map<String, Object>> displaced) {
        for (Map<String, Object> row : displaced) {
            long otherSubject = ((Number) row.get("subject_id")).longValue();
            if (otherSubject == subjectId) continue;
            if (jdbc.queryForObject("SELECT COUNT(*) FROM subject WHERE id = ?", Long.class, otherSubject) == 0) {
                continue;
            }
            jdbc.update(
                    "INSERT INTO subject_permission (subject_id, user_id, permission_type) VALUES (?, ?, ?)",
                    otherSubject,
                    patientId,
                    row.get("permission_type"));
        }
    }

    private Boolean latestResearchUse() {
        return jdbc.queryForObject(
                "SELECT research_use FROM patient_consent_record WHERE subject_id = ?"
                        + " ORDER BY recorded_at DESC, id DESC LIMIT 1",
                Boolean.class,
                subjectId);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 短于 H2 默认的一秒行锁超时，又足够让撤回线程真的去抢同一把锁。 */
    private static void sleepQuietly() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
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