"""Artificial tests of composition, not a repeat of closed arithmetic suites."""
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch
import json
import os
import sys
import unittest

import numpy as np

if __name__ == '__main__':
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from research_runtime import lifecycle_monitor as m

d, p = m.drift, m.performance
ROOT = Path(__file__).resolve().parents[4]
ACCOUNTING = dict(feature_calls=0, feature_returns=0, performance_calls=0, performance_returns=0,
                  psi_calls=0, psi_returns=0, summary_calls=0, summary_returns=0,
                  pair_calls=0, pair_returns=0, protected_io_attempts=0)
_active = False
_initial_modules = set()
_originals = ((d, 'evaluate_feature_drift', 'feature'), (p, 'evaluate_performance_monitor', 'performance'),
              (d, '_psi_counts', 'psi'), (p, '_summary', 'summary'), (p, '_brier_difference', 'pair'))
_saved = [(module, name, key, getattr(module, name)) for module, name, key in _originals]


def setUpModule():
    global _active, _initial_modules
    _active = True
    _initial_modules = set(sys.modules)
    for key in ACCOUNTING: ACCOUNTING[key] = 0

    def guard(event, args):
        if not _active: return
        blocked = event == 'socket.connect'
        if event == 'import' and str(args[0]).split('.')[0] in {'sklearn', 'torch', 'xgboost', 'joblib'}:
            blocked = True
        if event == 'open' and isinstance(args[0], (str, bytes, os.PathLike)):
            path = Path(os.fsdecode(args[0])).resolve()
            blocked = path.is_relative_to(ROOT / 'data') or path.name.endswith('.private.json') or path.suffix.lower() in {
                '.csv', '.tsv', '.rda', '.rds', '.rdata', '.npz', '.joblib', '.pkl', '.parquet', '.xlsx'}
        if blocked:
            ACCOUNTING['protected_io_attempts'] += 1
            raise RuntimeError('LIFECYCLE_CONSTRUCTED_PROTECTED_IO_FORBIDDEN')

    sys.addaudithook(guard)
    def wrap(original, key):
        def counted(*args, **kwargs):
            ACCOUNTING[key + '_calls'] += 1
            result = original(*args, **kwargs)
            ACCOUNTING[key + '_returns'] += 1
            return result
        return counted
    for module, name, key, original in _saved:
        setattr(module, name, wrap(original, key))


def tearDownModule():
    global _active
    _active = False
    for module, name, key, original in _saved: setattr(module, name, original)
    assert ACCOUNTING['protected_io_attempts'] == 0
    added = set(sys.modules) - _initial_modules
    assert not any(name in added for name in ('sklearn', 'torch', 'xgboost', 'research_runtime.training',
                                            'research_runtime.discrete', 'research_runtime.graph_native'))
    print(json.dumps(dict(artificial_accounting=ACCOUNTING, clinical_reads=0, model_loads=0, CIF=0, fits=0,
                         KM_IPCW_estimates=0)), flush=True)


def fixture(counts=(5, 5, 5, 5), unknown=False, empty_group=False):
    models = (p.ModelBinding('synthetic-model-A', 'a' * 64, 'raw_uncalibrated_24month_CIF'),
              p.ModelBinding('synthetic-model-B', 'b' * 64, 'saved_calibrated_24month_CIF'))
    group = p.SubgroupBinding('predefined', 'synthetic-rule-v1', 'f' * 64)
    pp = p.MonitoringProtocol('synthetic-reference-v1', 'd' * 64, 'synthetic-first-AD-24m',
                             'synthetic-fixed-anchor', 'SYNTHETIC-IDS', models, (group,))
    bins = d.ReferenceBinning(pp.reference_data_version, pp.reference_manifest_sha256, models[0].version,
                             'synthetic-ten-features-v1', pp.subject_namespace, ((0.,),) * 10)
    protocol = m.LifecycleProtocol(bins, pp, models[0].version)
    def batch(prefix, version, manifest):
        ids, values, observations = [], [], []
        predictions = {model.version: {} for model in models}
        members = []
        for state, high, count in ((-1., False, counts[0]), (-1., True, counts[1]),
                                   (1., False, counts[2]), (1., True, counts[3])):
            for _ in range(count):
                sid = f'SYNTHETIC-PERSON-{prefix}-{len(ids):05}'
                ids.append(sid)
                values.append([state] * 10)
                observations.append(p.HorizonObservation(sid, 0, 12 if unknown else 24))
                predictions[models[0].version][sid] = .7 if high else .1
                predictions[models[1].version][sid] = .8 if high else .2
                if high and not empty_group: members.append(sid)
        features = d.FeatureSnapshot(version, manifest, models[0].version, bins.feature_schema_version,
                                    pp.subject_namespace, tuple(ids), np.array(values, dtype=float).reshape(-1, 10))
        performance = p.MonitoringBatch(version, manifest, pp.endpoint_id, pp.cohort_anchor_id,
            pp.subject_namespace, models, (group,), tuple(observations), predictions, {'predefined': tuple(members)})
        return features, performance
    rf, rb = batch('REFERENCE', pp.reference_data_version, pp.reference_manifest_sha256)
    cf, cb = batch('CURRENT', 'synthetic-current-v1', 'e' * 64)
    return rf, cf, rb, cb, protocol


