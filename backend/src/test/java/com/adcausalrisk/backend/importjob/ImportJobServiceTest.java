package com.adcausalrisk.backend.importjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.importjob.csv.CsvImportParser;
import com.adcausalrisk.backend.importjob.csv.ImportRow;
import com.adcausalrisk.backend.importjob.csv.ImportTable;
import com.adcausalrisk.backend.importjob.csv.ParsedImport;
import com.adcausalrisk.backend.importjob.entity.DataImportJob;
import com.adcausalrisk.backend.importjob.entity.IdempotencyRecord;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.adcausalrisk.backend.subject.SubjectAccessPolicy;
import com.adcausalrisk.backend.subject.SubjectPermissionRepository;
import com.adcausalrisk.backend.subject.SubjectRepository;
import com.adcausalrisk.backend.visit.VisitRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Pure Mockito tests for the two branches of {@link ImportJobService} that the API suite cannot
 * reach deterministically: losing the {@code uq_idempotency_record} race, and finding that the data
 * moved between preview and commit.
 */
class ImportJobServiceTest {

    private static final String OPERATION = "IMPORT_JOB_PREVIEW";

    private static final long USER_ID = 2L;

    private static final AppUserPrincipal RESEARCHER =
            new AppUserPrincipal(USER_ID, "researcher", "", List.of("RESEARCHER"), true);

    private static final byte[] CSV =
            "subjectCode,alias,status\nDEMO-001,甲,ACTIVE\n".getBytes(StandardCharsets.UTF_8);

    private final DataImportJobRepository jobRepository = mock(DataImportJobRepository.class);

    private final IdempotencyRecordRepository idempotencyRepository =
            mock(IdempotencyRecordRepository.class);

    private final SubjectRepository subjectRepository = mock(SubjectRepository.class);

