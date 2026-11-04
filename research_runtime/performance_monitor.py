"""No-fit, fixed-24-month calibration and predefined subgroup descriptions.

Caller-owned memory only. Reuse the existing endpoint validation and finite
denominator bounds; do not invoke IPCW estimation or calibration regression.
"""
from bisect import bisect_right
from collections import Counter
from dataclasses import dataclass
from itertools import combinations
import hashlib
import json
import math

from .decision_curve import HorizonObservation, CurveProtocol, CurveError, _checked, _fixed_ratio_bounds
from .input_admission import TOKEN, SHA

# Same fixed calibration partition as evaluation.BIN_EDGES, without importing
# its sklearn/calibration estimators. Equality enters the upper bin; 1 is final.
BIN_EDGES = (0., .2, .4, .6, .8, 1.)
MAX_GROUPS = 8
POLICIES = ('raw_uncalibrated_24month_CIF', 'saved_calibrated_24month_CIF')


class PerformanceMonitorError(ValueError):
    """Stable code excluding identifiers, scores and raw exceptions."""


@dataclass(frozen=True)
class ModelBinding:
    version: str
    artifact_sha256: str
    prediction_policy: str


@dataclass(frozen=True)
class SubgroupBinding:
    name: str
    rule_version: str
    rule_sha256: str


@dataclass(frozen=True)
class MonitoringProtocol:
    reference_data_version: str
    reference_manifest_sha256: str
    endpoint_id: str
    cohort_anchor_id: str
    subject_namespace: str
    models: tuple[ModelBinding, ...]
    subgroups: tuple[SubgroupBinding, ...] = ()
    bin_edges: tuple[float, ...] = BIN_EDGES
    horizon_months: int = 24


@dataclass(frozen=True)
class MonitoringBatch:
    data_version: str
    manifest_sha256: str
    endpoint_id: str
    cohort_anchor_id: str
    subject_namespace: str
    models: tuple[ModelBinding, ...]
    subgroups: tuple[SubgroupBinding, ...]
    observations: tuple[HorizonObservation, ...]
    predictions: dict[str, dict[str, float]]
    memberships: dict[str, tuple[str, ...]]


def _require(condition, code):
    if not condition:
        raise PerformanceMonitorError(code) from None


def _token(value):
    return type(value) is str and TOKEN.fullmatch(value) is not None


def _sha(value):
    return type(value) is str and SHA.fullmatch(value) is not None


def _models(models):
    _require(type(models) is tuple and 1 <= len(models) <= 9
             and all(type(m) is ModelBinding and _token(m.version) and _sha(m.artifact_sha256)
                     and type(m.prediction_policy) is str and m.prediction_policy in POLICIES for m in models)
             and len({m.version for m in models}) == len(models), 'MONITOR_MODELS')


def _groups(groups):
    _require(type(groups) is tuple and len(groups) <= MAX_GROUPS
             and all(type(g) is SubgroupBinding and _token(g.name) and g.name != 'overall'
                     and _token(g.rule_version) and _sha(g.rule_sha256) for g in groups)
             and len({g.name for g in groups}) == len(groups), 'MONITOR_GROUP_RULES')


def _protocol(protocol):
    _require(type(protocol) is MonitoringProtocol, 'MONITOR_PROTOCOL')
    _require(all(_token(v) for v in (protocol.reference_data_version, protocol.endpoint_id,
             protocol.cohort_anchor_id, protocol.subject_namespace))
             and _sha(protocol.reference_manifest_sha256), 'MONITOR_PROTOCOL_IDENTITY')
    _require(type(protocol.horizon_months) is int and protocol.horizon_months == 24,
             'MONITOR_HORIZON')
    _require(type(protocol.bin_edges) is tuple and protocol.bin_edges == BIN_EDGES
             and all(type(v) in (int, float) for v in protocol.bin_edges), 'MONITOR_FIXED_BINS')
    _models(protocol.models)
    _groups(protocol.subgroups)


