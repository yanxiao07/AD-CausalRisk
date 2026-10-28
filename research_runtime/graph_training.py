"""Fixed graph comparison on a caller-qualified in-memory cohort.

Reuses the existing split checks, fit journal, person-month/CIF and IPCW metrics.
No data reader, original-model loader, calibration, search or deployment.
"""
from datetime import datetime, timezone
from importlib.metadata import version
import hashlib
import json
from pathlib import Path
import time
import warnings

import numpy as np
from .discrete import person_period, predict_cif, validate_contract
from .evaluation import censor_survival, horizon_ipcw_weights, horizon_metrics, calibration_description
from .graph import protocol, arm_edges
from .graph_native import (FEATURES, PACKAGES, make_graph_estimator, save_graph, load_graph,
                           state_digest)
from .graph_acceptance import peak_memory_bytes
from .training import Journal, checked_design, input_fingerprint, prediction_hash, sha, write_json


class GraphJournal(Journal):
    def __init__(self, folder):
        self.limits = dict(predictive=54, calibration=0)
        self.counts = dict.fromkeys(self.limits, 0)
        self.stream = (folder/'Fit_Ledger.jsonl').open('x', encoding='utf-8')


class GraphFitBudget:
    """In-memory attempts/returns/accepted fits; a failure latches the budget.

    Time/memory are checked before and after each fit, not a process watchdog.
    The real-study host must supply independent counters and hard supervision.
    """
    def __init__(self, limit, *, max_fit_seconds=300., max_total_seconds=14400.,
                 max_memory_bytes=2147483648, evaluation_event=None):
        if (type(limit) is not int or not 0 <= limit <= 225
                or any(type(v) not in (int,float) or not np.isfinite(v) or v <= 0
                       for v in (max_fit_seconds,max_total_seconds))
                or type(max_memory_bytes) is not int or max_memory_bytes <= 0
                or (evaluation_event is not None and not callable(evaluation_event))):
            raise ValueError('INVALID_GRAPH_FIT_BUDGET')
        self.limit = limit; self.max_fit_seconds = float(max_fit_seconds)
        self.max_total_seconds = float(max_total_seconds); self.max_memory_bytes = max_memory_bytes
        self.evaluation_event = evaluation_event
        self.attempts = self.fit_returns = self.completed = 0
        self.total_fit_seconds = 0.; self.failed = False

    def event(self, phase, arm, seed):
        if self.evaluation_event is not None:
            self.evaluation_event(dict(phase=phase, arm=arm, seed=seed, fit_attempts=self.attempts,
                fit_returns=self.fit_returns, fits_completed=self.completed, total_fit_seconds=self.total_fit_seconds))

    def reserve(self):
        if self.failed:raise ValueError('GRAPH_FIT_FAILED_NO_RETRY')
        if self.attempts >= self.limit:raise ValueError('GRAPH_FIT_BUDGET_EXCEEDED')
        if self.total_fit_seconds >= self.max_total_seconds or peak_memory_bytes() > self.max_memory_bytes:
            raise ValueError('GRAPH_FIT_RESOURCE_LIMIT')


