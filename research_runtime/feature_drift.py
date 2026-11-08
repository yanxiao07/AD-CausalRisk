"""Fixed-reference, in-memory ten-feature drift and missingness summaries.

Reuse the stage15 PSI formula, without its file pipeline or calibration fitting.
Metadata agreement does not authenticate input provenance or authorize its use.
"""
from collections import Counter
from dataclasses import dataclass
import hashlib
import json
import math

import numpy as np

from .input_admission import FEATURES, UNITS as _UNIT_MAP, SHA, TOKEN

UNITS = tuple(_UNIT_MAP[name] for name in FEATURES)
PSI_FLOOR = 1e-6
MINIMUM_CELL = 5
MAXIMUM_ROWS = 100000


class FeatureDriftError(ValueError):
    """Stable, redacted code without participant values or identifiers."""


@dataclass(frozen=True)
class FeatureSnapshot:
    data_version: str
    manifest_sha256: str
    model_version: str
    feature_schema_version: str
    subject_namespace: str
    subject_ids: tuple[str, ...]
    values: np.ndarray
    features: tuple[str, ...] = FEATURES
    units: tuple[str, ...] = UNITS


@dataclass(frozen=True)
class ReferenceBinning:
    reference_data_version: str
    reference_manifest_sha256: str
    model_version: str
    feature_schema_version: str
    subject_namespace: str
    cut_points: tuple[tuple[float, ...], ...]
    features: tuple[str, ...] = FEATURES
    units: tuple[str, ...] = UNITS


def _require(condition, code):
    if not condition:
        raise FeatureDriftError(code) from None


def _metadata(value, reference=False):
    version = value.reference_data_version if reference else value.data_version
    manifest = value.reference_manifest_sha256 if reference else value.manifest_sha256
    _require(all(type(s) is str and TOKEN.fullmatch(s) is not None for s in
                 (version, value.model_version, value.feature_schema_version, value.subject_namespace)),
             'DRIFT_METADATA_TOKEN')
    _require(type(manifest) is str and SHA.fullmatch(manifest) is not None, 'DRIFT_MANIFEST_DIGEST')
    _require(type(value.features) is tuple and value.features == FEATURES, 'DRIFT_FEATURE_ORDER')
    _require(type(value.units) is tuple and value.units == UNITS, 'DRIFT_UNITS')


def _cuts(binning):
    _require(type(binning) is ReferenceBinning, 'DRIFT_REFERENCE_BINNING_REQUIRED')
    _metadata(binning, reference=True)
    _require(type(binning.cut_points) is tuple and len(binning.cut_points) == 10, 'DRIFT_TEN_BIN_DEFINITIONS')
    cuts = []
    for row in binning.cut_points:
        _require(type(row) is tuple and 1 <= len(row) <= 19
                 and all(type(v) in (int, float) for v in row), 'DRIFT_FIXED_CUT_POINTS')
        try:
            values = tuple(float(v) for v in row)
        except (ValueError, TypeError, OverflowError):
            raise FeatureDriftError('DRIFT_FIXED_CUT_POINTS') from None
        _require(all(math.isfinite(v) for v in values)
                 and all(a < b for a, b in zip(values, values[1:])), 'DRIFT_FIXED_CUT_POINTS')
        cuts.append(values)
    return tuple(cuts)


def binning_digest(binning):
    """Identity for an already-fixed reference plan; never derives cut points.

    This hashes binning metadata, not participants, rows or snapshot values.
    The owner freezes this identity before examining a comparison batch.
    """
    cuts = _cuts(binning)
    value = dict(referenceDataVersion=binning.reference_data_version,
                 referenceManifestSha256=binning.reference_manifest_sha256,
                 modelVersion=binning.model_version, featureSchemaVersion=binning.feature_schema_version,
                 subjectNamespace=binning.subject_namespace, features=binning.features,
                 units=binning.units, cutPoints=cuts,
                 intervals='RIGHT_CLOSED_IMPLICIT_INFINITE_TAILS', psiFloor=PSI_FLOOR)
    raw = json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')
    return hashlib.sha256(raw).hexdigest()