def protocol_digest(protocol):
    """Identity for metadata, predeclared model/rule bindings and fixed bins.

    No participant data are hashed. Freezing this before examining comparison
    data is the caller's obligation, not a claim established by this function.
    """
    _protocol(protocol)
    value = dict(referenceDataVersion=protocol.reference_data_version,
        referenceManifestSha256=protocol.reference_manifest_sha256,
        endpointId=protocol.endpoint_id, cohortAnchorId=protocol.cohort_anchor_id,
        subjectNamespace=protocol.subject_namespace, horizonMonths=24, binEdges=protocol.bin_edges,
        models=[dict(version=m.version, artifactSha256=m.artifact_sha256,
                     predictionPolicy=m.prediction_policy) for m in protocol.models],
        subgroups=[dict(name=g.name, ruleVersion=g.rule_version, ruleSha256=g.rule_sha256)
                   for g in protocol.subgroups])
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')).hexdigest()


def _batch(batch, protocol):
    _require(type(batch) is MonitoringBatch, 'MONITOR_BATCH')
    _require(_token(batch.data_version) and _sha(batch.manifest_sha256), 'MONITOR_BATCH_IDENTITY')
    _require(all(_token(v) for v in (batch.endpoint_id, batch.cohort_anchor_id, batch.subject_namespace)),
             'MONITOR_BATCH_IDENTITY')
    _models(batch.models)
    _groups(batch.subgroups)
    _require((batch.endpoint_id, batch.cohort_anchor_id, batch.subject_namespace) ==
             (protocol.endpoint_id, protocol.cohort_anchor_id, protocol.subject_namespace)
             and type(batch.models) is tuple and batch.models == protocol.models
             and type(batch.subgroups) is tuple and batch.subgroups == protocol.subgroups,
             'MONITOR_CONTEXT_MISMATCH')
    _require(type(batch.observations) is tuple, 'MONITOR_OBSERVATIONS')
    names = tuple(m.version for m in protocol.models)
    _require(type(batch.predictions) is dict and set(batch.predictions) == set(names), 'MONITOR_MODEL_PANELS')
    _require(all(type(panel) is dict for panel in batch.predictions.values()), 'MONITOR_MODEL_PANELS')
    if batch.observations:
        try:
            labels, scores = _checked(CurveProtocol(batch.data_version, batch.endpoint_id, (.5,)),
                                      batch.observations, batch.predictions)
        except CurveError as error:
            raise PerformanceMonitorError('MONITOR_' + str(error)) from None
    else:
        _require(all(type(p) is dict and not p for p in batch.predictions.values()), 'MONITOR_EMPTY_PANEL')
        labels, scores = (), {name: () for name in names}
    ids = tuple(r.subject_id for r in batch.observations)
    _require(type(batch.memberships) is dict and set(batch.memberships) == {g.name for g in protocol.subgroups},
             'MONITOR_SUBGROUP_SET')
    groups = {}
    for name, members in batch.memberships.items():
        _require(type(members) is tuple and all(type(s) is str for s in members)
                 and len(set(members)) == len(members) and set(members) <= set(ids), 'MONITOR_GROUP_MEMBERSHIP')
        groups[name] = frozenset(members)
    # Freeze by identity in protocol model order; dictionary insertion order is irrelevant.
    return ids, labels, {name: scores[name] for name in names}, groups


def _bin(probability):
    return min(bisect_right(BIN_EDGES, probability) - 1, len(BIN_EDGES) - 2)


def _pattern(ids, labels, scores, groups, protocol):
    names = [m.version for m in protocol.models]
    group_names = [g.name for g in protocol.subgroups]
    return {s: (labels[i], tuple(_bin(scores[name][i]) for name in names),
                tuple(s in groups[name] for name in group_names)) for i, s in enumerate(ids)}


def _absolute_count_gap(known_ad, unknown, probability_sum):
    """Attainable integer event-count extrema, not a continuous relaxation."""
    low, high = known_ad, known_ad + unknown
    candidates = [max(low, min(high, k)) for k in (math.floor(probability_sum), math.ceil(probability_sum))]
    return min(abs(k - probability_sum) for k in candidates), max(abs(low - probability_sum), abs(high - probability_sum))