def fit_graph_subjects(frame, arm, seed, *, budget):
    """Fit one original graph pipeline on caller-qualified training subjects.

    No test/fold selection, calibration, parameter saving or final-model fit.
    The caller isolates training subjects and verifies paired arm/seed schedules.
    Imputation/scaling are still performed only by the unchanged native fit.
    """
    import pandas as pd
    from sklearn.preprocessing._data import _is_constant_feature
    spec, protocol_hash = protocol()
    if (not isinstance(budget, GraphFitBudget) or arm not in spec['arms']
            or type(seed) is not int or seed not in spec['future_initialization_seeds']
            or not isinstance(frame,pd.DataFrame) or not frame.columns.is_unique):
        raise ValueError('GRAPH_FIT_SCOPE_REQUIRED')
    budget.reserve()
    snapshot = frame.copy(deep=True)
    work = validate_contract(frame, list(FEATURES)).reset_index(drop=True)
    if (set(work.event_type) != {0,1,2} or np.isinf(work[list(FEATURES)].to_numpy(float)).any()
            or not np.array_equal(work[FEATURES[8]].isna(), work[FEATURES[9]].isna())):
        raise ValueError('GRAPH_FIT_CLASSES_VALUES_OR_BILATERAL_MASK')
    periods, labels = person_period(work, list(FEATURES)); periods_before = periods.copy(deep=True)
    labels_before = labels.copy(); budget.attempts += 1
    started = time.perf_counter(); measured = False
    try:
        budget.event('FIT_ATTEMPT',arm,seed)
        model = make_graph_estimator(arm,seed).fit(periods,labels)
        budget.fit_returns += 1
        elapsed = time.perf_counter()-started; budget.total_fit_seconds += elapsed; measured = True
        budget.event('FIT_RETURN',arm,seed)
        e = model.named_steps['hazards']
        if (not frame.equals(snapshot) or not periods.equals(periods_before) or not np.array_equal(labels,labels_before)
                or e.arm != arm or e.seed != seed or e.protocol_sha256_ != protocol_hash
                or e.network_.arm != arm or tuple(e.network_.edges) != arm_edges(arm)
                or not np.array_equal(e.classes_,[0,1,2])):
            raise ValueError('GRAPH_FIT_INPUT_OR_MODEL_SCOPE_CHANGED')
        values = periods[list(FEATURES)].to_numpy(float)
        with warnings.catch_warnings():
            warnings.simplefilter('ignore',RuntimeWarning)
            medians = np.nan_to_num(np.nanmedian(values,axis=0),nan=0.)
        imputed = np.where(np.isnan(values),medians,values)
        expected_mean = imputed.mean(0); variance = imputed.var(0)
        expected_scale = np.sqrt(variance)
        expected_scale[_is_constant_feature(variance,expected_mean,len(imputed))] = 1
        if (any(np.shape(x)!=(10,) or not np.isfinite(x).all() for x in (e.medians_,e.mean_,e.scale_))
                or (e.scale_<=0).any() or not np.array_equal(e.medians_,medians)
                or not np.allclose(e.mean_,expected_mean,rtol=1e-12,atol=1e-12)
                or not np.allclose(e.scale_,expected_scale,rtol=1e-12,atol=1e-12)):
            raise ValueError('GRAPH_FIT_TRAINING_PREPROCESSING_MISMATCH')
        if len(e.loss_history_) != spec['future_optimizer']['epochs'] or not np.isfinite(e.loss_history_).all():
            raise ValueError('GRAPH_FIT_INCOMPLETE_OPTIMIZATION')
        if (elapsed > budget.max_fit_seconds or budget.total_fit_seconds > budget.max_total_seconds
                or peak_memory_bytes() > budget.max_memory_bytes):
            raise ValueError('GRAPH_FIT_RESOURCE_LIMIT')
        budget.completed += 1; budget.event('FIT_COMPLETE',arm,seed)
        return model
    except BaseException:
        if not measured:budget.total_fit_seconds += time.perf_counter()-started
        budget.failed = True
        raise


def conditional_interval(delta):
    """Same subject-resampling kernel as paired_interval, frozen 97.5% quantiles.

The original 95% helper stays unchanged. No models or KM curves are refitted.
"""
    delta = np.asarray(delta, dtype=float)
    if delta.ndim != 1 or len(delta) < 2 or not np.isfinite(delta).all():
        raise ValueError('INVALID_GRAPH_PAIRED_DIFFERENCES')
    settings = protocol()[0]['conditional_intervals']
    rng = np.random.default_rng(settings['seed'])
    means = np.empty(settings['subject_resamples'])
    for i in range(len(means)):
        means[i] = delta[rng.integers(0, len(delta), len(delta))].mean()
    return np.quantile(means, settings['quantiles']).tolist()


def checked_cif(model, frame):
    estimator = model.named_steps['hazards']
    before = state_digest(estimator.network_)
    stats = np.r_[estimator.medians_, estimator.mean_, estimator.scale_].copy()
    result = predict_cif(model, frame, list(FEATURES)).iloc[:, 1:].to_numpy()
    if (state_digest(estimator.network_) != before
            or not np.array_equal(stats, np.r_[estimator.medians_, estimator.mean_, estimator.scale_])):
        raise ValueError('GRAPH_PREDICT_MUTATED_MODEL')
    if (result.shape != (len(frame), 2) or not np.isfinite(result).all()
            or (result < 0).any() or (result.sum(1) > 1+1e-12).any()):
        raise ValueError('INVALID_GRAPH_TWO_CAUSE_CIF')
    return result


