"""Artificial FR-25 truths, source reuse and active clinical/model I/O denial."""
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch
import json
import math
import os
import sys
import unittest

if __name__ == '__main__':
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

import numpy as np
from research_runtime import feature_drift as d

ROOT = Path(__file__).resolve().parents[2]
ACCOUNTING = dict(psi_calls=0, psi_returns=0, frozen_reference_psi_cases=0, protected_io_attempts=0)
_active = False
_original_psi = d._psi_counts
_initial_modules = set()


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
            blocked = (path.is_relative_to(ROOT / 'data') or path.suffix.lower() in
                       {'.csv', '.tsv', '.rda', '.rds', '.rdata', '.npz', '.joblib', '.pkl', '.parquet', '.xlsx'}
                       or path.name.endswith('.private.json'))
        if blocked:
            ACCOUNTING['protected_io_attempts'] += 1
            raise RuntimeError('DRIFT_CONSTRUCTED_PROTECTED_IO_FORBIDDEN')

    sys.addaudithook(guard)

    def tracked(reference, current):
        ACCOUNTING['psi_calls'] += 1
        value = _original_psi(reference, current)
        ACCOUNTING['psi_returns'] += 1
        return value

    d._psi_counts = tracked


def tearDownModule():
    global _active
    _active = False
    d._psi_counts = _original_psi
    assert ACCOUNTING['protected_io_attempts'] == 0
    added = set(sys.modules) - _initial_modules
    assert not any(name in added for name in ('research_runtime.training', 'research_runtime.discrete',
                                            'research_runtime.graph_native', 'sklearn', 'torch', 'xgboost'))
    print(json.dumps(dict(artificial_accounting=ACCOUNTING, clinical_reads=0, model_loads=0, CIF=0,
                          predictive_preprocessing_calibration_local_fits=0)), flush=True)


def snapshot(counts, prefix, version, manifest, values=(-1., 1.)):
    rows = [v for count, v in zip(counts, values) for _ in range(count)]
    matrix = np.repeat(np.asarray(rows, dtype=float)[:, None], 10, axis=1)
    return d.FeatureSnapshot(version, manifest * 64, 'synthetic-model-v1', 'ten-feature-v1',
        'SYNTHETIC-IDS', tuple(f'SYNTHETIC-PERSON-{prefix}-{i:05}' for i in range(len(rows))), matrix)


def fixture(rc=(10, 20), cc=(20, 10), values=(-1., 1.), cuts=(0.,)):
    reference = snapshot(rc, 'REFERENCE', 'synthetic-reference-v1', 'a', values)
    current = snapshot(cc, 'CURRENT', 'synthetic-current-v1', 'b', values)
    bins = d.ReferenceBinning(reference.data_version, reference.manifest_sha256, reference.model_version,
        reference.feature_schema_version, reference.subject_namespace, (cuts,) * 10)
    return reference, current, bins


def evaluate(reference, current, bins, digest=None):
    return d.evaluate_feature_drift(reference, current, bins,
        expected_binning_sha256=d.binning_digest(bins) if digest is None else digest)


