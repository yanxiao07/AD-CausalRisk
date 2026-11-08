"""Fixed FR-10 rank-dependence primitives. NumPy and caller-owned memory only.

No files, models, prediction, optimization, graph mutation or causal discovery.
Constructed and explicitly bound research entries share the same numeric core.
Caller metadata does not verify provenance or authorize participant record use.
"""
from dataclasses import dataclass
from types import MappingProxyType
import re

import numpy as np

PROTOCOL_SHA256 = '7182ad9d7eb447b89094436d6b068a7e3652f088cd5337a2d091635f5bab1448'
FEATURES = ('age_years', 'education_years', 'mmse', 'cdr_sb', 'cdr_global',
            'adas__total13', 'faq__faqtotal', 'apoe4_count',
            'mri_left_hippocampus_volume', 'mri_right_hippocampus_volume')
PRIOR_DIRECTIONS = ((0,8),(1,8),(7,8),(0,2),(1,2),(7,2),(8,2),
                    (0,3),(1,3),(7,3),(8,3))
PAIRS = tuple(tuple(sorted(edge)) for edge in PRIOR_DIRECTIONS)
DRAW_COUNT = 100


class StructureStabilityError(ValueError):
    def __init__(self, code, operations=None):
        super().__init__(code)
        self.code = code
        self.operations = dict(operations or {})


class NotEstimable(ValueError):
    """A defined support failure, rather than a numerical fallback."""


@dataclass(frozen=True)
class TrainingFold:
    fold: int
    subject_ids: tuple[str, ...]
    values: np.ndarray
    expected_training_ids: tuple[str, ...]
    held_out_ids: tuple[str, ...]
    features: tuple[str, ...] = FEATURES


def _numeric(values, columns, allow_missing=False):
    if (type(values) is not np.ndarray or values.ndim != 2
            or values.shape[1] != columns or values.dtype.kind not in 'if'):
        raise StructureStabilityError('STRUCTURE_NUMERIC_ARRAY_REQUIRED')
    current = values.astype(np.float64, copy=True)
    if np.isinf(current).any() or (not allow_missing and np.isnan(current).any()):
        raise StructureStabilityError('STRUCTURE_NONFINITE_INPUT')
    return current


def average_ranks(values):
    """Average ties, including repeated sampled occurrences; no imputation."""
    current = _numeric(values, 10)
    n = len(current)
    if n < 2:
        raise NotEstimable('INSUFFICIENT_SCORE_ROWS')
    ranks = np.empty_like(current)
    for j in range(10):
        order = np.argsort(current[:, j], kind='stable')
        ordered = current[order, j]
        starts = np.r_[0, np.flatnonzero(ordered[1:] != ordered[:-1]) + 1]
        ends = np.r_[starts[1:], n]
        ranks[order, j] = np.repeat((starts + ends + 1) / 2.0, ends - starts)
    return ranks


def rank_precision_scores(values):
    """Return only the eleven fixed symmetric pair scores; no learned arrows."""
    ranks = average_ranks(values)
    sd = ranks.std(axis=0, ddof=1)
    if (sd == 0).any():
        raise NotEstimable('CONSTANT_RANK_FEATURE')
    z = (ranks - ranks.mean(axis=0)) / sd
    correlation = z.T @ z / (len(z) - 1)
    if (not np.isfinite(correlation).all()
            or np.max(np.abs(correlation - correlation.T)) > 1e-10):
        raise StructureStabilityError('STRUCTURE_CORRELATION_NUMERICS')
    regularized = .9 * correlation + .1 * np.eye(10)
    try:
        precision = np.linalg.solve(regularized, np.eye(10))
    except Exception:
        raise StructureStabilityError('STRUCTURE_SOLVE_FAILED') from None
    if (type(precision) is not np.ndarray or precision.shape != (10, 10) or not np.isfinite(precision).all()
            or np.max(np.abs(precision - precision.T)) > 1e-10
            or np.max(np.abs(regularized @ precision - np.eye(10))) > 1e-10
            or (np.diag(precision) <= 0).any()):
        raise StructureStabilityError('STRUCTURE_PRECISION_NUMERICS')
    scores = np.array([-precision[u, v] / np.sqrt(precision[u, u] * precision[v, v])
                       for u, v in PAIRS])
    return _checked_scores(scores)


def _checked_scores(scores):
    if (type(scores) is not np.ndarray or scores.shape != (11,)
            or scores.dtype.kind not in 'if' or not np.isfinite(scores).all()
            or (np.abs(scores) > 1).any()):
        raise StructureStabilityError('STRUCTURE_SCORE_RETURN')
    return scores.astype(np.float64, copy=True)