def _summary(labels, probabilities):
    n = len(labels)
    known_ad = labels.count(1)
    known_non_ad = labels.count(0)
    unknown = labels.count(None)
    known_n = known_ad + known_non_ad
    known_errors = math.fsum((p - y) ** 2 for y, p in zip(labels, probabilities) if y is not None)
    unknown_errors = [(p * p, (1 - p) ** 2) for y, p in zip(labels, probabilities) if y is None]
    brier_lower = known_errors + math.fsum(min(a, b) for a, b in unknown_errors)
    brier_upper = known_errors + math.fsum(max(a, b) for a, b in unknown_errors)
    bins = []
    absolute_lowers, absolute_uppers = [], []
    for j, (left, right) in enumerate(zip(BIN_EDGES[:-1], BIN_EDGES[1:])):
        positions = [i for i, p in enumerate(probabilities) if _bin(p) == j]
        ys = tuple(labels[i] for i in positions)
        ps = tuple(probabilities[i] for i in positions)
        count = len(positions)
        ad, non_ad, missing = ys.count(1), ys.count(0), ys.count(None)
        total = math.fsum(ps)
        risk = _fixed_ratio_bounds(ad, ad + missing, count)
        lo, hi = _absolute_count_gap(ad, missing, total)
        absolute_lowers.append(lo)
        absolute_uppers.append(hi)
        mean = total / count if count else None
        bins.append(dict(lower=left, upper=right, subjects=count, knownAD=ad, knownNonAD=non_ad,
            unknown=missing, status='EMPTY' if not count else 'COMPLETION_BOUNDS' if missing else 'DESCRIPTIVE_KNOWN',
            meanPrediction=mean, knownSubsetADRate=ad / (ad + non_ad) if ad + non_ad else None,
            fullGroupADRateBounds=risk,
            signedGapBounds=dict(lower=risk['lower'] - mean, upper=risk['upper'] - mean) if count else None,
            absoluteGapBounds=dict(lower=lo / count, upper=hi / count) if count else None))
    class_status = ('NO_KNOWN_OUTCOMES' if not known_n else 'SINGLE_KNOWN_CLASS'
                    if not known_ad or not known_non_ad else 'TWO_KNOWN_CLASSES')
    return dict(subjects=n, knownAD=known_ad, knownNonAD=known_non_ad, unknown=unknown,
        knownOutcomeClassStatus=class_status, status='EMPTY' if not n else 'COMPLETION_BOUNDS' if unknown else 'DESCRIPTIVE_KNOWN',
        meanPrediction=math.fsum(probabilities) / n if n else None,
        knownSubsetBrier=known_errors / known_n if known_n else None,
        brierBounds=dict(lower=brier_lower / n, upper=brier_upper / n) if n else None,
        eceBounds=dict(lower=math.fsum(absolute_lowers) / n, upper=math.fsum(absolute_uppers) / n) if n else None,
        fullGroupADRateBounds=_fixed_ratio_bounds(known_ad, known_ad + unknown, n),
        calibrationBins=bins, discriminationEvaluated=False, calibrationRegressionFitted=False)


def _brier_difference(labels, left, right):
    if not labels:
        return None
    lower, upper = [], []
    for y, a, b in zip(labels, left, right):
        if y is None:
            at_zero, at_one = b*b - a*a, (1-b)**2 - (1-a)**2
            lower.append(min(at_zero, at_one))
            upper.append(max(at_zero, at_one))
        else:
            value = (b-y)**2 - (a-y)**2
            lower.append(value)
            upper.append(value)
    return dict(lower=math.fsum(lower) / len(labels), upper=math.fsum(upper) / len(labels))