class FeatureDriftTests(unittest.TestCase):
    def test_independent_two_bin_analytic_truth(self):
        report = evaluate(*fixture())
        self.assertEqual(report['status'], 'COMPLETE')
        for row in report['featureResults']:
            self.assertAlmostEqual(row['psi'], 2 / 3 * math.log(2), places=14)
            self.assertEqual(row['missingRateDelta'], 0)
        self.assertEqual(report['operations'], dict(featureComparisons=10, psiEvaluations=10))

    def test_identical_variable_distribution_is_zero(self):
        report = evaluate(*fixture(cc=(10, 20)))
        self.assertTrue(all(row['psi'] == 0 for row in report['featureResults']))

    def test_right_closed_cutpoint_boundary(self):
        r, c, bins = fixture(rc=(10, 10), cc=(10, 10))
        values = r.values.copy()
        values[5:10] = 0.  # Equality belongs to lower bin, matching sealed pd.cut.
        report = evaluate(replace(r, values=values), c, bins)
        self.assertTrue(all(row['psi'] == 0 for row in report['featureResults']))

    def test_zero_cell_floor_matches_independent_formula(self):
        report = evaluate(*fixture(rc=(10, 10, 10), cc=(0, 15, 15), values=(-1., 1., 3.), cuts=(0., 2.)))
        expected = (1e-6 - 1/3) * math.log(1e-6 / (1/3)) + 2 * (.5 - 1/3) * math.log(.5 / (1/3))
        for row in report['featureResults']:
            self.assertAlmostEqual(row['psi'], expected, places=13)
        self.assertEqual(report['policy']['psiFloor'], 1e-6)

    def test_missingness_uses_complete_batch_denominator(self):
        report = evaluate(*fixture(rc=(5, 10, 15), cc=(10, 10, 10), values=(-1., 1., np.nan)))
        for row in report['featureResults']:
            self.assertEqual((row['referenceObserved'], row['referenceMissing']), (15, 15))
            self.assertEqual((row['currentObserved'], row['currentMissing']), (20, 10))
            self.assertAlmostEqual(row['missingRateDelta'], -1/6)
            self.assertEqual(row['psiStatus'], 'ESTIMABLE')

    def test_reference_constant_is_unknown_not_zero(self):
        report = evaluate(*fixture(rc=(30, 0)))
        self.assertEqual(report['status'], 'COMPLETE_WITH_NOT_ESTIMABLE_FEATURES')
        self.assertTrue(all(row['psi'] is None and 'REFERENCE_SINGLE_OCCUPIED_BIN' in row['reasons'] for row in report['featureResults']))

    def test_current_constant_is_unknown_not_zero(self):
        report = evaluate(*fixture(cc=(30, 0)))
        self.assertTrue(all(row['psi'] is None and 'CURRENT_SINGLE_OCCUPIED_BIN' in row['reasons'] for row in report['featureResults']))

    def test_within_bin_values_do_not_change_report_or_reveal_rare_variation(self):
        r, c, bins = fixture(cc=(30, 0))
        altered = c.values.copy()
        altered[0] = -.25  # Single different raw value stays in the same fixed bin.
        self.assertEqual(evaluate(r, c, bins), evaluate(r, replace(c, values=altered), bins))

    def test_all_missing_is_unknown_with_known_missing_rate(self):
        report = evaluate(*fixture(rc=(0, 0, 20), cc=(0, 0, 20), values=(-1., 1., np.nan)))
        self.assertEqual(report['operations']['psiEvaluations'], 0)
        for row in report['featureResults']:
            self.assertIsNone(row['psi'])
            self.assertEqual(row['referenceMissingRate'], 1)
            self.assertEqual(row['currentMissingRate'], 1)
            self.assertEqual(row['missingRateDelta'], 0)

    def test_both_empty_never_divide_or_claim_zero_drift(self):
        report = evaluate(*fixture(rc=(0, 0), cc=(0, 0)))
        self.assertEqual(report['population']['referenceRows'], 0)
        for row in report['featureResults']:
            self.assertIsNone(row['psi'])
            self.assertIsNone(row['missingRateDelta'])
            self.assertEqual(row['missingnessStatus'], 'NOT_ESTIMABLE')
        json.dumps(report, allow_nan=False)

    def test_one_empty_preserves_unknown(self):
        report = evaluate(*fixture(rc=(0, 0)))
        self.assertEqual(report['status'], 'COMPLETE_WITH_NOT_ESTIMABLE_FEATURES')
        self.assertTrue(all(row['missingRateDelta'] is None for row in report['featureResults']))

    def test_small_population_suppresses_counts_and_all_metrics_before_psi(self):
        before = ACCOUNTING['psi_calls']
        report = evaluate(*fixture(rc=(3, 0)))
        self.assertEqual(report['status'], 'SUPPRESSED')
        self.assertIsNone(report['population'])
        self.assertIsNone(report['featureResults'])
        self.assertEqual(report['operations'], dict(featureComparisons=0, psiEvaluations=0))
        self.assertEqual(ACCOUNTING['psi_calls'], before)

    def test_privacy_threshold_five_vs_four(self):
        self.assertEqual(evaluate(*fixture(rc=(5, 5), cc=(5, 5)))['status'], 'COMPLETE')
        self.assertEqual(evaluate(*fixture(rc=(4, 6), cc=(5, 5)))['status'], 'SUPPRESSED')

    def test_cross_feature_intersection_singletons_suppress_despite_safe_marginals(self):
        r, c, bins = fixture(rc=(10, 10), cc=(10, 10))
        values = r.values.copy()
        values[:, 1] = [-1.] + [1.] * 9 + [-1.] * 9 + [1.]
        report = evaluate(replace(r, values=values), c, bins)
        self.assertEqual(report['privacyStatus'], 'SUPPRESSED')
        self.assertIsNone(report['featureResults'])

    def test_cross_batch_membership_complement_singletons_suppress(self):
        r, c, bins = fixture(rc=(10, 10), cc=(10, 10))
        ids = r.subject_ids[:-1] + ('SYNTHETIC-PERSON-NEW',)
        report = evaluate(r, replace(c, subject_ids=ids), bins)
        self.assertEqual(report['status'], 'SUPPRESSED')
        self.assertIsNone(report['population'])

    def test_cross_batch_state_transition_singletons_suppress(self):
        r, c, bins = fixture(rc=(10, 10), cc=(10, 10))
        values = c.values.copy()
        values[[0, 10]] = values[[10, 0]]
        report = evaluate(r, replace(c, subject_ids=r.subject_ids, values=values), bins)
        self.assertEqual(report['status'], 'SUPPRESSED')

    def test_safe_overlap_is_allowed_without_publishing_identities(self):
        r, c, bins = fixture(rc=(10, 10), cc=(10, 10))
        report = evaluate(r, replace(c, subject_ids=r.subject_ids), bins)
        self.assertEqual(report['population']['overlappingDeclaredIdentities'], 20)
        self.assertTrue(all(row['psi'] == 0 for row in report['featureResults']))
        self.assertNotIn('SYNTHETIC-PERSON', json.dumps(report))

    def test_context_mismatches_block_before_output(self):
        r, c, bins = fixture()
        for field, value in (('model_version', 'different'), ('feature_schema_version', 'different'), ('subject_namespace', 'different')):
            with self.assertRaisesRegex(d.FeatureDriftError, 'DRIFT_COMPARISON_CONTEXT'):
                evaluate(r, replace(c, **{field: value}), bins)
        for field, value in (('data_version', 'different'), ('manifest_sha256', 'f' * 64)):
            with self.assertRaisesRegex(d.FeatureDriftError, 'DRIFT_REFERENCE_BINDING'):
                evaluate(replace(r, **{field: value}), c, bins)

    def test_feature_order_and_units_are_canonical(self):
        r, c, bins = fixture()
        for item in (replace(c, features=tuple(reversed(c.features))), replace(c, units=c.units[:-1] + ('cm3',))):
            with self.assertRaises(d.FeatureDriftError):
                evaluate(r, item, bins)

    def test_cutpoints_cannot_be_silently_retuned(self):
        r, c, bins = fixture()
        identity = d.binning_digest(bins)
        changed = replace(bins, cut_points=((.5,),) * 10)
        with self.assertRaisesRegex(d.FeatureDriftError, 'DRIFT_BINNING_IDENTITY'):
            evaluate(r, c, changed, identity)
        self.assertNotEqual(identity, d.binning_digest(changed))

    def test_invalid_cutpoints_rejected(self):
        _, _, bins = fixture()
        for invalid in ((), (0., 0.), (1., 0.), (np.inf,), (np.nan,), (True,), ('0',), tuple(range(20))):
            with self.assertRaisesRegex(d.FeatureDriftError, 'DRIFT_FIXED_CUT_POINTS'):
                d.binning_digest(replace(bins, cut_points=(invalid,) * 10))

    def test_duplicate_blank_or_unbound_identities_rejected(self):
        r, c, bins = fixture()
        for ids in ((c.subject_ids[0],) * len(c.subject_ids), ('',) + c.subject_ids[1:], (' bad',) + c.subject_ids[1:]):
            with self.assertRaisesRegex(d.FeatureDriftError, 'DRIFT_SUBJECT_IDENTITIES'):
                evaluate(r, replace(c, subject_ids=ids), bins)
        with self.assertRaisesRegex(d.FeatureDriftError, 'DRIFT_NUMERIC_MATRIX'):
            evaluate(r, replace(c, subject_ids=c.subject_ids[:-1]), bins)

    def test_infinity_malformed_dtype_or_shape_never_becomes_missing(self):
        r, c, bins = fixture()
        for values in (c.values.astype(bool), c.values.astype(complex), c.values.astype(str), c.values[:, :9]):
            with self.assertRaisesRegex(d.FeatureDriftError, 'DRIFT_NUMERIC_MATRIX'):
                evaluate(r, replace(c, values=values), bins)
        values = c.values.copy()
        values[0, 0] = np.nan
        values[0, 1] = np.inf
        with self.assertRaisesRegex(d.FeatureDriftError, 'DRIFT_INFINITE_VALUE'):
            evaluate(r, replace(c, values=values), bins)

    def test_identity_digest_metadata_and_bounds(self):
        r, c, bins = fixture()
        for digest in ('bad', '0' * 64, None):
            with self.assertRaisesRegex(d.FeatureDriftError, 'DRIFT_BINNING_IDENTITY'):
                d.evaluate_feature_drift(r, c, bins, expected_binning_sha256=digest)
        with self.assertRaisesRegex(d.FeatureDriftError, 'DRIFT_METADATA_TOKEN'):
            evaluate(r, replace(c, model_version='private input with spaces'), bins)
        with patch.object(d, 'MAXIMUM_ROWS', 1):
            with self.assertRaisesRegex(d.FeatureDriftError, 'DRIFT_SUBJECT_IDENTITIES'):
                evaluate(r, c, bins)

    def test_determinism_row_permutation_and_input_preservation(self):
        r, c, bins = fixture()
        original_r, original_c = r.values.copy(), c.values.copy()
        a = evaluate(r, c, bins)
        b = evaluate(r, replace(c, values=c.values[::-1], subject_ids=c.subject_ids[::-1]), bins)
        self.assertEqual(a, b)
        self.assertTrue(np.array_equal(original_r, r.values, equal_nan=True))
        self.assertTrue(np.array_equal(original_c, c.values, equal_nan=True))
        self.assertTrue(c.values.flags.writeable)

    def test_output_whitelist_and_no_clinical_monitoring_claim(self):
        report = evaluate(*fixture())
        self.assertEqual(set(report['featureResults'][0]), {'feature', 'unit', 'referenceObserved', 'referenceMissing',
            'currentObserved', 'currentMissing', 'referenceMissingRate', 'currentMissingRate', 'missingRateDelta',
            'missingnessStatus', 'psi', 'psiStatus', 'reasons'})
        raw = json.dumps(report, allow_nan=False)
        for forbidden in ('subject_ids', 'cut_points', 'SYNTHETIC-PERSON', 'binCounts', 'rankMatrix'):
            self.assertNotIn(forbidden, raw)
        self.assertTrue(all(report[k] is False for k in ('calibrationEvaluated', 'subgroupPerformanceEvaluated',
            'automaticAlert', 'automaticModelUpdate', 'clinicalUse', 'causalEffectIdentified', 'provenanceVerified',
            'historicalAvailabilityCertified', 'referenceBinningIndependentlyVerified', 'participantOutputsSaved',
            'privacyFormalDPCertified', 'populationConfidenceInterval')))
        self.assertEqual((report['fits'], report['modelLoads'], report['CIFCalls']), (0, 0, 0))

    def test_sealed_stage15_formula_reused_on_artificial_inputs_only(self):
        # Frozen artificial comparison from run_stage15_model_monitoring.py,
        # SHA256 ce4a1a31b6a6368cf9bf2c3503beba7c3d9cf593526ebaec115d7a91b4fd7914.
        # Reference counts are (20,20,20,20,20), current (0,20,20,20,40).
        # Only the first and last bins contribute, with the original 1e-6 floor.
        # Keep the analytic oracle independent of the implementation and avoid
        # reading a maintainer-only script or importing pandas on clean clones.
        r, c, bins = fixture(rc=(50, 50), cc=(50, 50))
        rv = np.repeat(np.arange(100, dtype=float)[:, None], 10, axis=1)
        cv = rv + 20
        edges = (19.8, 39.6, 59.4, 79.2)
        report = evaluate(replace(r, values=rv), replace(c, values=cv), replace(bins, cut_points=(edges,) * 10))
        expected = (1e-6 - 0.2) * math.log(1e-6 / 0.2) + 0.2 * math.log(2)
        ACCOUNTING['frozen_reference_psi_cases'] += 1
        self.assertIsNotNone(expected)
        self.assertEqual(report['status'], 'COMPLETE')
        self.assertTrue(all(abs(row['psi'] - expected) < 1e-12 for row in report['featureResults']))


if __name__ == '__main__':
    unittest.main()
