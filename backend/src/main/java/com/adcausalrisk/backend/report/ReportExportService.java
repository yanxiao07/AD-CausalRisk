package com.adcausalrisk.backend.report;

import com.adcausalrisk.backend.assessment.AssessmentService;
import com.adcausalrisk.backend.common.*;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;

/** Exports only persisted, authorized results. No inference or clinical recommendations. */
@Service
public class ReportExportService {
    private final AssessmentService assessments;
    private final BusinessQueries queries;
    private final ObjectMapper json;

    public ReportExportService(
            AssessmentService assessments, BusinessQueries queries, ObjectMapper json) {
        this.assessments = assessments;
        this.queries = queries;
        this.json = json;
    }

    public record Export(byte[] bytes, String contentType, String filename) {}

    public Export export(long id, String format, String audience, AppUserPrincipal user) {
        if (!Set.of("research", "doctor").contains(audience))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "报告用途无效");
        var outcome = assessments.findAssessment(id, user);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("title", "AD-CausalRisk 合成研究排序报告");
        report.put("audience", audience);
        report.put("assessmentId", id);
        report.put("subjectId", outcome.assessment().getSubjectId());
        report.put("taskId", outcome.task().getId());
        report.put("generatedAt", java.time.Instant.now().toString());
        report.put("dataSource", "SYNTHETIC_DEMO");
        report.put("notice", "合成课程演示；排序不是经外部验证的个人风险概率，不构成诊断或治疗建议。");
        report.put("reviewStatus", outcome.assessment().getReviewStatus().name());
        report.put(
                "reviews",
                queries.rows(
                        "SELECT r.decision,r.comment,r.reviewed_at,u.username reviewer_name FROM"
                                + " assessment_review r JOIN sys_user u ON u.id=r.reviewer_id WHERE"
                                + " r.assessment_id=? ORDER BY r.id",
                        id));
        report.put(
                "inputBinding",
                queries.rows(
                        "SELECT input_digest,data_version,captured_at FROM task_input_snapshot"
                                + " WHERE task_id=?",
                        outcome.task().getId()));
        if (((List<?>) report.get("inputBinding")).isEmpty())
            report.put("inputBindingLimitation", "历史任务未保存输入快照，不能从当前资料重建原输入");
        report.put("result", outcome.result());
        report.put("explanationBasis", "演示规则基准0.18；最终分数截断至[0.05,0.95]；贡献存在四位舍入，路径和预测归因不等于病因或干预效果。");
        try {
            String content = json.writerWithDefaultPrettyPrinter().writeValueAsString(report);
            String readable = readable(report, outcome.result(), audience);
            return switch (format) {
                case "json" ->
                        new Export(
                                content.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                "application/json",
                                "assessment-" + id + ".json");
                case "html" ->
                        new Export(
                                ("<!doctype html><html lang=\"zh-CN\"><meta"
                                     + " charset=\"utf-8\"><title>合成研究报告</title><style>body{max-width:900px;margin:40px"
                                     + " auto;font:16px/1.7"
                                     + " sans-serif;color:#183440}pre{white-space:pre-wrap;overflow-wrap:anywhere}h1{font-size:26px}@media"
                                     + " print{body{margin:0}}</style><h1>AD-CausalRisk"
                                     + " 合成研究排序报告</h1><pre>"
                                                + html(readable)
                                                + "</pre>"
                                                + (audience.equals("research")
                                                        ? "<details><summary>结构化研究数据</summary><pre>"
                                                                + html(content)
                                                                + "</pre></details>"
                                                        : "")
                                                + "</html>")
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                "text/html;charset=UTF-8",
                                "assessment-" + id + ".html");
                case "pdf" ->
                        new Export(pdf(readable), "application/pdf", "assessment-" + id + ".pdf");
                default ->
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "仅支持HTML、PDF、JSON导出");
            };
        } catch (IOException error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "报告生成失败");
        }
    }

    private String readable(
            Map<String, Object> report,
            com.adcausalrisk.backend.agent.AgentResult result,
            String audience) {
        StringBuilder text =
                new StringBuilder("AD-CausalRisk 合成研究排序报告\n")
                        .append(audience.equals("doctor") ? "随访沟通模板（软件演示）\n" : "研究核查模板\n");
        text.append(report.get("notice")).append("\n来源：SYNTHETIC_DEMO\n");
        for (String key :
                List.of("assessmentId", "subjectId", "taskId", "generatedAt", "reviewStatus"))
            text.append(key).append(": ").append(report.get(key)).append('\n');
        text.append("\n演示排序分数：")
                .append(result.riskScore() == null ? "不可用（BLOCKED保持null）" : result.riskScore())
                .append("\n演示等级：")
                .append(result.riskLevel())
                .append("\n质量状态：")
                .append(result.qualityStatus())
                .append('\n');
        text.append("数据版本：")
                .append(result.dataVersion())
                .append("\n模型：")
                .append(result.modelVersion())
                .append("\n工作流：")
                .append(result.workflowVersion())
                .append("\n契约：")
                .append(result.contractVersion())
                .append('\n');
        text.append("\n解释与基准\n").append(report.get("explanationBasis")).append('\n');
        for (var value : result.explanations())
            text.append(value.label())
                    .append(" | ")
                    .append(value.contribution())
                    .append(" | ")
                    .append(value.direction())
                    .append(" | ")
                    .append(value.source())
                    .append("\n")
                    .append(
                            value.interpretation() == null
                                    ? value.message()
                                    : value.interpretation())
                    .append('\n');
        text.append("\n缺失模态：")
                .append(String.join("、", result.missingModalities()))
                .append("\n提示与限制\n");
        for (String value : result.warnings()) text.append("- ").append(value).append('\n');
        for (String value : result.limitations()) text.append("- ").append(value).append('\n');
        if (report.containsKey("inputBindingLimitation"))
            text.append(report.get("inputBindingLimitation")).append('\n');
        text.append("\n复核记录\n")
                .append(report.get("reviews"))
                .append("\n输入快照绑定\n")
                .append(report.get("inputBinding"))
                .append('\n');
        if (audience.equals("research")) {
            text.append("\n解释路径（非病因证明）\n");
            for (var path : result.graph().paths())
                text.append(String.join(" → ", path.nodes()))
                        .append(" | ")
                        .append(path.status())
                        .append(" | ")
                        .append(path.evidenceLevel())
                        .append('\n');
            text.append("\n观测节点\n");
            for (var node : result.graph().nodes())
                text.append(node.label())
                        .append(" | ")
                        .append(node.status())
                        .append(" | ")
                        .append(node.observedAt())
                        .append(" | ")
                        .append(node.evidenceLevel())
                        .append('\n');
            text.append("\n条件关联\n");
            for (var edge : result.graph().edges())
                text.append(edge.from())
                        .append(" → ")
                        .append(edge.to())
                        .append(" | ")
                        .append(edge.note())
                        .append('\n');
            text.append("\n实际执行轨迹\n");
            for (var stage : result.stages())
                text.append(stage.stage())
                        .append(" | ")
                        .append(stage.status())
                        .append(" | ")
                        .append(stage.durationMs())
                        .append(" ms\n");
        }
        return text.toString();
    }

    private static String html(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
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
