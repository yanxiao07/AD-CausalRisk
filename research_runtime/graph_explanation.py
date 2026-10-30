"""Graph explanation diagnostics and bounded in-memory perturbation aggregation.

The original fixed-weight API remains unchanged. The separate perturbation API
delegates training to graph_training, with no readers or participant outputs.
"""
from copy import deepcopy
from itertools import combinations
import hashlib
import json
import time
import warnings

import numpy as np
from scipy.stats import spearmanr
import torch

from . import graph
from .graph_native import FEATURES, state_digest
from .graph_training import checked_cif
from .training import checked_design, input_fingerprint, prediction_hash
from .evaluation import censor_survival, horizon_ipcw_weights
from .graph_acceptance import peak_memory_bytes

GROUPS = tuple((name, (name,)) for name in FEATURES[:8]) + (('bilateral_hippocampus', FEATURES[8:]),)
ORDERS = ('forward', 'reverse')
TOLERANCE = 1e-12
MATERIAL = 1e-6


def model_identity(model):
    e = model.named_steps['hazards']; h = hashlib.sha256(state_digest(e.network_).encode())
    h.update(repr((e.arm, e.seed, e.network_.arm, e.network_.edges, e.protocol_sha256_, e.network_.training)).encode())
    for values in (e.medians_, e.mean_, e.scale_, e.classes_):h.update(np.asarray(values).tobytes())
    return h.hexdigest()


class Calls:
    def __init__(self, limit=1230, *, max_state_rows=None, evaluation_event=None):
        if (type(limit) is not int or limit < 0
                or (max_state_rows is not None and (type(max_state_rows) is not int or max_state_rows < 0))
                or (evaluation_event is not None and not callable(evaluation_event))):
            raise ValueError('INVALID_EXPLANATION_CIF_BUDGET')
        self.count = 0; self.limit = limit; self.completed = 0
        self.state_rows_attempted = self.state_rows_completed = 0
        self.max_state_rows = max_state_rows; self.evaluation_event = evaluation_event
        self.failed = False

    def _event(self, phase):
        if self.evaluation_event is not None:
            self.evaluation_event(dict(phase=phase, cif_attempts=self.count, cif_completed=self.completed,
                cif_state_rows_attempted=self.state_rows_attempted, cif_state_rows_completed=self.state_rows_completed))

    def __call__(self, model, frame):
        if self.failed:raise ValueError('EXPLANATION_CIF_FAILED_NO_RETRY')
        if (self.count >= self.limit
                or (self.max_state_rows is not None and self.state_rows_attempted+len(frame) > self.max_state_rows)):
            raise ValueError('EXPLANATION_CIF_BUDGET_EXCEEDED')
        before = model_identity(model); fingerprint = input_fingerprint(frame, 'multimodal_values')
        self.count += 1; self.state_rows_attempted += len(frame)
        try:
            self._event('CIF_ATTEMPT')
            result = checked_cif(model, frame)
            if model_identity(model) != before or input_fingerprint(frame, 'multimodal_values') != fingerprint:
                raise ValueError('EXPLANATION_MODEL_OR_INPUT_MUTATED')
            self.completed += 1; self.state_rows_completed += len(frame)
            self._event('CIF_COMPLETE')
            return result
        except BaseException:
            self.failed = True
            raise


def observation_groups(frame):
    masks = []
    for _, columns in GROUPS:
        observed = frame[list(columns)].notna().to_numpy()
        if not (observed == observed[:, :1]).all():raise ValueError('PARTIAL_EXPLANATION_GROUP')
        masks.append(observed.all(1))
    return np.column_stack(masks)


def median_reference(frame, medians):
    medians = np.asarray(medians)
    if medians.shape != (10,) or not np.isfinite(medians).all():raise ValueError('INVALID_GRAPH_REFERENCE')
    result = frame.copy(deep=True)
    for index, name in enumerate(FEATURES):result.loc[result[name].notna(), name] = medians[index]
    if not np.array_equal(result[list(FEATURES)].isna(), frame[list(FEATURES)].isna()):
        raise ValueError('REFERENCE_CHANGED_OBSERVATION_MASK')
    return result


