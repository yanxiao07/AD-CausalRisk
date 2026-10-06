"""Synthetic scoring extracted from the original local demo adapter.
No fitted research model, scientific aggregate or participant data is loaded.
"""
from __future__ import annotations
from datetime import date
import math
from typing import Any

JAVA_CONTRACT_VERSION = "stage4-v1"

JAVA_MODEL_VERSION = "python-demo-logistic-v1"

JAVA_DATA_VERSION_PREFIX = "demo-"

class ContractError(ValueError):
    """Raised when the JavaWeb synthetic-data contract is not satisfied."""

    def __init__(self, message: str, status: int = 400, code: str = "bad_request") -> None:
        super().__init__(message)
        self.status = status
        self.code = code

def _finite_number(value: Any, field: str, allow_none: bool = True) -> float | None:
    """Validate numeric observation fields without accepting NaN/Infinity."""
    if value is None and allow_none:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)):
        raise ContractError(f"{field} 必须是有限数字或 null")
    return float(value)

def _clamp(value: float, lower: float, upper: float) -> float:
    return max(lower, min(upper, value))

def _decimal(value: float) -> float:
    """Keep the wire representation aligned with Java's four-decimal result."""
    return round(float(value), 4)

def _python_explanation(feature: str, label: str, contribution: float, message: str,
                        source: str = JAVA_MODEL_VERSION) -> dict[str, Any]:
    direction = "UP" if contribution > 0.002 else "DOWN" if contribution < -0.002 else "NEUTRAL"
    return {
        "feature": feature,
        "label": label,
        "direction": direction,
        "contribution": _decimal(contribution),
        "message": message,
        "source": source,
    }

def _blocked_result(warning: str) -> dict[str, Any]:
    """Return the only legal shape for a quality-blocked research result."""
    return {
        "status": "ok",
        "contractVersion": JAVA_CONTRACT_VERSION,
        "provider": "python-research-contract",
        "modelVersion": JAVA_MODEL_VERSION,
        "dataVersion": None,
        "score": None,
        "riskLevel": "UNAVAILABLE",
        "qualityStatus": "BLOCKED",
        "warnings": [warning],
        "explanations": [],
        "paths": [{
            "from": "数据质量阻断",
            "to": "风险排序",
            "direction": "BLOCKED",
            "weight": 1.0,
            "evidenceLevel": "QA",
            "note": warning,
        }],
    }