def _snapshot(snapshot):
    _require(type(snapshot) is FeatureSnapshot, 'DRIFT_SNAPSHOT_REQUIRED')
    _metadata(snapshot)
    ids = snapshot.subject_ids
    _require(type(ids) is tuple and len(ids) <= MAXIMUM_ROWS
             and all(type(s) is str and 0 < len(s) <= 128 and s == s.strip() for s in ids)
             and len(set(ids)) == len(ids), 'DRIFT_SUBJECT_IDENTITIES')
    values = snapshot.values
    _require(type(values) is np.ndarray and values.ndim == 2 and values.shape == (len(ids), 10)
             and values.dtype.kind in 'if', 'DRIFT_NUMERIC_MATRIX')
    try:
        current = values.astype(np.float64, copy=True)
    except (TypeError, ValueError, OverflowError):
        raise FeatureDriftError('DRIFT_NUMERIC_MATRIX') from None
    _require(not np.isinf(current).any(), 'DRIFT_INFINITE_VALUE')
    current.setflags(write=False)
    return ids, current


def _states(ids, values, cuts):
    states = np.empty(values.shape, dtype=np.int16)
    for j, edges in enumerate(cuts):
        states[:, j] = np.searchsorted(edges, values[:, j], side='left')
        states[np.isnan(values[:, j]), j] = -1
    return {s: tuple(int(v) for v in row) for s, row in zip(ids, states)}, states


def _psi_counts(reference, current):
    # Same distribution floor and unrenormalized PSI sum as sealed stage15.
    # Bins are supplied once by the reference owner, never estimated on current.
    n_reference, n_current = sum(reference), sum(current)
    p = [max(v / n_reference, PSI_FLOOR) for v in reference]
    q = [max(v / n_current, PSI_FLOOR) for v in current]
    result = math.fsum((b - a) * math.log(b / a) for a, b in zip(p, q))
    _require(math.isfinite(result) and result >= 0, 'DRIFT_PSI_NUMERICS')
    return result


