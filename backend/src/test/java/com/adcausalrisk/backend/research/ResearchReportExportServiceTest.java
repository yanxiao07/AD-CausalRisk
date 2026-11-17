package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.audit.AuditService;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchReportExportServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ResearchTableAssessmentService tasks = mock(ResearchTableAssessmentService.class);
    private final AuditService audit = mock(AuditService.class);
    private final ResearchReportExportService service =
            new ResearchReportExportService(tasks, json, audit);

    @Test
    void exportsThePersistedResearchResultAsJsonHtmlAndPdf() throws Exception {
        var principal = mock(AppUserPrincipal.class);
        when(principal.userId()).thenReturn(7L);
        when(tasks.find(9L, principal)).thenReturn(task(9L));

        var jsonExport = service.export(9L, "json", principal);
        assertThat(jsonExport.contentType()).isEqualTo("application/json");
        assertThat(new String(jsonExport.bytes(), StandardCharsets.UTF_8))
                .contains("\"taskId\" : 9", "研究验证用途", "模型验证报告");

        var htmlExport = service.export(9L, "html", principal);
        assertThat(htmlExport.contentType()).startsWith("text/html");
        assertThat(new String(htmlExport.bytes(), StandardCharsets.UTF_8))
                .contains("模型验证报告", "不可用于临床决策", "5.20%");

        var pdfExport = service.export(9L, "pdf", principal);
        assertThat(pdfExport.contentType()).isEqualTo("application/pdf");
        assertThat(pdfExport.bytes()).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
        verify(audit, org.mockito.Mockito.times(3))
                .recordQuietly(anyLong(), anyString(), anyString());
    }

    @Test
    void rejectsUnknownFormatsBeforeReadingTheTask() {
        assertThatThrownBy(() -> service.export(9L, "docx", mock(AppUserPrincipal.class)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PDF");
    }

    private ResearchTableAssessmentService.Task task(long taskId) throws Exception {
        var result = json.readTree("""
                {
                  "dataScope":"CONSTRUCTED_VALIDATION",
                  "clinicalUse":false,
                  "admission":{"predictionAllowed":false},
                  "prediction":{"adCif24m":0.052025946757113264,"otherDementiaCif24m":0.1782662116032392},
                  "model":{"family":"logistic","specification":"seven","artifactSha256":"66cfc253"},
                  "explanation":{"groups":["mmse"],
                    "contributions":[[[0.0001475,-0.0967103]]]}
                }
                """);
        return new ResearchTableAssessmentService.Task(
                taskId, 9, 203, "DEMO-SMOKE-252019", "测试受试者",
                "run-manual-test", "COMPLETED", null, result,
                Instant.parse("2026-11-15T09:49:32Z"), Instant.parse("2026-11-15T09:49:32Z"),
                "PENDING", null, null, null);
    }
}
