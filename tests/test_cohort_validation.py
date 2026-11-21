import unittest

from research_runtime.cohort_validation import evaluate


def cases(outcome, probability, count):
    return [{"outcome": outcome, "probability": probability} for _ in range(count)]


class CohortValidationTests(unittest.TestCase):
    def test_six_cases_in_one_cell_can_report_brier_but_have_no_auc(self):
        report = evaluate(cases("AD", 0.15, 6))
        self.assertEqual(report["status"], "COMPLETED")
        self.assertAlmostEqual(report["metrics"]["knownSubsetBrier"], (1 - 0.15) ** 2)
        self.assertIsNone(report["metrics"]["knownSubsetAuc"])

    def test_small_unknown_cell_suppresses_all_metrics(self):
        report = evaluate(cases("AD", 0.15, 5) + cases("UNKNOWN", 0.15, 1))
        self.assertEqual(report["status"], "SUPPRESSED")
        self.assertIsNone(report["metrics"])

    def test_auc_and_rate_bounds_use_the_declared_end_point(self):
        report = evaluate(cases("AD", 0.9, 5) + cases("OTHER_DEMENTIA", 0.1, 5)
                          + cases("UNKNOWN", 0.15, 5))
        metrics = report["metrics"]
        self.assertEqual(metrics["knownSubsetAuc"], 1)
        self.assertEqual(metrics["knownCases"], 10)
        self.assertEqual(metrics["unknownCases"], 5)
        self.assertAlmostEqual(metrics["observedRateBounds"]["lower"], 5 / 15)
        self.assertAlmostEqual(metrics["observedRateBounds"]["upper"], 10 / 15)

    def test_invalid_rows_are_rejected_before_aggregation(self):
        for probability in (True, float("nan"), -0.1, 1.1):
            with self.subTest(probability=probability):
                with self.assertRaisesRegex(ValueError, "INVALID_VALIDATION_ROW"):
                    evaluate(cases("AD", probability, 5))
