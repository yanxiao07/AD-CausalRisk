"""Review the repository's constructed research assessment examples."""

import argparse
import json
from pathlib import Path
from time import perf_counter

from research_runtime.research_table_protocol import digest


FIXTURES = Path(__file__).resolve().parents[2] / "docs/contracts/examples/research-table-agent"
CASES = ("seven", "ten", "blocked", "missing-availability")
CHECKS = {
    "request_binding": ("request", "result"),
    "assessment_state": ("result",),
    "same_assessment": ("result",),
    "historical_availability": ("request", "result", "availability"),
    "interpretation_scope": ("result", "scope"),
}
LIMITATIONS = {
    "availability": {"requiresFirstAvailability": True},
    "scope": {"constructedOnly": True, "clinicalUse": False, "individualEffect": False},
}
QUESTIONS = (
    "Review the selected constructed assessment using read_evidence. Cards: request, result, "
    "availability, scope. Return a JSON object with a findings list. Each item must have "
    "check, status, evidence_ids. Use each check exactly once. Read every cited card. "
    "Compare request_binding (request and result run, data version, window, time policy and request digest; "
    "SUPPORTED if all equal, MISMATCH otherwise), assessment_state (result; report COMPLETED "
    "or BLOCKED), same_assessment (result; SUPPORTED if completed with both outputs and the "
    "binding present and explanationPointMatches is true; UNAVAILABLE for a blocked result "
    "with all three absent; MISMATCH otherwise), "
    "historical_availability (request, result, availability; UNVERIFIED when any observed value "
    "has unknown availability, the policy is retrospective, or the historical certification "
    "is false; MISSING_EVIDENCE if the availability card is missing; SUPPORTED otherwise), interpretation_scope "
    "(result and scope; CONSTRUCTED_ONLY if the result is constructed and both sources agree "
    "that clinical use and individual causal effects are outside scope; MISMATCH otherwise). "
    "Cite the named cards in evidence_ids, omitting a card only when its tool response says MISSING_EVIDENCE. "
    "Use JSON only. Never infer a clinical recommendation or a causal effect."
)


def load_case(case):
    if case not in CASES:
        raise ValueError("Unknown constructed example")
    fixture = "seven" if case == "missing-availability" else case
    request = json.loads((FIXTURES / f"request-{fixture}.json").read_text(encoding="utf-8"))
    result = json.loads((FIXTURES / f"response-{fixture}.json").read_text(encoding="utf-8"))
    if (
        result["dataScope"] != "CONSTRUCTED_VALIDATION"
        or result["clinicalUse"] is not False
        or result["individualCausalEffectIdentified"] is not False
    ):
        raise ValueError("Only constructed assessment examples are supported")
    binding_fields = ("runId", "dataVersion", "predictionWindowMonths", "availabilityPolicy")
    point = result["prediction"]
    explanation = result["explanation"]
    cards = {
        "request": {
            **{key: request[key] for key in binding_fields},
            "requestSha256": digest(request),
            "observedWithUnknownAvailability": sum(
                row["value"] is not None and row["availableDate"] is None
                for row in request["features"].values()
            ),
        },
        "result": {
            **{key: result[key] for key in binding_fields},
            **{key: result[key] for key in (
                "status", "dataScope", "historicalAvailabilityCertified",
                "clinicalUse", "individualCausalEffectIdentified", "requestSha256",
            )},
            "predictionPresent": result["prediction"] is not None,
            "explanationPresent": result["explanation"] is not None,
            "bindingPresent": result["binding"] is not None,
            "explanationPointMatches": (
                point is not None and explanation is not None
                and explanation["predictionCif"] == [[point["adCif24m"], point["otherDementiaCif24m"]]]
            ),
        },
        **LIMITATIONS,
    }
    if case == "missing-availability":
        del cards["availability"]
    return cards


def baseline(cards):
    request, result = cards["request"], cards["result"]
    matches = all(result[key] == request[key] for key in (
        "runId", "dataVersion", "predictionWindowMonths", "availabilityPolicy", "requestSha256"
    ))
    outputs = tuple(result[key] for key in (
        "predictionPresent", "explanationPresent", "bindingPresent", "explanationPointMatches"
    ))
    same_assessment = {
        ("COMPLETED", (True, True, True, True)): "SUPPORTED",
        ("BLOCKED", (False, False, False, False)): "UNAVAILABLE",
    }.get((result["status"], outputs), "MISMATCH")
    if "availability" not in cards:
        historical = "MISSING_EVIDENCE"
    elif (
        request["observedWithUnknownAvailability"]
        or request["availabilityPolicy"] == "RETROSPECTIVE_SNAPSHOT"
        or not result["historicalAvailabilityCertified"]
    ):
        historical = "UNVERIFIED"
    else:
        historical = "SUPPORTED"
    scope = cards["scope"]
    scope_matches = (
        scope["constructedOnly"] and result["dataScope"] == "CONSTRUCTED_VALIDATION"
        and scope["clinicalUse"] == result["clinicalUse"] is False
        and scope["individualEffect"] == result["individualCausalEffectIdentified"] is False
    )
    statuses = (
        "SUPPORTED" if matches else "MISMATCH", result["status"], same_assessment, historical,
        "CONSTRUCTED_ONLY" if scope_matches else "MISMATCH",
    )
    return [
        {"check": check, "status": status, "evidence_ids": [item for item in ids if item in cards]}
        for (check, ids), status in zip(CHECKS.items(), statuses, strict=True)
    ]