def _null_pairs():
    return [dict(pair=[FEATURES[u], FEATURES[v]], full_score=None, selected=None,
                 positive=None, negative=None, frequency=None,
                 sign_concordance=None, retained_candidate=None,
                 reasons=['FOLD_NOT_ESTIMABLE']) for u, v in PAIRS]


def summarize_selections(full_scores, draws):
    """Exactly 100 scheduled entries, None for a whole invalid draw, never 0."""
    full = _checked_scores(full_scores)
    if not isinstance(draws, (list, tuple)) or len(draws) != DRAW_COUNT:
        raise StructureStabilityError('STRUCTURE_SCHEDULED_DRAW_COUNT')
    valid = [_checked_scores(value) for value in draws if value is not None]
    if len(valid) < 90:
        return dict(status='NOT_ESTIMABLE', reason='INSUFFICIENT_VALID_DRAWS',
                    scheduled_draws=100, valid_draws=len(valid), invalid_draws=100-len(valid),
                    pairs=_null_pairs())
    panel = np.stack(valid)
    result = []
    for j, (u, v) in enumerate(PAIRS):
        positive = int((panel[:, j] >= .20).sum())
        negative = int((panel[:, j] <= -.20).sum())
        selected = positive + negative
        frequency = selected / len(valid)
        concordance = max(positive, negative) / selected if selected else None
        # Integer comparisons make the fixed frequency/sign equalities exact.
        reasons = []
        if not selected: reasons.append('NEVER_SELECTED')
        if 5 * selected < 4 * len(valid): reasons.append('FREQUENCY_BELOW_THRESHOLD')
        if not selected or 10 * max(positive, negative) < 9 * selected:
            reasons.append('SIGN_INCONSISTENT')
        if abs(full[j]) < .20: reasons.append('FULL_SAMPLE_BELOW_THRESHOLD')
        if not ((full[j] > 0 and positive > negative) or (full[j] < 0 and negative > positive)):
            reasons.append('FULL_SAMPLE_SIGN_CONFLICT')
        result.append(dict(pair=[FEATURES[u], FEATURES[v]], full_score=float(full[j]),
                           selected=selected, positive=positive, negative=negative,
                           frequency=frequency, sign_concordance=concordance,
                           retained_candidate=not reasons, reasons=reasons))
    return dict(status='ESTIMABLE', reason=None, scheduled_draws=100,
                valid_draws=len(valid), invalid_draws=100-len(valid), pairs=result)


def _ids(value):
    if (type(value) is not tuple or not value or any(type(s) is not str or not s.strip() for s in value)
            or len(set(value)) != len(value)):
        raise StructureStabilityError('STRUCTURE_SUBJECT_IDENTITIES')
    return value


def _snapshots(folds):
    if (type(folds) not in (tuple, list) or len(folds) != 5
            or any(type(f) is not TrainingFold or type(f.fold) is not int or f.fold != k
                   for k, f in enumerate(folds, 1))):
        raise StructureStabilityError('STRUCTURE_ORIGINAL_FIVE_FOLDS')
    universe = None
    tested = []
    for fold in folds:
        ids = _ids(fold.subject_ids)
        expected = _ids(fold.expected_training_ids)
        held = _ids(fold.held_out_ids)
        if (type(fold.features) is not tuple or fold.features != FEATURES or ids != expected
                or set(ids) & set(held)):
            raise StructureStabilityError('STRUCTURE_TRAINING_BOUNDARY')
        current = set(ids) | set(held)
        if universe is not None and current != universe:
            raise StructureStabilityError('STRUCTURE_FOLD_UNIVERSE')
        universe = current
        tested.extend(held)
    if len(tested) != len(set(tested)) or set(tested) != universe:
        raise StructureStabilityError('STRUCTURE_HELD_OUT_PARTITION')
    snapshots = []
    reference = {}
    for fold in folds:
        values = _numeric(fold.values, 10, allow_missing=True)
        if len(values) != len(fold.subject_ids):
            raise StructureStabilityError('STRUCTURE_TRAINING_ROW_COUNT')
        for subject, row in zip(fold.subject_ids, values):
            if subject in reference and not np.array_equal(reference[subject], row, equal_nan=True):
                raise StructureStabilityError('STRUCTURE_SHARED_SNAPSHOT_CONFLICT')
            reference[subject] = row
        values.setflags(write=False)
        snapshots.append(values)
    return snapshots