def score_java_synthetic_payload(payload: dict[str, Any]) -> dict[str, Any]:
    """Score only the de-identified synthetic payload used by JavaWeb.

    This is a contract adapter for the existing demo research scorer. It is not
    a second clinical model and must not be used to infer an individual cause
    or treatment effect.
    """
    allowed = {
        "contractVersion", "subjectCode", "modelVersion", "dataVersion",
        "predictionWindowMonths", "visits", "features",
    }
    unexpected = sorted(set(payload) - allowed)
    if unexpected:
        raise ContractError("Java 算法请求包含未允许字段: " + ", ".join(unexpected))
    if payload.get("contractVersion") != JAVA_CONTRACT_VERSION:
        raise ContractError("Java/Python 算法契约版本不匹配", status=409, code="unsupported_contract")
    subject_code = payload.get("subjectCode")
    if not isinstance(subject_code, str) or not subject_code.startswith("DEMO-") or len(subject_code) > 50:
        raise ContractError("Python 契约只接受 DEMO-* 合成档案编号")
    if payload.get("modelVersion") != JAVA_MODEL_VERSION:
        raise ContractError("当前 Python 契约只开放 python-demo-logistic-v1", status=409, code="unsupported_model")
    data_version = payload.get("dataVersion")
    if not isinstance(data_version, str) or not data_version.startswith(JAVA_DATA_VERSION_PREFIX) or len(data_version) > 100:
        raise ContractError("Python 契约只接受 demo-* 数据版本")
    window = payload.get("predictionWindowMonths", 24)
    if isinstance(window, bool) or not isinstance(window, int) or not 6 <= window <= 60:
        raise ContractError("预测窗口必须是 6 至 60 个月的整数")
    visits = payload.get("visits")
    features = payload.get("features")
    if not isinstance(visits, list) or len(visits) > 100:
        raise ContractError("访视列表格式无效或数量超过限制")
    if not isinstance(features, list) or len(features) > 500:
        raise ContractError("特征列表格式无效或数量超过限制")

    normalized_visits: list[dict[str, Any]] = []
    for index, item in enumerate(visits, start=1):
        if not isinstance(item, dict):
            raise ContractError(f"第 {index} 条访视必须是对象")
        if set(item) - {"visitCode", "visitDate", "mmse", "moca", "cdrSb"}:
            raise ContractError(f"第 {index} 条访视包含未允许字段")
        visit_date = item.get("visitDate")
        if not isinstance(visit_date, str):
            raise ContractError(f"第 {index} 条访视缺少日期")
        try:
            date.fromisoformat(visit_date)
        except ValueError as exc:
            raise ContractError(f"第 {index} 条访视日期格式无效") from exc
        visit_code = item.get("visitCode")
        if not isinstance(visit_code, str) or not visit_code.strip() or len(visit_code) > 50:
            raise ContractError(f"第 {index} 条访视编号无效")
        normalized_visits.append({
            "visitCode": visit_code,
            "visitDate": visit_date,
            "mmse": _finite_number(item.get("mmse"), f"第 {index} 条 MMSE"),
            "moca": _finite_number(item.get("moca"), f"第 {index} 条 MoCA"),
            "cdrSb": _finite_number(item.get("cdrSb"), f"第 {index} 条 CDR-SB"),
        })

    normalized_features: list[dict[str, Any]] = []
    for index, item in enumerate(features, start=1):
        if not isinstance(item, dict):
            raise ContractError(f"第 {index} 条特征必须是对象")
        if set(item) - {"featureName", "featureValue", "recordedAt", "unit"}:
            raise ContractError(f"第 {index} 条特征包含未允许字段")
        name = item.get("featureName")
        recorded_at = item.get("recordedAt")
        if not isinstance(name, str) or not name.strip() or len(name) > 100:
            raise ContractError(f"第 {index} 条特征名称无效")
        if not isinstance(recorded_at, str):
            raise ContractError(f"第 {index} 条特征缺少记录日期")
        try:
            date.fromisoformat(recorded_at)
        except ValueError as exc:
            raise ContractError(f"第 {index} 条特征日期格式无效") from exc
        normalized_features.append({
            "featureName": name,
            "featureValue": _finite_number(item.get("featureValue"), f"第 {index} 条特征值"),
            "recordedAt": recorded_at,
        })

    if not normalized_visits:
        result = _blocked_result("缺少可用访视，无法进行研究型排序")
        result["dataVersion"] = data_version
        return result

    ordered_visits = sorted(normalized_visits, key=lambda item: item["visitDate"])
    latest = ordered_visits[-1]
    if all(latest[key] is None for key in ("mmse", "moca", "cdrSb")):
        result = _blocked_result("最近访视缺少 MMSE、MoCA、CDR-SB 等核心认知指标")
        result["dataVersion"] = data_version
        return result

    warnings: list[str] = []
    score = 0.18
    explanations: list[dict[str, Any]] = []

    if latest["mmse"] is not None:
        contribution = _clamp((27.0 - latest["mmse"]) / 10.0, -0.08, 0.22)
        score += contribution
        explanations.append(_python_explanation(
            "MMSE", "MMSE 认知评分", contribution,
            "当前认知评分对风险排序贡献偏高" if contribution >= 0 else "当前认知评分对风险排序贡献偏低",
        ))
    else:
        warnings.append("最近访视缺少 MMSE，解释完整度下降")

    if latest["moca"] is not None:
        contribution = _clamp((25.0 - latest["moca"]) / 12.0, -0.04, 0.14)
        score += contribution
        explanations.append(_python_explanation(
            "MoCA", "MoCA 认知评分", contribution,
            "MoCA 信号使排序分数上移" if contribution >= 0 else "MoCA 信号使排序分数下移",
        ))
    else:
        warnings.append("最近访视缺少 MoCA，未将该模态纳入本次评分")

    if latest["cdrSb"] is not None:
        contribution = _clamp(latest["cdrSb"] / 10.0, 0.0, 0.20)
        score += contribution
        explanations.append(_python_explanation(
            "CDR_SB", "CDR-SB", contribution, "CDR-SB 信号在当前演示排序中贡献上移",
        ))
    else:
        warnings.append("最近访视缺少 CDR-SB，结果需人工复核")

    if len(ordered_visits) >= 2 and ordered_visits[0]["mmse"] is not None and latest["mmse"] is not None:
        delta = latest["mmse"] - ordered_visits[0]["mmse"]
        contribution = _clamp(-delta / 30.0, -0.08, 0.14)
        score += contribution
        explanations.append(_python_explanation(
            "MMSE_DELTA", "纵向 MMSE 变化", contribution,
            "纵向变化使排序分数上移" if contribution > 0 else "纵向变化暂未使排序分数上移",
        ))

    feature_map: dict[str, float] = {}
    for feature in sorted(normalized_features, key=lambda item: item["recordedAt"], reverse=True):
        if feature["featureValue"] is not None and feature["featureName"] not in feature_map:
            feature_map[feature["featureName"]] = feature["featureValue"]

    apoe = feature_map.get("APOE_E4_COUNT")
    if apoe is not None:
        contribution = _clamp(apoe * 0.045, 0.0, 0.09)
        score += contribution
        explanations.append(_python_explanation(
            "APOE_E4_COUNT", "APOE ε4 计数", contribution,
            "遗传模态作为个体化上下文进入排序", "synthetic_feature_store",
        ))
    else:
        warnings.append("APOE 特征缺失，个体化遗传上下文不完整")

    hippocampus = feature_map.get("MRI_HIPPOCAMPUS_L", feature_map.get("MRI_HIPPOCAMPUS_R"))
    if hippocampus is not None:
        contribution = _clamp((0.42 - hippocampus) * 0.22, -0.03, 0.07)
        score += contribution
        explanations.append(_python_explanation(
            "MRI_HIPPOCAMPUS", "MRI 海马数值", contribution,
            "影像模态作为结构性上下文进入排序", "synthetic_feature_store",
        ))
    else:
        warnings.append("MRI 海马特征缺失，结构性上下文不完整")

    score = _clamp(score, 0.05, 0.95)
    explanations.sort(key=lambda item: abs(float(item["contribution"])), reverse=True)
    paths: list[dict[str, Any]] = [{
        "from": "认知状态",
        "to": "风险排序",
        "direction": "UPSTREAM",
        "weight": 0.78,
        "evidenceLevel": "RESEARCH",
        "note": "基于访视时间顺序的条件性研究路径，不等于个体病因证明",
    }]
    if "APOE_E4_COUNT" in feature_map:
        paths.append({
            "from": "APOE ε4 上下文",
            "to": "个体化图谱上下文",
            "direction": "MODULATES",
            "weight": 0.51,
            "evidenceLevel": "RESEARCH",
            "note": "仅表示该模态进入当前个体化解释，不代表遗传决定论",
        })
    if "MRI_HIPPOCAMPUS_L" in feature_map or "MRI_HIPPOCAMPUS_R" in feature_map:
        paths.append({
            "from": "MRI 海马数值",
            "to": "认知信号",
            "direction": "CONTEXT",
            "weight": 0.44,
            "evidenceLevel": "RESEARCH",
            "note": "影像信号用于排序解释，需结合数据质量和人工复核",
        })
    if latest["cdrSb"] is None or warnings:
        paths.append({
            "from": "缺失模态",
            "to": "人工复核",
            "direction": "REQUIRES_REVIEW",
            "weight": 0.90,
            "evidenceLevel": "QA",
            "note": "当前路径受缺失信息影响，评估结果只能作为研究参考",
        })
    risk_level = "LOW" if score < 0.34 else "MEDIUM" if score < 0.65 else "HIGH"
    return {
        "status": "ok",
        "contractVersion": JAVA_CONTRACT_VERSION,
        "provider": "python-research-contract",
        "modelVersion": JAVA_MODEL_VERSION,
        "dataVersion": data_version,
        "score": _decimal(score),
        "riskLevel": risk_level,
        "qualityStatus": "PASS" if not warnings else "WARN",
        "warnings": warnings,
        "explanations": explanations,
        "paths": paths,
    }
