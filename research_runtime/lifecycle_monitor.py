"""Same-snapshot composition: joint disclosure gate BEFORE either calculator.

Accepts caller memory only. No data/model loading, fitting or deployment logic.
The declared reference plan and participant namespace are not authenticated here.
"""
from collections import Counter
from dataclasses import dataclass, replace
import hashlib
import json

from . import feature_drift as drift
from . import performance_monitor as performance


class LifecycleMonitorError(ValueError):
    """Redacted integration error without participant data."""


@dataclass(frozen=True)
class LifecycleProtocol:
    binning: drift.ReferenceBinning
    performance: performance.MonitoringProtocol
    primary_model_version: str


def _require(condition, code):
    if not condition:
        raise LifecycleMonitorError(code) from None


def protocol_digest(protocol):
    """Bind both predefined plans and the feature snapshot's primary model."""
    _require(type(protocol) is LifecycleProtocol, 'LIFECYCLE_PROTOCOL')
    binning_hash = drift.binning_digest(protocol.binning)
    performance_hash = performance.protocol_digest(protocol.performance)
    b, p = protocol.binning, protocol.performance
    _require(type(protocol.primary_model_version) is str
             and protocol.primary_model_version == b.model_version
             and protocol.primary_model_version in {m.version for m in p.models}, 'LIFECYCLE_PRIMARY_MODEL')
    _require((b.reference_data_version, b.reference_manifest_sha256, b.subject_namespace) ==
             (p.reference_data_version, p.reference_manifest_sha256, p.subject_namespace), 'LIFECYCLE_REFERENCE_PLAN')
    value = dict(schemaVersion='same-snapshot-lifecycle-v1', binningSha256=binning_hash,
                 performanceProtocolSha256=performance_hash, primaryModelVersion=protocol.primary_model_version,
                 minimumNonemptyJointCell=5)
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def _freeze(features, batch, protocol):
    ids, values = drift._snapshot(features)
    prepared = performance._batch(batch, protocol.performance)
    pids, labels, scores, groups = prepared
    _require((features.data_version, features.manifest_sha256, features.subject_namespace) ==
             (batch.data_version, batch.manifest_sha256, batch.subject_namespace), 'LIFECYCLE_SNAPSHOT_BINDING')
    _require(set(ids) == set(pids), 'LIFECYCLE_PARTICIPANT_SET')
    b = protocol.binning
    _require((features.model_version, features.feature_schema_version, features.subject_namespace) ==
             (b.model_version, b.feature_schema_version, b.subject_namespace), 'LIFECYCLE_FEATURE_CONTEXT')
    # Freeze everything reused by the calculators. Canonical ID order also makes
    # floating-point reductions invariant to caller row/dictionary order.
    ordered = tuple(sorted(ids))
    positions = {s: i for i, s in enumerate(ids)}
    frozen_values = values[[positions[s] for s in ordered], :].copy()
    frozen_values.setflags(write=False)
    frozen_features = replace(features, subject_ids=ordered, values=frozen_values)
    ppositions = {s: i for i, s in enumerate(pids)}
    histories = {o.subject_id: o for o in batch.observations}
    frozen_batch = replace(batch, observations=tuple(histories[s] for s in ordered),
        predictions={name: {s: panel[ppositions[s]] for s in ordered} for name, panel in scores.items()},
        memberships={name: tuple(s for s in ordered if s in members) for name, members in groups.items()})
    frozen_prepared = (ordered, tuple(labels[ppositions[s]] for s in ordered),
        {name: tuple(panel[ppositions[s]] for s in ordered) for name, panel in scores.items()}, groups)
    return frozen_features, frozen_batch, frozen_prepared


