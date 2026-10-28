"""Bounded artificial-data acceptance, never a real-cohort training runner.

Exactly three seeds x three arms. Reuses the existing subject split validation,
person-month expansion, CIF and metric functions. No calibration/parameter search.
"""
import argparse
import hashlib
from importlib.metadata import version
import json
from pathlib import Path
import time
from functools import lru_cache

import numpy as np
from .discrete import person_period, predict_cif
from .evaluation import censor_survival, horizon_metrics
from .graph import protocol
from .graph_native import FEATURES, PACKAGES, make_graph_estimator, save_graph, load_graph, digest, state_digest
from .training import checked_design, synthetic_cohort, input_fingerprint, write_json


@lru_cache(maxsize=1)
def _process_memory_counter_type():
    """ctypes caches POINTER types: do not create a new structure per poll."""
    import ctypes
    from ctypes import wintypes
    class Counters(ctypes.Structure):
        _fields_ = [('cb', wintypes.DWORD), ('PageFaultCount', wintypes.DWORD)] + [
            (name, ctypes.c_size_t) for name in ('PeakWorkingSetSize', 'WorkingSetSize',
            'QuotaPeakPagedPoolUsage', 'QuotaPagedPoolUsage', 'QuotaPeakNonPagedPoolUsage',
            'QuotaNonPagedPoolUsage', 'PagefileUsage', 'PeakPagefileUsage')]
    return Counters


def peak_memory_bytes():
    import os
    if os.name == 'nt':
        import ctypes
        from ctypes import wintypes
        Counters = _process_memory_counter_type()
        value = Counters(); value.cb = ctypes.sizeof(value)
        current = ctypes.windll.kernel32.GetCurrentProcess
        current.restype = wintypes.HANDLE
        get_info = ctypes.windll.psapi.GetProcessMemoryInfo
        get_info.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
        if not get_info(current(), ctypes.byref(value), value.cb):
            raise OSError('CANNOT_MEASURE_PEAK_MEMORY')
        return int(value.PeakWorkingSetSize)
    import resource, sys
    return int(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * (1 if sys.platform == 'darwin' else 1024))