def evaluate(parts, digest=None):
    return m.evaluate_lifecycle_monitor(*parts, expected_protocol_sha256=m.protocol_digest(parts[-1]) if digest is None else digest)


def reorder(batch):
    return replace(batch, observations=tuple(reversed(batch.observations)),
                   predictions={name: dict(reversed(tuple(panel.items()))) for name, panel in batch.predictions.items()},
                   memberships={name: tuple(reversed(ids)) for name, ids in batch.memberships.items()})


class LifecycleMonitorTests(unittest.TestCase):
    def assert_before_calculators(self, parts, error=ValueError, digest=None):
        with patch.object(d, 'evaluate_feature_drift', side_effect=AssertionError('premature feature calculation')) as fd, \
             patch.object(p, 'evaluate_performance_monitor', side_effect=AssertionError('premature performance calculation')) as pm:
            with self.assertRaises(error): evaluate(parts, digest)
            fd.assert_not_called()
            pm.assert_not_called()

    def assert_suppressed(self, parts):
        with patch.object(d, 'evaluate_feature_drift', side_effect=AssertionError('premature feature calculation')) as fd, \
             patch.object(p, 'evaluate_performance_monitor', side_effect=AssertionError('premature performance calculation')) as pm:
            result = evaluate(parts)
            self.assertEqual(result['status'], 'SUPPRESSED')
            self.assertIsNone(result['featureReport'])
            self.assertIsNone(result['performanceReport'])
            self.assertEqual(set(result['operations'].values()), {0})
            fd.assert_not_called()
            pm.assert_not_called()
            return result

    def test_separately_safe_crossed_cells_one_are_suppressed_before_both_apis(self):
        rf, cf, rb, cb, protocol = fixture((1, 9, 9, 1))
        self.assertEqual(d.evaluate_feature_drift(rf, cf, protocol.binning,
            expected_binning_sha256=d.binning_digest(protocol.binning))['status'], 'COMPLETE')
        self.assertEqual(p.evaluate_performance_monitor(rb, cb, protocol.performance,
            expected_protocol_sha256=p.protocol_digest(protocol.performance))['status'], 'COMPLETE')
        self.assert_suppressed((rf, cf, rb, cb, protocol))

    def test_unified_pass_reuses_unchanged_public_outputs(self):
        parts = fixture()
        result = evaluate(parts)
        self.assertEqual(result['status'], 'COMPLETE')
        rf, cf, rb, cb, protocol = parts
        self.assertEqual(result['featureReport'], d.evaluate_feature_drift(rf, cf, protocol.binning,
            expected_binning_sha256=d.binning_digest(protocol.binning)))
        self.assertEqual(result['performanceReport'], p.evaluate_performance_monitor(rb, cb, protocol.performance,
            expected_protocol_sha256=p.protocol_digest(protocol.performance)))
        self.assertEqual(set(result['operations'].values()), {1})

    def test_feature_and_probability_order_join_by_id_deterministically(self):
        rf, cf, rb, cb, protocol = fixture()
        swapped = replace(cf, subject_ids=tuple(reversed(cf.subject_ids)), values=cf.values[::-1].copy())
        self.assertEqual(evaluate((rf, cf, rb, cb, protocol)), evaluate((rf, swapped, reorder(rb), reorder(cb), protocol)))

    def test_protocol_digest_binds_artifact_policy_rules_anchor_bins(self):
        *_, protocol = fixture()
        original = m.protocol_digest(protocol)
        pp = protocol.performance
        variants = [replace(protocol, performance=replace(pp, cohort_anchor_id='synthetic-other-anchor')),
            replace(protocol, performance=replace(pp, models=(replace(pp.models[0], artifact_sha256='c' * 64), pp.models[1]))),
            replace(protocol, performance=replace(pp, models=(replace(pp.models[0], prediction_policy=pp.models[1].prediction_policy), pp.models[1]))),
            replace(protocol, performance=replace(pp, subgroups=(replace(pp.subgroups[0], rule_sha256='c' * 64),))),
            replace(protocol, binning=replace(protocol.binning, cut_points=((.1,),) * 10))]
        self.assertTrue(all(m.protocol_digest(v) != original for v in variants))

    def test_stale_expected_digest_rejected_before_calls(self):
        self.assert_before_calculators(fixture(), digest='0' * 64)

    def test_reference_plans_must_agree(self):
        rf, cf, rb, cb, protocol = fixture()
        self.assert_before_calculators((rf, cf, rb, cb, replace(protocol,
            performance=replace(protocol.performance, reference_manifest_sha256='c' * 64))))

    def test_primary_model_is_explicit_and_present(self):
        rf, cf, rb, cb, protocol = fixture()
        for primary in ('synthetic-model-missing', protocol.performance.models[1].version, None):
            self.assert_before_calculators((rf, cf, rb, cb, replace(protocol, primary_model_version=primary)))

    def test_current_manifest_and_version_must_match_feature_snapshot(self):
        rf, cf, rb, cb, protocol = fixture()
        for changed in (replace(cb, manifest_sha256='c' * 64), replace(cb, data_version='synthetic-other-v1')):
            self.assert_before_calculators((rf, cf, rb, changed, protocol))

    def test_same_size_different_participant_set_rejected(self):
        rf, cf, rb, cb, protocol = fixture()
        changed = replace(cf, subject_ids=cf.subject_ids[:-1] + ('SYNTHETIC-UNKNOWN-PERSON',))
        self.assert_before_calculators((rf, changed, rb, cb, protocol))

    def test_schema_units_namespace_model_context_rejected(self):
        rf, cf, rb, cb, protocol = fixture()
        for changed in (replace(cf, feature_schema_version='synthetic-other-schema'), replace(cf, units=('years',) * 10),
                        replace(cf, subject_namespace='OTHER-IDS'), replace(cf, model_version='synthetic-other-model')):
            self.assert_before_calculators((rf, changed, rb, cb, protocol))

    def test_endpoint_anchor_rule_artifact_prediction_policy_rejected(self):
        rf, cf, rb, cb, protocol = fixture()
        for changed in (replace(cb, endpoint_id='synthetic-other-endpoint'), replace(cb, cohort_anchor_id='synthetic-other-anchor'),
            replace(cb, models=(replace(cb.models[0], artifact_sha256='c' * 64), cb.models[1])),
            replace(cb, models=(replace(cb.models[0], prediction_policy=cb.models[1].prediction_policy), cb.models[1])),
            replace(cb, subgroups=(replace(cb.subgroups[0], rule_sha256='c' * 64),))):
            self.assert_before_calculators((rf, cf, rb, changed, protocol))

    def test_invalid_reference_binding_rejected(self):
        rf, cf, rb, cb, protocol = fixture()
        self.assert_before_calculators((replace(rf, manifest_sha256='c' * 64), cf,
                                       replace(rb, manifest_sha256='c' * 64), cb, protocol))

    def test_partial_overlap_one_suppressed_even_with_safe_margins(self):
        rf, cf, rb, cb, protocol = fixture()
        old, shared = cb.observations[0].subject_id, rb.observations[0].subject_id
        cb = replace(cb, observations=(replace(cb.observations[0], subject_id=shared),) + cb.observations[1:],
            predictions={name: {shared if sid == old else sid: value for sid, value in panel.items()} for name, panel in cb.predictions.items()})
        cf = replace(cf, subject_ids=(shared,) + cf.subject_ids[1:])
        self.assert_suppressed((rf, cf, rb, cb, protocol))

    def test_fully_overlapping_histories_pass_and_changed_history_rejected(self):
        rf, _, rb, _, protocol = fixture()
        cf = replace(rf, data_version='synthetic-current-v1', manifest_sha256='e' * 64)
        cb = replace(rb, data_version=cf.data_version, manifest_sha256=cf.manifest_sha256)
        self.assertEqual(evaluate((rf, cf, rb, cb, protocol))['status'], 'COMPLETE')
        changed = replace(cb, observations=(replace(cb.observations[0], observed_month=12),) + cb.observations[1:])
        self.assert_before_calculators((rf, cf, rb, changed, protocol))

    def test_single_missing_state_crossed_with_models_suppresses(self):
        rf, cf, rb, cb, protocol = fixture()
        values = cf.values.copy()
        values[0, 0] = np.nan
        self.assert_suppressed((rf, replace(cf, values=values), rb, cb, protocol))

    def test_unknown_outcomes_remain_bounds(self):
        result = evaluate(fixture(unknown=True))
        metrics = result['performanceReport']['batchResults'][0]['panels'][0]['models']['synthetic-model-A']
        self.assertEqual(metrics['unknown'], 20)
        self.assertIsNone(metrics['knownSubsetBrier'])
        self.assertLess(metrics['brierBounds']['lower'], metrics['brierBounds']['upper'])

    def test_empty_subgroup_is_null_and_composite_limited(self):
        result = evaluate(fixture(empty_group=True))
        self.assertEqual(result['status'], 'COMPLETE_WITH_LIMITATIONS')
        self.assertEqual(result['performanceReport']['status'], 'COMPLETE_WITH_EMPTY_GROUPS')
        group = result['performanceReport']['batchResults'][0]['panels'][1]['models']['synthetic-model-A']
        self.assertIsNone(group['brierBounds'])

    def test_both_empty_snapshots_remain_not_estimable(self):
        result = evaluate(fixture((0, 0, 0, 0)))
        self.assertEqual(result['status'], 'COMPLETE_WITH_LIMITATIONS')
        self.assertIsNone(result['featureReport']['featureResults'][0]['psi'])
        self.assertIsNone(result['performanceReport']['batchResults'][0]['panels'][0]['models']['synthetic-model-A']['brierBounds'])

    def test_missing_feature_support_is_null_not_zero(self):
        rf, cf, rb, cb, protocol = fixture()
        rv, cv = rf.values.copy(), cf.values.copy()
        rv[:, 0] = cv[:, 0] = np.nan
        result = evaluate((replace(rf, values=rv), replace(cf, values=cv), rb, cb, protocol))
        self.assertEqual(result['status'], 'COMPLETE_WITH_LIMITATIONS')
        self.assertIsNone(result['featureReport']['featureResults'][0]['psi'])

    def test_memory_frozen_before_component_entry(self):
        parts = fixture()
        baseline = evaluate(parts)
        rf, cf, rb, cb, protocol = parts
        original = d.evaluate_feature_drift
        def mutate(*args, **kwargs):
            cf.values[:] = 999.
            for panel in cb.predictions.values():
                for sid in panel: panel[sid] = .99
            cb.memberships['predefined'] = ()
            return original(*args, **kwargs)
        with patch.object(d, 'evaluate_feature_drift', side_effect=mutate):
            self.assertEqual(evaluate(parts), baseline)

    def test_component_exception_cannot_return_partial_report(self):
        with patch.object(p, 'evaluate_performance_monitor', side_effect=RuntimeError('synthetic component failure')):
            with self.assertRaises(RuntimeError): evaluate(fixture())

    def test_unexpected_component_status_rejected(self):
        with patch.object(p, 'evaluate_performance_monitor', return_value={'status': 'SUPPRESSED'}):
            with self.assertRaisesRegex(m.LifecycleMonitorError, 'LIFECYCLE_COMPONENT_STATUS'): evaluate(fixture())

    def test_nonfinite_component_output_rejected(self):
        original = p.evaluate_performance_monitor
        def bad(*args, **kwargs):
            result = original(*args, **kwargs)
            result['unexpectedNonfinite'] = float('nan')
            return result
        with patch.object(p, 'evaluate_performance_monitor', side_effect=bad):
            with self.assertRaisesRegex(m.LifecycleMonitorError, 'LIFECYCLE_NONFINITE_OR_NON_JSON_REPORT'): evaluate(fixture())

    def test_input_inf_probability_nan_rejected_before_calls(self):
        rf, cf, rb, cb, protocol = fixture()
        values = cf.values.copy()
        values[0, 0] = float('inf')
        self.assert_before_calculators((rf, replace(cf, values=values), rb, cb, protocol))
        predictions = {name: dict(panel) for name, panel in cb.predictions.items()}
        predictions[cb.models[0].version][cb.observations[0].subject_id] = float('nan')
        self.assert_before_calculators((rf, cf, rb, replace(cb, predictions=predictions), protocol))

    def test_report_json_determinism_and_no_participant_outputs(self):
        parts = fixture()
        first, second = evaluate(parts), evaluate(parts)
        raw = json.dumps(first, allow_nan=False, sort_keys=True)
        self.assertEqual(raw, json.dumps(second, allow_nan=False, sort_keys=True))
        self.assertEqual(json.loads(raw), first)
        self.assertNotIn('SYNTHETIC-PERSON', raw)
        for key in ('subject_ids', 'observations', 'predictions', 'memberships', 'cut_points'):
            self.assertNotIn('"' + key + '"', raw)
        for key in ('provenanceVerified', 'clinicalUse', 'automaticAlert', 'automaticModelUpdate',
                    'actualDeploymentVerified', 'outOfFoldPredictionsVerified', 'adaptivePublicationSafe'):
            self.assertFalse(first[key])


if __name__ == '__main__':
    unittest.main(verbosity=2)
