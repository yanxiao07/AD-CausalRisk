package com.adcausalrisk.backend.assessment;

import com.adcausalrisk.backend.common.*;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.fasterxml.jackson.databind.*;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class AssessmentTraceController {
    private final AssessmentService assessments;
    private final BusinessQueries queries;
    private final ObjectMapper json;

    public AssessmentTraceController(
            AssessmentService assessments, BusinessQueries queries, ObjectMapper json) {
        this.assessments = assessments;
        this.queries = queries;
        this.json = json;
    }

    @GetMapping("/api/assessment-tasks/{id}/input")
    public Envelope<?> input(@PathVariable long id, Authentication auth) {
        assessments.findTask(id, (AppUserPrincipal) auth.getPrincipal());
        var rows =
                queries.rows(
                        "SELECT"
                            + " input_digest,data_version,captured_at,request_json,quality_check_id,provenance_json"
                            + " FROM task_input_snapshot WHERE task_id=?",
                        id);
        if (rows.isEmpty())
            return Envelope.success(
                    Map.of("available", false, "message", "历史任务未保存输入快照，不能从当前资料追溯重建"));
        var result = rows.get(0);
        try {
            result.put("request", json.readTree(result.remove("requestJson").toString()));
            Object provenance = result.remove("provenanceJson");
            result.put(
                    "provenance", provenance == null ? null : json.readTree(provenance.toString()));
        } catch (Exception invalid) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "输入快照不可读取");
        }
        result.put("available", true);
        return Envelope.success(result);
    }

    private Map<String, JsonNode> index(JsonNode array, boolean edge) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (var value : array)
            result.put(
                    edge
                            ? value.path("from").asText() + "->" + value.path("to").asText()
                            : value.path("id").asText(),
                    value);
        return result;
    }

    @GetMapping("/api/subjects/{id}/graph-diff")
    public Envelope<?> graphDiff(
            @PathVariable long id,
            @RequestParam long from,
            @RequestParam long to,
            Authentication auth) {
        var actor = (AppUserPrincipal) auth.getPrincipal();
        var left = assessments.findAssessment(from, actor);
        var right = assessments.findAssessment(to, actor);
        if (left.assessment().getSubjectId() != id || right.assessment().getSubjectId() != id)
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "两个评估必须属于当前同一档案");
        JsonNode a = json.valueToTree(left.result().graph()),
                b = json.valueToTree(right.result().graph());
        var nodesA = index(a.path("nodes"), false);
        var nodesB = index(b.path("nodes"), false);
        var edgesA = index(a.path("edges"), true);
        var edgesB = index(b.path("edges"), true);
        return Envelope.success(
                Map.of(
                        "from",
                        from,
                        "to",
                        to,
                        "addedNodes",
                        nodesB.entrySet().stream()
                                .filter(e -> !nodesA.containsKey(e.getKey()))
                                .map(Map.Entry::getValue)
                                .toList(),
                        "removedNodes",
                        nodesA.entrySet().stream()
                                .filter(e -> !nodesB.containsKey(e.getKey()))
                                .map(Map.Entry::getValue)
                                .toList(),
                        "changedNodes",
                        nodesB.entrySet().stream()
                                .filter(
                                        e ->
                                                nodesA.containsKey(e.getKey())
                                                        && !e.getValue()
                                                                .equals(nodesA.get(e.getKey())))
                                .map(Map.Entry::getValue)
                                .toList(),
                        "addedEdges",
                        edgesB.entrySet().stream()
                                .filter(e -> !edgesA.containsKey(e.getKey()))
                                .map(Map.Entry::getValue)
                                .toList(),
                        "changedEdges",
                        edgesB.entrySet().stream()
                                .filter(
                                        e ->
                                                edgesA.containsKey(e.getKey())
                                                        && !e.getValue()
                                                                .equals(edgesA.get(e.getKey())))
                                .map(Map.Entry::getValue)
                                .toList(),
                        "removedEdges",
                        edgesA.entrySet().stream()
                                .filter(e -> !edgesB.containsKey(e.getKey()))
                                .map(Map.Entry::getValue)
                                .toList(),
                        "limitation",
                        "仅比较图结构与观测状态，不证明个体因果变化"));
    }
}
