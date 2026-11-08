package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.common.*;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Public, committed aggregate evidence only. Never loads model parameters or patient inputs. */
@Service
public class ResearchEvidenceService {
    private final Path artifactsRoot;

    private record Report(String key, String title, String file) {}

    private static final List<Report> REPORTS =
            List.of(
                    new Report(
                            "table-perturbation",
                            "表格模型训练扰动与解释一致性",
                            "Training_Perturbation_Results.md"),
                    new Report(
                            "graph-perturbation",
                            "图模型训练扰动",
                            "Graph_Training_Perturbation_Results.md"),
                    new Report("graph-strata", "自然模态分层对照", "Graph_Modality_Strata_Results.md"),
                    new Report("graph-explanation", "图预测解释与忠实性", "Graph_Explanation_Results.md"),
                    new Report("table-xai", "表格精确分组归因", "Table_Grouped_XAI_Results.md"),
                    new Report(
                            "causal-identification",
                            "因果识别结果及未完成边界",
                            "Causal_Identification_Results.md"),
                    new Report("admission", "真实模型宿主准入", "Research_Host_Evidence_Admission.md"),
                    new Report("feature-monitor", "特征漂移计算与输入要求", "Feature_Drift_Monitoring.md"),
                    new Report("performance-monitor", "性能监测与未知结局界限", "Performance_Monitoring.md"),
                    new Report("lifecycle-monitor", "统一生命周期监测与隐私门槛", "Lifecycle_Monitoring.md"));

    public ResearchEvidenceService(@Value("${research.artifacts-root:}") String root) {
        artifactsRoot = root.isBlank() ? null : Path.of(root).toAbsolutePath().normalize();
    }

    private static void authorize(AppUserPrincipal user) {
        if (user.roleCodes().contains("PATIENT")
                || user.roleCodes().stream()
                        .noneMatch(r -> r.equals("ADMIN") || r.equals("RESEARCHER")))
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "研究证据仅向管理员和研究员开放");
    }

    private Map<String, Object> report(Report report, boolean content) {
        try {
            byte[] bytes;
            try (var stream =
                    new ClassPathResource("research-evidence/" + report.file()).getInputStream()) {
                bytes = stream.readAllBytes();
            }
            var value = new LinkedHashMap<String, Object>();
            value.put("key", report.key());
            value.put("title", report.title());
            value.put("source", "docs/" + report.file());
            value.put(
                    "sha256",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            if (content) value.put("markdown", new String(bytes, StandardCharsets.UTF_8));
            return value;
        } catch (Exception unavailable) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "封存研究说明未打包或不可读取");
        }
    }

    public Map<String, Object> catalog(AppUserPrincipal user) {
        authorize(user);
        List<Map<String, Object>> models = new ArrayList<>();
        for (String kind : List.of("seven", "multimodal"))
            for (String algorithm : List.of("logistic", "random_forest", "xgboost")) {
                String directory =
                        kind.equals("seven")
                                ? "core_final_training_2026-10-24"
                                : "core_multimodal_comparison_2026-10-24";
                String filename =
                        "final__"
                                + algorithm
                                + (kind.equals("seven") ? "" : "__multimodal_values")
                                + ".joblib";
                models.add(
                        model(
                                kind + "-" + algorithm,
                                kind.equals("seven") ? "七变量表格基线" : "十变量多模态基线",
                                "data/adni/processed/" + directory + "/" + filename));
            }
        for (String seed : List.of("20260926", "20260927", "20260928"))
            for (String arm : List.of("prior", "self_only", "semantic_permutation"))
                models.add(
                        model(
                                "graph-" + seed + "-" + arm,
                                "原生图固定三臂研究",
                                "data/adni/processed/core_graph_comparison_2026-10-25/final__"
                                        + seed
                                        + "__"
                                        + arm
                                        + ".npz"));
        return Map.of(
                "evidenceVersion",
                "committed-research-evidence-v1",
                "lookupConfigured",
                artifactsRoot != null,
                "models",
                models,
                "reports",
                REPORTS.stream().map(r -> report(r, false)).toList(),
                "predictionAdmission",
                "NO_GO_INDEPENDENT_EVIDENCE_INCOMPLETE",
                "monitoringAdmission",
                "QUALIFIED_REFERENCE_AND_CURRENT_SNAPSHOTS_REQUIRED",
                "limitations",
                List.of(
                        "文件定位不等于权重字节、完整状态、参考或真实输入准入。此接口不加载模型、不执行推理或训练。",
                        "复用已封存的聚合说明；五次扰动范围不是置信区间，SUPPRESSED/null/不可估计保留原结论。",
                        "固定先验图增益未确立，不能把既有预测基线或归因改称已识别的个体因果效应。"));
    }

    private Map<String, Object> model(String id, String family, String relative) {
        boolean located =
                artifactsRoot != null
                        && Files.isRegularFile(artifactsRoot.resolve(relative).normalize());
        return Map.of(
                "id",
                id,
                "family",
                family,
                "relativePath",
                relative,
                "located",
                located,
                "admission",
                "NOT_ADMITTED_FOR_BUSINESS_INFERENCE",
                "parameterBytesRead",
                false);
    }

    public Map<String, Object> evidence(String key, AppUserPrincipal user) {
        authorize(user);
        return report(
                REPORTS.stream()
                        .filter(r -> r.key().equals(key))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.VALIDATION_ERROR, "未登记的研究说明")),
                true);
    }
}