def summarize(work, splits, predictions):
    """Aggregate only: no participant arrays written; no recalibration fits."""
    spec, _ = protocol(); target = work.event_type.eq(1).to_numpy(float)
    weights = np.full(len(work), np.nan); curves = []
    for a, b in splits:
        g = censor_survival(work.iloc[a]); curves.append(g)
        weights[b] = horizon_ipcw_weights(work.iloc[b], g)
    rows = []; errors = {}; per_seed_errors = {}; fold_rows = []
    for arm in spec['arms']:
        raw = np.stack([predictions[seed, arm] for seed in spec['future_initialization_seeds']])
        if raw.shape != (3, len(work), 2) or not np.isfinite(raw).all():
            raise ValueError('INCOMPLETE_GRAPH_OOF')
        mean = raw.mean(0); errors[arm] = weights * (target-mean[:, 0])**2
        metrics = []; seeds = []
        for i, ((a, b), g) in enumerate(zip(splits, curves), 1):
            row = horizon_metrics(work.iloc[b], mean[b, 0], g); metrics.append(row)
            fold_rows.append(dict(fold=i, arm=arm, subjects=len(b), metrics=row))
        pooled = float(errors[arm].mean())
        if abs(pooled-sum(len(b)*m['ipcw_brier_24m'] for (_, b), m in zip(splits, metrics))/len(work)) > 1e-12:
            raise ValueError('GRAPH_POOLED_BRIER_DENOMINATOR_MISMATCH')
        for index, seed in enumerate(spec['future_initialization_seeds']):
            values = raw[index]
            error = weights*(target-values[:, 0])**2; per_seed_errors[seed, arm] = error
            fold_metrics = [horizon_metrics(work.iloc[b], values[b, 0], g) for (_, b), g in zip(splits, curves)]
            seeds.append(dict(seed=seed, pooled_oof_brier=float(error.mean()),
                              fold_weighted_mean_metrics={key:float(sum(len(b)*m[key] for (_, b), m in zip(splits, fold_metrics))/len(work)) for key in fold_metrics[0]},
                              ad_oof_sha256=prediction_hash(values[:, 0]), other_oof_sha256=prediction_hash(values[:, 1])))
        rows.append(dict(arm=arm, seed_aggregation='equal-weight mean of three two-cause CIFs',
                         pooled_oof_brier=pooled,
                         fold_weighted_mean_metrics={key:float(sum(len(b)*m[key] for (_, b), m in zip(splits, metrics))/len(work)) for key in metrics[0]},
                         ad_oof_sha256=prediction_hash(mean[:, 0]), other_oof_sha256=prediction_hash(mean[:, 1]),
                         calibration_description=calibration_description(work, mean[:, 0], weights),
                         per_seed=seeds, two_cause_max_sum=float(mean.sum(1).max()),
                         missing_subgroups=[dict(name=name, subjects=int(mask.sum()),
                              brier=float(errors[arm][mask].mean()) if mask.any() else None,
                              mean_ad_cif=float(mean[mask, 0].mean()) if mask.any() else None)
                             for name, mask in subgroup_masks(work).items()]))
    contrasts = []
    for left, right in spec['primary_comparisons']:
        delta = errors[left]-errors[right]; interval = conditional_interval(delta)
        seed_deltas = [dict(seed=seed, brier_difference=float((per_seed_errors[seed, left]-per_seed_errors[seed, right]).mean()))
                       for seed in spec['future_initialization_seeds']]
        contrasts.append(dict(contrast=f'{left}_minus_{right}', brier_difference=float(delta.mean()),
                              conditional_97_5_interval=interval, per_seed=seed_deltas,
                              all_seed_point_differences_negative=all(row['brier_difference'] < 0 for row in seed_deltas)))
    support = all(row['conditional_97_5_interval'][1] < 0 and row['all_seed_point_differences_negative'] for row in contrasts)
    return dict(models=rows, contrasts=contrasts, fold_results=fold_rows,
                fixed_internal_prior_increment_supported=support,
                conclusion='FIXED_INTERNAL_PRIOR_INCREMENT_SUPPORTED' if support else 'PRIOR_INCREMENT_NOT_ESTABLISHED',
                interval_scope='Fixed realized models/folds/train-KM; conditional subject resampling only; excludes refit and development selection uncertainty')


def subgroup_masks(work):
    apoe = work['apoe4_count'].notna().to_numpy()
    mri = work[['mri_left_hippocampus_volume', 'mri_right_hippocampus_volume']].notna().all(axis=1).to_numpy()
    return dict(both=apoe & mri, apoe_only=apoe & ~mri, mri_only=~apoe & mri, neither=~apoe & ~mri)


