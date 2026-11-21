"""Single-cohort, no-fit validation of saved 24-month probabilities."""

from collections import Counter
import json
import math
import sys

from .performance_monitor import _bin, _summary

MAX_CASES = 2000
MIN_CELL = 5


def evaluate(rows):
    if type(rows) is not list or len(rows) > MAX_CASES:
        raise ValueError("INVALID_VALIDATION_BATCH")
    labels, probabilities = [], []
    counts = Counter()
    for row in rows:
        if type(row) is not dict or set(row) != {"outcome", "probability"}:
            raise ValueError("INVALID_VALIDATION_ROW")
        outcome, score = row["outcome"], row["probability"]
        if (outcome not in ("AD", "OTHER_DEMENTIA", "NO_EVENT", "UNKNOWN")
                or type(score) not in (float, int) or not math.isfinite(score) or not 0 <= score <= 1):
            raise ValueError("INVALID_VALIDATION_ROW")
        label = None if outcome == "UNKNOWN" else int(outcome == "AD")
        labels.append(label)
        probabilities.append(score)
        counts[label, _bin(score)] += 1
    if not rows or any(count < MIN_CELL for count in counts.values()):
        return {"status": "SUPPRESSED", "reason": "SMALL_OUTCOME_RISK_CELL", "metrics": None}
    summary = _summary(labels, probabilities)
    auc = None
    if summary["knownOutcomeClassStatus"] == "TWO_KNOWN_CLASSES":
        # Mann–Whitney statistic with half credit for tied predictions.
        positive = [score for label, score in zip(labels, probabilities) if label == 1]
        negative = [score for label, score in zip(labels, probabilities) if label == 0]
        auc = sum((a > b) + 0.5 * (a == b) for a in positive for b in negative) / (len(positive) * len(negative))
    return {"status": "COMPLETED", "reason": None, "metrics": {
        "knownSubsetBrier": summary["knownSubsetBrier"],
        "knownSubsetAuc": auc,
        "meanPrediction": summary["meanPrediction"],
        "brierBounds": summary["brierBounds"],
        "observedRateBounds": summary["fullGroupADRateBounds"],
        "eceBounds": summary["eceBounds"],
        "knownCases": summary["knownAD"] + summary["knownNonAD"],
        "unknownCases": summary["unknown"],
    }}


def main():
    raw = sys.stdin.buffer.read(262145)
    if len(raw) > 262144:
        raise ValueError("VALIDATION_INPUT_TOO_LARGE")
    result = evaluate(json.loads(raw))
    print(json.dumps(result, allow_nan=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