def telescoping(model, frame, baseline, calls, *, reference_values=None):
    """Two original orders with optional raw-scale replacement medians.

    The caller verifies/caches ``baseline`` for this same model and input. Its
    reuse keeps each game at seventeen CIF calls; reference values never alter
    model imputation/scaling. No fitting, readers or participant persistence.
    """
    import pandas as pd
    if (not isinstance(frame, pd.DataFrame) or not 1 <= len(frame) <= 256
            or not frame.columns.is_unique or not set(FEATURES) <= set(frame)
            or not callable(calls)):
        raise ValueError('INVALID_ORDERED_EXPLANATION_INPUT')
    for name in FEATURES:
        dtype = frame[name].dtype
        if (not pd.api.types.is_numeric_dtype(dtype) or pd.api.types.is_bool_dtype(dtype)
                or pd.api.types.is_complex_dtype(dtype)):
            raise ValueError('INVALID_ORDERED_EXPLANATION_VALUES')
    snapshot = frame.copy(deep=True)
    work = frame.astype({name:'float64' for name in FEATURES}, copy=True)
    if np.isinf(work[list(FEATURES)].to_numpy()).any():
        raise ValueError('INVALID_ORDERED_EXPLANATION_VALUES')
    observed = observation_groups(work)
    baseline = np.asarray(baseline)
    if (baseline.dtype.kind not in 'fi' or baseline.shape != (len(work), 2)
            or not np.isfinite(baseline).all() or (baseline < 0).any()
            or (baseline.sum(1) > 1+TOLERANCE).any()):
        raise ValueError('INVALID_ORDERED_EXPLANATION_BASELINE')
    baseline = baseline.astype(float, copy=True)
    medians = np.asarray(model.named_steps['hazards'].medians_ if reference_values is None else reference_values)
    if medians.dtype.kind not in 'fi':raise ValueError('INVALID_GRAPH_REFERENCE')
    reference = median_reference(work, medians.astype(float, copy=True))
    before = model_identity(model) if hasattr(model.named_steps['hazards'], 'network_') else None

    def evaluate(state):
        prior = state.copy(deep=True); value = np.asarray(calls(model, state))
        if (not state.equals(prior) or not frame.equals(snapshot)
                or (before is not None and model_identity(model) != before)):
            raise ValueError('ORDERED_EXPLANATION_MODEL_OR_INPUT_MUTATED')
        if (value.dtype.kind not in 'fi' or value.shape != baseline.shape or not np.isfinite(value).all()
                or (value < 0).any() or (value.sum(1) > 1+TOLERANCE).any()):
            raise ValueError('INVALID_ORDERED_EXPLANATION_CIF')
        return value.astype(float, copy=True)

    start = evaluate(reference); result = {}
    maximum = 0.
    for order in ORDERS:
        indices = list(range(9)) if order == 'forward' else list(reversed(range(9)))
        state = reference.copy(deep=True); previous = start
        contributions = np.zeros((len(frame), 9, 2))
        for step, group in enumerate(indices):
            columns = list(GROUPS[group][1]); state.loc[:, columns] = work[columns].to_numpy()
            current = baseline if step == 8 else evaluate(state)
            contributions[:, group] = current-previous; previous = current
        residual = float(np.max(np.abs(start+contributions.sum(1)-baseline)))
        if residual > TOLERANCE or np.any(contributions[~observed] != 0):
            raise ValueError('CIF_EXPLANATION_RECONSTRUCTION_OR_MISSINGNESS_FAILURE')
        maximum = max(maximum, residual); result[order] = contributions
    return start, result, maximum


def without_routes(model, removed):
    original = tuple(model.named_steps['hazards'].network_.edges)
    removed = tuple(removed)
    if len(set(removed)) != len(removed) or not set(removed).issubset(original):
        raise ValueError('UNUSED_OR_DUPLICATE_ROUTE_REMOVAL')
    result = deepcopy(model)
    result.named_steps['hazards'].network_.edges = tuple(edge for edge in original if edge not in removed)
    if state_digest(result.named_steps['hazards'].network_) != state_digest(model.named_steps['hazards'].network_):
        raise ValueError('ROUTE_DIAGNOSTIC_CHANGED_PARAMETERS')
    return result


def trace(model, frame, baseline):
    e = model.named_steps['hazards']; before = model_identity(model)
    if tuple(e.network_.edges) != graph.arm_edges(e.arm):raise ValueError('DISPLAY_GRAPH_DIFFERS_FROM_PREDICTOR')
    values, observed = e._transform(frame[list(FEATURES)].to_numpy(float))
    with torch.no_grad():result = e.network_(values, observed)
    parameters = {'input_projection':e.network_.input_projection.detach().numpy(),
                  'node_embeddings':e.network_.node_embeddings.detach().numpy(),
                  'head':e.network_.head.detach().numpy(),
                  'layers':[{'weight':w.detach().numpy(), 'attention':a.detach().numpy()}
                            for w, a in zip(e.network_.weights, e.network_.attentions)]}
    reference = graph.reference_forward(values.numpy(), observed.numpy(), e.arm, parameters)
    allowed = result['allowed_routes'].numpy(); errors = []
    if not np.array_equal(allowed, reference['allowed_routes']):raise ValueError('ACTUAL_GATE_MISMATCH')
    for i, alpha in enumerate(result['attention']):
        alpha = alpha.numpy()
        if np.any(alpha[~allowed] != 0) or not np.allclose(alpha.sum(1), 1, rtol=0, atol=TOLERANCE):
            raise ValueError('ACTUAL_ATTENTION_GATE_OR_NORMALIZATION_FAILURE')
        errors.append(float(np.max(np.abs(alpha-reference['attention'][i]))))
    for name in ('encoded', 'states', 'hazards', 'cif'):
        errors.append(float(np.max(np.abs(result[name].numpy()-reference[name]))))
    # Additive contributions apply to logits, not directly to nonlinear CIFs.
    node_logits = np.einsum('njd,jdc->njc', result['states'].numpy(), parameters['head'][:80].reshape(10, 8, 3))
    readout = node_logits.sum(1)[:, None, :]+parameters['head'][80:][None]
    errors.append(float(np.max(np.abs(readout-result['readout'].numpy()))))
    errors.append(float(np.max(np.abs(result['cif'].numpy()[:, :2]-baseline))))
    if not np.isfinite(errors).all() or max(errors) > TOLERANCE or model_identity(model) != before:
        raise ValueError('ACTUAL_TRACE_RECONSTRUCTION_FAILURE')
    edges = graph.arm_edges(e.arm)
    attention = np.column_stack([np.mean([a.numpy()[:, s, t] for a in result['attention']], axis=0)
                                 for s, t in edges]) if edges else np.zeros((len(frame), 0))
    active = np.column_stack([allowed[:, s, t] for s, t in edges]) if edges else np.zeros((len(frame), 0), bool)
    return attention, active, max(errors)


def descriptive(values):
    values = np.asarray(values, dtype=float)
    if not np.isfinite(values).all():raise ValueError('NONFINITE_EXPLANATION_SUMMARY')
    return dict(n=len(values), mean=float(values.mean()) if len(values) else None,
                median=float(np.median(values)) if len(values) else None,
                p95=float(np.quantile(values, .95)) if len(values) else None)


def rank(left, right):
    left, right = np.asarray(left), np.asarray(right)
    if len(left) < 2 or np.ptp(left) <= TOLERANCE or np.ptp(right) <= TOLERANCE:return None
    return float(spearmanr(left, right).statistic)


