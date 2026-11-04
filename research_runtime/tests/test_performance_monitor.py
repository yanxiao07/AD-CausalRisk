"""Artificial no-fit monitoring truths, shared-completion and privacy checks."""
from copy import deepcopy
from dataclasses import replace
from itertools import product
from pathlib import Path
import ast
import json
import math
import os
import sys
import unittest

if __name__ == '__main__':
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from research_runtime import performance_monitor as m

ROOT = Path(__file__).resolve().parents[4]
ACCOUNTING = dict(summary_calls=0, summary_returns=0, paired_calls=0, paired_returns=0, protected_io_attempts=0)
_active = False
_initial_modules = set()
_summary, _pair = m._summary, m._brier_difference


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
            p = Path(os.fsdecode(args[0])).resolve()
            blocked = p.is_relative_to(ROOT / 'data') or p.name.endswith('.private.json') or p.suffix.lower() in {
                '.csv', '.tsv', '.rda', '.rds', '.rdata', '.npz', '.joblib', '.pkl', '.parquet', '.xlsx'}
        if blocked:
            ACCOUNTING['protected_io_attempts'] += 1
            raise RuntimeError('PERFORMANCE_CONSTRUCTED_PROTECTED_IO_FORBIDDEN')

    sys.addaudithook(guard)

    def summary(*args):
        ACCOUNTING['summary_calls'] += 1
        value = _summary(*args)
        ACCOUNTING['summary_returns'] += 1
        return value

    def pair(*args):
        ACCOUNTING['paired_calls'] += 1
        value = _pair(*args)
        ACCOUNTING['paired_returns'] += 1
        return value

    m._summary, m._brier_difference = summary, pair


def tearDownModule():
    global _active
    _active = False
    m._summary, m._brier_difference = _summary, _pair
    assert ACCOUNTING['protected_io_attempts'] == 0
    added = set(sys.modules) - _initial_modules
    assert not any(name in added for name in ('sklearn', 'torch', 'xgboost', 'research_runtime.training',
                                            'research_runtime.discrete', 'research_runtime.graph_native'))
    print(json.dumps(dict(artificial_accounting=ACCOUNTING, clinical_reads=0, model_loads=0, CIF=0,
                         risk_preprocessing_calibration_local_fits=0, KM_estimates=0)), flush=True)


MODELS = (m.ModelBinding('synthetic-model-A', 'a' * 64, 'raw_uncalibrated_24month_CIF'),
          m.ModelBinding('synthetic-model-B', 'b' * 64, 'saved_calibrated_24month_CIF'))


def fixture(blocks=((0, .1, .2, 5), (1, .9, .8, 5), (None, .4, .6, 5)), groups=('predefined',)):
    bindings = tuple(m.SubgroupBinding(name, 'synthetic-rule-v1', 'f' * 64) for name in groups)
    protocol = m.MonitoringProtocol('synthetic-reference-v1', 'd' * 64, 'synthetic-first-AD-24m',
        'synthetic-fixed-first-MCI-anchor', 'SYNTHETIC-IDS', MODELS, bindings)

    def batch(prefix, version, manifest):
        observations, a, b = [], {}, {}
        members = {name: [] for name in groups}
        for event, pa, pb, count in blocks:
            for _ in range(count):
                sid = f'SYNTHETIC-PERSON-{prefix}-{len(observations):05}'
                cause, month = (0, 12) if event is None else (0, 24) if event == 0 else (event, 8)
                observations.append(m.HorizonObservation(sid, cause, month))
                a[sid], b[sid] = pa, pb
                if event == 1:
                    for name in groups: members[name].append(sid)
        return m.MonitoringBatch(version, manifest * 64, protocol.endpoint_id, protocol.cohort_anchor_id,
            protocol.subject_namespace, MODELS, bindings, tuple(observations),
            {MODELS[0].version: a, MODELS[1].version: b}, {name: tuple(ids) for name, ids in members.items()})

    return batch('REFERENCE', protocol.reference_data_version, 'd'), batch('CURRENT', 'synthetic-current-v1', 'e'), protocol


def evaluate(r, c, protocol, digest=None):
    return m.evaluate_performance_monitor(r, c, protocol,
        expected_protocol_sha256=m.protocol_digest(protocol) if digest is None else digest)


def first(report, batch=0, group=0, model=0):
    return report['batchResults'][batch]['panels'][group]['models'][MODELS[model].version]


