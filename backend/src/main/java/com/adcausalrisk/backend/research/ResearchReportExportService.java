package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.audit.AuditService;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Exports a persisted research task. It never runs inference or changes a task. */
@Service
class ResearchReportExportService {
    record Export(byte[] bytes, String contentType, String filename) {}

    private final ResearchTableAssessmentService tasks;
    private final ObjectMapper json;
    private final AuditService audit;

    ResearchReportExportService(
            ResearchTableAssessmentService tasks, ObjectMapper json, AuditService audit) {
        this.tasks = tasks;
        this.json = json;
        this.audit = audit;
    }

    Export export(long taskId, String format, AppUserPrincipal principal) {
        String normalized = format == null ? "pdf" : format.toLowerCase(Locale.ROOT);
        if (!List.of("pdf", "html", "json").contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "仅支持 PDF、HTML、JSON 导出");
        }
        var task = tasks.report(taskId, principal);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("title", "AD-CausalRisk 模型验证报告");
        report.put("taskId", task.taskId());
        report.put("snapshotId", task.snapshotId());
        report.put("subjectId", task.subjectId());
        report.put("subjectCode", task.subjectCode());
        report.put("subjectAlias", task.subjectAlias());
        report.put("runId", task.runId());
        report.put("status", task.status());
        report.put("createdAt", task.createdAt() == null ? null : task.createdAt().toString());
        report.put("reviewStatus", task.reviewStatus());
        report.put("reviewComment", task.reviewComment());
        report.put("generatedAt", Instant.now().toString());
        report.put("notice", "研究验证用途，不可用于临床决策；百分比为未校准的 24 个月累积发生率，不等同临床风险或个体因果效应。");
        report.put("result", task.result());
        audit.recordQuietly(principal.userId(), "RESEARCH_REPORT_EXPORT",
                "taskId=" + taskId + " format=" + normalized);
        try {
            String pretty = json.writerWithDefaultPrettyPrinter().writeValueAsString(report);
            return switch (normalized) {
                case "json" -> new Export(
                        pretty.getBytes(StandardCharsets.UTF_8),
                        "application/json",
                        "research-task-" + taskId + ".json");
                case "html" -> new Export(
                        html(report, task).getBytes(StandardCharsets.UTF_8),
                        "text/html;charset=UTF-8",
                        "research-task-" + taskId + ".html");
                default -> new Export(
                        pdf(readable(report, task)),
                        "application/pdf",
                        "research-task-" + taskId + ".pdf");
            };
        } catch (IOException error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "研究报告生成失败");
        }
    }

    private String html(Map<String, Object> report, ResearchTableAssessmentService.Task task) {
        String readable = readable(report, task);
        return "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                + "<title>模型验证报告</title><style>"
                + "body{max-width:900px;margin:40px auto;font:16px/1.7 sans-serif;color:#183440}"
                + "pre{white-space:pre-wrap;overflow-wrap:anywhere}@media print{body{margin:0}}"
                + "</style><h1>AD-CausalRisk 模型验证报告</h1><pre>"
                + escape(readable)
                + "</pre></html>";
    }

    private String readable(Map<String, Object> report, ResearchTableAssessmentService.Task task) {
        JsonNode result = task.result();
        JsonNode prediction = result == null ? null : result.path("prediction");
        JsonNode model = result == null ? null : result.path("model");
        JsonNode explanation = result == null ? null : result.path("explanation");
        StringBuilder text = new StringBuilder("AD-CausalRisk 模型验证报告\n");
        text.append(report.get("notice")).append("\n\n");
        text.append("任务: ").append(task.taskId())
                .append(" / 快照: ").append(task.snapshotId())
                .append(" / 受试者: ").append(task.subjectCode())
                .append(" (#").append(task.subjectId()).append(")\n");
        text.append("runId: ").append(task.runId()).append('\n');
        text.append("状态: ").append(task.status())
                .append(" / 复核: ").append(task.reviewStatus()).append('\n');
        text.append("生成时间: ").append(report.get("generatedAt")).append('\n');
        if (result != null) {
            text.append("数据范围: ").append(result.path("dataScope").asText("—"))
                    .append(" / clinicalUse: ").append(result.path("clinicalUse").asBoolean(false))
                    .append(" / predictionAllowed: ")
                    .append(result.path("admission").path("predictionAllowed").asBoolean(false))
                    .append('\n');
        }
        text.append("\nAD 24个月风险: ").append(percent(prediction, "adCif24m"))
                .append(" / 其他痴呆 24个月: ").append(percent(prediction, "otherDementiaCif24m"))
                .append('\n');
        if (model != null) {
            text.append("模型: ").append(model.path("family").asText("—"))
                    .append(" / ").append(model.path("specification").asText("—"))
                    .append(" / artifact ").append(model.path("artifactSha256").asText("—"))
                    .append('\n');
        }
        text.append("复核意见: ").append(task.reviewComment() == null ? "—" : task.reviewComment()).append('\n');
        if (explanation != null && explanation.path("groups").isArray()) {
            text.append("\n分组贡献（同次模型归因，不是个体因果效应）\n");
            var groups = explanation.path("groups");
            var contributions = explanation.path("contributions").path(0);
            for (int i = 0; i < groups.size(); i++) {
                text.append(groups.get(i).asText()).append(" | AD ")
                        .append(contribution(contributions, i, 0)).append(" | 其他 ")
                        .append(contribution(contributions, i, 1)).append('\n');
            }
        }
        return text.toString();
    }

    private static String percent(JsonNode prediction, String field) {
        if (prediction == null || !prediction.path(field).isNumber()) return "不可用";
        return String.format(Locale.ROOT, "%.2f%% (%.6f)",
                prediction.path(field).asDouble() * 100, prediction.path(field).asDouble());
    }

    private static String contribution(JsonNode contributions, int group, int outcome) {
        if (contributions == null) return "—";
        JsonNode row = contributions.path(group);
        if (!row.isArray()) return "—";
        // The explanation contract stores one number per outcome, not a nested pair per value.
        JsonNode value = row.path(outcome);
        return value.isNumber() ? String.format(Locale.ROOT, "%+.6f", value.asDouble()) : "—";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private byte[] pdf(String text) throws IOException {
        try (var document = new PDDocument();
                var fontStream = getClass().getResourceAsStream("/fonts/ReportSans.ttf");
                var bytes = new ByteArrayOutputStream()) {
            if (fontStream == null) throw new IOException("Missing bundled report font");
            var font = PDType0Font.load(document, fontStream, true);
            List<String> lines = new ArrayList<>();
            for (String paragraph : text.split("\n", -1)) {
                StringBuilder line = new StringBuilder();
                for (int cp : paragraph.codePoints().toArray()) {
                    String glyph = new String(Character.toChars(cp));
                    try {
                        font.encode(glyph);
                    } catch (IllegalArgumentException unsupported) {
                        glyph = "[U+" + Integer.toHexString(cp).toUpperCase(Locale.ROOT) + "]";
                    }
                    if (font.getStringWidth(line + glyph) / 1000 * 10 > 505) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    line.append(glyph);
                }
                lines.add(line.toString());
            }
            for (int start = 0; start < lines.size(); start += 48) {
                var page = new PDPage();
                document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(font, 10);
                    stream.setLeading(15);
                    stream.newLineAtOffset(44, 750);
                    for (int i = start; i < Math.min(start + 48, lines.size()); i++) {
                        stream.showText(lines.get(i));
                        stream.newLine();
                    }
                    stream.endText();
                }
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }
}
