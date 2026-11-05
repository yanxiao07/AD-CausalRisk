"""Finite artificial P3 checks; no model, clinical source or local fitting."""
from copy import deepcopy
import json
import unittest

import numpy as np

from research_runtime.examples.prediction_explanation_candidate import (
    _case, _join_constructed, build_example,
)


class PredictionExplanationCandidateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # One observed game per process, reused for all refusal challenges.
        cls.expected, cls.prediction, cls.explanation, cls.counts = _case()

    def join(self, prediction=None, explanation=None):
        return _join_constructed(
            self.expected, self.prediction if prediction is None else prediction,
            self.explanation if explanation is None else explanation)

    def test_same_case_and_independent_analytic_truth(self):
        report = self.join()
        # a=.6, m=.4, h=.25: AD=.1+.012+.016+.0075+.0024.
        self.assertTrue(np.allclose(
            [report['prediction'][0]['ad_cif_24m'],
             report['prediction'][0]['other_dementia_cif_24m']],
            [.1379, .046], atol=1e-15, rtol=0))
        expected = np.zeros((1, 9, 2))
        expected[0, 0] = [.0132, .006]  # half the .0024 interaction to age
        expected[0, 2] = [.0172, 0]    # half to MMSE
        expected[0, 8] = [.0075, 0]    # bilateral columns replaced together
        self.assertTrue(np.allclose(report['explanation']['contributions'], expected,
                                   atol=1e-15, rtol=0))
        self.assertTrue(np.allclose(report['explanation']['referenceCif'], [[.1, .04]],
                                   atol=1e-15, rtol=0))
        self.assertEqual(self.counts, dict(dispatchCallbacks=1, shapleyCoalitions=8,
            analyticCalls=9, actualModelLoads=0, originalCifCalls=0, riskFits=0,
            calibrationFits=0, localFits=0))

    def test_wrong_model_both_objects_still_rejected_against_fixed_expected(self):
        prediction, explanation = deepcopy(self.prediction), deepcopy(self.explanation)
        for obj in (prediction, explanation):
            obj['basis']['model']['artifactSha256'] = '9'*64
        with self.assertRaisesRegex(ValueError, '^CONSTRUCTED_BINDING_MISMATCH$'):
            self.join(prediction, explanation)

    def test_snapshot_and_context_and_source_changes_rejected(self):
        for field in ('inputSha256', 'trustedContextSha256', 'dataVersion'):
            with self.subTest(field=field):
                explanation = deepcopy(self.explanation)
                explanation['basis'][field] = 'changed'
                with self.assertRaisesRegex(ValueError, '^CONSTRUCTED_BINDING_MISMATCH$'):
                    self.join(explanation=explanation)

    def test_reference_and_units_order_policy_state_changes_rejected(self):
        for field in ('referenceSha256', 'referencePolicy', 'featureOrder', 'units',
                      'predictionPolicy', 'predictionWindowMonths', 'causeOrder',
                      'formulaStateToken', 'groups'):
            with self.subTest(field=field):
                explanation = deepcopy(self.explanation)
                explanation['basis'][field] = 'changed'
                with self.assertRaisesRegex(ValueError, '^CONSTRUCTED_BINDING_MISMATCH$'):
                    self.join(explanation=explanation)

    def test_reference_payload_change_with_unchanged_labels_rejected(self):
        explanation = deepcopy(self.explanation)
        explanation['referenceValues'][0][0] = 61
        with self.assertRaisesRegex(ValueError, '^CONSTRUCTED_EXPLANATION_INPUT_REFERENCE_MISMATCH$'):
            self.join(explanation=explanation)

    def test_observed_payload_change_with_unchanged_labels_rejected(self):
        explanation = deepcopy(self.explanation)
        explanation['observedValues'][0][2] = 25
        with self.assertRaisesRegex(ValueError, '^CONSTRUCTED_EXPLANATION_INPUT_REFERENCE_MISMATCH$'):
            self.join(explanation=explanation)

    def test_output_subject_change_rejected(self):
        prediction = deepcopy(self.prediction)
        prediction['output']['predictions'][0]['subject_id'] = 'CONSTRUCTED-OTHER'
        with self.assertRaisesRegex(ValueError, '^CONSTRUCTED_SUBJECT_MISMATCH$'):
            self.join(prediction=prediction)

    def test_output_context_and_failed_unpermitted_states_rejected(self):
        for field, value in [('status', 'BLOCKED'), ('status', 'PERMISSION_REQUIRED'),
                             ('executionPermissionUsed', False), ('dataVersion', 'other'),
                             ('indexDate', '2020-01-03'), ('predictionWindowMonths', 12),
                             ('availabilityPolicy', 'AS_OF_INDEX'), ('model', {})]:
            with self.subTest(field=field, value=value):
                prediction = deepcopy(self.prediction)
                prediction['output'][field] = value
                with self.assertRaisesRegex(ValueError, '^CONSTRUCTED_PREDICTION_CONTEXT_MISMATCH$'):
                    self.join(prediction=prediction)

    def test_prediction_explanation_disagreement_rejected(self):
        explanation = deepcopy(self.explanation)
        explanation['prediction_cif'][0, 0] += .01
        with self.assertRaisesRegex(ValueError, '^CONSTRUCTED_PREDICTION_EXPLANATION_MISMATCH$'):
            self.join(explanation=explanation)

    def test_equal_labels_and_wrong_contribution_rejected(self):
        explanation = deepcopy(self.explanation)
        explanation['contributions'][0, 0, 0] += .01
        with self.assertRaisesRegex(ValueError, '^CONSTRUCTED_EXPLANATION_SEMANTICS$'):
            self.join(explanation=explanation)

    def test_wrong_method_group_order_reference_and_causal_claim_rejected(self):
        for field, value in [('method', 'LIME'), ('group_names', ('other',)*9),
                             ('reference_policy', 'population'), ('is_causal_effect', True)]:
            with self.subTest(field=field):
                explanation = deepcopy(self.explanation)
                explanation[field] = value
                with self.assertRaisesRegex(ValueError, '^CONSTRUCTED_EXPLANATION_SEMANTICS$'):
                    self.join(explanation=explanation)

    def test_nonfinite_and_wrong_shapes_rejected(self):
        for field, value in [('prediction_cif', [[float('nan'), .046]]),
                             ('reference_cif', [[float('inf'), .04]]),
                             ('contributions', [[[0, 0]]]), ('prediction_cif', [])]:
            with self.subTest(field=field, value=value):
                explanation = deepcopy(self.explanation)
                explanation[field] = value
                with self.assertRaisesRegex(ValueError, '^CONSTRUCTED_EXPLANATION_VALUES$'):
                    self.join(explanation=explanation)

    def test_negative_reference_probability_rejected(self):
        explanation = deepcopy(self.explanation)
        explanation['reference_cif'][0, 0] = -.01
        with self.assertRaisesRegex(ValueError, '^CONSTRUCTED_PREDICTION_EXPLANATION_MISMATCH$'):
            self.join(explanation=explanation)

    def test_join_does_not_mutate_evidence_and_result_is_detached(self):
        before = deepcopy(self.prediction)
        report = self.join()
        report['basis']['model']['features'].clear()
        report['prediction'].clear()
        report['explanation']['contributions'][0][0][0] = 999
        self.assertEqual(self.prediction, before)
        self.assertEqual(len(self.expected['model']['features']), 10)
        self.assertLess(float(self.explanation['contributions'].max()), 1)

    def test_finite_json_and_unestablished_claims_remain_false(self):
        report = self.join()
        self.assertEqual(json.loads(json.dumps(report, allow_nan=False)), report)
        for field in ('actualResearchModelEvaluated', 'realHostEvidenceEvaluated',
                      'sourceAuthenticityCertified', 'historicalAvailabilityCertified',
                      'individualCausalEffectIdentified', 'clinicalUse', 'defaultModelChanged'):
            self.assertIs(report[field], False)
        self.assertIsNone(report['explanation']['lime'])
        self.assertEqual(report['explanation']['limeStatus'], 'NOT_EVALUATED_NO_LOCAL_FIT')

    def test_missing_bilateral_mri_preserves_null_not_zero_and_has_dummy_attribution(self):
        report = build_example(missing_mri=True)
        self.assertEqual(report['basis']['referenceValues'][0][8:], [None, None])
        self.assertEqual(report['explanation']['contributions'][0][8], [0., 0.])
        self.assertEqual(report['executionCounts']['analyticCalls'], 5)
        self.assertEqual(report['executionCounts']['shapleyCoalitions'], 4)
        self.assertAlmostEqual(report['prediction'][0]['ad_cif_24m'], .1304)
        json.dumps(report, allow_nan=False)


if __name__ == '__main__':
    unittest.main()
