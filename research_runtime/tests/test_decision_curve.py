"""Independent full-completion enumeration, not clinical validation."""
import copy
from dataclasses import replace
from fractions import Fraction as F
from itertools import product
import json
import math
import unittest
from unittest.mock import patch

from research_runtime.decision_curve import (
    CurveError, CurveProtocol, HorizonObservation as O, evaluate_decision_curves,
)
from python_service.prediction_comparison import decision_curve as complete_curve


def protocol(thresholds=(.25, .5, .75)):
    return CurveProtocol('synthetic-data-v1', 'synthetic-AD-first-event-v1', thresholds)


def rows(labels):
    return tuple(O(f'SYNTHETIC-{i}', 1 if y == 1 else 0, 6 if y is None else 24)
                 for i, y in enumerate(labels))


def panel(probabilities, version='synthetic-model-A'):
    return {version: {f'SYNTHETIC-{i}': p for i, p in enumerate(probabilities)}}


def enumerated(labels, probabilities, threshold, reference=None):
    """Independent rational TP/FP oracle for EVERY shared outcome assignment."""
    unknown = [i for i, y in enumerate(labels) if y is None]
    values = []
    t = F(threshold)
    for assignment in product((0, 1), repeat=len(unknown)):
        completed = list(labels)
        for i, y in zip(unknown, assignment):
            completed[i] = y
        def benefit(scores):
            selected = [i for i, p in enumerate(scores) if p >= threshold]
            tp = sum(completed[i] for i in selected)
            fp = len(selected) - tp
            return F(tp, len(labels)) - F(fp, len(labels)) * t / (1 - t)
        value = benefit(probabilities)
        if reference is not None:
            value -= benefit(reference)
        values.append(float(value))
    return min(values), max(values)