def rename_ids(batch, ids):
    old = tuple(o.subject_id for o in batch.observations)
    mapping = dict(zip(old, ids))
    return replace(batch, observations=tuple(replace(o, subject_id=s) for o, s in zip(batch.observations, ids)),
        predictions={name: {mapping[s]: p for s, p in panel.items()} for name, panel in batch.predictions.items()},
        memberships={name: tuple(mapping[s] for s in group) for name, group in batch.memberships.items()})


def completed_outcomes(labels):
    unknown = [i for i, y in enumerate(labels) if y is None]
    for values in product((0, 1), repeat=len(unknown)):
        complete = list(labels)
        for i, y in zip(unknown, values): complete[i] = y
        yield complete


def truth(labels, probabilities):
    briers, eces = [], []
    for ys in completed_outcomes(labels):
        briers.append(sum((p-y)**2 for p, y in zip(probabilities, ys)) / len(ys))
        errors = []
        for low, high in zip(m.BIN_EDGES[:-1], m.BIN_EDGES[1:]):
            ix = [i for i, p in enumerate(probabilities) if low <= p < high or (high == 1 and p == 1)]
            errors.append(abs(sum(ys[i] for i in ix) - sum(probabilities[i] for i in ix)))
        eces.append(sum(errors) / len(ys))
    return (min(briers), max(briers)), (min(eces), max(eces))