def local_rank_summary(left, right, observed):
    values = []; insufficient = constant = 0
    for a, b, mask in zip(left, right, observed):
        if mask.sum() < 3:insufficient += 1; continue
        value = rank(np.abs(a[mask]), np.abs(b[mask]))
        if value is None:constant += 1
        else:values.append(value)
    return dict(spearman=descriptive(values), insufficient_observed_groups=insufficient, constant_ranks=constant)


def aggregate(work, splits, raw, references, contributions, edge_deltas, all_route_deltas, attentions, active):
    spec, _ = graph.protocol(); seeds = spec['future_initialization_seeds']; n = len(work)
    observed = observation_groups(work); rows = []; sensitivity = []; seed_rows = []; fold_rows = []
    means = {}
    for arm in spec['arms']:
        means[arm] = {}
        for order in ORDERS:
            values = np.stack([contributions[s, arm, order] for s in seeds]); mean = values.mean(0)
            means[arm][order] = mean[:, :, 0]
            rows.append(dict(arm=arm, order=order, method='fixed-reference ordered telescoping CIF',
                groups=[dict(group=name, observed_subjects=int(observed[:, j].sum()),
                     mean_signed_ad=float(mean[:, j, 0].mean()), mean_abs_ad=float(np.abs(mean[:, j, 0]).mean()),
                     mean_signed_other=float(mean[:, j, 1].mean()), mean_abs_other=float(np.abs(mean[:, j, 1]).mean()),
                     observed_mean_abs_ad=float(np.abs(mean[observed[:, j], j, 0]).mean()) if observed[:, j].any() else None)
                     for j, (name, _) in enumerate(GROUPS)]))
            for a, b in combinations(seeds, 2):
                seed_rows.append(dict(arm=arm, order=order, seeds=[a, b],
                    global_magnitude_spearman=rank(np.abs(contributions[a, arm, order][:, :, 0]).mean(0),
                                                   np.abs(contributions[b, arm, order][:, :, 0]).mean(0)),
                    local=local_rank_summary(contributions[a, arm, order][:, :, 0], contributions[b, arm, order][:, :, 0], observed)))
            vectors = [np.abs(mean[b, :, 0]).mean(0) for _, b in splits]
            for a, b in combinations(range(5), 2):
                fold_rows.append(dict(arm=arm, order=order, folds=[a+1, b+1], magnitude_spearman=rank(vectors[a], vectors[b])))
        forward, reverse = means[arm]['forward'], means[arm]['reverse']
        difference = np.abs(forward-reverse).sum(1)
        material = (np.abs(forward) > MATERIAL) & (np.abs(reverse) > MATERIAL) & observed
        flips = (forward*reverse < 0) & material
        sensitivity.append(dict(arm=arm, local_order_l1=descriptive(difference),
            subjects_above_1e_6=int((difference > MATERIAL).sum()),
            material_group_contributions=int(material.sum()), sign_flips=int(flips.sum()),
            local_order_ranks=local_rank_summary(forward, reverse, observed)))
    routes = []; attention_checks = []
    for arm in spec['arms']:
        edges = graph.arm_edges(arm)
        total = np.stack([all_route_deltas[s, arm] for s in seeds]).mean(0)
        delta = np.stack([edge_deltas[s, arm] for s in seeds]).mean(0)
        attention = np.stack([attentions[s, arm] for s in seeds]).mean(0)
        mask = active[arm]
        routes.append(dict(arm=arm, all_cross_routes_removed_mean_abs_ad=float(np.abs(total[:, 0]).mean()),
            all_cross_routes_removed_mean_signed_ad=float(total[:, 0].mean()),
            effective_cross_routes=descriptive(mask.sum(1)),
            edges=[dict(source=FEATURES[s], target=FEATURES[t], active_subjects=int(mask[:, j].sum()),
                        mean_attention=float(attention[:, j].mean()),
                        mean_abs_ad_change=float(np.abs(delta[:, j, 0]).mean()),
                        mean_signed_ad_change=float(delta[:, j, 0].mean())) for j, (s, t) in enumerate(edges)]))
        corrs = []; eligible = matches = insufficient = constant = 0
        for person in range(n):
            supported = mask[person]
            if supported.sum() < 2:insufficient += 1; continue
            magnitude = np.abs(delta[person, supported, 0]); weights = attention[person, supported]
            value = rank(weights, magnitude)
            if value is None or magnitude.max() <= TOLERANCE:constant += 1; continue
            eligible += 1; corrs.append(value)
            chosen = int(np.argmax(weights))
            matches += int(magnitude[chosen] >= magnitude.max()-TOLERANCE)
        attention_checks.append(dict(arm=arm, eligible_subjects=eligible, top_attention_matches_top_deletion=matches,
            top_agreement_fraction=matches/eligible if eligible else None, magnitude_spearman=descriptive(corrs),
            insufficient_active_edges=insufficient, constant_attention_or_effect=constant))
    cross = [dict(order=order, arms=[a, b],
                  global_magnitude_spearman=rank(np.abs(means[a][order]).mean(0), np.abs(means[b][order]).mean(0)),
                  local=local_rank_summary(means[a][order], means[b][order], observed))
             for order in ORDERS for a, b in combinations(spec['arms'], 2)]
    return dict(importance=rows, order_sensitivity=sensitivity, seed_sensitivity=seed_rows,
                fold_sensitivity=fold_rows, cross_arm_sensitivity=cross,
                route_sensitivity=routes, attention_vs_deletion=attention_checks)