def evaluate_performance_monitor(reference, current, protocol, *, expected_protocol_sha256):
    """Joint privacy first, then finite-sample calibration/Brier descriptions.

    A shared ID denotes the same anchored event history. Revised history or a
    new landmark cannot silently reuse it; different longitudinal targets need
    their own declared protocol. No cross-batch difference is reported here.
    """
    _protocol(protocol)
    _require(_sha(expected_protocol_sha256) and protocol_digest(protocol) == expected_protocol_sha256,
             'MONITOR_PROTOCOL_DIGEST')
    r = _batch(reference, protocol)
    c = _batch(current, protocol)
    _require(reference.data_version == protocol.reference_data_version
             and reference.manifest_sha256 == protocol.reference_manifest_sha256, 'MONITOR_REFERENCE_BINDING')
    r_history = {o.subject_id: (o.event_type, o.observed_month) for o in reference.observations}
    c_history = {o.subject_id: (o.event_type, o.observed_month) for o in current.observations}
    _require(all(r_history[s] == c_history[s] for s in r_history.keys() & c_history.keys()),
             'MONITOR_OVERLAPPING_HISTORY_CHANGED')
    rp, cp = _pattern(*r, protocol), _pattern(*c, protocol)
    joint = Counter((rp.get(s, 'ABSENT'), cp.get(s, 'ABSENT')) for s in rp.keys() | cp.keys())
    suppressed = any(count < 5 for count in joint.values())
    report = dict(schemaVersion='fixed24-performance-monitor-v1', status='SUPPRESSED' if suppressed else 'COMPLETE',
        privacyStatus='SUPPRESSED' if suppressed else 'JOINT_BATCH_MODEL_BIN_OUTCOME_SUBGROUP_CELLS_AT_LEAST_FIVE',
        protocolSha256=expected_protocol_sha256,
        context=dict(referenceDataVersion=reference.data_version, currentDataVersion=current.data_version,
            referenceManifestSha256=reference.manifest_sha256, currentManifestSha256=current.manifest_sha256,
            endpointId=protocol.endpoint_id, cohortAnchorId=protocol.cohort_anchor_id, horizonMonths=24,
            models=[dict(version=m.version, artifactSha256=m.artifact_sha256, predictionPolicy=m.prediction_policy) for m in protocol.models],
            subgroups=[dict(name=g.name, ruleVersion=g.rule_version, ruleSha256=g.rule_sha256) for g in protocol.subgroups],
            calibrationBinEdges=list(BIN_EDGES), calibrationIntervals='LEFT_CLOSED_RIGHT_OPEN_EXCEPT_FINAL_INCLUDES_ONE',
            endpointRule='CAUSE1_AD_FIRST_EVENT;CAUSE2_OTHER_DEMENTIA_KNOWN_NON_AD',
            censoringRule='CAUSE0_BEFORE_MONTH24_UNKNOWN;CAUSE0_MONTH24_KNOWN_NON_AD'),
        batchResults=None, operations=dict(numericSummaries=0, pairedBrierComparisons=0),
        boundsMeaning='ATTAINABLE_FINITE_SAMPLE_COMPLETION_EXTREMA_NOT_JOINT_ACROSS_METRICS_OR_PANELS',
        knownSubsetMeaning='RECORDED_KNOWN_ONLY_NOT_ESTIMATED_FULL_POPULATION_PERFORMANCE',
        crossBatchDifferencesComputed=False, populationConfidenceInterval=False, independentCensoringEstablished=False,
        IPCWEstimated=False, KMEstimated=False, discriminationEvaluated=False, calibrationRegressionFitted=False,
        subgroupMembershipIndependentlyVerified=False, outOfFoldPredictionsVerified=False,
        provenanceVerified=False, historicalAvailabilityCertified=False, clinicalUse=False, causalEffectIdentified=False,
        automaticAlert=False, automaticModelUpdate=False, participantOutputsSaved=False, privacyFormalDPCertified=False,
        modelLoads=0, CIFCalls=0, fits=0)
    if suppressed:
        return report
    results = []
    any_empty = False
    for batch, prepared in ((reference, r), (current, c)):
        ids, labels, scores, groups = prepared
        panels = []
        definitions = [('overall', frozenset(ids))] + [(g.name, groups[g.name]) for g in protocol.subgroups]
        for name, members in definitions:
            positions = [i for i, s in enumerate(ids) if s in members]
            ys = tuple(labels[i] for i in positions)
            ps = {m.version: tuple(scores[m.version][i] for i in positions) for m in protocol.models}
            metrics = {}
            for version, probabilities in ps.items():
                report['operations']['numericSummaries'] += 1
                metrics[version] = _summary(ys, probabilities)
            pairs = []
            for left, right in combinations(ps, 2):
                report['operations']['pairedBrierComparisons'] += 1
                pairs.append(dict(leftModelVersion=left, rightModelVersion=right, difference='RIGHT_MINUS_LEFT',
                                  bounds=_brier_difference(ys, ps[left], ps[right])))
            panels.append(dict(group=name, models=metrics, pairedBrierDifferences=pairs))
            any_empty = any_empty or not positions
        results.append(dict(dataVersion=batch.data_version, panels=panels))
    report['batchResults'] = results
    if any_empty:
        report['status'] = 'COMPLETE_WITH_EMPTY_GROUPS'
    return report