class DecisionCurveBoundsTests(unittest.TestCase):
    def assertBounds(self, actual, expected):
        self.assertAlmostEqual(actual['lower'], expected[0])
        self.assertAlmostEqual(actual['upper'], expected[1])

    def test_complete_data_matches_existing_course_arithmetic(self):
        ids = [f'DEMO-{i}' for i in range(4)]
        labels, probs = (0, 0, 1, 1), (.1, .4, .35, .8)
        old_protocol = dict(schemaVersion='synthetic-binary-evaluation-v1', mode='SYNTHETIC_TEST',
            protocolVersion='demo-p1', dataVersion='demo-d1', endpointId='demo-e1',
            horizonMonths=24, labelPolicy='CONSTRUCTED_COMPLETE_BINARY_NO_CENSORING',
            splitType='HOLDOUT', evaluationSubjects=ids,
            splits=[dict(foldId='demo-fold', trainSubjects=['DEMO-TRAIN'], testSubjects=ids)],
            thresholds=[.35, .5], calibrationBinEdges=[0, .5, 1])
        from python_service.prediction_evaluation import protocol_sha256
        from python_service.prediction_evaluation import PANEL_SCHEMA
        old_panel = dict(schemaVersion=PANEL_SCHEMA, mode='SYNTHETIC_TEST',
            protocolSha256=protocol_sha256(old_protocol), modelVersion='demo-model',
            source='CONSTRUCTED_TEST_VECTOR', predictions=[
                dict(subjectCode=s, foldId='demo-fold', outcome=y, probability=p)
                for s, y, p in zip(ids, labels, probs)])
        old = complete_curve(old_protocol, old_panel)
        value = evaluate_decision_curves(protocol((.35, .5)), rows(labels), panel(probs))
        for a, b, ref in zip(value['curves']['synthetic-model-A'], old['curves'], value['references']):
            self.assertBounds(a['netBenefitBounds'], (b['modelNetBenefit'],) * 2)
            self.assertBounds(ref['selectAllBounds'], (b['treatAllNetBenefit'],) * 2)

    def test_exhaustive_labels_and_all_shared_completions(self):
        a, b = (0, .25, .5, 1), (1, .5, .25, 0)
        for labels in product((0, 1, None), repeat=4):
            panels = panel(a)
            panels.update(panel(b, 'synthetic-model-B'))
            value = evaluate_decision_curves(protocol(), rows(labels), panels)
            for j, threshold in enumerate(protocol().thresholds):
                for name, scores in (('synthetic-model-A', a), ('synthetic-model-B', b)):
                    item = value['curves'][name][j]
                    self.assertBounds(item['netBenefitBounds'], enumerated(labels, scores, threshold))
                    self.assertBounds(item['minusSelectNoneBounds'], enumerated(labels, scores, threshold, (0,) * 4))
                    self.assertBounds(item['minusSelectAllBounds'], enumerated(labels, scores, threshold, (1,) * 4))
                self.assertBounds(value['pairedComparisons'][0]['curves'][j]['netBenefitDifferenceBounds'],
                                  enumerated(labels, b, threshold, a))

    def test_identical_models_cancel_unknowns_instead_of_subtracting_marginal_bounds(self):
        panels = panel((.8, .2))
        panels.update(panel((.8, .2), 'synthetic-model-B'))
        value = evaluate_decision_curves(protocol(), rows((None, None)), panels)
        self.assertLess(value['curves']['synthetic-model-A'][0]['netBenefitBounds']['lower'], 0)
        for row in value['pairedComparisons'][0]['curves']:
            self.assertBounds(row['netBenefitDifferenceBounds'], (0, 0))

    def test_pairing_uses_identity_and_is_order_invariant(self):
        observations = rows((1, None, 0))
        panels = panel((.9, .2, .4))
        a = evaluate_decision_curves(protocol(), observations, panels)
        shuffled = {k: dict(reversed(list(p.items()))) for k, p in panels.items()}
        self.assertEqual(a, evaluate_decision_curves(protocol(), observations[::-1], shuffled))

    def test_competing_event_and_horizon_censor_known_zero_early_censor_unknown(self):
        observations = (O('a', 2, 2), O('b', 0, 24), O('c', 0, 23), O('d', 0, 0), O('e', 1, 24))
        value = evaluate_decision_curves(protocol((.5,)), observations, {'model': dict.fromkeys('abcde', 1)})
        self.assertEqual((value['knownOutcomes'], value['unknownOutcomes'], value['knownADEvents']), (3, 2, 1))
        self.assertBounds(value['references'][0]['selectAllBounds'], (-.6, .2))

    def test_threshold_equality_endpoints_and_negative_benefits_preserved(self):
        value = evaluate_decision_curves(protocol((.5,)), rows((0, 0, 0)), panel((0, .5, 1)))
        item = value['curves']['synthetic-model-A'][0]
        self.assertEqual(item['selectedSubjects'], 2)
        self.assertBounds(item['netBenefitBounds'], (-2/3, -2/3))

    def test_all_unknown_no_selection_and_all_selection(self):
        for p, expected in ((0, (0, 0)), (1, (-1, 1))):
            value = evaluate_decision_curves(protocol((.5,)), rows((None,)), panel((p,)))
            self.assertBounds(value['curves']['synthetic-model-A'][0]['netBenefitBounds'], expected)

    def test_undefined_not_converted_to_a_confidence_interval_or_clinical_claim(self):
        value = evaluate_decision_curves(protocol(), rows((None,)), panel((1,)))
        for key in ('samplingConfidenceInterval', 'independentCensoringEstablished', 'clinicalUtilityEstablished',
                    'sourceAuthenticityVerified', 'outOfFoldPredictionsVerified', 'causalEffectIdentified', 'persisted'):
            self.assertIs(value[key], False)
        self.assertIsNone(value['clinicalThresholdRecommendation'])
        self.assertEqual((value['modelCalls'], value['fits']), (0, 0))

    def test_no_subject_identifiers_or_predictions_in_output_no_mutation(self):
        observations, panels = rows((1, None)), panel((.137, .827))
        before = copy.deepcopy(panels)
        value = evaluate_decision_curves(protocol(), observations, panels)
        self.assertEqual(panels, before)
        encoded = json.dumps(value, allow_nan=False)
        self.assertNotIn('SYNTHETIC-0', encoded)
        self.assertNotIn('0.137', encoded)

    def test_no_io_or_optional_dependencies_required(self):
        with patch('builtins.open', side_effect=AssertionError('file access')):
            evaluate_decision_curves(protocol(), rows((1, None)), panel((.2, .8)))

    def test_invalid_protocol_thresholds_and_horizon(self):
        for thresholds in ((), [], (0,), (1,), (True,), ('0.5',), (math.nan,), (.5, .5), (.8, .2), (.2,) * 65):
            with self.subTest(thresholds=thresholds), self.assertRaises(CurveError):
                evaluate_decision_curves(protocol(thresholds), rows((0,)), panel((.5,)))
        for horizon in (True, 12, 24.0):
            with self.assertRaises(CurveError):
                evaluate_decision_curves(replace(protocol(), horizon_months=horizon), rows((0,)), panel((.5,)))

    def test_invalid_observations_and_duplicate_subjects(self):
        for observations in ((), [O('a', 1, 2)], (O('a', True, 2),), (O('a', 3, 2),),
            (O('a', 0, -1),), (O('a', 0, 25),), (O('a', 1, 2.0),), (O('', 1, 2),),
            (O('a', 1, 2), O('a', 0, 24))):
            with self.assertRaises(CurveError):
                evaluate_decision_curves(protocol(), observations, {'model': {'a': .5}})

    def test_mismatched_panels_and_nonfinite_boolean_string_scores_rejected(self):
        for panels in ({}, {'model': {}}, {'model': {'wrong-person': .5}},
                       {'model': {'SYNTHETIC-0': .5, 'extra': .5}}, {'': {'SYNTHETIC-0': .5}}):
            with self.assertRaises(CurveError):
                evaluate_decision_curves(protocol(), rows((0,)), panels)
        for p in (True, '0.5', math.nan, math.inf, -.1, 1.1, None, 10 ** 1000):
            with self.assertRaises(CurveError):
                evaluate_decision_curves(protocol(), rows((0,)), panel((p,)))

    def test_extreme_threshold_has_finite_json_output(self):
        value = evaluate_decision_curves(protocol((math.nextafter(1, 0),)), rows((0, None)), panel((1, 1)))
        json.dumps(value, allow_nan=False)
        self.assertTrue(math.isfinite(value['curves']['synthetic-model-A'][0]['netBenefitBounds']['lower']))