def evaluate(frame, splits, loader, expected, progress=lambda value:None):
    work, splits, _ = checked_design(frame, 'multimodal_values', splits)
    spec, protocol_hash = graph.protocol(); seeds = spec['future_initialization_seeds']; arms = spec['arms']
    torch.set_num_threads(1); started = time.perf_counter(); calls = Calls()
    n = len(work); weights = np.full(n, np.nan); target = work.event_type.eq(1).to_numpy(float)
    raw = {(s, a):np.full((n, 2), np.nan) for s in seeds for a in arms}
    references = {key:np.full((n, 2), np.nan) for key in raw}
    contributions = {(s, a, o):np.full((n, 9, 2), np.nan) for s, a in raw for o in ORDERS}
    edge_deltas = {(s, a):np.full((n, len(graph.arm_edges(a)), 2), np.nan) for s, a in raw}
    all_deltas = {key:np.full((n, 2), np.nan) for key in raw}
    attentions = {(s, a):np.full((n, len(graph.arm_edges(a))), np.nan) for s, a in raw}
    active = {a:np.zeros((n, len(graph.arm_edges(a))), bool) for a in arms}
    models = {}; identities = {}; baseline_rows = []; loads = traces = 0
    reconstruction_max = trace_max = 0.
    for fold, (a, b) in enumerate(splits, 1):
        weights[b] = horizon_ipcw_weights(work.iloc[b], censor_survival(work.iloc[a]))
        for seed in seeds:
            for arm in arms:
                model = loader(arm, seed, fold); loads += 1
                if (model.named_steps['hazards'].arm != arm or model.named_steps['hazards'].seed != seed):
                    raise ValueError('EXPLANATION_MODEL_ROLE_MISMATCH')
                models[fold, seed, arm] = model; identities[fold, seed, arm] = model_identity(model)
                raw[seed, arm][b] = calls(model, work.iloc[b])
    for arm in arms:
        row = next(row for row in expected['models'] if row['arm'] == arm)
        for seed in seeds:
            old = next(value for value in row['per_seed'] if value['seed'] == seed)
            if prediction_hash(raw[seed, arm][:, 0]) != old['ad_oof_sha256'] or prediction_hash(raw[seed, arm][:, 1]) != old['other_oof_sha256']:
                raise ValueError('ORIGINAL_TWO_CAUSE_SEED_OOF_CHANGED')
        mean = np.stack([raw[s, arm] for s in seeds]).mean(0)
        if (prediction_hash(mean[:, 0]) != row['ad_oof_sha256'] or prediction_hash(mean[:, 1]) != row['other_oof_sha256']
                or abs(float(np.mean(weights*(target-mean[:, 0])**2))-row['pooled_oof_brier']) > TOLERANCE):
            raise ValueError('ORIGINAL_ENSEMBLE_OOF_OR_BRIER_CHANGED')
        baseline_rows.append(dict(arm=arm, ad_oof_sha256=prediction_hash(mean[:, 0]),
                                 other_oof_sha256=prediction_hash(mean[:, 1]), brier=row['pooled_oof_brier']))
    progress(dict(all_two_cause_baselines_verified=True, model_loads=loads, cif_calls=calls.count))
    for fold, (_, b) in enumerate(splits, 1):
        test = work.iloc[b]
        for seed in seeds:
            for arm in arms:
                model = models[fold, seed, arm]; baseline = raw[seed, arm][b]; edges = graph.arm_edges(arm)
                attention, allowed, error = trace(model, test, baseline); traces += 1
                trace_max = max(trace_max, error); attentions[seed, arm][b] = attention
                active[arm][b] = allowed
                reference, values, error = telescoping(model, test, baseline, calls)
                references[seed, arm][b] = reference; reconstruction_max = max(reconstruction_max, error)
                for order in ORDERS:contributions[seed, arm, order][b] = values[order]
                all_delta = baseline-calls(without_routes(model, edges), test)
                all_deltas[seed, arm][b] = all_delta
                if not edges and np.any(all_delta != 0):raise ValueError('SELF_ONLY_ROUTE_ABLATION_NOT_ZERO')
                for j, edge in enumerate(edges):
                    delta = baseline-calls(without_routes(model, (edge,)), test)
                    if np.any(delta[~allowed[:, j]] != 0):raise ValueError('INACTIVE_ROUTE_HAS_PREDICTIVE_EFFECT')
                    edge_deltas[seed, arm][b, j] = delta
                if not np.array_equal(calls(model, test), baseline) or model_identity(model) != identities[fold, seed, arm]:
                    raise ValueError('EXPLANATION_CHANGED_ORIGINAL_MODEL_OR_CIF')
                if time.perf_counter()-started > 1800 or peak_memory_bytes() > 2147483648:
                    raise ValueError('EXPLANATION_RESOURCE_LIMIT_EXCEEDED')
        progress(dict(completed_explanation_fold=fold, cif_calls=calls.count, trace_pairs=traces, model_loads=loads))
    if calls.count != 1230 or loads != 45 or traces != 45:raise ValueError('INCOMPLETE_EXPLANATION_BUDGET')
    summary = aggregate(work, splits, raw, references, contributions, edge_deltas, all_deltas, attentions, active)
    return dict(status='FIXED_GRAPH_EXPLANATION_AND_FAITHFULNESS_COMPLETE', subjects=n, outer_folds=5,
        protocol_sha256=protocol_hash, baseline=baseline_rows, **summary,
        existing_model_loads=loads, cif_calls=calls.count, native_numpy_trace_pairs=traces,
        cif_reconstruction_max_error=reconstruction_max, trace_reconstruction_max_error=trace_max,
        tolerance=TOLERANCE, fits=0, calibration_fits=0,
        elapsed_seconds=time.perf_counter()-started, peak_process_memory_bytes=peak_memory_bytes(),
        clinical_use=False, individual_causal_effect_identified=False, external_validation=False,
        default_model_changed=False, participant_predictions_saved=False, participant_explanations_saved=False,
        explanation_type='fixed-reference, order-dependent predictive decomposition; fixed-weight route deletion',
        limitations=['Exact additivity does not establish uniqueness, biological reference validity or causality.',
            'Canonical/reverse orders are two fixed diagnostics, not Shapley averaging or SHAP/LIME.',
            'Route deletion includes renormalization and downstream messages; it is not an additive edge attribution.',
            'Attention is a computational weight; agreement with deletion is descriptive and not causal fidelity.',
            'Fixed stored models and cohort; no new bootstrap, significance, independent or refit uncertainty.',
            'Retrospective MRI/as-of/QC/ICV limits, six cross-connected nodes and five competing events remain.'])


