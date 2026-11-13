"""Artificial fitted-state fixtures only; no artifact reads or training."""
from copy import deepcopy
import json
import threading
import unittest
from unittest.mock import patch

import numpy as np

from research_runtime.examples.input_admission_constructed import constructed_pair
from research_runtime.input_admission import AdmissionError
from research_runtime.inmemory_inference import authorize_once, InferenceError
from research_runtime.table_assessment import TableAssessment, TableAssessmentError
from research_runtime import table_explanation as table
from research_runtime.tests.test_table_explanation import constructed_model


def case(spec='seven'):
    model = constructed_model(spec)
    request, context = constructed_pair(spec, 'logistic')
    medians = table.training_reference_statistics(model, spec)['median'].tolist()
    for name, value in zip(request['model']['features'], medians):
        item = request['records'][0]['features'][name]
        item['value'] = 1 if name == 'apoe4_count' else value
        if name in ('age_years', 'mmse'):
            item['value'] += 1
        context['evidence'][item['evidenceRef']]['observation'] = deepcopy(item)
    return model, request, context


def permit(request, context):
    return authorize_once(request, trusted_context=context,
        authorization_ref='CONSTRUCTED-ASSESSMENT-PREDICTION', authorization_granted=True)


def run(assessment, request, context, grant=None, **kwargs):
    return assessment.run(request, trusted_context=context,
        permit=permit(request, context) if grant is None else grant,
        explanation_authorization_ref='CONSTRUCTED-BOUNDED-EXPLANATION',
        explanation_authorized=True, **kwargs)