def run(output):
    output = Path(output); output.mkdir(parents=True, exist_ok=False)
    spec, protocol_hash = protocol()
    work, folds, _ = checked_design(synthetic_cohort(), 'multimodal_values')
    a, b = folds[0]  # One constructed split, not five-fold scientific evaluation.
    train, test = work.iloc[a], work.iloc[b]
    assert not set(train.subject_id) & set(test.subject_id)
    periods, labels = person_period(train, list(FEATURES))
    fingerprint = input_fingerprint(work, 'multimodal_values')
    scope_identity = hashlib.sha256(json.dumps(dict(cohort=fingerprint, train=a.tolist(), test=b.tolist()),
                                               sort_keys=True).encode()).hexdigest()
    plan = dict(scope='ARTIFICIAL_DATA_ONLY', protocol_sha256=protocol_hash,
                input_fingerprint=fingerprint, subjects=len(work), training_subjects=len(train),
                test_subjects=len(test), training_person_months=len(periods),
                train_positions=a.tolist(), test_positions=b.tolist(),
                fit_limits=dict(constructed_predictive=9, real_predictive=0, calibration=0),
                epochs=40, max_total_fit_seconds=300, max_peak_memory_bytes=2147483648,
                max_archive_bytes_each=1000000,
                software={p: version(p) for p in PACKAGES},
                initialization_seeds=spec['future_initialization_seeds'], arms=spec['arms'])
    write_json(output/'Acceptance_Plan.json', plan)
    g = censor_survival(train)
    rows = []; archives = []; total_fit_seconds = 0
    with (output/'Fit_Ledger.jsonl').open('x', encoding='utf-8') as ledger:
        for seed in spec['future_initialization_seeds']:
            initial = set(); batches = set(); inputs = set()
            for arm in spec['arms']:
                if len(rows) >= 9 or total_fit_seconds > plan['max_total_fit_seconds']:
                    raise RuntimeError('CONSTRUCTED_FIT_BUDGET_EXCEEDED')
                ledger.write(json.dumps(dict(attempt=len(rows)+1, seed=seed, arm=arm,
                                             data='ARTIFICIAL_DATA_ONLY'))+'\n'); ledger.flush()
                model = make_graph_estimator(arm, seed).fit(periods, labels)
                estimator = model.named_steps['hazards']
                assert state_digest(estimator.network_) != estimator.initial_state_sha256_
                total_fit_seconds += estimator.fit_seconds_
                if total_fit_seconds > plan['max_total_fit_seconds']:
                    raise RuntimeError('CONSTRUCTED_TIME_BUDGET_EXCEEDED')
                if peak_memory_bytes() > plan['max_peak_memory_bytes']:
                    raise RuntimeError('CONSTRUCTED_MEMORY_BUDGET_EXCEEDED')
                initial.add(estimator.initial_state_sha256_); batches.add(estimator.batch_schedule_sha256_)
                inputs.add(estimator.training_input_sha256_)
                prediction = predict_cif(model, test, list(FEATURES))
                path = output/f'{seed}__{arm}.npz'
                manifest = save_graph(model, path, scope_identity=scope_identity); archives.append(manifest)
                restored = load_graph(path, manifest['sha256'], expected_arm=arm,
                                      expected_scope_identity=scope_identity,
                                      expected_training_input_sha256=estimator.training_input_sha256_)
                replay = predict_cif(restored, test, list(FEATURES))
                error = float(np.abs(prediction.iloc[:, 1:].to_numpy()-replay.iloc[:, 1:].to_numpy()).max())
                assert error == 0 and path.stat().st_size <= plan['max_archive_bytes_each']
                for bad in (dict(expected_arm='unknown', expected_training_input_sha256=estimator.training_input_sha256_),
                            dict(expected_arm=arm, expected_training_input_sha256='0'*64)):
                    try: load_graph(path, manifest['sha256'], expected_scope_identity=scope_identity, **bad)
                    except ValueError: pass
                    else: raise AssertionError('WRONG_IDENTITY_ACCEPTED')
                try: load_graph(path, '0'*64, expected_arm=arm, expected_scope_identity=scope_identity,
                                expected_training_input_sha256=estimator.training_input_sha256_)
                except ValueError: pass
                else: raise AssertionError('WRONG_HASH_ACCEPTED')
                try: load_graph(path, manifest['sha256'], expected_arm=arm, expected_scope_identity='0'*64,
                                expected_training_input_sha256=estimator.training_input_sha256_)
                except ValueError: pass
                else: raise AssertionError('WRONG_COHORT_SPLIT_ACCEPTED')
                # Prediction of extreme held-out values cannot alter training statistics.
                before = np.r_[estimator.medians_, estimator.mean_, estimator.scale_].copy()
                extreme = test.copy(); extreme.loc[:, list(FEATURES)] = 1e6
                predict_cif(model, extreme, list(FEATURES))
                np.testing.assert_array_equal(before, np.r_[estimator.medians_, estimator.mean_, estimator.scale_])
                imputed = np.where(np.isnan(periods[list(FEATURES)].to_numpy()), estimator.medians_, periods[list(FEATURES)])
                np.testing.assert_allclose(estimator.mean_, np.asarray(imputed).mean(0), atol=1e-12)
                np.testing.assert_allclose(estimator.medians_, np.nanmedian(periods[list(FEATURES)].to_numpy(), axis=0), atol=1e-12)
                assert np.isfinite(estimator.loss_history_).all() and len(estimator.loss_history_) == 40
                rows.append(dict(arm=arm, seed=seed, fit_seconds=estimator.fit_seconds_,
                                 initial_state_sha256=estimator.initial_state_sha256_,
                                 batch_schedule_sha256=estimator.batch_schedule_sha256_,
                                 training_input_sha256=estimator.training_input_sha256_,
                                 archive_bytes=path.stat().st_size, replay_max_abs_error=error,
                                 first_epoch_loss=estimator.loss_history_[0], last_epoch_loss=estimator.loss_history_[-1],
                                 synthetic_metrics=horizon_metrics(test, prediction.ad_cif_24m.to_numpy(), g)))
            assert len(initial) == len(batches) == len(inputs) == 1
    result = dict(status='NATIVE_GRAPH_CONSTRUCTED_ACCEPTANCE_COMPLETE',
                  constructed_predictive_fits=len(rows), real_predictive_fits=0, calibration_fits=0,
                  clinical_table_parses=0, old_research_model_loads=0,
                  native_training_implemented=True, whole_research_complete=False,
                  protocol_sha256=protocol_hash, input_fingerprint=fingerprint,
                  source_sha256=digest(Path(__file__).with_name('graph_native.py')),
                  total_fit_seconds=total_fit_seconds, peak_process_memory_bytes=peak_memory_bytes(),
                  save_load_cif_max_abs_error=max(r['replay_max_abs_error'] for r in rows),
                  same_initialization_and_batch_schedule_per_seed=True, subject_isolation=True,
                  training_only_preprocessing=True, identity_negative_checks=36,
                  graph_gain_established=False, individual_causal_effect_identified=False,
                  clinical_use=False, default_model_changed=False, rows=rows, archives=archives)
    write_json(output/'Acceptance_Result.json', result)
    print(json.dumps({k:result[k] for k in ('status', 'constructed_predictive_fits', 'real_predictive_fits',
                                           'total_fit_seconds', 'save_load_cif_max_abs_error')}))
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    run(parser.parse_args().output)