def classification_enumeration(labels, decisions):
    """Build each COMPLETE confusion matrix; retain undefined ratios separately."""
    unknown = [i for i, value in enumerate(labels) if value is None]
    counts = {key: [] for key in ('tp', 'fp', 'tn', 'fn')}
    ratios = {key: [] for key in ('sensitivity', 'specificity', 'positivePredictiveValue',
                                 'negativePredictiveValue', 'accuracy')}
    for replacement in product((0, 1), repeat=len(unknown)):
        y = list(labels)
        for i, value in zip(unknown, replacement):
            y[i] = value
        # Direct set intersections, independent of implementation aggregation.
        positives = {i for i, label in enumerate(y) if label == 1}
        negatives = set(range(len(y))) - positives
        predicted = {i for i, d in enumerate(decisions) if d}
        unselected = set(range(len(y))) - predicted
        tp, fp = len(predicted & positives), len(predicted & negatives)
        tn, fn = len(unselected & negatives), len(unselected & positives)
        for key, value in zip(counts, (tp, fp, tn, fn)):
            counts[key].append(value)
        for key, a, b in (('sensitivity', tp, len(positives)), ('specificity', tn, len(negatives)),
                           ('positivePredictiveValue', tp, len(predicted)),
                           ('negativePredictiveValue', tn, len(unselected)), ('accuracy', tp + tn, len(y))):
            ratios[key].append(F(a, b) if b else None)
    return counts, ratios