class TableAssessmentTests(unittest.TestCase):
    def test_seven_and_ten_same_input_model_prediction_and_saved_reference(self):
        for spec, count in [('seven', 4), ('multimodal_values', 8)]:
            with self.subTest(spec=spec):
                model, request, context = case(spec)
                before = deepcopy((request, context))
                assessment = TableAssessment(model, verified_identity=request['model'])
                result = run(assessment, request, context)
                self.assertEqual(result['status'], 'COMPLETED')
                self.assertEqual(result['prediction']['predictionCalls'], 1)
                self.assertEqual(result['explanationCifCalls'], count)
                explanation = result['explanation']
                point = result['prediction']['predictions'][0]
                truth = np.array([[point['ad_cif_24m'], point['other_dementia_cif_24m']]])
                np.testing.assert_allclose(explanation['predictionCif'], truth, atol=1e-12, rtol=0)
                np.testing.assert_allclose(np.array(explanation['referenceCif']) + np.array(explanation['contributions']).sum(1), truth, atol=1e-12, rtol=0)
                self.assertEqual(result['basis']['referencePolicy'], 'SAVED_TABLE_MEDIAN')
                self.assertFalse(result['clinicalUse'])
                self.assertEqual((request, context), before)
                json.dumps(result, allow_nan=False)

    def test_mean_reference_is_explicitly_bound(self):
        model, request, context = case()
        a = run(TableAssessment(model, verified_identity=request['model']), request, context)
        b = run(TableAssessment(model, verified_identity=request['model'], reference_kind='mean'), request, context)
        self.assertEqual(b['basis']['referencePolicy'], 'SAVED_TABLE_MEAN')
        self.assertNotEqual(a['basis']['referenceSha256'], b['basis']['referenceSha256'])
        self.assertEqual(a['prediction']['predictions'], b['prediction']['predictions'])

    def test_source_qc_block_precedes_prediction_and_explanation(self):
        model, request, context = case()
        request['records'][0]['features']['mmse']['qc'] = 'UNKNOWN'
        item = request['records'][0]['features']['mmse']
        context['evidence'][item['evidenceRef']]['observation'] = deepcopy(item)
        assessment = TableAssessment(model, verified_identity=request['model'])
        with patch.object(model, 'predict_proba', side_effect=AssertionError('CALLED')), patch.object(table, 'explain_shapley', side_effect=AssertionError('CALLED')):
            result = assessment.run(request, trusted_context=context)
        self.assertEqual(result['status'], 'BLOCKED')
        self.assertIsNone(result['prediction']['predictions'])
        self.assertIsNone(result['explanation'])
        self.assertEqual(result['prediction']['predictionCalls'], 0)

    def test_no_prediction_permit_means_no_explanation(self):
        model, request, context = case()
        assessment = TableAssessment(model, verified_identity=request['model'])
        with patch.object(table, 'explain_shapley', side_effect=AssertionError('CALLED')):
            result = assessment.run(request, trusted_context=context)
        self.assertEqual(result['status'], 'PERMISSION_REQUIRED')
        self.assertIsNone(result['explanation'])

    def test_explanation_needs_separate_explicit_host_permission(self):
        model, request, context = case()
        assessment = TableAssessment(model, verified_identity=request['model'])
        grant = permit(request, context)
        with self.assertRaisesRegex(TableAssessmentError, '^TABLE_EXPLANATION_PERMISSION_REQUIRED$'):
            assessment.run(request, trusted_context=context, permit=grant)
        self.assertEqual(run(assessment, request, context, grant)['status'], 'COMPLETED')

    def test_invalid_budget_rejected_before_consuming_prediction_permit(self):
        model, request, context = case()
        assessment = TableAssessment(model, verified_identity=request['model'])
        grant = permit(request, context)
        for budget in (0, 513, True):
            with self.assertRaisesRegex(TableAssessmentError, '^TABLE_ASSESSMENT_BUDGET$'):
                run(assessment, request, context, grant, max_coalitions=budget)
        self.assertEqual(run(assessment, request, context, grant)['status'], 'COMPLETED')

    def test_failed_explanation_never_returns_partial_scores_or_reusable_permit(self):
        model, request, context = case()
        assessment = TableAssessment(model, verified_identity=request['model'])
        grant = permit(request, context)
        with patch.object(table, 'explain_shapley', side_effect=RuntimeError('PRIVATE_DETAILS')):
            with self.assertRaisesRegex(TableAssessmentError, '^TABLE_ASSESSMENT_EXPLANATION_FAILED$'):
                run(assessment, request, context, grant)
        with self.assertRaises(InferenceError):
            run(assessment, request, context, grant)

    def test_corrupt_explanation_prediction_or_contributions_are_rejected(self):
        original = table.explain_shapley
        for field in ('prediction_cif', 'contributions', 'reference_cif'):
            model, request, context = case()
            assessment = TableAssessment(model, verified_identity=request['model'])
            def corrupt(*args, **kwargs):
                result = original(*args, **kwargs)
                result[field] = result[field] + .01
                return result
            with patch.object(table, 'explain_shapley', side_effect=corrupt):
                with self.assertRaisesRegex(TableAssessmentError, '^TABLE_ASSESSMENT_RECONSTRUCTION$'):
                    run(assessment, request, context)

    def test_changed_model_state_is_rejected_before_calculation(self):
        model, request, context = case()
        assessment = TableAssessment(model, verified_identity=request['model'])
        model.named_steps['hazards'].estimator_.intercept_[1] += .1
        with self.assertRaisesRegex(InferenceError, '^PREDICTOR_STATE_CHANGED$'):
            run(assessment, request, context)

    def test_model_mutation_during_explanation_is_not_published(self):
        model, request, context = case()
        assessment = TableAssessment(model, verified_identity=request['model'])
        original = table.explain_shapley
        def mutate(*args, **kwargs):
            result = original(*args, **kwargs)
            model.named_steps['hazards'].estimator_.intercept_[1] += .1
            return result
        with patch.object(table, 'explain_shapley', side_effect=mutate):
            with self.assertRaisesRegex(TableAssessmentError, '^TABLE_ASSESSMENT_MODEL_CHANGED$'):
                run(assessment, request, context)

    def test_no_loading_or_fitting_and_no_invented_lime(self):
        model, request, context = case()
        with patch('joblib.load', side_effect=AssertionError('LOAD')), patch('sklearn.pipeline.Pipeline.fit', side_effect=AssertionError('FIT')):
            assessment = TableAssessment(model, verified_identity=request['model'])
            result = run(assessment, request, context)
        self.assertEqual(result['explanation']['limeStatus'], 'NOT_EVALUATED_NO_LOCAL_FIT')

    def test_same_assessment_serializes_prediction_and_explanation_together(self):
        model, request, context = case()
        assessment = TableAssessment(model, verified_identity=request['model'])
        grants = [permit(request, context), permit(request, context)]
        entered, release, second_started = threading.Event(), threading.Event(), threading.Event()
        results, errors, calls = [], [], []
        original_predict, original_explain = assessment._predictor._predict, table.explain_shapley
        def predict(rows):
            calls.append('predict')
            return original_predict(rows)
        def explain(*args, **kwargs):
            if not entered.is_set():
                entered.set()
                if not release.wait(5):
                    raise RuntimeError('TEST_TIMEOUT')
            return original_explain(*args, **kwargs)
        def invoke(index):
            try:
                if index == 1:
                    second_started.set()
                results.append(run(assessment, request, context, grants[index]))
            except Exception as error:
                errors.append(error)
        assessment._predictor._predict = predict
        threads = [threading.Thread(target=invoke, args=(i,)) for i in range(2)]
        with patch.object(table, 'explain_shapley', side_effect=explain):
            try:
                threads[0].start()
                self.assertTrue(entered.wait(5))
                threads[1].start()
                self.assertTrue(second_started.wait(5))
                self.assertEqual(calls, ['predict'])
                self.assertEqual(results, [])
            finally:
                release.set()
                for thread in threads:
                    if thread.ident is not None:
                        thread.join(5)
        self.assertFalse(any(thread.is_alive() for thread in threads))
        self.assertEqual(errors, [])
        self.assertEqual(len(results), 2)

    def test_unsupported_family_reference_and_multiple_records_are_rejected(self):
        model, request, context = case()
        with self.assertRaisesRegex(AdmissionError, '^REQUEST_FORMAT$'):
            TableAssessment(model, verified_identity=[])
        with self.assertRaisesRegex(TableAssessmentError, '^TABLE_ASSESSMENT_FAMILY$'):
            TableAssessment(model, verified_identity=dict(request['model'], family='prior_graph'))
        with self.assertRaisesRegex(TableAssessmentError, '^TABLE_ASSESSMENT_REFERENCE$'):
            TableAssessment(model, verified_identity=request['model'], reference_kind='invented')
        row = deepcopy(request['records'][0])
        row['subjectCode'] = 'CONSTRUCTED-OTHER'
        request['records'].append(row)
        with self.assertRaisesRegex(TableAssessmentError, '^TABLE_ASSESSMENT_SINGLE_RECORD$'):
            TableAssessment(model, verified_identity=request['model']).run(request, trusted_context=context)


if __name__ == '__main__':
    unittest.main()
