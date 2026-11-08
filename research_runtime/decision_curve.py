"""Fixed-horizon net-benefit bounds with unknown outcomes left unknown.

Pure arithmetic on caller-supplied observations and predictions. No censoring
model, source reader, prediction, threshold selection or clinical authorization.
Bounds describe possible completions of this sample, not sampling uncertainty.
"""
from dataclasses import dataclass
from itertools import combinations
import math


VERSION = 'fixed-horizon-decision-curve-bounds-v2'
MAX_SUBJECTS = 10000
MAX_THRESHOLDS = 64
MAX_MODELS = 9


class CurveError(ValueError):
    """Stable errors deliberately exclude input values and identifiers."""


@dataclass(frozen=True)
class HorizonObservation:
    subject_id: str
    event_type: int
    observed_month: int


@dataclass(frozen=True)
class CurveProtocol:
    data_version: str
    endpoint_id: str
    thresholds: tuple[float, ...]
    horizon_months: int = 24


def _require(condition, code):
    if not condition:
        raise CurveError(code)


def _name(value):
    return type(value) is str and bool(value.strip()) and len(value) <= 128


def _probability(value):
    if type(value) not in (int, float):
        return False
    try:
        return math.isfinite(value) and 0 <= value <= 1
    except OverflowError:
        return False


def _checked(protocol, observations, panels):
    _require(type(protocol) is CurveProtocol, 'PROTOCOL')
    _require(_name(protocol.data_version) and _name(protocol.endpoint_id), 'VERSIONS')
    _require(type(protocol.horizon_months) is int and protocol.horizon_months == 24, 'HORIZON')
    thresholds = protocol.thresholds
    _require(type(thresholds) is tuple and 1 <= len(thresholds) <= MAX_THRESHOLDS, 'THRESHOLDS')
    _require(all(_probability(t) and 0 < t < 1 for t in thresholds), 'THRESHOLDS')
    _require(all(a < b for a, b in zip(thresholds, thresholds[1:])), 'THRESHOLD_ORDER')
    _require(type(observations) is tuple and 1 <= len(observations) <= MAX_SUBJECTS, 'OBSERVATIONS')
    labels = {}
    for row in observations:
        _require(type(row) is HorizonObservation and _name(row.subject_id), 'OBSERVATION')
        _require(row.subject_id not in labels, 'DUPLICATE_SUBJECT')
        _require(type(row.event_type) is int and row.event_type in (0, 1, 2), 'EVENT_TYPE')
        _require(type(row.observed_month) is int and 0 <= row.observed_month <= 24, 'OBSERVED_MONTH')
        # Other dementia is a competing FIRST event, not death or unknown AD.
        labels[row.subject_id] = (int(row.event_type == 1)
                                  if row.event_type or row.observed_month == 24 else None)
    _require(type(panels) is dict and 1 <= len(panels) <= MAX_MODELS, 'PANELS')
    checked = {}
    for model, panel in panels.items():
        _require(_name(model), 'MODEL_VERSION')
        _require(type(panel) is dict and panel.keys() == labels.keys(), 'PAIRED_SUBJECT_SET')
        _require(all(_probability(p) for p in panel.values()), 'PROBABILITY')
        # Join by identity and copy; panel insertion order never defines pairing.
        checked[model] = tuple(float(panel[key]) for key in labels)
    return tuple(labels.values()), checked


def _completion_bounds(decision_difference, labels, odds):
    lower, upper = [], []
    for delta, outcome in zip(decision_difference, labels):
        if outcome is None:
            if_zero, if_one = -delta * odds, float(delta)
            lower.append(min(if_zero, if_one))
            upper.append(max(if_zero, if_one))
        else:
            value = delta * (outcome - (1 - outcome) * odds)
            lower.append(value)
            upper.append(value)
    size = len(labels)
    return {'lower': math.fsum(lower) / size, 'upper': math.fsum(upper) / size}


def _ratio_bounds(numerator, other, unknown_numerator, unknown_other):
    """Sharp a/(a+b) range over completions with a+b > 0.

    An unknown can add to a or b, or to neither. A zero denominator in an
    extremal assignment must not erase values attained by other assignments.
    """
    known = numerator + other
    minimum_denominator = known + unknown_other
    maximum_denominator = known + unknown_numerator
    lower = (numerator / minimum_denominator if minimum_denominator
             else 1. if unknown_numerator else None)
    upper = ((numerator + unknown_numerator) / maximum_denominator if maximum_denominator
             else 0. if unknown_other else None)
    return {'lower': lower, 'upper': upper, 'denominatorMayBeZero': known == 0,
            'denominatorAlwaysZero': known + unknown_numerator + unknown_other == 0}


def _fixed_ratio_bounds(lower, upper, denominator):
    return {'lower': lower / denominator if denominator else None,
            'upper': upper / denominator if denominator else None,
            'denominatorMayBeZero': denominator == 0, 'denominatorAlwaysZero': denominator == 0}


