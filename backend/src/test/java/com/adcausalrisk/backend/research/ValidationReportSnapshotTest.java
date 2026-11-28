package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.audit.AuditService;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises persisted report identity; the cohort is explicit artificial input, not a model run. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ValidationReportSnapshotTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    private ValidationCohortService cohort;
    private ValidationReportService reports;
    private AppUserPrincipal actor;
    private ValidationReportService.Scope scope;
    private List<ValidationCohortService.Case> current;

    @BeforeEach
    void setUp() {
        actor = new AppUserPrincipal(jdbc.queryForObject("SELECT id FROM sys_user WHERE username='researcher'", Long.class),
                "researcher", "", List.of("RESEARCHER"), true);
        scope = new ValidationReportService.Scope("SNAPSHOT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), "a".repeat(64));
        current = IntStream.range(0, 10).mapToObj(n -> new ValidationCohortService.Case("CASE-" + n,
                scope.project(), "COMPLETED", LocalDate.of(2024, 1, 1), LocalDate.of(2026, 1, 1),
                new BigDecimal("0.15"), "logistic / seven", scope.artifactSha256(), false,
                String.format("%064d", n), Map.of("mmse", new BigDecimal("27")), LocalDate.of(2026, 1, 2), "NO_EVENT")).toList();
        cohort = mock(ValidationCohortService.class);
        when(cohort.projectCases(eq(actor), eq(scope.project()), anyInt())).thenAnswer(call -> current);
        when(cohort.projectGrants(actor, scope.project())).thenAnswer(call -> current.stream().map(ValidationCohortService.Case::caseCode).collect(Collectors.toSet()));
        when(cohort.lockProjectGrants(actor, scope.project())).thenAnswer(call -> current.stream().map(ValidationCohortService.Case::caseCode).collect(Collectors.toSet()));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.parse("2026-11-28T01:00:00Z"), Instant.parse("2026-11-28T01:01:00Z"));
        reports = new ValidationReportService(jdbc, cohort, mock(SyntheticCareIdentity.class), json, clock,
                mock(AuditService.class), transactions, "", "");
    }

    @Test
    void reauthorizationReadsTheReusedCurrentReportRatherThanTheNewerSmallerReport() {
        var tenCases = current;
        var ten = reports.run(actor, scope);
        String frozen = jdbc.queryForObject("SELECT report_json FROM validation_cohort_report WHERE id=?", String.class, ten.id());
        current = tenCases.subList(0, 9);
        assertThat(reports.latest(actor, scope)).isNull();
        var nine = reports.run(actor, scope);
        assertThat(reports.latest(actor, scope).id()).isEqualTo(nine.id());
        current = tenCases;
        assertThat(reports.run(actor, scope).id()).isEqualTo(ten.id());
        assertThat(reports.latest(actor, scope).id()).isEqualTo(ten.id());
        assertThat(reports.latest(actor, scope).includedCases()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT report_json FROM validation_cohort_report WHERE id=?", String.class, ten.id())).isEqualTo(frozen);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM validation_cohort_report WHERE project_code=?", Long.class, scope.project())).isEqualTo(2L);
    }

    @Test
    void changedOutcomeDoesNotReuseAReportWithTheSameMembers() {
        reports.run(actor, scope);
        var first = current.get(0);
        var changed = new ValidationCohortService.Case(first.caseCode(), first.project(), first.predictionStatus(),
                first.indexDate(), first.horizonDate(), first.adRisk(), first.model(), first.artifactSha256(),
                first.clinicalUse(), first.inputSha256(), first.inputs(), LocalDate.of(2025, 1, 1), "AD");
        current = java.util.stream.Stream.concat(java.util.stream.Stream.of(changed), current.stream().skip(1)).toList();
        assertThat(reports.latest(actor, scope)).isNull();
        var updated = reports.run(actor, scope);
        assertThat(reports.latest(actor, scope).id()).isEqualTo(updated.id());
    }

    @Test
    void readRechecksAuthorizationEvenIfCohortWasReadBeforeWithdrawal() {
        reports.run(actor, scope);
        when(cohort.lockProjectGrants(actor, scope.project())).thenReturn(java.util.Set.of());
        assertThatThrownBy(() -> reports.latest(actor, scope)).isInstanceOf(BusinessException.class);
    }

    @Test
    void emptyCurrentCohortHasNoReport() {
        reports.run(actor, scope);
        current = List.of();
        assertThat(reports.latest(actor, scope)).isNull();
    }
}