def evaluate_feature_drift(reference, current, binning, *, expected_binning_sha256):
    """Fixed-reference PSI and missingness, with whole-report joint suppression.

    Batches may overlap or contain repeated measurements across time. IDs are
    unique within a batch and must use the same declared namespace. Privacy cells
    join both batches' membership and all ten missing/bin states by those IDs;
    any nonempty cell below five suppresses all numeric population/feature output.
    This is not formal differential privacy or protection against adaptive queries.
    """
    cuts = _cuts(binning)
    r_ids, r_values = _snapshot(reference)
    c_ids, c_values = _snapshot(current)
    _require(type(expected_binning_sha256) is str
             and SHA.fullmatch(expected_binning_sha256) is not None
             and binning_digest(binning) == expected_binning_sha256, 'DRIFT_BINNING_IDENTITY')
    _require(reference.data_version == binning.reference_data_version
             and reference.manifest_sha256 == binning.reference_manifest_sha256,
             'DRIFT_REFERENCE_BINDING')
    _require(all((s.model_version, s.feature_schema_version, s.subject_namespace) ==
                 (binning.model_version, binning.feature_schema_version, binning.subject_namespace)
                 for s in (reference, current)), 'DRIFT_COMPARISON_CONTEXT')
    r_map, r_states = _states(r_ids, r_values, cuts)
    c_map, c_states = _states(c_ids, c_values, cuts)
    joint = Counter((r_map.get(s, 'ABSENT'), c_map.get(s, 'ABSENT')) for s in set(r_ids) | set(c_ids))
    suppressed = any(n < MINIMUM_CELL for n in joint.values())
    result = dict(schemaVersion='ten-feature-reference-drift-v1',
        status='SUPPRESSED' if suppressed else 'COMPLETE',
        privacyStatus='SUPPRESSED' if suppressed else 'JOINT_MEMBERSHIP_BIN_CELLS_AT_LEAST_FIVE',
        comparison=dict(referenceDataVersion=reference.data_version, currentDataVersion=current.data_version,
                        referenceManifestSha256=reference.manifest_sha256, currentManifestSha256=current.manifest_sha256,
                        modelVersion=binning.model_version, featureSchemaVersion=binning.feature_schema_version,
                        binningSha256=expected_binning_sha256),
        policy=dict(features=list(FEATURES), units=list(UNITS), psiFloor=PSI_FLOOR,
                    missingnessDenominator='ALL_BATCH_ROWS', psiDenominator='OBSERVED_FINITE_ROWS_PER_BATCH',
                    intervals='RIGHT_CLOSED_IMPLICIT_INFINITE_TAILS', minimumNonemptyJointCell=MINIMUM_CELL,
                    singleOccupiedBin='NOT_ESTIMABLE_CONSTANT_OR_UNRESOLVED_AT_FIXED_RESOLUTION'),
        population=None, featureResults=None, operations=dict(featureComparisons=0, psiEvaluations=0),
        modelLoads=0, CIFCalls=0, fits=0, calibrationEvaluated=False, subgroupPerformanceEvaluated=False,
        automaticAlert=False, automaticModelUpdate=False, clinicalUse=False, causalEffectIdentified=False,
        provenanceVerified=False, historicalAvailabilityCertified=False, referenceBinningIndependentlyVerified=False,
        participantOutputsSaved=False, privacyFormalDPCertified=False, populationConfidenceInterval=False)
    if suppressed:
        return result
    result['population'] = dict(referenceRows=len(r_ids), currentRows=len(c_ids),
                                overlappingDeclaredIdentities=len(set(r_ids) & set(c_ids)))
    rows = []
    for j, feature in enumerate(FEATURES):
        r_counts = [int((r_states[:, j] == k).sum()) for k in range(len(cuts[j]) + 1)]
        c_counts = [int((c_states[:, j] == k).sum()) for k in range(len(cuts[j]) + 1)]
        nr, nc = sum(r_counts), sum(c_counts)
        mr, mc = len(r_ids) - nr, len(c_ids) - nc
        r_rate = mr / len(r_ids) if r_ids else None
        c_rate = mc / len(c_ids) if c_ids else None
        reasons = []
        if nr < 5: reasons.append('REFERENCE_OBSERVED_SUPPORT_INSUFFICIENT')
        if nc < 5: reasons.append('CURRENT_OBSERVED_SUPPORT_INSUFFICIENT')
        if nr and sum(n > 0 for n in r_counts) < 2: reasons.append('REFERENCE_SINGLE_OCCUPIED_BIN')
        if nc and sum(n > 0 for n in c_counts) < 2: reasons.append('CURRENT_SINGLE_OCCUPIED_BIN')
        psi = None
        if not reasons:
            result['operations']['psiEvaluations'] += 1
            psi = _psi_counts(r_counts, c_counts)
        result['operations']['featureComparisons'] += 1
        rows.append(dict(feature=feature, unit=UNITS[j], referenceObserved=nr, referenceMissing=mr,
            currentObserved=nc, currentMissing=mc, referenceMissingRate=r_rate, currentMissingRate=c_rate,
            missingRateDelta=(c_rate - r_rate) if r_rate is not None and c_rate is not None else None,
            missingnessStatus='ESTIMABLE' if r_rate is not None and c_rate is not None else 'NOT_ESTIMABLE',
            psi=psi, psiStatus='NOT_ESTIMABLE' if reasons else 'ESTIMABLE', reasons=reasons))
    result['featureResults'] = rows
    if any(row['psiStatus'] == 'NOT_ESTIMABLE' or row['missingnessStatus'] == 'NOT_ESTIMABLE' for row in rows):
        result['status'] = 'COMPLETE_WITH_NOT_ESTIMABLE_FEATURES'
    return result