def _band(n):
    if n < 50: return '<50'
    if n < 100: return '50-99'
    if n < 200: return '100-199'
    if n < 500: return '200-499'
    if n < 1000: return '500-999'
    return '>=1000'


def _draw_indices(n, fold, replicate):
    rng = np.random.Generator(np.random.PCG64(np.random.SeedSequence([20261102, fold, replicate])))
    return rng.integers(0, n, size=n)


class _Counters:
    def __init__(self):
        self.value = {key: 0 for key in ('score_attempts','score_returns','full_score_attempts',
            'full_score_returns','bootstrap_score_attempts','bootstrap_score_returns',
            'draw_attempts','draw_returns','numerical_score_calls','numerical_score_returns',
            'valid_draws','invalid_draws')}

    def reserve(self, kind):
        key, cap = ('full_score_attempts', 5) if kind == 'full' else ('bootstrap_score_attempts', 500)
        if kind not in ('full', 'bootstrap') or self.value['score_attempts'] >= 505 or self.value[key] >= cap:
            raise StructureStabilityError('STRUCTURE_SCORE_BUDGET')
        self.value[key] += 1
        self.value['score_attempts'] += 1

    def score(self, values, kind):
        self.value['numerical_score_calls'] += 1
        value = rank_precision_scores(values)
        self.value['numerical_score_returns'] += 1
        value = _checked_scores(value)
        self.value[kind + '_score_returns'] += 1
        self.value['score_returns'] += 1
        return value


def _evaluate_folds(folds, *, data_version, constructed, progress=None):
    """One fixed five-fold run, at most 505 statistical attempts.

    Each fold contains training values only and separately bound identity lists.
    Numeric errors/callback errors raise a redacted error with attempted counts;
    there is no partial result, redraw, fitting or on-disk state.
    """
    ops = _Counters()
    try:
        snapshots = _snapshots(folds)
        folds = tuple(folds)  # Freeze the caller's container before progress callbacks.

        def emit(phase, fold, replicate=None):
            if progress is not None:
                event = dict(phase=phase, fold=fold, operations=dict(ops.value))
                if replicate is not None: event['replicate'] = replicate
                try: progress(event)
                except Exception:
                    raise StructureStabilityError('STRUCTURE_PROGRESS_FAILED') from None
                if any(not np.array_equal(f.values, snapshot, equal_nan=True)
                       for f, snapshot in zip(folds, snapshots)):
                    raise StructureStabilityError('STRUCTURE_CALLER_INPUT_CHANGED')

        results = []
        for fold, raw in zip(folds, snapshots):
            eligible = raw[~np.isnan(raw).any(axis=1)]
            band = _band(len(eligible))
            invalid_reasons = dict(INSUFFICIENT_DISTINCT_SUBJECTS=0, CONSTANT_RANK_FEATURE=0)
            if len(eligible) < 50:
                report = dict(status='NOT_ESTIMABLE', reason='INSUFFICIENT_TRAINING_SUBJECTS',
                              scheduled_draws=0, valid_draws=0, invalid_draws=0, pairs=_null_pairs())
            else:
                ops.reserve('full')
                try: full = ops.score(eligible, 'full')
                except NotEstimable as error:
                    if error.args != ('CONSTANT_RANK_FEATURE',):
                        raise StructureStabilityError('STRUCTURE_UNEXPECTED_SUPPORT_FAILURE') from None
                    report = dict(status='NOT_ESTIMABLE', reason='CONSTANT_FULL_TRAINING_RANK',
                                  scheduled_draws=0, valid_draws=0, invalid_draws=0, pairs=_null_pairs())
                else:
                    draws = []
                    for replicate in range(1, 101):
                        ops.value['draw_attempts'] += 1
                        indices = _draw_indices(len(eligible), fold.fold, replicate)
                        if (type(indices) is not np.ndarray or indices.shape != (len(eligible),)
                                or indices.dtype.kind not in 'iu' or (indices < 0).any()
                                or (indices >= len(eligible)).any()):
                            raise StructureStabilityError('STRUCTURE_DRAW_RETURN')
                        ops.value['draw_returns'] += 1
                        ops.reserve('bootstrap')
                        if len(np.unique(indices)) < 20:
                            value = None
                            invalid_reasons['INSUFFICIENT_DISTINCT_SUBJECTS'] += 1
                        else:
                            try: value = ops.score(eligible[indices], 'bootstrap')
                            except NotEstimable as error:
                                if error.args != ('CONSTANT_RANK_FEATURE',):
                                    raise StructureStabilityError('STRUCTURE_UNEXPECTED_SUPPORT_FAILURE') from None
                                value = None
                                invalid_reasons['CONSTANT_RANK_FEATURE'] += 1
                        draws.append(value)
                        ops.value['invalid_draws' if value is None else 'valid_draws'] += 1
                        emit('DRAW_COMPLETE', fold.fold, replicate)
                    report = summarize_selections(full, draws)
            report.update(fold=fold.fold, eligible_subject_band=band, invalid_reasons=invalid_reasons)
            results.append(report)
            emit('FOLD_COMPLETE', fold.fold)
        if any(not np.array_equal(f.values, snapshot, equal_nan=True) for f, snapshot in zip(folds, snapshots)):
            raise StructureStabilityError('STRUCTURE_CALLER_INPUT_CHANGED')
        estimable = all(row['status'] == 'ESTIMABLE' for row in results)
        intersection = ([list(pair) for j, pair in enumerate((FEATURES[u], FEATURES[v]) for u, v in PAIRS)
                         if all(row['pairs'][j]['retained_candidate'] for row in results)] if estimable else None)
        return dict(schemaVersion='restricted-rank-structure-stability-v1', protocolSha256=PROTOCOL_SHA256,
                    dataVersion=data_version, constructed=constructed,
                    status='COMPLETE' if estimable else 'COMPLETE_WITH_NOT_ESTIMABLE_FOLDS',
                    folds=results, priorDirections=[[FEATURES[u], FEATURES[v]] for u, v in PRIOR_DIRECTIONS],
                    crossFold=dict(status='DESCRIPTIVE_INTERSECTION' if estimable else 'NOT_ESTIMABLE',
                                   retainedPairs=intersection, independentFolds=False),
                    operations=dict(ops.value), statisticalEstimatesArePredictiveFits=False,
                    fits=0, modelLoads=0, CIFCalls=0, clinicalUse=False, causalEffectIdentified=False,
                    directionLearned=False, individualStructureEstimated=False,
                    graphChanged=False, defaultModelChanged=False, populationConfidenceInterval=False,
                    falseDiscoveryControl=False, provenanceVerified=False, historicalRealtimeCertified=False,
                    participantOutputsSaved=False, privacyFormalDPCertified=False)
    except StructureStabilityError as error:
        raise StructureStabilityError(error.code, ops.value) from None
    except Exception:
        raise StructureStabilityError('STRUCTURE_EXECUTION_FAILED', ops.value) from None


