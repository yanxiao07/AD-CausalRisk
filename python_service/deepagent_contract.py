"""Deterministic, synthetic-only deepagent-v1 research workflow.

This adapter makes the four research stages inspectable for Java integration.
The warning tool is a demonstration rule, not the fitted ADNI study model.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
import time
from datetime import date
from typing import Any, Callable


CONTRACT_VERSION = "deepagent-v1"
WORKFLOW_VERSION = "causalrisk-workflow-v1"
MODEL_VERSION = "deepagent-causal-warning-v1"
STAGES = ("DATA_FUSION", "CAUSAL_GRAPH", "WARNING_MODEL", "EXPLANATION")
RUN_ID_RE = re.compile(r"run-[A-Za-z0-9][A-Za-z0-9-]{0,63}\Z")
SUBJECT_RE = re.compile(r"DEMO-[A-Za-z0-9-]{1,45}\Z")
LIMITATIONS = [
    "合成数据的研究型排序，不是临床诊断或经外部验证的风险概率。",
    "图谱箭头是先验约束下的条件性解释，不证明个体病因或干预效应。",
    "本接口不调用或导出 ADNI 受控参与者数据；结果须人工复核。",
]
ALLOWED_FIELDS = {
    "contractVersion", "runId", "subjectCode", "dataVersion", "modelVersion",
    "predictionWindowMonths", "visits", "features",
}
FEATURES = {"APOE_E4_COUNT", "MRI_HIPPOCAMPUS_L", "MRI_HIPPOCAMPUS_R"}


class AgentContractError(ValueError):
    def __init__(self, message: str, status: int = 400, code: str = "VALIDATION_400") -> None:
        super().__init__(message)
        self.status = status
        self.code = code


def validate_request(payload: dict[str, Any]) -> None:
    if not isinstance(payload, dict):
        raise AgentContractError("请求必须是 JSON 对象")
    extra = set(payload) - ALLOWED_FIELDS
    if extra:
        raise AgentContractError("请求包含未允许字段")
    if payload.get("contractVersion") != CONTRACT_VERSION or payload.get("modelVersion") != MODEL_VERSION:
        raise AgentContractError("契约或模型版本不匹配", 409, "AGENT_CONTRACT_MISMATCH")
    if not isinstance(payload.get("runId"), str) or not RUN_ID_RE.fullmatch(payload["runId"]):
        raise AgentContractError("runId 格式无效")
    if not isinstance(payload.get("subjectCode"), str) or not SUBJECT_RE.fullmatch(payload["subjectCode"]):
        raise AgentContractError("只接受 DEMO-* 合成档案")
    version = payload.get("dataVersion")
    if not isinstance(version, str) or not version.startswith("demo-") or len(version) > 100:
        raise AgentContractError("只接受 demo-* 数据版本")
    window = payload.get("predictionWindowMonths")
    if type(window) is not int or not 6 <= window <= 60:
        raise AgentContractError("预测窗口必须是 6 至 60 个月的整数")
    visits, features = payload.get("visits"), payload.get("features")
    if not isinstance(visits, list) or len(visits) > 100 or not isinstance(features, list) or len(features) > 500:
        raise AgentContractError("访视或特征列表无效")
    visit_codes: set[str] = set()
    for item in visits:
        if not isinstance(item, dict) or set(item) - {"visitCode", "visitDate", "mmse", "moca", "cdrSb"}:
            raise AgentContractError("访视字段无效")
        _check_date(item.get("visitDate"), "访视日期")
        if not isinstance(item.get("visitCode"), str) or not item["visitCode"].strip() or len(item["visitCode"]) > 50:
            raise AgentContractError("访视编号无效")
        if item["visitCode"] in visit_codes:
            raise AgentContractError("访视编号重复")
        visit_codes.add(item["visitCode"])
        for key, lower, upper in (("mmse", 0, 30), ("moca", 0, 30), ("cdrSb", 0, 18)):
            _check_number(item.get(key), key, lower, upper)
    for item in features:
        if not isinstance(item, dict) or set(item) - {"featureName", "featureValue", "recordedAt", "unit"}:
            raise AgentContractError("特征字段无效")
        _check_date(item.get("recordedAt"), "特征日期")
        name = item.get("featureName")
        if not isinstance(name, str) or not name.strip() or len(name) > 100:
            raise AgentContractError("特征名称无效")
        if name not in FEATURES:
            raise AgentContractError("特征名称不在合成模型白名单内")
        expected_unit = "count" if name == "APOE_E4_COUNT" else "normalized"
        if item.get("unit") != expected_unit:
            raise AgentContractError("特征单位与合成模型不兼容")
        value = item.get("featureValue")
        if name == "APOE_E4_COUNT":
            if value is not None and (type(value) is not int or value not in (0, 1, 2)):
                raise AgentContractError("APOE ε4 计数必须为 0、1、2 或 null")
        elif name in {"MRI_HIPPOCAMPUS_L", "MRI_HIPPOCAMPUS_R"}:
            _check_number(value, name, 0, 1)
        else:
            _check_number(value, name, None, None)


def _check_date(value: Any, label: str) -> None:
    if not isinstance(value, str):
        raise AgentContractError(f"{label}缺失")
    try:
        parsed = date.fromisoformat(value)
    except ValueError as exc:
        raise AgentContractError(f"{label}格式无效") from exc
    if parsed > date.today():
        raise AgentContractError(f"{label}不能晚于今天")


def _check_number(value: Any, label: str, lower: float | None, upper: float | None) -> None:
    if value is None:
        return
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise AgentContractError(f"{label}必须是有限数字或 null")
    if lower is not None and value < lower or upper is not None and value > upper:
        raise AgentContractError(f"{label}超出允许范围")


def payload_digest(payload: dict[str, Any]) -> str:
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def run_workflow(payload: dict[str, Any], score_tool: Callable[[dict[str, Any]], dict[str, Any]]) -> dict[str, Any]:
    validate_request(payload)
    stages: list[dict[str, Any]] = []
    warnings: list[str] = []
    start = time.perf_counter()

    # DATA_FUSION: preserve source dates, select only observations available at
    # the latest visit, and record missing modalities instead of imputing them.
    visits = sorted(payload["visits"], key=lambda row: (row["visitDate"], row["visitCode"]))
    latest = visits[-1] if visits else None
    cutoff = latest["visitDate"] if latest else None
    eligible = [f for f in payload["features"] if cutoff and f["recordedAt"] <= cutoff]
    excluded_future = len(payload["features"]) - len(eligible)
    if excluded_future:
        warnings.append("索引访视之后采集的特征已排除，防止时间泄漏")
    feature_map: dict[str, dict[str, Any]] = {}
    duplicate_feature_values: dict[tuple[str, str], Any] = {}
    conflicting_feature = False
    for feature in sorted(eligible, key=lambda row: row["recordedAt"]):
        key = (feature["featureName"], feature["recordedAt"])
        if key in duplicate_feature_values and duplicate_feature_values[key] != feature["featureValue"]:
            conflicting_feature = True
        duplicate_feature_values[key] = feature["featureValue"]
        if feature["featureValue"] is not None:
            feature_map[feature["featureName"]] = feature
    missing = [name for name in ("APOE_E4_COUNT", "MRI_HIPPOCAMPUS_L", "MRI_HIPPOCAMPUS_R") if name not in feature_map]
    if missing:
        warnings.append("部分遗传或 MRI 指标缺失，图谱对应路径标为 MISSING")
    has_cognition = latest is not None and any(latest.get(key) is not None for key in ("mmse", "moca", "cdrSb"))
    ambiguous_latest = latest is not None and sum(row["visitDate"] == cutoff for row in visits) > 1
    unsupported_window = payload["predictionWindowMonths"] != 24
    blocked = not has_cognition or ambiguous_latest or conflicting_feature or unsupported_window
    if ambiguous_latest:
        warnings.append("最近访视日期对应多个记录，索引访视无法唯一确定")
    if conflicting_feature:
        warnings.append("同一特征在同一天存在冲突值，需人工核对")
    if unsupported_window:
        warnings.append("当前合成研究规则仅支持 24 个月预警任务")
    stages.append(_stage("DATA_FUSION", "BLOCKED" if blocked else "COMPLETED", start, {
        "visitCount": len(visits), "featureCount": len(feature_map), "excludedPostIndexFeatures": excluded_future,
        "missingModalities": missing, "indexDate": cutoff, "qualityRuleVersion": "synthetic-qa-v1",
    }))
    if blocked:
        if not has_cognition:
            warnings.append("缺少可用的核心认知访视，风险排序已阻断")
        return _result(payload, "BLOCKED", "DATA_FUSION", "BLOCKED", None, "UNAVAILABLE", warnings,
                       {"nodes": [], "edges": [], "paths": []}, [], stages, missing)

    # CAUSAL_GRAPH: a fixed, transparent prior graph with per-subject activation.
    tic = time.perf_counter()
    nodes = [{"id": "COGNITION", "label": "认知状态", "status": "ACTIVE", "evidenceLevel": "PRIOR"},
             {"id": "WARNING", "label": "研究型风险排序", "status": "ACTIVE", "evidenceLevel": "MODEL"}]
    for name, label in (("APOE_E4_COUNT", "APOE ε4"), ("MRI_HIPPOCAMPUS_L", "左海马 MRI"),
                        ("MRI_HIPPOCAMPUS_R", "右海马 MRI")):
        nodes.append({"id": name, "label": label, "status": "ACTIVE" if name in feature_map else "MISSING",
                      "evidenceLevel": "PRIOR", "observedAt": feature_map.get(name, {}).get("recordedAt")})
    edge_specs = (("APOE_E4_COUNT", "COGNITION"), ("MRI_HIPPOCAMPUS_L", "COGNITION"),
                  ("MRI_HIPPOCAMPUS_R", "COGNITION"), ("COGNITION", "WARNING"))
    edges = [{"from": source, "to": target,
              "status": "ACTIVE" if source == "COGNITION" or source in feature_map else "MISSING",
              "evidenceLevel": "PRIOR", "direction": "CONDITIONAL",
              "note": "研究先验约束路径，不代表已识别的个体因果效应"} for source, target in edge_specs]
    paths = [{"nodes": [edge["from"], edge["to"]], "status": edge["status"],
              "evidenceLevel": edge["evidenceLevel"]} for edge in edges]
    graph = {"nodes": nodes, "edges": edges, "paths": paths, "graphVersion": "synthetic-prior-v1"}
    stages.append(_stage("CAUSAL_GRAPH", "COMPLETED", tic, {"activePaths": sum(p["status"] == "ACTIVE" for p in paths)}))

    # WARNING_MODEL: the existing versioned demonstration scorer supplies
    # contributions. The graph mask decides which observed inputs may enter.
    tic = time.perf_counter()
    legacy = score_tool({
        "contractVersion": "stage4-v1", "subjectCode": payload["subjectCode"],
        "modelVersion": "python-demo-logistic-v1", "dataVersion": payload["dataVersion"],
        "predictionWindowMonths": payload["predictionWindowMonths"], "visits": visits,
        "features": [f for f in eligible if f["featureName"] in FEATURES],
    })
    if legacy["qualityStatus"] == "BLOCKED":
        raise AgentContractError("评分工具与融合质量闸门不一致", 500, "ASSESSMENT_FAILED")
    score = legacy["score"]
    warnings.extend(legacy["warnings"])
    stages.append(_stage("WARNING_MODEL", "COMPLETED", tic, {"method": "graph-masked-synthetic-rule-v1"}))

    tic = time.perf_counter()
    explanations = [dict(item, evidenceLevel="PREDICTIVE_DEMO", interpretation="预测贡献，不是因果效应")
                    for item in legacy["explanations"]]
    for item in explanations:
        item["source"] = "graph-masked-synthetic-rule-v1"
    stages.append(_stage("EXPLANATION", "COMPLETED", tic, {"contributionCount": len(explanations)}))
    quality = "WARN" if warnings else "PASS"
    return _result(payload, "COMPLETED", "EXPLANATION", quality, score,
                   legacy["riskLevel"], list(dict.fromkeys(warnings)), graph, explanations, stages, missing)


def _stage(name: str, status: str, started: float, summary: dict[str, Any]) -> dict[str, Any]:
    return {"stage": name, "status": status, "durationMs": round((time.perf_counter() - started) * 1000, 3),
            "summary": summary}


def _result(payload: dict[str, Any], status: str, current_stage: str, quality: str,
            score: float | None, level: str, warnings: list[str], graph: dict[str, Any],
            explanations: list[dict[str, Any]], stages: list[dict[str, Any]], missing: list[str]) -> dict[str, Any]:
    return {
        "status": status, "contractVersion": CONTRACT_VERSION, "runId": payload["runId"],
        "workflowVersion": WORKFLOW_VERSION, "currentStage": current_stage,
        "dataVersion": payload["dataVersion"], "modelVersion": payload["modelVersion"],
        "qualityStatus": quality, "riskScore": score, "riskLevel": level,
        "warnings": warnings, "graph": graph, "explanations": explanations,
        "missingModalities": missing, "limitations": LIMITATIONS, "stages": stages,
        "dataSource": "SYNTHETIC_DEMO", "reviewRequired": True,
    }
