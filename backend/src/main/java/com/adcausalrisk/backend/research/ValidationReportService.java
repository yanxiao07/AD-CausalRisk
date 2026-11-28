package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.audit.AuditService;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
class ValidationReportService {
    private static final int CASE_LIMIT = 2000;
    private static final String METHOD = "SAVED_CIF_24M_V1";
    private final JdbcTemplate jdbc;
    private final ValidationCohortService cohort;
    private final SyntheticCareIdentity syntheticCare;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final String python;
    private final String workspace;
    private final Set<String> activeReports = ConcurrentHashMap.newKeySet();
    private final Semaphore calculatorSlots = new Semaphore(2);

    ValidationReportService(JdbcTemplate jdbc, ValidationCohortService cohort, SyntheticCareIdentity syntheticCare,
            ObjectMapper mapper, Clock clock, AuditService audit, PlatformTransactionManager transactionManager,
            @Value("${research.validation.python:}") String python,
            @Value("${research.validation.workspace:}") String workspace) {
        this.jdbc = jdbc;
        this.cohort = cohort;
        this.syntheticCare = syntheticCare;
        this.mapper = mapper;
        this.clock = clock;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
        this.python = python;
        this.workspace = workspace;
    }

    record Scope(String project, String artifactSha256) {
        void validate() {
            if (project == null || !project.matches("[A-Z0-9][A-Z0-9_-]{0,49}")
                    || artifactSha256 == null || !artifactSha256.matches("[0-9a-f]{64}")) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择有效的研究项目与模型版本");
            }
        }
    }
    record Sample(String outcome, BigDecimal probability) {}
    record BoundCase(String caseCode, String inputSha256, String indexDate, String observedDate, Sample sample) {}
    record FrozenSnapshot(String method, Scope scope, boolean synthetic, List<BoundCase> cases) {}
    record Bounds(double lower, double upper) {
        boolean valid() { return probability(lower) && probability(upper) && lower <= upper; }
    }
    record RateBounds(double lower, double upper, boolean denominatorMayBeZero, boolean denominatorAlwaysZero) {
        boolean valid() { return new Bounds(lower, upper).valid() && !denominatorMayBeZero && !denominatorAlwaysZero; }
    }
    record Metrics(Double knownSubsetBrier, Double knownSubsetAuc, double meanPrediction, Bounds brierBounds,
                   RateBounds observedRateBounds, Bounds eceBounds, int knownCases, int unknownCases) {
        boolean valid(int count) {
            return knownCases >= 0 && knownCases <= count && unknownCases == count - knownCases
                    && (knownCases == 0 ? knownSubsetBrier == null : probability(knownSubsetBrier))
                    && (knownSubsetAuc == null || knownCases >= 2 && probability(knownSubsetAuc))
                    && probability(meanPrediction) && brierBounds != null && brierBounds.valid()
                    && observedRateBounds != null && observedRateBounds.valid() && eceBounds != null && eceBounds.valid();
        }
    }
    record CalculatorReply(String status, String reason, Metrics metrics) {
        boolean valid(int count) {
            return "COMPLETED".equals(status) && reason == null && metrics != null && metrics.valid(count)
                    || "SUPPRESSED".equals(status) && "SMALL_OUTCOME_RISK_CELL".equals(reason) && metrics == null;
        }
    }
    record Report(String id, String project, String artifactSha256, String snapshotSha256, Instant createdAt,
                  String method, int includedCases, String status, String reason, Metrics metrics,
                  String versionReviewState) {}
    private record Stored(String enrollmentIds, String report) {}

    public Report latest(AppUserPrincipal actor, Scope scope) {
        scope.validate();
        var grants = cohort.projectGrants(actor, scope.project());
        try (var stored = jdbc.queryForStream("""
                SELECT enrollment_ids_json,report_json FROM validation_cohort_report
                WHERE researcher_id=? AND project_code=? AND artifact_sha256=?
                ORDER BY created_at DESC,id DESC
                """, (rs, row) -> new Stored(rs.getString(1), rs.getString(2)),
                actor.userId(), scope.project(), scope.artifactSha256())) {
            return stored.filter(row -> grants.containsAll(readIds(row.enrollmentIds())))
                    .map(row -> readReport(row.report())).findFirst().orElse(null);
        }
    }

    public Report run(AppUserPrincipal actor, Scope scope) {
        scope.validate();
        var cases = cohort.projectCases(actor, scope.project(), CASE_LIMIT + 1);
        if (cases.size() > CASE_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "此项目超过单次验证容量");
        }
        var selected = cases.stream().filter(item -> scope.artifactSha256().equals(item.artifactSha256())
                && item.adRisk() != null && item.inputSha256() != null && item.indexDate() != null).toList();
        if (selected.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "当前账号在此项目和版本下没有可验证的预测");
        }
        var bound = selected.stream().map(item -> new BoundCase(item.caseCode(), item.inputSha256(),
                item.indexDate().toString(), item.observedDate() == null ? null : item.observedDate().toString(),
                new Sample(item.outcome24m(), item.adRisk().stripTrailingZeros()))).toList();
        boolean synthetic = syntheticCare.enabled() && selected.stream().allMatch(item -> Boolean.FALSE.equals(item.clinicalUse()));
        String digest = sha256(write(new FrozenSnapshot(METHOD, scope, synthetic, bound)));
        var ids = bound.stream().map(BoundCase::caseCode).toList();
        String activeKey = actor.userId() + ":" + digest;
        if (!activeReports.add(activeKey)) throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "此快照正在验证");
        try {
            Report existing = transaction.execute(status -> {
                requireGrants(actor, scope, ids);
                return find(actor, scope, digest);
            });
            if (existing != null) return existing;
            CalculatorReply result = synthetic ? calculate(bound.stream().map(BoundCase::sample).toList())
                    : new CalculatorReply("BLOCKED", "INDEPENDENT_PROTOCOL_REQUIRED", null);
            var report = new Report(UUID.randomUUID().toString(), scope.project(), scope.artifactSha256(),
                    digest, clock.instant(), METHOD, bound.size(), result.status(), result.reason(), result.metrics(),
                    "WAITING_FOR_INDEPENDENT_COMPARISON");
            var saved = transaction.execute(status -> {
                requireGrants(actor, scope, ids);
                Report replay = find(actor, scope, digest);
                if (replay != null) return replay;
                jdbc.update("""
                        INSERT INTO validation_cohort_report
                        (id,researcher_id,project_code,artifact_sha256,snapshot_sha256,enrollment_ids_json,report_json,created_at)
                        VALUES (?,?,?,?,?,?,?,?)
                        """, report.id(), actor.userId(), scope.project(), scope.artifactSha256(), digest,
                        new String(write(ids), java.nio.charset.StandardCharsets.UTF_8),
                        new String(write(report), java.nio.charset.StandardCharsets.UTF_8), Timestamp.from(report.createdAt()));
                return report;
            });
            if (saved == report) {
                audit.recordQuietly(actor.userId(), "VALIDATION_COHORT_REPORT",
                        "reportId=" + report.id() + " status=" + report.status());
            }
            return saved;
        } finally {
            activeReports.remove(activeKey);
        }
    }

    /**
     * 写入/读取报告前的复核。两步都必须做：
     *
     * <ol>
     *   <li>入组记录仍在（防止管理员已显式撤销授权）；
     *   <li>患者最新表态仍然允许科研使用（防止请求发出后、报告算完前撤回）。
     * </ol>
     *
     * <p>只查第一步会留下一个窗口：报告算完时队列已经显示 0 例，但报告照样落库，
     * 里面还带着那位患者的数据。
     */
    private void requireGrants(AppUserPrincipal actor, Scope scope, List<String> ids) {
        if (!cohort.lockProjectGrants(actor, scope.project()).containsAll(ids)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        cohort.requireResearchUseStillGranted(ids);
    }

    private Report find(AppUserPrincipal actor, Scope scope, String digest) {
        return jdbc.queryForList("""
                SELECT report_json FROM validation_cohort_report
                WHERE researcher_id=? AND project_code=? AND artifact_sha256=? AND snapshot_sha256=?
                """, String.class, actor.userId(), scope.project(), scope.artifactSha256(), digest)
                .stream().map(this::readReport).findFirst().orElse(null);
    }

    private CalculatorReply calculate(List<Sample> samples) {
        if (python.isBlank() || workspace.isBlank()) throw unavailable();
        if (!calculatorSlots.tryAcquire()) throw new BusinessException(ErrorCode.RESEARCH_UNAVAILABLE, "验证服务繁忙，请稍后重试");
        Process process = null;
        try {
            process = new ProcessBuilder(python, "-E", "-B", "-m", "research_runtime.cohort_validation")
                    .directory(Path.of(workspace).toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process child = process;
            var exchange = new FutureTask<>(() -> {
                try (var stdin = child.getOutputStream()) {
                    stdin.write(write(samples));
                }
                try (var stdout = child.getInputStream()) {
                    byte[] response = stdout.readNBytes(4097);
                    if (response.length > 4096 || child.waitFor() != 0) throw unavailable();
                    return response;
                }
            });
            Thread.startVirtualThread(exchange);
            byte[] response = exchange.get(15, TimeUnit.SECONDS);
            CalculatorReply result = mapper.readerFor(CalculatorReply.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                            DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).readValue(response);
            if (result == null || !result.valid(samples.size())) throw unavailable();
            return result;
        } catch (IOException | ExecutionException | TimeoutException failure) {
            throw unavailable();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            calculatorSlots.release();
        }
    }

    private static boolean probability(Double value) {
        return value != null && Double.isFinite(value) && value >= 0 && value <= 1;
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.RESEARCH_UNAVAILABLE, "验证计算服务暂不可用，请稍后重试");
    }

    private List<String> readIds(String stored) {
        try {
            return mapper.readerForListOf(String.class).readValue(stored);
        } catch (IOException invalid) {
            throw new IllegalStateException("Stored enrollment binding is invalid", invalid);
        }
    }

    private Report readReport(String stored) {
        try {
            return mapper.readValue(stored, Report.class);
        } catch (IOException invalid) {
            throw new IllegalStateException("Stored validation report is invalid", invalid);
        }
    }

    private byte[] write(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (IOException invalid) {
            throw new IllegalStateException("Validation request serialization failed", invalid);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