def _classification(selected, labels):
    tp = sum(d == 1 and y == 1 for d, y in zip(selected, labels))
    fp = sum(d == 1 and y == 0 for d, y in zip(selected, labels))
    tn = sum(d == 0 and y == 0 for d, y in zip(selected, labels))
    fn = sum(d == 0 and y == 1 for d, y in zip(selected, labels))
    unknown_selected = sum(d == 1 and y is None for d, y in zip(selected, labels))
    unknown_unselected = sum(d == 0 and y is None for d, y in zip(selected, labels))
    return {
        'known': {'tp': tp, 'fp': fp, 'tn': tn, 'fn': fn},
        'unknownSelected': unknown_selected, 'unknownUnselected': unknown_unselected,
        'countBounds': {name: {'lower': value, 'upper': value + extra} for name, value, extra in (
            ('tp', tp, unknown_selected), ('fp', fp, unknown_selected),
            ('tn', tn, unknown_unselected), ('fn', fn, unknown_unselected))},
        'sensitivity': _ratio_bounds(tp, fn, unknown_selected, unknown_unselected),
        'specificity': _ratio_bounds(tn, fp, unknown_unselected, unknown_selected),
        'positivePredictiveValue': _fixed_ratio_bounds(tp, tp + unknown_selected, sum(selected)),
        'negativePredictiveValue': _fixed_ratio_bounds(tn, tn + unknown_unselected, len(labels) - sum(selected)),
        'accuracy': _fixed_ratio_bounds(tp + tn, tp + tn + unknown_selected + unknown_unselected, len(labels)),
        'rangeMeaning': 'EACH_METRIC_OVER_COMPLETIONS_WITH_NONZERO_DENOMINATOR',
        'jointRangePolicy': 'MARGINAL_PER_METRIC_NO_JOINT_COMPLETION_CLAIM',
    }


def evaluate_decision_curves(protocol, observations, panels):
    """Bound all models and paired differences at predeclared thresholds.

    panels maps model version -> {subject_id: AD FIRST-event CIF at 24 months}.
    Unknown subjects have one shared binary completion across ALL strategies.
    No independence of censoring is assumed. Membership/OOF, exact dates,
    probability calibration and endpoint declarations remain caller obligations.
    """
    labels, scores = _checked(protocol, observations, panels)
    size = len(labels)
    curves = {model: [] for model in scores}
    comparisons = [{'leftModelVersion': left, 'rightModelVersion': right,
                    'difference': 'RIGHT_MINUS_LEFT', 'curves': []}
                   for left, right in combinations(scores, 2)]
    references = []
    for threshold in protocol.thresholds:
        odds = threshold / (1 - threshold)
        decisions = {model: tuple(int(p >= threshold) for p in values)
                     for model, values in scores.items()}
        references.append({'threshold': threshold,
                           'selectAllBounds': _completion_bounds((1,) * size, labels, odds),
                           'selectNoneBounds': {'lower': 0., 'upper': 0.}})
        for model, selected in decisions.items():
            curves[model].append({
                'threshold': threshold, 'falsePositiveWeight': odds,
                'selectedSubjects': sum(selected),
                'selectedUnknownOutcomes': sum(d for d, y in zip(selected, labels) if y is None),
                'classification': _classification(selected, labels),
                'netBenefitBounds': _completion_bounds(selected, labels, odds),
                'minusSelectNoneBounds': _completion_bounds(selected, labels, odds),
                # Paired subtraction, NOT subtraction of unrelated endpoints.
                'minusSelectAllBounds': _completion_bounds(tuple(d - 1 for d in selected), labels, odds),
            })
        for comparison in comparisons:
            left = decisions[comparison['leftModelVersion']]
            right = decisions[comparison['rightModelVersion']]
            comparison['curves'].append({'threshold': threshold, 'netBenefitDifferenceBounds':
                _completion_bounds(tuple(b - a for a, b in zip(left, right)), labels, odds)})
    return {
        'evaluationVersion': VERSION,
        'context': {'dataVersion': protocol.data_version, 'endpointId': protocol.endpoint_id,
                    'horizonMonths': 24, 'modelVersions': list(scores),
                    'thresholds': list(protocol.thresholds),
                    'classificationRule': 'PROBABILITY_GREATER_THAN_OR_EQUAL_TO_THRESHOLD',
                    'endpointRule': 'AD_FIRST_EVENT_CAUSE1;CAUSE2_OTHER_DEMENTIA_IS_NON_AD',
                    'censoringRule': 'CAUSE0_BEFORE_MONTH24_UNKNOWN;MONTH24_KNOWN_NON_AD'},
        'subjects': size, 'knownOutcomes': sum(y is not None for y in labels),
        'unknownOutcomes': sum(y is None for y in labels),
        'knownADEvents': labels.count(1), 'knownNonADOutcomes': labels.count(0),
        'curves': curves, 'references': references, 'pairedComparisons': comparisons,
        'boundsMeaning': 'POINTWISE_FINITE_SAMPLE_SHARED_OUTCOME_COMPLETION_RANGE',
        'samplingConfidenceInterval': False, 'independentCensoringEstablished': False,
        'sourceAuthenticityVerified': False, 'outOfFoldPredictionsVerified': False,
        'clinicalUtilityEstablished': False, 'clinicalThresholdRecommendation': None,
        'causalEffectIdentified': False, 'modelCalls': 0, 'fits': 0, 'persisted': False,
    }
