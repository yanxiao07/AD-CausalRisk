"""Artificial arrays and analytic callbacks only; no fits or model files."""
from copy import deepcopy
import json
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import numpy as np
import pandas as pd

from research_runtime import graph_explanation as ex
from research_runtime.graph_native import FEATURES


ARMS = ('self_only', 'prior', 'semantic_permutation')
SEEDS = (20260926, 20260927, 20260928)


def frame_fixture(n=12):
    frame = pd.DataFrame({name:np.arange(n, dtype=float)*.01+j+1 for j, name in enumerate(FEATURES)})
    for i in range(n):
        if i % 3:frame.loc[i, list(FEATURES[8:])] = np.nan
        if i % 3 == 2:frame.loc[i, FEATURES[7]] = np.nan
    frame['subject_id'] = ['SYNTHETIC-STRATA-'+str(i) for i in range(n)]
    frame['event_type'] = 0; frame['observed_month'] = 24
    return frame


def contribution_fixture(frame):
    observed = ex.observation_groups(frame); result = {}
    for seed, shift in zip(SEEDS, (-.003, .001, .005)):
        for index, arm in enumerate(ARMS, 1):
            forward = .001*observed[:, :, None]*np.arange(1, 10)[None, :, None]*np.array([1., .5])
            reverse = forward.copy()
            reverse[:, 0] += shift*index*np.array([1., .5])
            reverse[:, 1] -= shift*index*np.array([1., .5])
            result[seed, arm, 'forward'] = forward.copy()
            result[seed, arm, 'reverse'] = reverse
    return result


def row(summary, arm='prior', cause='AD'):
    return next(r for r in summary['models'] if r['arm'] == arm and r['cause'] == cause)