class PerformanceMonitorTests(unittest.TestCase):
    def test_all_known_analytic_Brier_ECE_and_pair_difference(self):
        report = evaluate(*fixture(blocks=((0, .1, .2, 5), (1, .9, .8, 5))))
        self.assertEqual(report['status'], 'COMPLETE')
        a, b = first(report), first(report, model=1)
        for lohi in ('lower', 'upper'):
            self.assertAlmostEqual(a['brierBounds'][lohi], .01)
            self.assertAlmostEqual(a['eceBounds'][lohi], .1)
            self.assertAlmostEqual(b['brierBounds'][lohi], .04)
        pair = report['batchResults'][0]['panels'][0]['pairedBrierDifferences'][0]
        self.assertAlmostEqual(pair['bounds']['lower'], .03)
        self.assertAlmostEqual(pair['bounds']['upper'], .03)

    def test_early_censor_unknown_and_competing_other_dementia_known_nonAD(self):
        report = evaluate(*fixture(blocks=((2, .1, .2, 5), (None, .4, .6, 5)), groups=()))
        row = first(report)
        self.assertEqual((row['knownAD'], row['knownNonAD'], row['unknown']), (0, 5, 5))
        self.assertAlmostEqual(row['knownSubsetBrier'], .01)
        self.assertEqual(row['knownOutcomeClassStatus'], 'SINGLE_KNOWN_CLASS')
        self.assertIn('CAUSE2_OTHER_DEMENTIA', report['context']['endpointRule'])
        self.assertGreater(row['brierBounds']['upper'], row['brierBounds']['lower'])

    def test_unknown_ECE_extrema_are_discrete_not_continuous_zero(self):
        row = first(evaluate(*fixture(blocks=((None, .25, .75, 5),), groups=())))
        self.assertEqual(row['knownOutcomeClassStatus'], 'NO_KNOWN_OUTCOMES')
        self.assertIsNone(row['knownSubsetBrier'])
        self.assertAlmostEqual(row['brierBounds']['lower'], .0625)
        self.assertAlmostEqual(row['brierBounds']['upper'], .5625)
        self.assertAlmostEqual(row['eceBounds']['lower'], .05)  # k/5 nearest .25 is .2.
        self.assertAlmostEqual(row['eceBounds']['upper'], .75)

    def test_public_unknown_bounds_match_full_completion_enumeration(self):
        report = evaluate(*fixture(blocks=((None, .1, .7, 5), (None, .9, .3, 5)), groups=()))
        expected = truth((None,) * 10, (.1,) * 5 + (.9,) * 5)
        row = first(report)
        for field, pair in zip(('brierBounds', 'eceBounds'), expected):
            self.assertAlmostEqual(row[field]['lower'], pair[0], places=13)
            self.assertAlmostEqual(row[field]['upper'], pair[1], places=13)

    def test_81_arithmetic_cases_against_independent_binary_completions(self):
        ps = (.1, .3, .7, .9)
        for labels in product((0, 1, None), repeat=4):
            row = m._summary(labels, ps)
            expected = truth(labels, ps)
            for field, pair in zip(('brierBounds', 'eceBounds'), expected):
                self.assertAlmostEqual(row[field]['lower'], pair[0], places=13)
                self.assertAlmostEqual(row[field]['upper'], pair[1], places=13)

    def test_27_shared_outcome_pair_cases_against_enumeration(self):
        left, right = (.1, .4, .9), (.9, .4, .1)
        for labels in product((0, 1, None), repeat=3):
            differences = [sum((b-y)**2 - (a-y)**2 for a, b, y in zip(left, right, ys)) / 3
                           for ys in completed_outcomes(labels)]
            bounds = m._brier_difference(labels, left, right)
            self.assertAlmostEqual(bounds['lower'], min(differences), places=13)
            self.assertAlmostEqual(bounds['upper'], max(differences), places=13)

    def test_identical_model_pair_bounds_are_exact_zero_not_unrelated_ranges(self):
        report = evaluate(*fixture(blocks=((None, .25, .25, 5),), groups=()))
        self.assertGreater(first(report)['brierBounds']['upper'], first(report)['brierBounds']['lower'])
        bounds = report['batchResults'][0]['panels'][0]['pairedBrierDifferences'][0]['bounds']
        self.assertEqual(bounds, dict(lower=0., upper=0.))

    def test_calibration_boundary_zero_interior_and_one(self):
        blocks = tuple((0, p, p, 5) for p in (0., .2, .4, .6, .8, 1.))
        row = first(evaluate(*fixture(blocks=blocks, groups=())))
        self.assertEqual([r['subjects'] for r in row['calibrationBins']], [5, 5, 5, 5, 10])
        self.assertAlmostEqual(row['calibrationBins'][-1]['meanPrediction'], .9)

    def test_empty_subgroup_and_bins_preserve_null_denominators(self):
        report = evaluate(*fixture(blocks=((0, .1, .2, 10),)))
        self.assertEqual(report['status'], 'COMPLETE_WITH_EMPTY_GROUPS')
        row = first(report, group=1)
        self.assertEqual(row['status'], 'EMPTY')
        self.assertIsNone(row['brierBounds'])
        self.assertIsNone(row['eceBounds'])
        self.assertTrue(row['fullGroupADRateBounds']['denominatorAlwaysZero'])
        self.assertTrue(all(b['meanPrediction'] is None and b['absoluteGapBounds'] is None for b in row['calibrationBins']))

    def test_empty_batch_no_fabricated_zero_performance(self):
        r, c, p = fixture(groups=())
        empty = replace(r, observations=(), predictions={v.version: {} for v in p.models}, memberships={})
        report = evaluate(empty, c, p)
        self.assertEqual(report['status'], 'COMPLETE_WITH_EMPTY_GROUPS')
        self.assertIsNone(first(report)['knownSubsetBrier'])
        self.assertIsNone(first(report)['brierBounds'])

    def test_recorded_known_subset_is_not_the_full_unknown_population(self):
        row = first(evaluate(*fixture(groups=())))
        self.assertEqual(row['subjects'], 15)
        self.assertEqual(row['unknown'], 5)
        self.assertAlmostEqual(row['knownSubsetBrier'], .01)
        self.assertAlmostEqual(row['brierBounds']['lower'], (.1 + 5 * .16) / 15)
        self.assertAlmostEqual(row['brierBounds']['upper'], (.1 + 5 * .36) / 15)

    def test_single_known_class_is_descriptive_not_discrimination(self):
        row = first(evaluate(*fixture(blocks=((1, .8, .7, 5),), groups=())))
        self.assertEqual(row['knownOutcomeClassStatus'], 'SINGLE_KNOWN_CLASS')
        self.assertIs(row['discriminationEvaluated'], False)
        self.assertAlmostEqual(row['brierBounds']['lower'], .04)

    def test_privacy_failure_precedes_all_summaries_and_pair_calls(self):
        before = dict(ACCOUNTING)
        report = evaluate(*fixture(blocks=((0, .1, .2, 4), (1, .9, .8, 6)), groups=()))
        self.assertEqual(report['status'], 'SUPPRESSED')
        self.assertIsNone(report['batchResults'])
        self.assertEqual(report['operations'], dict(numericSummaries=0, pairedBrierComparisons=0))
        self.assertEqual(before, ACCOUNTING)

    def test_cross_model_singletons_hidden_by_safe_single_model_bins(self):
        report = evaluate(*fixture(blocks=((0, .1, .1, 1), (0, .1, .9, 9),
                                         (0, .9, .1, 9), (0, .9, .9, 1)), groups=()))
        self.assertEqual(report['status'], 'SUPPRESSED')

    def test_cross_subgroup_intersection_singletons(self):
        r, c, p = fixture(blocks=((0, .1, .2, 20),), groups=('g1', 'g2'))
        def groups(batch):
            ids = tuple(o.subject_id for o in batch.observations)
            return replace(batch, memberships=dict(g1=ids[:10], g2=ids[:1] + ids[10:19]))
        self.assertEqual(evaluate(groups(r), groups(c), p)['status'], 'SUPPRESSED')

    def test_outcome_cross_cell_small_even_when_bins_are_safe(self):
        self.assertEqual(evaluate(*fixture(blocks=((0, .4, .4, 9), (1, .4, .4, 1)), groups=()))['status'], 'SUPPRESSED')

    def test_cross_batch_membership_difference_one(self):
        r, c, p = fixture(blocks=((0, .1, .2, 20),), groups=())
        ids = tuple(o.subject_id for o in r.observations)
        c = rename_ids(c, ids[:-1] + ('SYNTHETIC-PERSON-NEW',))
        self.assertEqual(evaluate(r, c, p)['status'], 'SUPPRESSED')

    def test_cross_batch_transition_one_with_safe_marginals(self):
        r, c, p = fixture(blocks=((0, .1, .1, 10), (0, .9, .9, 10)), groups=())
        c = rename_ids(c, tuple(o.subject_id for o in r.observations))
        predictions = deepcopy(c.predictions)
        ids = list(predictions[MODELS[0].version])
        for values in predictions.values(): values[ids[0]], values[ids[10]] = values[ids[10]], values[ids[0]]
        self.assertEqual(evaluate(r, replace(c, predictions=predictions), p)['status'], 'SUPPRESSED')

    def test_safe_same_anchored_overlap_allowed(self):
        r, c, p = fixture()
        c = rename_ids(c, tuple(o.subject_id for o in r.observations))
        report = evaluate(r, c, p)
        self.assertEqual(report['status'], 'COMPLETE')
        self.assertEqual(first(report), first(report, batch=1))

    def test_overlapping_revised_or_followup_history_does_not_silently_merge(self):
        r, c, p = fixture()
        c = rename_ids(c, tuple(o.subject_id for o in r.observations))
        rows = list(c.observations)
        rows[0] = replace(rows[0], observed_month=23)
        with self.assertRaisesRegex(m.PerformanceMonitorError, 'MONITOR_OVERLAPPING_HISTORY_CHANGED'):
            evaluate(r, replace(c, observations=tuple(rows)), p)

    def test_model_artifact_policy_and_subgroup_rule_mismatch(self):
        r, c, p = fixture()
        for field, value in (('artifact_sha256', 'c' * 64), ('prediction_policy', 'saved_calibrated_24month_CIF')):
            models = (replace(c.models[0], **{field: value}),) + c.models[1:]
            with self.assertRaisesRegex(m.PerformanceMonitorError, 'MONITOR_CONTEXT_MISMATCH'):
                evaluate(r, replace(c, models=models), p)
        rules = (replace(c.subgroups[0], rule_sha256='c' * 64),)
        with self.assertRaisesRegex(m.PerformanceMonitorError, 'MONITOR_CONTEXT_MISMATCH'):
            evaluate(r, replace(c, subgroups=rules), p)

    def test_reference_endpoint_anchor_namespace_and_manifest_binding(self):
        r, c, p = fixture()
        for field in ('endpoint_id', 'cohort_anchor_id', 'subject_namespace'):
            with self.assertRaisesRegex(m.PerformanceMonitorError, 'MONITOR_CONTEXT_MISMATCH'):
                evaluate(r, replace(c, **{field: 'different'}), p)
        with self.assertRaisesRegex(m.PerformanceMonitorError, 'MONITOR_REFERENCE_BINDING'):
            evaluate(replace(r, manifest_sha256='e' * 64), c, p)

    def test_fixed_protocol_digest_and_bin_mutation_rejected(self):
        r, c, p = fixture()
        with self.assertRaisesRegex(m.PerformanceMonitorError, 'MONITOR_PROTOCOL_DIGEST'):
            evaluate(r, c, p, '0' * 64)
        with self.assertRaisesRegex(m.PerformanceMonitorError, 'MONITOR_FIXED_BINS'):
            evaluate(r, c, replace(p, bin_edges=(0., .5, 1.)))
        changed = replace(p, subgroups=(replace(p.subgroups[0], rule_version='new-rule'),))
        with self.assertRaisesRegex(m.PerformanceMonitorError, 'MONITOR_PROTOCOL_DIGEST'):
            evaluate(r, c, changed, m.protocol_digest(p))

    def test_invalid_probability_and_panels_have_redacted_errors(self):
        r, c, p = fixture()
        sid = c.observations[0].subject_id
        for value in (True, float('nan'), float('inf'), -.1, 1.1, '.2'):
            panels = deepcopy(c.predictions)
            panels[MODELS[0].version][sid] = value
            with self.assertRaisesRegex(m.PerformanceMonitorError, 'MONITOR_PROBABILITY') as caught:
                evaluate(r, replace(c, predictions=panels), p)
            self.assertNotIn(sid, str(caught.exception))
        with self.assertRaisesRegex(m.PerformanceMonitorError, 'MONITOR_MODEL_PANELS'):
            evaluate(r, replace(c, predictions={MODELS[0].version: []}), p)

    def test_ids_causes_months_and_group_membership_checked(self):
        r, c, p = fixture()
        for invalid in (replace(c.observations[0], event_type=True), replace(c.observations[0], observed_month=25)):
            with self.assertRaises(m.PerformanceMonitorError):
                evaluate(r, replace(c, observations=(invalid,) + c.observations[1:]), p)
        with self.assertRaisesRegex(m.PerformanceMonitorError, 'MONITOR_DUPLICATE_SUBJECT'):
            evaluate(r, replace(c, observations=(c.observations[1],) + c.observations[1:]), p)
        with self.assertRaisesRegex(m.PerformanceMonitorError, 'MONITOR_GROUP_MEMBERSHIP'):
            evaluate(r, replace(c, memberships=dict(predefined=('UNKNOWN-PERSON',))), p)

    def test_bounds_model_group_limits_and_empty_policy(self):
        r, c, p = fixture()
        for invalid in (replace(p, models=()), replace(p, models=p.models * 5),
                        replace(p, subgroups=(m.SubgroupBinding('overall', 'v1', 'a' * 64),)),
                        replace(p, horizon_months=12)):
            with self.assertRaises(m.PerformanceMonitorError):
                m.protocol_digest(invalid)

    def test_dictionary_order_invariance_and_input_output_isolation(self):
        r, c, p = fixture()
        original = deepcopy(c)
        a = evaluate(r, c, p)
        panels = {name: dict(reversed(list(values.items()))) for name, values in reversed(list(c.predictions.items()))}
        b = evaluate(r, replace(c, predictions=panels), p)
        self.assertEqual(a, b)
        self.assertEqual(c, original)
        first(a)['calibrationBins'][0]['subjects'] = -100
        self.assertEqual(c, original)

    def test_no_participant_outputs_no_fit_or_independence_claim(self):
        report = evaluate(*fixture())
        raw = json.dumps(report, allow_nan=False)
        self.assertNotIn('SYNTHETIC-PERSON', raw)
        self.assertNotIn('memberships', raw)
        for key in ('modelLoads', 'CIFCalls', 'fits'): self.assertEqual(report[key], 0)
        for key in ('crossBatchDifferencesComputed', 'populationConfidenceInterval', 'independentCensoringEstablished',
                    'IPCWEstimated', 'KMEstimated', 'discriminationEvaluated', 'calibrationRegressionFitted',
                    'subgroupMembershipIndependentlyVerified', 'outOfFoldPredictionsVerified', 'provenanceVerified',
                    'historicalAvailabilityCertified', 'clinicalUse', 'causalEffectIdentified', 'automaticAlert',
                    'automaticModelUpdate', 'participantOutputsSaved', 'privacyFormalDPCertified'):
            self.assertIs(report[key], False)

    def test_original_bin_edges_and_pure_helper_reuse(self):
        tree = ast.parse((ROOT / 'tmp/python-modality-strata-repair-20261029/research_runtime/evaluation.py').read_bytes())
        assignment = next(n for n in tree.body if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'BIN_EDGES' for t in n.targets))
        self.assertEqual(m.BIN_EDGES, ast.literal_eval(assignment.value))
        from research_runtime import decision_curve as existing
        self.assertIs(m._checked, existing._checked)
        self.assertIs(m._fixed_ratio_bounds, existing._fixed_ratio_bounds)


if __name__ == '__main__':
    unittest.main()