def validate_draft(content, expected, retrieved):
    def unique_object(pairs):
        result = dict(pairs)
        if len(result) != len(pairs):
            raise ValueError("Duplicate draft key")
        return result

    draft = json.loads(content, object_pairs_hook=unique_object)
    if not isinstance(draft, dict) or set(draft) != {"findings"}:
        return False
    findings = draft["findings"]
    if not isinstance(findings, list) or len(findings) != len(expected):
        return False
    remaining = {row["check"]: row for row in expected}
    for row in findings:
        if not isinstance(row, dict) or set(row) != {"check", "status", "evidence_ids"}:
            return False
        check, citations = row["check"], row["evidence_ids"]
        if (
            not isinstance(check, str) or not isinstance(citations, list)
            or any(not isinstance(citation, str) for citation in citations)
        ):
            return False
        wanted = remaining.pop(check, None)
        if wanted is None or row["status"] != wanted["status"]:
            return False
        if sorted(citations) != sorted(wanted["evidence_ids"]) or not set(citations) <= retrieved:
            return False
    return not remaining


def review(case, model=None):
    """Return canonical findings, plus an audit of any optional model attempt."""
    cards = load_case(case)
    start = perf_counter()
    findings = baseline(cards)
    report = {"case": case, "findings": findings, "baselineMs": round((perf_counter() - start) * 1000, 3)}
    if model is None:
        return {**report, "agent": {"status": "SKIPPED"}}

    from deepagents import create_deep_agent
    from deepagents.backends import StateBackend
    from langchain.agents.middleware import AgentMiddleware
    from langchain_core.messages import ToolMessage
    from langchain_core.tools import tool
    from langsmith import tracing_context

    retrieved = set()
    violations = []

    @tool
    def read_evidence(evidence_id: str) -> dict:
        """Read a projected evidence card by its exact ID."""
        if evidence_id not in cards:
            if evidence_id not in {"request", "result", *LIMITATIONS}:
                violations.append("UNKNOWN_EVIDENCE_ID")
            return {"error": "MISSING_EVIDENCE"}
        retrieved.add(evidence_id)
        return cards[evidence_id]

    class EvidenceToolsOnly(AgentMiddleware):
        def wrap_model_call(self, request, handler):
            allowed = [item for item in request.tools if getattr(item, "name", None) == "read_evidence"]
            return handler(request.override(tools=allowed))

        def wrap_tool_call(self, request, handler):
            if request.tool_call["name"] != "read_evidence":
                violations.append("TOOL_FORBIDDEN")
                return ToolMessage(content="TOOL_FORBIDDEN", tool_call_id=request.tool_call["id"], status="error")
            return handler(request)

    start = perf_counter()
    reason = None
    try:
        with tracing_context(enabled=False):
            agent = create_deep_agent(
                model=model, tools=[read_evidence], backend=StateBackend(), middleware=[EvidenceToolsOnly()]
            )
            state = agent.invoke(
                {"messages": [{"role": "user", "content": QUESTIONS}]}, config={"recursion_limit": 14}
            )
        if violations:
            reason = violations[0]
        elif not validate_draft(state["messages"][-1].content, findings, retrieved):
            reason = "DRAFT_UNVERIFIED"
    except (TypeError, ValueError):
        reason = "DRAFT_UNVERIFIED"
    except TimeoutError:
        reason = "AGENT_TIMEOUT"
    except Exception as exc:
        # Provider messages may include request details. Report only the class.
        reason = type(exc).__name__
    return {
        **report,
        "agent": {
            "status": "VERIFIED" if reason is None else "FALLBACK",
            "reason": reason,
            "retrieved": sorted(retrieved),
            "elapsedMs": round((perf_counter() - start) * 1000, 1),
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("case", choices=(*CASES, "all"))
    parser.add_argument("--openai-model", help="Opt in to sending the constructed cards to OpenAI")
    args = parser.parse_args()
    model = None
    if args.openai_model:
        from langchain_openai import ChatOpenAI

        model = ChatOpenAI(model=args.openai_model, timeout=15, max_retries=0, temperature=0)
    cases = CASES if args.case == "all" else (args.case,)
    print(json.dumps([review(case, model) for case in cases], indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
