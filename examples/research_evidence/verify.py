"""Run the SDK offline and verify evidence checks and deterministic fallback."""

import copy
import json
from unittest.mock import patch

from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel
from langchain_core.messages import AIMessage

from .review import CASES, baseline, load_case, review


EXPECTED = {
    "seven": ("SUPPORTED", "COMPLETED", "SUPPORTED", "UNVERIFIED", "CONSTRUCTED_ONLY"),
    "ten": ("SUPPORTED", "COMPLETED", "SUPPORTED", "UNVERIFIED", "CONSTRUCTED_ONLY"),
    "blocked": ("SUPPORTED", "BLOCKED", "UNAVAILABLE", "UNVERIFIED", "CONSTRUCTED_ONLY"),
    "missing-availability": ("SUPPORTED", "COMPLETED", "SUPPORTED", "MISSING_EVIDENCE", "CONSTRUCTED_ONLY"),
}


class OfflineModel(FakeMessagesListChatModel):
    def bind_tools(self, tools, **kwargs):
        if {item.name for item in tools} != {"read_evidence"}:
            raise AssertionError("The agent exposed a tool outside the evidence allowlist")
        return self


class TimeoutModel(OfflineModel):
    def _generate(self, *args, **kwargs):
        raise TimeoutError("Synthetic provider timeout")


def scripted(findings, evidence_ids, extra=()):
    calls = [("read_evidence", {"evidence_id": name}) for name in evidence_ids]
    messages = [
        AIMessage(content="", tool_calls=[{"name": name, "args": args, "id": f"call-{i}"}])
        for i, (name, args) in enumerate([*calls, *extra])
    ]
    return OfflineModel(responses=[*messages, AIMessage(content=json.dumps({"findings": findings}))])


def main():
    with patch("socket.socket.connect", side_effect=AssertionError("Network call during offline check")):
        for case in CASES:
            cards = load_case(case)
            evidence_ids = list(cards)
            if case == "missing-availability":
                evidence_ids.append("availability")
            result = review(case, scripted(baseline(cards), evidence_ids))
            assert result["agent"]["status"] == "VERIFIED", result
            assert result["findings"] == review(case)["findings"], result
            assert tuple(row["status"] for row in result["findings"]) == EXPECTED[case], result
            print(f"PASS {case}")

        cards = load_case("seven")
        expected = baseline(cards)
        false_status = copy.deepcopy(expected)
        false_status[2]["status"] = "UNAVAILABLE"
        fake_citation = copy.deepcopy(expected)
        fake_citation[0]["evidence_ids"].append("invented")
        cases = (
            ("wrong finding", scripted(false_status, cards), "DRAFT_UNVERIFIED"),
            ("invented citation", scripted(fake_citation, cards), "DRAFT_UNVERIFIED"),
            ("unread citation", scripted(expected, ["result"]), "DRAFT_UNVERIFIED"),
            ("forbidden tool", scripted(expected, cards, [("read_file", {"file_path": "/none"})]),
             "TOOL_FORBIDDEN"),
            ("unknown source", scripted(expected, cards, [("read_evidence", {"evidence_id": "unknown"})]),
             "UNKNOWN_EVIDENCE_ID"),
            ("provider timeout", TimeoutModel(responses=[]), "AGENT_TIMEOUT"),
        )
        for label, model, reason in cases:
            result = review("seven", model)
            assert result["agent"]["status"] == "FALLBACK" and result["agent"]["reason"] == reason, result
            assert result["findings"] == expected, result
            print(f"PASS {label}")


if __name__ == "__main__":
    main()