def run_graph_comparison(frame, output, *, splits, input_bindings, research_scope):
    work, splits, _ = checked_design(frame, 'multimodal_values', splits)
    spec, protocol_hash = protocol()
    folder = Path(output); folder.mkdir(parents=True, exist_ok=False)
    journal = GraphJournal(folder)
    write_json(folder/'Folds.private.json', dict(outer=[dict(train_positions=a.tolist(), test_positions=b.tolist()) for a, b in splits]))
    base = Path(__file__).parent
    plan = dict(schemaVersion='fixed-graph-comparison-v1', subjects=len(work), features=list(FEATURES),
                arms=spec['arms'], initialization_seeds=spec['future_initialization_seeds'],
                fit_order=spec['future_training_order'], optimizer=spec['future_optimizer'],
                limits=journal.limits, expected_outer_models=45, expected_final_models=9,
                input_fingerprint=input_fingerprint(work, 'multimodal_values'), input_bindings=input_bindings,
                folds_sha256=sha(folder/'Folds.private.json'), protocol_sha256=protocol_hash,
                source_sha256={name:sha(base/name) for name in ('graph_training.py', 'graph_native.py', 'graph.py',
                    'graph_acceptance.py', 'training.py', 'evaluation.py', 'discrete.py', 'estimators.py')},
                software={p:version(p) for p in PACKAGES}, lock_sha256=sha(base/'requirements.graph.lock'),
                primary_comparisons=spec['primary_comparisons'], conditional_intervals=spec['conditional_intervals'],
                max_total_fit_seconds=7200, max_fit_seconds_each=300, max_peak_memory_bytes=2147483648,
                max_artifact_bytes_each=1000000, research_scope=research_scope,
                clinical_use=False, external_validation=False, historical_realtime_certified=False,
                individual_causal_effect_identified=False, default_model_changed=False,
                created_utc=datetime.now(timezone.utc).isoformat())
    write_json(folder/'Comparison_Plan.json', plan); plan_sha = sha(folder/'Comparison_Plan.json')
    predictions = {(s, arm):np.full((len(work), 2), np.nan) for s in spec['future_initialization_seeds'] for arm in spec['arms']}
    artifacts = []; rows = []; total_fit = 0.
    def fit_and_save(train, evaluation, seed, arm, fold):
        nonlocal total_fit
        if sha(folder/'Comparison_Plan.json') != plan_sha or protocol()[1] != protocol_hash:
            raise ValueError('GRAPH_PLAN_OR_PROTOCOL_CHANGED')
        if any(sha(base/name) != digest for name, digest in plan['source_sha256'].items()):
            raise ValueError('GRAPH_SOURCE_CHANGED')
        periods, y = person_period(train, list(FEATURES))
        journal.attempt('predictive', arm=arm, seed=seed, outer=fold,
                        stage='final' if fold is None else 'outer', subjects=len(train))
        model = make_graph_estimator(arm, seed).fit(periods, y)
        estimator = model.named_steps['hazards']; total_fit += estimator.fit_seconds_
        if (estimator.fit_seconds_ > plan['max_fit_seconds_each'] or total_fit > plan['max_total_fit_seconds']
                or peak_memory_bytes() > plan['max_peak_memory_bytes']):
            raise ValueError('GRAPH_RESOURCE_BUDGET_EXCEEDED')
        values = periods[list(FEATURES)].to_numpy(float)
        with np.errstate(invalid='ignore'):
            import warnings
            with warnings.catch_warnings():
                warnings.simplefilter('ignore', RuntimeWarning)
                medians = np.nan_to_num(np.nanmedian(values, axis=0), nan=0.)
        if not np.array_equal(estimator.medians_, medians):
            raise ValueError('GRAPH_TRAINING_IMPUTATION_MISMATCH')
        imputed = np.where(np.isnan(values), medians, values)
        if not np.allclose(estimator.mean_, imputed.mean(0), rtol=0, atol=1e-10):
            raise ValueError('GRAPH_TRAINING_STANDARDIZATION_MISMATCH')
        expected_scale = imputed.std(0); expected_scale[expected_scale == 0] = 1
        if not np.allclose(estimator.scale_, expected_scale, rtol=1e-12, atol=1e-12):
            raise ValueError('GRAPH_TRAINING_SCALE_MISMATCH')
        if not np.isfinite(estimator.loss_history_).all() or len(estimator.loss_history_) != 40:
            raise ValueError('GRAPH_OPTIMIZATION_INCOMPLETE')
        cif = checked_cif(model, evaluation)
        scope = hashlib.sha256(json.dumps(dict(plan=plan_sha, fold=fold, seed=seed), sort_keys=True).encode()).hexdigest()
        path = folder/((f'outer_{fold}' if fold is not None else 'final')+f'__{seed}__{arm}.npz')
        artifact = save_graph(model, path, scope_identity=scope)
        if path.stat().st_size > plan['max_artifact_bytes_each']:
            raise ValueError('GRAPH_ARCHIVE_TOO_LARGE')
        restored = load_graph(path, artifact['sha256'], expected_arm=arm, expected_scope_identity=scope,
                              expected_training_input_sha256=estimator.training_input_sha256_)
        error = float(np.abs(cif-checked_cif(restored, evaluation)).max())
        if error != 0 or sha(path) != artifact['sha256']:
            raise ValueError('GRAPH_SAVED_MODEL_REPLAY_MISMATCH')
        artifact.update(outer_fold=fold, role='GRAPH_FINAL' if fold is None else 'GRAPH_OUTER_VALIDATION',
                        comparison_plan_sha256=plan_sha, replay_max_abs_error=error)
        artifacts.append(artifact)
        row = dict(attempt=journal.counts['predictive'], arm=arm, seed=seed, outer_fold=fold,
                   training_subjects=len(train), training_person_months=len(periods), fit_seconds=estimator.fit_seconds_,
                   initial_state_sha256=estimator.initial_state_sha256_, batch_schedule_sha256=estimator.batch_schedule_sha256_,
                   training_input_sha256=estimator.training_input_sha256_,
                   first_epoch_loss=estimator.loss_history_[0], last_epoch_loss=estimator.loss_history_[-1],
                   loss_history=estimator.loss_history_,
                   replay_max_abs_error=error)
        rows.append(row)
        write_json(folder/f'Fit_{journal.counts["predictive"]:02d}_Checkpoint.json', row)
        print(json.dumps(dict(completed_graph_fits=journal.counts['predictive'], outer_fold=fold, seed=seed,
                              arm=arm, fit_seconds=estimator.fit_seconds_)), flush=True)
        return cif
    def check_triplet():
        for key in ('initial_state_sha256', 'batch_schedule_sha256', 'training_input_sha256'):
            if len({row[key] for row in rows[-3:]}) != 1:
                raise ValueError('GRAPH_ARMS_NOT_MATCHED:'+key)
    try:
        for fold, (a, b) in enumerate(splits, 1):
            train, test = work.iloc[a], work.iloc[b]
            for seed in spec['future_initialization_seeds']:
                for arm in spec['arms']:
                    predictions[seed, arm][b] = fit_and_save(train, test, seed, arm, fold)
                check_triplet()
        aggregate = summarize(work, splits, predictions)
        for seed in spec['future_initialization_seeds']:
            for arm in spec['arms']:
                fit_and_save(work, work, seed, arm, None)
            check_triplet()
        if journal.counts != journal.limits or len(artifacts) != 54:
            raise ValueError('INCOMPLETE_GRAPH_MODEL_DELIVERY')
        manifest = dict(schemaVersion='fixed-graph-model-manifest-v1', subjects=len(work), features=list(FEATURES),
                        protocol_sha256=protocol_hash, comparison_plan_sha256=plan_sha,
                        models=artifacts, software=plan['software'], calibrated=False,
                        clinical_use=False, default_model_changed=False)
        write_json(folder/'Model_Manifest.json', manifest)
        result = dict(status='FIXED_GRAPH_COMPARISON_AND_DELIVERY_COMPLETE', subjects=len(work), outer_folds=5,
                      schemaVersion='fixed-graph-comparison-v1', fits=journal.counts, **aggregate,
                      comparison_plan_sha256=plan_sha, protocol_sha256=protocol_hash,
                      software=plan['software'], total_fit_seconds=total_fit,
                      peak_process_memory_bytes=peak_memory_bytes(), model_count=54, saved_model_replay_max_abs_error=0.,
                      raw_table_parses=0, old_model_loads=0, external_validation=False, clinical_use=False,
                      individual_causal_effect_identified=False, default_model_changed=False,
                      participant_predictions_saved=False, participant_graphs_saved=False,
                      limitations=['Retrospective stored snapshot; MRI processing after index for 275/276.',
                         'Internally developed cohort and protocol, not independent or prospective validation.',
                         'Historical prior directions unverified; only 6/10 nodes have cross routes.',
                         'Four nodes self/readout only; attention and graph contrast are not causal effects.',
                         'Fixed 40 epochs, no convergence guarantee; three seeds and one semantic permutation.',
                         'Only five competing events; cannot certify other-dementia accuracy.',
                         'Intervals exclude retraining, source, selection and independent-validation uncertainty.'])
        write_json(folder/'Comparison_Result.json', result)
        write_json(folder/'Run_State.json', dict(status='COMPLETE', fits=journal.counts))
        return result
    except Exception as error:
        write_json(folder/'Run_State.json', dict(status='FAILED', attempted_fits=journal.counts, error_type=type(error).__name__))
        raise
    finally:
        journal.close()