class ModalityStrataTests(unittest.TestCase):
    def setUp(self):
        self.frame = frame_fixture(); self.values = contribution_fixture(self.frame)

    def summarize(self):
        return ex.aggregate_modality_strata(self.frame, self.values)

    def test_partition_empty_stratum_and_cause_coverage(self):
        result = self.summarize()
        self.assertEqual([(s['name'], s['subjects']) for s in result['strata']],
                         [('both', 4), ('apoe_only', 4), ('mri_only', 0), ('neither', 4)])
        empty = result['strata'][2]
        self.assertEqual(empty['status'], 'EMPTY_STRATUM')
        self.assertEqual(len(empty['models']), 6)
        for r in empty['models']:
            for key in ('order_l1', 'clinical_core_l1', 'common_rank_spearman'):
                self.assertEqual(r[key], dict(n=0, mean=None, median=None, p95=None))
            self.assertIsNone(r['sign_flips']['fraction'])

    def test_MRI_only_is_supported_without_merging(self):
        self.frame.loc[0, FEATURES[7]] = np.nan
        self.values = contribution_fixture(self.frame)
        strata = self.summarize()['strata']
        self.assertEqual([s['subjects'] for s in strata], [3, 4, 1, 4])

    def test_signed_seeds_are_averaged_before_absolute_values(self):
        r = row(self.summarize()['pooled'])
        self.assertAlmostEqual(r['order_l1']['mean'], .004)
        self.assertNotAlmostEqual(r['order_l1']['mean'], np.mean([.012, .004, .020]))
        self.assertAlmostEqual(row(self.summarize()['pooled'], cause='OTHER')['order_l1']['mean'], .002)

    def test_seed_cancellation_is_retained(self):
        for arm in ARMS:
            for seed, shift in zip(SEEDS, (-.03, 0, .03)):
                forward = self.values[seed, arm, 'forward']
                reverse = forward.copy(); reverse[:, 0] += shift; reverse[:, 1] -= shift
                self.values[seed, arm, 'reverse'] = reverse
        self.assertLess(row(self.summarize()['pooled'])['order_l1']['mean'], 1e-15)

    def test_rank_denominator_is_common_across_arms(self):
        for seed in SEEDS:
            for order in ex.ORDERS:
                observed = ex.observation_groups(self.frame)[0]
                self.values[seed, 'prior', order][0] = observed[:, None]*np.array([.002, .001])
        result = self.summarize()['pooled']
        for arm in ARMS:
            r = row(result, arm)
            self.assertEqual(r['common_rank_spearman']['n'], 11)
            self.assertEqual(r['rank_eligibility']['any_arm_constant'], 1)
            self.assertEqual(r['rank_eligibility']['this_arm_constant'], int(arm == 'prior'))
            self.assertEqual(r['order_l1']['n'], 12)

    def test_insufficient_groups_do_not_drop_L1_subjects(self):
        self.frame.loc[0, list(FEATURES[2:])] = np.nan
        self.values = contribution_fixture(self.frame)
        r = row(self.summarize()['pooled'])
        self.assertEqual(r['rank_eligibility']['insufficient_observed_groups'], 1)
        self.assertEqual(r['order_l1']['n'], 12)

    def test_zero_material_denominator_is_null_not_zero(self):
        self.values = {key:np.zeros_like(value) for key, value in self.values.items()}
        for r in self.summarize()['pooled']['models']:
            self.assertEqual(r['sign_flips']['common_eligible_person_groups'], 0)
            self.assertIsNone(r['sign_flips']['fraction'])
            self.assertEqual(r['common_rank_spearman']['n'], 0)
            self.assertEqual(r['order_l1']['mean'], 0)

    def test_sign_eligibility_uses_all_arms(self):
        for key, array in self.values.items():
            array[:] = 0
            array[:, :2] = np.array([.003, .005])[None, :, None]
            if key[2] == 'reverse':array[:, :2] = np.array([-.003, .011])[None, :, None]
            if key[1] == 'semantic_permutation':
                array[:, 0] = 0; array[:, 1] = .005
        result = self.summarize()['pooled']
        for r in result['models']:
            self.assertEqual(r['sign_flips']['common_eligible_person_groups'], 12)
            self.assertEqual(r['sign_flips']['flips'], 0)

    def test_pooled_quantile_is_not_weighted_stratum_quantiles(self):
        sensitivity = np.arange(12)**2*.00001
        for key in self.values:
            if key[2] == 'reverse':
                forward = self.values[key[0], key[1], 'forward']
                self.values[key] = forward.copy()
                self.values[key][:, 0] += sensitivity[:, None]/2
                self.values[key][:, 1] -= sensitivity[:, None]/2
        result = self.summarize(); actual = row(result['pooled'])['order_l1']['p95']
        self.assertAlmostEqual(actual, float(np.quantile(sensitivity, .95)))
        wrong = sum(s['subjects']*row(s)['order_l1']['p95'] for s in result['strata'] if s['subjects'])/12
        self.assertGreater(abs(actual-wrong), 1e-5)
        self.assertLessEqual(result['pooled_mean_reconstruction_max_error'], 1e-12)

    def test_contrast_is_distribution_of_paired_differences(self):
        for key in self.values:
            if key[2] == 'reverse':
                sensitivity = np.resize([0, .2, .2] if key[1] == 'prior' else [0, .01, .3], 12)
                forward = self.values[key[0], key[1], 'forward']
                self.values[key] = forward.copy()
                self.values[key][:, 0] += sensitivity[:, None]/2
                self.values[key][:, 1] -= sensitivity[:, None]/2
        pooled = self.summarize()['pooled']
        contrast = next(r for r in pooled['contrasts'] if r['cause'] == 'AD' and r['contrast'] == 'prior_minus_self_only')
        self.assertAlmostEqual(contrast['paired_order_l1_difference']['median'], 0)
        self.assertAlmostEqual(row(pooled)['order_l1']['median']-row(pooled, 'self_only')['order_l1']['median'], .19)

    def test_clinical_core_is_component_of_same_game(self):
        for key in self.values:
            if key[2] == 'reverse':
                present = self.frame[FEATURES[7]].notna().to_numpy()
                self.values[key][present, 7] += .02
                self.values[key][present, 0] -= .02
        r = row(self.summarize()['pooled'])
        self.assertGreater(r['order_l1']['mean'], r['clinical_core_l1']['mean'])

    def test_inputs_are_not_mutated_and_output_has_no_person_arrays(self):
        frame = self.frame.copy(deep=True); arrays = deepcopy(self.values)
        result = self.summarize(); pd.testing.assert_frame_equal(frame, self.frame)
        for key in arrays:np.testing.assert_array_equal(arrays[key], self.values[key])
        serialized = json.dumps(result, allow_nan=False)
        self.assertNotIn('SYNTHETIC-STRATA-', serialized)
        self.assertNotIn('subject_id', serialized)
        self.assertEqual((result['fits'], result['cif_calls']), (0, 0))

    def test_incomplete_or_extra_contribution_keys_rejected(self):
        self.values.pop(next(iter(self.values)))
        with self.assertRaisesRegex(ValueError, 'INCOMPLETE'):self.summarize()

    def test_invalid_contribution_shape_type_or_nonfinite_rejected(self):
        key = next(iter(self.values)); original = self.values[key]
        for bad in [np.zeros((12, 8, 2)), np.zeros((12, 9, 2), bool),
                    original.astype(complex), np.full((12, 9, 2), np.nan), np.full((12, 9, 2), np.inf)]:
            with self.subTest(dtype=bad.dtype, shape=bad.shape):
                self.values[key] = bad
                with self.assertRaisesRegex(ValueError, 'INVALID_MODALITY_CONTRIBUTIONS'):self.summarize()

    def test_missing_group_nonzero_contribution_rejected(self):
        self.values[next(iter(self.values))][2, 7] = .1
        with self.assertRaisesRegex(ValueError, 'INVALID_MODALITY_CONTRIBUTIONS'):self.summarize()

    def test_order_total_mismatch_rejected(self):
        self.values[next(iter(self.values))][0, 0] += .1
        with self.assertRaisesRegex(ValueError, 'ORDER_TOTALS_DIFFER'):self.summarize()

    def test_partial_bilateral_group_rejected(self):
        self.frame.loc[0, FEATURES[8]] = np.nan
        with self.assertRaisesRegex(ValueError, 'PARTIAL_EXPLANATION_GROUP'):self.summarize()

    def test_boolean_complex_infinite_and_missing_columns_rejected(self):
        original = self.frame.copy()
        for value in [True, complex(1, 2), np.inf, 'text']:
            self.frame = original.copy(); self.frame[FEATURES[0]] = value
            with self.assertRaisesRegex(ValueError, 'INVALID_MODALITY_STRATA_VALUES'):self.summarize()
        self.frame = original.drop(columns=FEATURES[0])
        with self.assertRaisesRegex(ValueError, 'INVALID_MODALITY_STRATA_FRAME'):self.summarize()

    def test_bounded_855_analytic_calls_reuse_original_telescoping(self):
        frame = frame_fixture(1160)
        model = SimpleNamespace(named_steps={'hazards':SimpleNamespace(medians_=np.zeros(10))})
        def analytic(_model, state):
            x = np.nan_to_num(state[list(FEATURES)].to_numpy(float))
            score = .1+.00001*x.sum(1)+.000001*x[:, 0]*x[:, 1]
            return np.c_[score, score*.2]
        with patch.object(ex, 'model_identity', return_value='artificial'), patch.object(ex, 'checked_cif', side_effect=analytic) as predict:
            calls = ex.Calls(855, max_state_rows=198360)
            for positions in np.array_split(np.arange(1160), 5):
                part = frame.iloc[positions]
                for _ in range(9):
                    full = calls(model, part)
                    reference, values, error = ex.telescoping(model, part, full, calls)
                    self.assertLessEqual(error, 1e-12)
                    for value in values.values():np.testing.assert_allclose(reference+value.sum(1), full, atol=1e-12, rtol=0)
                    np.testing.assert_array_equal(calls(model, part), full)
            self.assertEqual((calls.count, calls.completed, predict.call_count), (855, 855, 855))
            self.assertEqual((calls.state_rows_attempted, calls.state_rows_completed), (198360, 198360))
            with self.assertRaisesRegex(ValueError, 'BUDGET_EXCEEDED'):calls(model, frame.iloc[:1])
            self.assertEqual(predict.call_count, 855)

    def test_callback_failure_locks_out_retries(self):
        with patch.object(ex, 'model_identity', return_value='artificial'), patch.object(ex, 'checked_cif', side_effect=RuntimeError('injected')) as predict:
            calls = ex.Calls(2)
            with self.assertRaisesRegex(RuntimeError, 'injected'):calls(None, self.frame)
            with self.assertRaisesRegex(ValueError, 'FAILED_NO_RETRY'):calls(None, self.frame)
            self.assertEqual((calls.count, calls.completed, predict.call_count), (1, 0, 1))


if __name__ == '__main__':unittest.main()