RESEARCH_BINDING = MappingProxyType({
    'protocol_sha256': PROTOCOL_SHA256,
    'snapshot_sha256': 'a896cbb721fb5ac71c7bf781ebe6a1e7bd9c27639b90a68997b81039e27ab868',
    'graph_folds_sha256': 'd335d31d25b97281d7f9dd5b97d67c74ef5b640693b70fe717f75ebdde210cbc',
})
RESEARCH_DATA_VERSION = 'adni-first-mci-ten-original-graph-folds-2026-10-24'


def evaluate_training_folds(folds, *, data_version, constructed, progress=None):
    """Constructed entry; the original synthetic-only boundary remains enforced."""
    if (constructed is not True or type(data_version) is not str
            or re.fullmatch(r'synthetic-[A-Za-z0-9._-]{1,96}', data_version) is None):
        raise StructureStabilityError('STRUCTURE_CONSTRUCTED_SCOPE', _Counters().value)
    return _evaluate_folds(folds, data_version=data_version, constructed=True, progress=progress)


def evaluate_research_training_folds(folds, *, binding, progress=None):
    """Fixed caller-bound research memory, not a file reader or authorization.

    The owning host must verify original bytes, positional identity, single-use
    authorization, resource limits and the output policy independently. Matching
    caller strings do not certify clinical provenance or historical availability.
    """
    if (type(binding) is not dict or binding != RESEARCH_BINDING
            or type(folds) not in (list, tuple) or len(folds) != 5
            or any(type(f) is not TrainingFold or len(f.subject_ids) != 928
                   or len(f.expected_training_ids) != 928 or len(f.held_out_ids) != 232 for f in folds)):
        raise StructureStabilityError('STRUCTURE_RESEARCH_SCOPE', _Counters().value)
    return _evaluate_folds(folds, data_version=RESEARCH_DATA_VERSION, constructed=False, progress=progress)