    private final VisitRepository visitRepository = mock(VisitRepository.class);

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    private final CsvImportParser parser = mock(CsvImportParser.class);

    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);

    /** Fixed business clock used by the tests that do not care about the date. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private ImportJobService service() {
        return service(CLOCK);
    }

    private ImportJobService service(Clock clock) {
        // canCreate only inspects role codes, so the real policy is enough here.
        var permissions = mock(SubjectPermissionRepository.class);
        when(permissions.findDataScope(any())).thenReturn("ALL");
        SubjectAccessPolicy accessPolicy = new SubjectAccessPolicy(permissions);
        return new ImportJobService(
                jobRepository,
                idempotencyRepository,
                subjectRepository,
                visitRepository,
                jdbcTemplate,
                accessPolicy,
                parser,
                new ObjectMapper(),
                clock,
                transactionManager,
                mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    /**
     * Two concurrent retries share a key. The database unique constraint lets one win; the loser
     * must answer with the winner's job, not a 500.
     */
    @Test
    void losingTheIdempotencyRaceReplaysTheWinnerInsteadOfFailing() {
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        given(
                        idempotencyRepository.findByUserIdAndOperationAndIdempotencyKey(
                                USER_ID, OPERATION, "k"))
                .willReturn(Optional.empty())
                .willReturn(
                        Optional.of(
                                new IdempotencyRecord(USER_ID, OPERATION, "k", sha256(CSV), 99L)));
        given(parser.parse(anyString(), any(), any())).willReturn(preview(1, 1));
        given(jobRepository.saveAndFlush(any(DataImportJob.class)))
                .willThrow(new DataIntegrityViolationException("uq_idempotency_record"));
        given(jobRepository.findById(99L)).willReturn(Optional.of(winner()));

        var view = service().preview(RESEARCHER, "k", CSV);

        assertThat(view.dataVersion()).isEqualTo("demo-winner");
        verify(jobRepository).findById(99L);
    }

    /** The same key with different bytes is a different request and must be refused. */
    @Test
    void reusingAKeyForDifferentContentIsRejected() {
        given(
                        idempotencyRepository.findByUserIdAndOperationAndIdempotencyKey(
                                USER_ID, OPERATION, "k"))
                .willReturn(
                        Optional.of(
                                new IdempotencyRecord(
                                        USER_ID,
                                        OPERATION,
                                        "k",
                                        sha256("别的文件".getBytes(StandardCharsets.UTF_8)),
                                        5L)));

        assertThatThrownBy(() -> service().preview(RESEARCHER, "k", CSV))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Idempotency-Key");

        verifyNoInteractions(jobRepository);
    }

    /** A preview that no longer re-validates to the same count must not insert anything. */
    @Test
    void commitRefusesWhenTheReplayNoLongerMatchesThePreview() {
        given(jobRepository.findByIdForUpdate(1L)).willReturn(Optional.of(ready()));
        given(parser.parse(anyString(), any(), any())).willReturn(preview(2, 1));

        assertThatThrownBy(() -> service().commit(1L, RESEARCHER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已变化");

        verifyNoInteractions(subjectRepository);
    }

    /**
     * The lock is the whole point of the concurrency fix: {@code commit} must read the job through
     * the write-locking query, never the plain one. Without it two concurrent commits both observe
     * PREVIEW_READY and both insert.
     */
    @Test
    void commitReadsTheJobUnderAWriteLock() {
        given(jobRepository.findByIdForUpdate(1L)).willReturn(Optional.of(ready()));
        given(parser.parse(anyString(), any(), any())).willReturn(preview(2, 2));

        service().commit(1L, RESEARCHER);

        verify(jobRepository).findByIdForUpdate(1L);
        verify(jobRepository, never()).findById(1L);
    }

    /** The second commit finds COMMITTED and must be refused before anything is inserted. */
    @Test
    void commitRefusesAJobThatIsAlreadyCommitted() {
        DataImportJob job = ready();
        job.markCommitted();
        given(jobRepository.findByIdForUpdate(1L)).willReturn(Optional.of(job));

        assertThatThrownBy(() -> service().commit(1L, RESEARCHER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可入库");

        verifyNoInteractions(subjectRepository);
    }

    /**
     * Ownership is not authorisation to write. An account demoted after preview (RESEARCHER ->
     * PATIENT) still owns its job, but must not be able to commit it.
     */
    @Test
    void commitRequiresTheImportPrivilegeEvenForTheOwner() {
        AppUserPrincipal demoted =
                new AppUserPrincipal(USER_ID, "researcher", "", List.of("PATIENT"), true);

        assertThatThrownBy(() -> service().commit(1L, demoted))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权导入数据");

        verifyNoInteractions(jobRepository);
    }

    /**
     * A failed insert that is not an idempotency-key collision (a foreign key, a truncated key)
     * must still be a controlled 400 - the race handler previously rethrew the raw exception, which
     * became a 500.
     */
    @Test
    void previewWithoutARecordedKeyBecomesValidationErrorInsteadOf500() {
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        given(
                        idempotencyRepository.findByUserIdAndOperationAndIdempotencyKey(
                                USER_ID, OPERATION, "k"))
                .willReturn(Optional.empty());
        given(parser.parse(anyString(), any(), any())).willReturn(preview(1, 1));
        given(jobRepository.saveAndFlush(any(DataImportJob.class)))
                .willThrow(new DataIntegrityViolationException("fk_data_import_job_user"));

        assertThatThrownBy(() -> service().preview(RESEARCHER, "k", CSV))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("冲突");
    }

    /** The contract's 1-100 character bound is enforced before anything touches the database. */
    @Test
    void previewRejectsAnIdempotencyKeyOutsideTheContractBounds() {
        assertThatThrownBy(() -> service().preview(RESEARCHER, "k".repeat(101), CSV))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Idempotency-Key");

        assertThatThrownBy(() -> service().preview(RESEARCHER, " ", CSV))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Idempotency-Key");

        verifyNoInteractions(jobRepository, idempotencyRepository);
    }

    /**
     * A restored or partially recovered database can leave a record whose {@code result_id} is
     * null; {@code findById(null)} would raise IllegalArgumentException and become a 500.
     */
    @Test
    void replayRefusesARecordWithoutAResultId() {
        given(
                        idempotencyRepository.findByUserIdAndOperationAndIdempotencyKey(
                                USER_ID, OPERATION, "k"))
                .willReturn(
                        Optional.of(
                                new IdempotencyRecord(USER_ID, OPERATION, "k", sha256(CSV), null)));

        assertThatThrownBy(() -> service().preview(RESEARCHER, "k", CSV))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("导入任务不存在");

        verifyNoInteractions(jobRepository);
    }

    /**
     * Replay is the one read that does not go through {@code requireReadable}. A restored record
     * whose result id now points at another user's job must not disclose that job.
     */
    @Test
    void replayRefusesAJobOwnedByAnotherUser() {
        DataImportJob foreign =
                new DataImportJob(
                        99L,
                        "demo-foreign",
                        Instant.now(),
                        ImportTable.SUBJECT,
                        1,
                        1,
                        0,
                        "[]",
                        null,
                        sha256(CSV),
                        null);
        given(
                        idempotencyRepository.findByUserIdAndOperationAndIdempotencyKey(
                                USER_ID, OPERATION, "k"))
                .willReturn(
                        Optional.of(
                                new IdempotencyRecord(USER_ID, OPERATION, "k", sha256(CSV), 7L)));
        given(jobRepository.findById(7L)).willReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service().preview(RESEARCHER, "k", CSV))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("导入任务不存在");
    }

    /**
     * "Today" must come from the configured business zone, never from the host's default zone. At
     * 2026-02-28T20:00Z it is 2026-03-01 in Asia/Shanghai, so the captured date proves which zone
     * the parser was given.
     */
    @Test
    void theBusinessDayComesFromTheConfiguredClockNotTheHostZone() {
        Clock shanghai =
                Clock.fixed(Instant.parse("2026-02-28T20:00:00Z"), ZoneId.of("Asia/Shanghai"));
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        given(
                        idempotencyRepository.findByUserIdAndOperationAndIdempotencyKey(
                                USER_ID, OPERATION, "k"))
                .willReturn(Optional.empty());
        given(parser.parse(anyString(), any(), any())).willReturn(preview(1, 1));
        given(jobRepository.saveAndFlush(any(DataImportJob.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        service(shanghai).preview(RESEARCHER, "k", CSV);

        ArgumentCaptor<LocalDate> today = ArgumentCaptor.forClass(LocalDate.class);
        verify(parser).parse(anyString(), any(), today.capture());
        assertThat(today.getValue())
                .as("the UTC date is 2026-02-28; the business zone must decide")
                .isEqualTo(LocalDate.of(2026, 3, 1));
    }

    /**
     * Counts can match while the row set changes - one valid row out, another in. The digest is
     * what catches that, and nothing may be inserted.
     */
    @Test
    void commitRefusesWhenTheValidRowSetChangedEvenWithTheSameCount() {
        given(jobRepository.findByIdForUpdate(1L))
                .willReturn(
                        Optional.of(
                                ready(
                                        sha256(
                                                "a completely different set"
                                                        .getBytes(StandardCharsets.UTF_8)))));
        given(parser.parse(anyString(), any(), any())).willReturn(preview(2, 2));

        assertThatThrownBy(() -> service().commit(1L, RESEARCHER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已变化");

        verifyNoInteractions(subjectRepository);
    }

    /** A job created before V6 has no digest; the count check still applies on its own. */
    @Test
    void commitFallsBackToTheCountCheckWhenNoDigestWasRecorded() {
        given(jobRepository.findByIdForUpdate(1L)).willReturn(Optional.of(ready(null)));
        given(parser.parse(anyString(), any(), any())).willReturn(preview(2, 2));

        service().commit(1L, RESEARCHER);

        verify(subjectRepository).saveAllAndFlush(any());
    }

    private static ParsedImport preview(int totalRows, int validRows) {
        List<ImportRow> rows =
                validRows == 1
                        ? List.of(new ImportRow.Subject(2, "DEMO-001", "甲", "ACTIVE"))
                        : List.of();
        return new ParsedImport(
                ImportTable.SUBJECT, totalRows, validRows, totalRows - validRows, List.of(), rows);
    }

    private static DataImportJob ready() {
        return ready(null);
    }

    private static DataImportJob ready(String previewDigest) {
        return new DataImportJob(
                USER_ID,
                "demo-ready",
                Instant.now(),
                ImportTable.SUBJECT,
                2,
                2,
                0,
                "[]",
                "subjectCode,alias,status\nDEMO-001,甲,ACTIVE\n",
                sha256(CSV),
                previewDigest);
    }

    private static DataImportJob winner() {
        return new DataImportJob(
                USER_ID,
                "demo-winner",
                Instant.now(),
                ImportTable.SUBJECT,
                1,
                1,
                0,
                "[]",
                null,
                sha256(CSV),
                null);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