class ThresholdClassificationTests(unittest.TestCase):
    def classify(self, labels, decisions):
        return evaluate_decision_curves(protocol((.5,)), rows(labels), panel(decisions))[
            'curves']['synthetic-model-A'][0]['classification']

    def test_all_1296_label_and_selection_patterns_match_full_completion_enumeration(self):
        for labels in product((0, 1, None), repeat=4):
            for decisions in product((0, 1), repeat=4):
                actual = self.classify(labels, decisions)
                counts, ratios = classification_enumeration(labels, decisions)
                for key, values in counts.items():
                    self.assertEqual(actual['countBounds'][key], {'lower': min(values), 'upper': max(values)})
                for key, values in ratios.items():
                    defined = [x for x in values if x is not None]
                    if defined:
                        self.assertAlmostEqual(actual[key]['lower'], float(min(defined)))
                        self.assertAlmostEqual(actual[key]['upper'], float(max(defined)))
                    else:
                        self.assertIsNone(actual[key]['lower'])
                        self.assertIsNone(actual[key]['upper'])
                    self.assertEqual(actual[key]['denominatorMayBeZero'], None in values)
                    self.assertEqual(actual[key]['denominatorAlwaysZero'], not defined)

    def test_hand_calculated_complete_confusion_matrix(self):
        value = self.classify((1, 1, 0, 0), (1, 0, 1, 0))
        self.assertEqual(value['known'], dict(tp=1, fp=1, tn=1, fn=1))
        for key in ('sensitivity', 'specificity', 'positivePredictiveValue', 'negativePredictiveValue', 'accuracy'):
            self.assertEqual((value[key]['lower'], value[key]['upper']), (.5, .5))
            self.assertFalse(value[key]['denominatorMayBeZero'])

    def test_all_unknown_all_selected_sensitivity_defined_only_at_one(self):
        value = self.classify((None, None), (1, 1))
        self.assertEqual((value['sensitivity']['lower'], value['sensitivity']['upper']), (1, 1))
        self.assertTrue(value['sensitivity']['denominatorMayBeZero'])
        self.assertFalse(value['sensitivity']['denominatorAlwaysZero'])
        self.assertEqual((value['specificity']['lower'], value['specificity']['upper']), (0, 0))
        self.assertTrue(value['negativePredictiveValue']['denominatorAlwaysZero'])

    def test_all_unknown_none_selected_sensitivity_defined_only_at_zero(self):
        value = self.classify((None, None), (0, 0))
        self.assertEqual((value['sensitivity']['lower'], value['sensitivity']['upper']), (0, 0))
        self.assertEqual((value['specificity']['lower'], value['specificity']['upper']), (1, 1))
        self.assertTrue(value['specificity']['denominatorMayBeZero'])
        self.assertTrue(value['positivePredictiveValue']['denominatorAlwaysZero'])

    def test_known_single_class_undefined_metric_is_null(self):
        for labels, key in (((0, 0), 'sensitivity'), ((1, 1), 'specificity')):
            value = self.classify(labels, (1, 0))[key]
            self.assertIsNone(value['lower'])
            self.assertIsNone(value['upper'])
            self.assertTrue(value['denominatorAlwaysZero'])

    def test_zero_known_events_with_unknowns_keeps_full_population_counts(self):
        value = self.classify((0, 0, None, None), (1, 0, 1, 0))
        self.assertEqual(value['known'], dict(tp=0, fp=1, tn=1, fn=0))
        self.assertEqual((value['unknownSelected'], value['unknownUnselected']), (1, 1))
        self.assertEqual((value['accuracy']['lower'], value['accuracy']['upper']), (.25, .75))
        self.assertEqual((value['sensitivity']['lower'], value['sensitivity']['upper']), (0, 1))

    def test_unknown_counts_are_not_added_to_both_tp_and_fp_in_one_matrix(self):
        value = self.classify((None,), (1,))
        self.assertEqual(value['countBounds']['tp']['upper'], 1)
        self.assertEqual(value['countBounds']['fp']['upper'], 1)
        self.assertEqual(value['jointRangePolicy'], 'MARGINAL_PER_METRIC_NO_JOINT_COMPLETION_CLAIM')
        self.assertEqual(value['positivePredictiveValue']['upper'], 1)

    def test_competing_event_is_known_negative_in_classification(self):
        value = evaluate_decision_curves(protocol((.5,)), (O('a', 2, 1),), {'model': {'a': .5}})
        value = value['curves']['model'][0]['classification']
        self.assertEqual(value['known'], dict(tp=0, fp=1, tn=0, fn=0))
        self.assertEqual(value['specificity']['upper'], 0)
        self.assertTrue(value['sensitivity']['denominatorAlwaysZero'])

    def test_version_context_and_strict_finite_json_retained(self):
        value = evaluate_decision_curves(protocol(), rows((None, 0, 1)), panel((.5, .5, .5)))
        self.assertEqual(value['evaluationVersion'], 'fixed-horizon-decision-curve-bounds-v2')
        self.assertEqual(value['context']['thresholds'], [.25, .5, .75])
        json.dumps(value, allow_nan=False)


if __name__ == '__main__':
    unittest.main()