def _ensemble_games(single_games):
    """Average signed seed games first, then decompose the four games."""
    values = np.asarray(single_games)
    if values.ndim != 6 or values.shape[:3] != (3,4,2) or values.shape[-2:] != (9,2) or not np.isfinite(values).all():
        raise ValueError('INVALID_GRAPH_SEED_GAMES')
    a,b,c,d = values.mean(0)
    parts = dict(model=b-a, reference=c-a, interaction=d-b-c+a, total=d-a)
    error = float(np.max(np.abs(parts['total']-parts['model']-parts['reference']-parts['interaction'])))
    if error > TOLERANCE:raise ValueError('GRAPH_FOUR_GAME_IDENTITY_FAILURE')
    return (a,b,c,d), parts, error


def _fixed_rank_masks(rows):
    """One denominator per metric over arms, orders and all five repeats."""
    return {name:np.logical_and.reduce([np.isfinite(row[name]) for row in rows])
            for name in ('spearman','top1','top2')}


def evaluate_training_perturbations(frame, splits, loader, candidates, expected, *,
        expected_input_fingerprint, expected_subjects=1160, constructed=False,
        fitter=None, progress=None, max_cif_calls=12780, max_state_rows=2964960,
        max_elapsed_seconds=21600., max_memory_bytes=2147483648):
    """Stream the fixed 225-slot three-arm/three-seed/five-repeat OOF study.

    ``loader`` is a trusted host callback: it verifies archive bytes, scope and
    training-input binding in each supplied GRAPH_OUTER_VALIDATION candidate.
    This function also checks role/seed, non-reuse, input identity, all 24 old
    two-cause OOF hashes and all 12 old AD Briers before any new fitting.
    A replacement fitter is allowed only for explicitly constructed acceptance.
    No readers, parameter saving, calibration, final fit, retry or deployment.
    Limits here are soft checks; a real host must supply hard supervision and
    independent accounting. Exceptions propagate with attempted counts in the
    progress stream; no partial result is returned as a completed study.
    """
    import weakref
    from .graph_training import GraphFitBudget, fit_graph_subjects
    from .training import subject_subsample
    # Local import avoids the legacy table module's import of graph rank.
    from .table_xai_study import _rank_rows, _finite_distribution

    if (type(constructed) is not bool or (fitter is not None and not constructed)
            or not callable(loader) or (progress is not None and not callable(progress))
            or type(expected_subjects) is not int or expected_subjects < 1
            or type(max_cif_calls) is not int or not 0 <= max_cif_calls <= 12780
            or type(max_state_rows) is not int or not 0 <= max_state_rows <= 2964960
            or type(max_memory_bytes) is not int or not 0 < max_memory_bytes <= 2147483648
            or type(max_elapsed_seconds) not in (int,float)
            or not np.isfinite(max_elapsed_seconds) or not 0 < max_elapsed_seconds <= 21600):
        raise ValueError('INVALID_GRAPH_PERTURBATION_SCOPE_OR_LIMITS')
    fitter = fit_graph_subjects if fitter is None else fitter
    if not callable(fitter):raise ValueError('INVALID_GRAPH_PERTURBATION_FITTER')
    work, splits, _ = checked_design(frame, 'multimodal_values', splits)
    if len(work) != expected_subjects or input_fingerprint(work,'multimodal_values') != expected_input_fingerprint:
        raise ValueError('GRAPH_PERTURBATION_INPUT_BINDING_MISMATCH')
    original = work.copy(deep=True)
    spec, protocol_hash = graph.protocol(); seeds = spec['future_initialization_seeds']; arms = spec['arms']
    keys = {(fold,seed,arm) for fold in range(1,6) for seed in seeds for arm in arms}
    bound = {}
    def digest(value):return isinstance(value,str) and len(value)==64 and all(x in '0123456789abcdef' for x in value)
    for row in candidates:
        key = (row.get('outer_fold'),row.get('seed'),row.get('arm'))
        if (key not in keys or key in bound or row.get('role') != 'GRAPH_OUTER_VALIDATION'
                or not all(digest(row.get(name)) for name in ('sha256','scope_identity','training_input_sha256'))):
            raise ValueError('GRAPH_OUTER_CANDIDATE_BINDING_REQUIRED')
        bound[key] = row.copy()
    if set(bound) != keys:raise ValueError('GRAPH_OUTER_CANDIDATE_MATRIX_INCOMPLETE')
    expected_rows = {row['arm']:row for row in expected['models']}
    if len(expected['models']) != 3 or set(expected_rows) != set(arms):raise ValueError('INVALID_GRAPH_BASELINE_MATRIX')
    started = time.perf_counter(); torch.set_num_threads(1)
    counters = dict(model_loads=0, single_model_games=0, ensemble_games=0, folds_completed=0,
                    baseline_hashes_verified=0, baseline_briers_verified=0)
    calls = Calls(max_cif_calls, max_state_rows=max_state_rows)
    budget = GraphFitBudget(225,max_memory_bytes=max_memory_bytes)
    seen = []; networks = []; old = {}; identities = {}; raw = {(s,a):np.full((len(work),2),np.nan) for s in seeds for a in arms}
    weights = np.full(len(work),np.nan); target = work.event_type.eq(1).to_numpy(float)
    distributions = {}; rank_diagnostics = {}; maximum_reconstruction = maximum_identity = 0.

    def emit(phase):
        if progress is not None:
            progress(dict(phase=phase, **counters, fit_attempts=budget.attempts, fit_returns=budget.fit_returns,
                fits_completed=budget.completed, total_fit_seconds=budget.total_fit_seconds,
                cif_attempts=calls.count,cif_completed=calls.completed,
                cif_state_rows_attempted=calls.state_rows_attempted,cif_state_rows_completed=calls.state_rows_completed,
                constructed=constructed))

    def guard():
        if (time.perf_counter()-started > max_elapsed_seconds or peak_memory_bytes() > max_memory_bytes):
            raise ValueError('GRAPH_PERTURBATION_RESOURCE_LIMIT')

    def callback(event):
        guard();emit(event['phase'])
    budget.evaluation_event = callback; calls.evaluation_event = callback

    def unique_model(model, arm, seed):
        if any(ref() is model for ref in seen):raise ValueError('GRAPH_PERTURBATION_MODEL_REUSED')
        seen.append(weakref.ref(model)); e = model.named_steps['hazards']
        if any(ref() is e.network_ for ref in networks):raise ValueError('GRAPH_PERTURBATION_NETWORK_REUSED')
        networks.append(weakref.ref(e.network_))
        if (e.arm != arm or e.seed != seed or e.protocol_sha256_ != protocol_hash
                or e.network_.arm != arm or tuple(e.network_.edges) != graph.arm_edges(arm)
                or not np.array_equal(e.classes_,[0,1,2])):
            raise ValueError('GRAPH_PERTURBATION_MODEL_ROLE_CHANGED')
        return e

    def game(model,test,baseline,reference):
        nonlocal maximum_reconstruction
        _,values,error = telescoping(model,test,baseline,calls,reference_values=reference)
        maximum_reconstruction = max(maximum_reconstruction,error)
        counters['single_model_games'] += 1
        return np.stack([values[order] for order in ORDERS])

    def keep(key,values):
        distributions.setdefault(key,[]).append(np.asarray(values).copy())

    def active_groups(test,reference):
        x = test[list(FEATURES)].to_numpy(float)
        return np.column_stack([np.any((x[:,[FEATURES.index(c) for c in cols]] != reference[[FEATURES.index(c) for c in cols]])
             & np.isfinite(x[:,[FEATURES.index(c) for c in cols]]),axis=1) for _,cols in GROUPS])

    try:
        # All historical OOF gates precede the first perturbation fit.
        for fold,(a,b) in enumerate(splits,1):
            guard();weights[b] = horizon_ipcw_weights(work.iloc[b],censor_survival(work.iloc[a]))
            for seed in seeds:
                for arm in arms:
                    key = (fold,seed,arm); counters['model_loads'] += 1
                    model = loader(arm,seed,fold); e = unique_model(model,arm,seed)
                    if e.training_input_sha256_ != bound[key]['training_input_sha256']:
                        raise ValueError('GRAPH_OLD_TRAINING_BINDING_CHANGED')
                    old[key] = model;identities[key] = model_identity(model)
                    raw[seed,arm][b] = calls(model,work.iloc[b]);del model,e
        for arm in arms:
            row = expected_rows[arm]; by_seed = {r['seed']:r for r in row['per_seed']}
            if len(row['per_seed']) != 3 or set(by_seed) != set(seeds):raise ValueError('INVALID_GRAPH_SEED_BASELINES')
            for seed in seeds:
                values = raw[seed,arm]; prior = by_seed[seed]
                if (prediction_hash(values[:,0]) != prior['ad_oof_sha256']
                        or prediction_hash(values[:,1]) != prior['other_oof_sha256']):
                    raise ValueError('ORIGINAL_TWO_CAUSE_SEED_OOF_CHANGED')
                counters['baseline_hashes_verified'] += 2
                if (not np.isfinite(prior['pooled_oof_brier'])
                        or abs(float(np.mean(weights*(target-values[:,0])**2))-prior['pooled_oof_brier']) > TOLERANCE):
                    raise ValueError('ORIGINAL_SEED_BRIER_CHANGED')
                counters['baseline_briers_verified'] += 1
            mean = np.stack([raw[s,arm] for s in seeds]).mean(0)
            if (prediction_hash(mean[:,0]) != row['ad_oof_sha256'] or prediction_hash(mean[:,1]) != row['other_oof_sha256']):
                raise ValueError('ORIGINAL_ENSEMBLE_OOF_CHANGED')
            counters['baseline_hashes_verified'] += 2
            if (not np.isfinite(row['pooled_oof_brier'])
                    or abs(float(np.mean(weights*(target-mean[:,0])**2))-row['pooled_oof_brier']) > TOLERANCE):
                raise ValueError('ORIGINAL_ENSEMBLE_BRIER_CHANGED')
            counters['baseline_briers_verified'] += 1
        if not work.equals(original):raise ValueError('GRAPH_BASELINE_INPUT_MUTATED')
        emit('ALL_24_HASH_12_BRIER_GATES_PASSED')
        for fold,(a,b) in enumerate(splits,1):
            guard();test = work.iloc[b].copy(deep=True); m = len(test)
            old_games = {}; r0 = None; preprocessing0 = None
            for arm in arms:
                for seed in seeds:
                    model = old[fold,seed,arm]; ref = model.named_steps['hazards'].medians_.copy()
                    if r0 is None:r0 = ref.copy()
                    if not np.array_equal(ref,r0):raise ValueError('GRAPH_ORIGINAL_RAW_REFERENCES_NOT_MATCHED')
                    stats = np.r_[model.named_steps['hazards'].mean_,model.named_steps['hazards'].scale_]
                    if preprocessing0 is None:preprocessing0 = stats.copy()
                    if not np.array_equal(stats,preprocessing0):raise ValueError('GRAPH_ORIGINAL_PREPROCESSING_NOT_MATCHED')
                    old_games[seed,arm] = game(model,test,raw[seed,arm][b],r0)
            # Only current-fold rank rows and scalar diagnostic arrays survive a repeat.
            ranks = {}; fold_drifts = {}; fold_l1 = {}; fold_games = {}; reference_active = []
            for replicate in range(1,6):
                subset,_ = subject_subsample(work,a,b,fold=fold,replicate=replicate)
                subset_before = subset.copy(deep=True); rb = None; matched = {}; preprocessing_b = None; training_identity_b = None
                for arm in arms:
                    singles = []; new_cifs = []; seed_drifts = []
                    for seed in seeds:
                        key = (fold,seed,arm); parent = old[key]
                        guard(); before_counts = (budget.attempts,budget.fit_returns,budget.completed)
                        model = fitter(subset,arm,seed,budget=budget)
                        if (budget.attempts,budget.fit_returns,budget.completed) != tuple(x+1 for x in before_counts):
                            raise ValueError('GRAPH_FITTER_ACCOUNTING_MISMATCH')
                        e = unique_model(model,arm,seed)
                        if not subset.equals(subset_before):raise ValueError('GRAPH_SUBSET_MUTATED')
                        signature = (e.initial_state_sha256_,e.batch_schedule_sha256_,e.training_input_sha256_)
                        if seed in matched and signature != matched[seed]:raise ValueError('GRAPH_ARMS_NOT_MATCHED')
                        matched[seed] = signature
                        ref = np.asarray(e.medians_).copy()
                        if rb is None:rb = ref.copy()
                        if not np.array_equal(ref,rb):raise ValueError('GRAPH_SUBSET_RAW_REFERENCES_NOT_MATCHED')
                        stats = np.r_[e.mean_,e.scale_]
                        if preprocessing_b is None:preprocessing_b = stats.copy();training_identity_b = e.training_input_sha256_
                        if not np.array_equal(stats,preprocessing_b) or e.training_input_sha256_ != training_identity_b:
                            raise ValueError('GRAPH_SUBSET_PREPROCESSING_OR_INPUT_NOT_MATCHED')
                        identity = model_identity(model); new = calls(model,test)
                        aa = old_games[seed,arm]
                        bb = game(model,test,new,r0)
                        cc = game(parent,test,raw[seed,arm][b],rb)
                        dd = game(model,test,new,rb)
                        singles.append(np.stack([aa,bb,cc,dd]));new_cifs.append(new)
                        seed_drifts.append(np.abs(new-raw[seed,arm][b]))
                        for oi,order in enumerate(ORDERS):
                            for cause in range(2):
                                keep(('seed_model_L1',arm,seed,replicate,order,cause),np.abs(bb[oi,:,:,cause]-aa[oi,:,:,cause]).sum(1))
                        for cause in range(2):keep(('seed_risk_drift',arm,seed,replicate,cause),seed_drifts[-1][:,cause])
                        if not np.array_equal(calls(model,test),new) or model_identity(model) != identity:
                            raise ValueError('GRAPH_PERTURBED_ENDPOINT_REPLAY_CHANGED')
                        del model,e,parent
                    games,parts,error = _ensemble_games(singles); maximum_identity = max(maximum_identity,error)
                    counters['ensemble_games'] += 4 if replicate == 1 else 3
                    # A was evaluated once and is reused across all five repeats.
                    base_mean = np.stack([raw[s,arm][b] for s in seeds]).mean(0)
                    new_mean = np.stack(new_cifs).mean(0);change = new_mean-base_mean
                    keep(('AD_Brier',arm,replicate),weights[b]*(target[b]-new_mean[:,0])**2)
                    active0 = active_groups(test,r0)
                    fold_games[replicate,arm] = games
                    reference_active.append(active_groups(test,rb))
                    fold_drifts[replicate,arm] = np.abs(change)
                    for cause in range(2):
                        keep(('risk_drift',arm,replicate,cause),np.abs(change[:,cause]))
                        keep(('signed_risk_change',arm,replicate,cause),change[:,cause])
                        for oi,order in enumerate(ORDERS):
                            for part,values in parts.items():keep(('L1',part,arm,replicate,order,cause),np.abs(values[oi,:,:,cause]).sum(1))
                            fold_l1[replicate,arm,order,cause] = np.abs(parts['model'][oi,:,:,cause]).sum(1)
                    del singles,new_cifs,games,parts
                del subset,subset_before
            # The denominator never changes with arm, order or perturbation repeat.
            common = active0 & np.logical_and.reduce(reference_active)
            for r in range(1,6):
                for arm in arms:
                    games = fold_games[r,arm]
                    for cause in range(2):
                        for oi,order in enumerate(ORDERS):
                            for comparison,index in (('model',1),('reference',2),('total',3)):
                                ranks[comparison,cause,r,arm,order] = _rank_rows(
                                    games[0][oi,:,:,cause],games[index][oi,:,:,cause],active0 if comparison=='model' else common)
            for comparison in ('model','reference','total'):
                for cause in range(2):
                    keep(('common_active_groups',comparison,cause),(active0 if comparison=='model' else common).sum(1))
                    matrix = [ranks[comparison,cause,r,arm,o] for r in range(1,6) for arm in arms for o in ORDERS]
                    masks = _fixed_rank_masks(matrix)
                    for metric,mask in masks.items():
                        keep(('rank_common_eligibility',comparison,cause,metric),mask.astype(float))
                        for r in range(1,6):
                            for arm in arms:
                                for order in ORDERS:
                                    row = ranks[comparison,cause,r,arm,order]
                                    keep(('rank',comparison,cause,metric,r,arm,order),row[metric][mask])
                                    if metric=='spearman':
                                        keep(('sign_eligible_groups',comparison,cause,r,arm,order),row['sign_eligible'])
                                        keep(('sign_flip_groups',comparison,cause,r,arm,order),row['sign_flips'])
                                    for flag in ('insufficient','constant','top1_tie','top2_boundary_tie'):
                                        key=(comparison,cause,r,arm,order,flag)
                                        # Once per row, irrespective of metric.
                                        if metric=='spearman':rank_diagnostics[key] = rank_diagnostics.get(key,0)+int(row[flag].sum())
                            for left,right in spec['primary_comparisons']:
                                for order in ORDERS:
                                    keep(('paired_rank',comparison,cause,metric,r,left,right,order),
                                         ranks[comparison,cause,r,left,order][metric][mask]-ranks[comparison,cause,r,right,order][metric][mask])
            for r in range(1,6):
                for left,right in spec['primary_comparisons']:
                    for cause in range(2):
                        keep(('paired_risk_drift',r,left,right,cause),fold_drifts[r,left][:,cause]-fold_drifts[r,right][:,cause])
                        for order in ORDERS:keep(('paired_model_L1',r,left,right,order,cause),
                            fold_l1[r,left,order,cause]-fold_l1[r,right,order,cause])
            for seed in seeds:
                for arm in arms:
                    key=(fold,seed,arm);model=old.pop(key)
                    if model_identity(model) != identities[key] or not np.array_equal(calls(model,test),raw[seed,arm][b]):
                        raise ValueError('GRAPH_ORIGINAL_ENDPOINT_REPLAY_CHANGED')
                    del model
            del old_games,ranks,fold_drifts,fold_l1,fold_games,reference_active,matrix,games
            counters['folds_completed'] += 1;emit('FOLD_COMPLETE')
        guard()
        if (not work.equals(original) or input_fingerprint(frame,'multimodal_values') != expected_input_fingerprint):
            raise ValueError('GRAPH_PERTURBATION_INPUT_MUTATED')
        required = (225,225,225,12780,12780,720,240,45)
        actual = (budget.attempts,budget.fit_returns,budget.completed,calls.count,calls.completed,
                  counters['single_model_games'],counters['ensemble_games'],counters['model_loads'])
        if actual != required or old or calls.state_rows_completed != 2556*len(work):
            raise ValueError('GRAPH_PERTURBATION_MATRIX_INCOMPLETE')
        rows = []
        for key,values in sorted(distributions.items()):
            pooled = np.concatenate(values);row = dict(key=list(key),distribution=_finite_distribution(pooled))
            if key[0]=='rank_common_eligibility':
                row.update(subjects=len(work),eligible_subjects=int(pooled.sum()),undefined_subjects=int(len(work)-pooled.sum()))
            elif key[0] in ('rank','paired_rank'):
                row.update(subjects=len(work),eligible_subjects=len(pooled),undefined_subjects=len(work)-len(pooled))
            elif key[0] in ('sign_eligible_groups','sign_flip_groups'):row['total_groups']=int(pooled.sum())
            rows.append(row)
        lookup = {tuple(row['key']):row['distribution'] for row in rows}
        prediction_lower = all(lookup['paired_risk_drift',r,left,right,0]['mean'] < 0
            for r in range(1,6) for left,right in spec['primary_comparisons'])
        explanation_lower = all(lookup['paired_model_L1',r,left,right,order,0]['mean'] < 0
            for r in range(1,6) for left,right in spec['primary_comparisons'] for order in ORDERS)
        emit('COMPLETE')
        return dict(status='CONSTRUCTED_GRAPH_PERTURBATION_MATRIX_COMPLETE' if constructed else 'FIXED_GRAPH_PERTURBATION_COMPLETE',
            constructed=constructed,subjects=len(work),outer_folds=5,replicates=5,protocol_sha256=protocol_hash,
            **counters,fit_attempts=budget.attempts,fit_returns=budget.fit_returns,fits_completed=budget.completed,
            original_AD_briers=[dict(arm=arm,ensemble=expected_rows[arm]['pooled_oof_brier'],
                per_seed=[dict(seed=r['seed'],brier=r['pooled_oof_brier']) for r in expected_rows[arm]['per_seed']]) for arm in arms],
            predictive_fits=None if constructed else budget.completed,calibration_fits=0,
            cif_attempts=calls.count,cif_completed=calls.completed,cif_state_rows_completed=calls.state_rows_completed,
            cif_state_rows_attempted=calls.state_rows_attempted,internal_month_rows=24*calls.state_rows_completed,
            signed_decomposition_max_error=maximum_identity,reconstruction_max_error=maximum_reconstruction,
            distributions=rows,rank_diagnostics=[dict(key=list(k),subjects=v) for k,v in sorted(rank_diagnostics.items())],
            empirical_AD_prediction_drift_lower_in_all_fixed_comparisons=prediction_lower,
            empirical_AD_model_contribution_L1_lower_in_all_fixed_comparisons_and_orders=explanation_lower,
            elapsed_seconds=time.perf_counter()-started,total_fit_seconds=budget.total_fit_seconds,
            peak_process_memory_bytes=peak_memory_bytes(),participant_predictions_saved=False,
            participant_explanations_saved=False,models_saved=False,clinical_use=False,
            individual_causal_effect_identified=False,default_model_changed=False,
            limitations=['Fixed internal deletion perturbations; not population confidence intervals or bootstrap uncertainty.',
                'Seed CIFs and signed contributions are averaged before drift, L1, ranks and squared error.',
                'Ranking denominators intersect all three arms, both orders and five repeats; ties remain undefined.',
                'Predictive stability does not imply accuracy, graph superiority or causal effects.',
                'Both two-order games remain reference dependent; five competing events limit other-cause interpretation.',
                'Constructed acceptance does not establish real graph retraining results.'])
    except BaseException:
        budget.failed = True;calls.failed = True
        try:emit('FAILED_NO_RETRY')
        except BaseException:pass
        raise
    finally:
        old.clear()