def evaluate_lifecycle_monitor(reference_features, current_features, reference_batch, current_batch,
                               protocol, *, expected_protocol_sha256):
    """One aggregate report, with unified suppression and no participant output.

    Both feature and outcome/probability views must describe the same manifest
    and ID set within each batch. ID order may differ. Overlapping IDs must have
    identical anchored event histories. The gate joins BOTH batches' membership,
    ten missing/bin states, outcome, every model bin and every subgroup flag.
    """
    digest = protocol_digest(protocol)
    _require(type(expected_protocol_sha256) is str and digest == expected_protocol_sha256,
             'LIFECYCLE_PROTOCOL_DIGEST')
    rf, rb, rp = _freeze(reference_features, reference_batch, protocol)
    cf, cb, cp = _freeze(current_features, current_batch, protocol)
    b, p = protocol.binning, protocol.performance
    _require((rf.data_version, rf.manifest_sha256) ==
             (b.reference_data_version, b.reference_manifest_sha256), 'LIFECYCLE_REFERENCE_BINDING')
    rh = {o.subject_id: (o.event_type, o.observed_month) for o in rb.observations}
    ch = {o.subject_id: (o.event_type, o.observed_month) for o in cb.observations}
    _require(all(rh[s] == ch[s] for s in rh.keys() & ch.keys()), 'LIFECYCLE_OVERLAPPING_HISTORY_CHANGED')
    cuts = drift._cuts(b)
    rstates, _ = drift._states(rf.subject_ids, rf.values, cuts)
    cstates, _ = drift._states(cf.subject_ids, cf.values, cuts)
    rpatterns, cpatterns = performance._pattern(*rp, p), performance._pattern(*cp, p)
    rjoint = {s: (rstates[s], rpatterns[s]) for s in rstates}
    cjoint = {s: (cstates[s], cpatterns[s]) for s in cstates}
    cells = Counter((rjoint.get(s, 'ABSENT'), cjoint.get(s, 'ABSENT')) for s in rjoint.keys() | cjoint.keys())
    suppressed = any(n < 5 for n in cells.values())
    report = dict(schemaVersion='same-snapshot-lifecycle-v1', status='SUPPRESSED' if suppressed else 'COMPLETE',
        protocolSha256=digest, privacyStatus='SUPPRESSED' if suppressed else 'UNIFIED_NONEMPTY_JOINT_CELLS_AT_LEAST_FIVE',
        context=dict(referenceDataVersion=rf.data_version, referenceManifestSha256=rf.manifest_sha256,
            currentDataVersion=cf.data_version, currentManifestSha256=cf.manifest_sha256,
            featureSchemaVersion=b.feature_schema_version, primaryModelVersion=protocol.primary_model_version,
            binningSha256=drift.binning_digest(b), performanceProtocolSha256=performance.protocol_digest(p),
            endpointId=p.endpoint_id, cohortAnchorId=p.cohort_anchor_id,
            models=[dict(version=m.version, artifactSha256=m.artifact_sha256, predictionPolicy=m.prediction_policy) for m in p.models],
            subgroups=[dict(name=g.name, ruleVersion=g.rule_version, ruleSha256=g.rule_sha256) for g in p.subgroups]),
        featureReport=None, performanceReport=None,
        operations=dict(featureCalculatorCalls=0, featureCalculatorReturns=0,
                        performanceCalculatorCalls=0, performanceCalculatorReturns=0),
        provenanceVerified=False, historicalAvailabilityCertified=False, actualDeploymentVerified=False,
        outOfFoldPredictionsVerified=False, clinicalUse=False, causalEffectIdentified=False,
        automaticAlert=False, automaticModelUpdate=False, privacyFormalDPCertified=False,
        adaptivePublicationSafe=False, participantOutputsSaved=False, modelLoads=0, CIFCalls=0, fits=0,
        KMEstimated=False, IPCWEstimated=False,
        interpretation='DESCRIPTIVE_FIXED_REFERENCE;COMPLETION_BOUNDS_NOT_CONFIDENCE_INTERVALS;NO_CROSS_BATCH_PERFORMANCE_EFFECT')
    if not suppressed:
        # The marginal calculators repeat their own validation/gates, then reuse
        # the existing statistics. A component failure never yields a partial report.
        report['operations']['featureCalculatorCalls'] += 1
        fr = drift.evaluate_feature_drift(rf, cf, b, expected_binning_sha256=drift.binning_digest(b))
        report['operations']['featureCalculatorReturns'] += 1
        report['operations']['performanceCalculatorCalls'] += 1
        pr = performance.evaluate_performance_monitor(rb, cb, p, expected_protocol_sha256=performance.protocol_digest(p))
        report['operations']['performanceCalculatorReturns'] += 1
        _require(fr['status'] in ('COMPLETE', 'COMPLETE_WITH_NOT_ESTIMABLE_FEATURES')
                 and pr['status'] in ('COMPLETE', 'COMPLETE_WITH_EMPTY_GROUPS'), 'LIFECYCLE_COMPONENT_STATUS')
        report['featureReport'], report['performanceReport'] = fr, pr
        if fr['status'] != 'COMPLETE' or pr['status'] != 'COMPLETE':
            report['status'] = 'COMPLETE_WITH_LIMITATIONS'
    # Refuse nonfinite values anywhere, including reused component output.
    try:
        json.dumps(report, allow_nan=False, sort_keys=True)
    except (ValueError, TypeError, OverflowError):
        raise LifecycleMonitorError('LIFECYCLE_NONFINITE_OR_NON_JSON_REPORT') from None
    return report
