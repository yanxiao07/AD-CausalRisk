package com.adcausalrisk.backend.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves that an edit and a card confirmation of the same memo are serialised by the memo row
 * lock, not just by the version number.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}: the two parties must commit independently,
 * otherwise the second one would share the first one's transaction and could never contend for
 * the row. That also means the rows written here survive until {@link #cleanUp()} removes them,
 * because the H2 database is shared by every context in the run.
 *
 * <p>Without the lock the confirmation can pass its version check while the edit is still
 * uncommitted, then write cards built from text the patient never saw. The invariant asserted
 * here is therefore twofold: the confirmation must <b>wait</b> while the edit holds the row, and
 * once it continues it must see the new text and refuse the stale preview.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PatientMemoConcurrencyTest {

    private static final String DEMO_PASSWORD = "DemoPass!2026";
    private static final String MEMOS = "/api/patients/me/memos";

    private static final String OLD_TEXT = "周三下棋地点：社区活动室。棋盒放在哪里：蓝色帆布袋。";
    private static final String NEW_TEXT = "周三下棋地点：社区活动室。棋盒放在哪里：红色纸箱。";

    /** Long enough for the confirmation thread to reach the lock query, short enough for H2's
     * default one second lock timeout. */
    private static final long LOCK_HOLD_MILLIS = 300;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PatientMemoService memoService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long memoId;

    @AfterEach
    void cleanUp() {
        if (memoId == null) {
            return;
        }
        jdbc.update("DELETE FROM patient_flash_card WHERE memo_id = ?", memoId);
        jdbc.update("DELETE FROM patient_memo WHERE id = ?", memoId);
        memoId = null;
    }

    @Test
    void aConfirmationWaitsForTheEditAndThenRefusesTheStalePreview() throws Exception {
        MockHttpSession session = login("patient");
        memoId = createMemo(session, OLD_TEXT);
        AppUserPrincipal patient = patientOwning(memoId);
        String staleVersion = memoService.draftCardsForMemo(memoId, patient).memoVersion();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch editHoldsTheRow = new CountDownLatch(1);
        CountDownLatch releaseEdit = new CountDownLatch(1);
        TransactionTemplate editing = new TransactionTemplate(transactionManager);
        try {
            Future<?> edit =
                    pool.submit(
                            () ->
                                    editing.executeWithoutResult(
                                            state -> {
                                                memoService.editMemo(memoId, NEW_TEXT, patient);
                                                editHoldsTheRow.countDown();
                                                awaitQuietly(releaseEdit);
                                            }));
            assertThat(editHoldsTheRow.await(10, TimeUnit.SECONDS))
                    .as("the editing transaction must reach its uncommitted state")
                    .isTrue();

            Future<Object> confirm =
                    pool.submit(
                            () -> {
                                try {
                                    return memoService.generateCardsForMemo(
                                            memoId, patient, staleVersion);
                                } catch (RuntimeException failure) {
                                    return failure;
                                }
                            });
            Thread.sleep(LOCK_HOLD_MILLIS);
            assertThat(confirm.isDone())
                    .as("确认必须等编辑事务释放备忘行锁，否则会按过期原文写卡")
                    .isFalse();

            releaseEdit.countDown();
            Object outcome = confirm.get(10, TimeUnit.SECONDS);
            edit.get(10, TimeUnit.SECONDS);

            assertThat(outcome)
                    .as("拿到锁后读到的是新原文，过期预览必须被拒绝")
                    .isInstanceOf(BusinessException.class)
                    .satisfies(failure -> assertThat(((BusinessException) failure).errorCode())
                            .isEqualTo(ErrorCode.MEMO_VERSION_MISMATCH));
            assertThat(cardCount())
                    .as("被拒绝的确认不能发布任何卡片")
                    .isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT content FROM patient_memo WHERE id = ?", String.class, memoId))
                    .as("编辑本身仍然成功提交")
                    .isEqualTo(NEW_TEXT);
        } finally {
            releaseEdit.countDown();
            pool.shutdownNow();
        }
    }

    private int cardCount() {
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM patient_flash_card WHERE memo_id = ?",
                        Integer.class,
                        memoId);
        return count == null ? 0 : count;
    }

    private AppUserPrincipal patientOwning(long id) {
        Long userId = jdbc.queryForObject("SELECT user_id FROM patient_memo WHERE id = ?", Long.class, id);
        return new AppUserPrincipal(userId, "patient", "", List.of("PATIENT"), true);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private long createMemo(MockHttpSession session, String content) throws Exception {
        return bodyOf(
                        mockMvc.perform(
                                        post(MEMOS)
                                                .session(session)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(json(Map.of("content", content))))
                                .andExpect(status().isOk())
                                .andReturn())
                .path("data")
                .path("id")
                .asLong();
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json(
                                                        Map.of(
                                                                "username",
                                                                username,
                                                                "password",
                                                                DEMO_PASSWORD))))
                        .andExpect(status().isOk())
                        .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).as("login %s must establish a session", username).isNotNull();
        return session;
    }

    private com.fasterxml.jackson.databind.JsonNode bodyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}