import copy
import csv
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from python_service.course_report import build_course_report, render_course_csv, CourseReportError
from python_service.deepagent_contract import run_workflow
from python_service.demo_scoring import score_java_synthetic_payload

ROOT = Path(__file__).resolve().parents[1]


def pair():
    return tuple(json.loads((ROOT / 'contracts/examples' / name).read_text(encoding='utf-8'))
                 for name in ('deepagent-v1.request.example.json', 'deepagent-v1.response.example.json'))


def decode(text):
    rows = list(csv.reader(io.StringIO(text, newline='')))
    assert rows[0] == ['field', 'valueJson']
    assert len({row[0] for row in rows[1:]}) == len(rows) - 1
    assert all(len(row) == 2 for row in rows)
    return {field: json.loads(value) for field, value in rows[1:]}


class ReportCsvTests(unittest.TestCase):
    def test_complete_report_losslessly_roundtrips_with_all_versions_and_boundaries(self):
        request, result = pair()
        actual = decode(render_course_csv(request, result))
        self.assertEqual(actual, build_course_report(request, result))
        self.assertEqual(actual['dataSource'], 'SYNTHETIC_DEMO')
        self.assertFalse(actual['clinicalValidityEstablished'])
        self.assertEqual(actual['riskScore'], .8304)

    def test_blocked_remains_null_unavailable_with_original_window(self):
        request, _ = pair()
        request['predictionWindowMonths'] = 12
        result = run_workflow(request, score_java_synthetic_payload)
        report = decode(render_course_csv(request, result))
        self.assertIsNone(report['riskScore'])
        self.assertEqual(report['riskLevel'], 'UNAVAILABLE')
        self.assertFalse(report['predictionAvailable'])
        self.assertEqual(report['predictionWindowMonths'], 12)

    def test_simplified_audience_never_reintroduces_redacted_content(self):
        request, result = pair()
        report = decode(render_course_csv(request, result, audience='teaching'))
        self.assertIsNone(report['sources'])
        self.assertIsNone(report['graph'])
        self.assertEqual(report['stages'], [])
        self.assertTrue(all(row['contribution'] is None for row in report['explanations']))

    def test_special_cells_keep_unicode_quotes_commas_lines_and_cannot_start_formulas(self):
        for code in ('=1+1', '+SUM(1,2)', '-1+1', '@SUM(1,2)', '\t=1+1', '中文,"\r\n=1+1'):
            with self.subTest(code=repr(code)):
                request, result = pair()
                request['visits'][0]['visitCode'] = code
                text = render_course_csv(request, result)
                self.assertEqual(decode(text)['sources']['visits'][0]['visitCode'], code)
                cells = list(csv.reader(io.StringIO(text, newline='')))
                self.assertTrue(all(not cell.startswith(('=', '+', '-', '@', '\t', '\r', '\n'))
                                    for row in cells for cell in row))

    def test_null_zero_false_and_original_precision_stay_distinct(self):
        request, _ = pair()
        request['visits'][0]['mmse'] = None
        request['visits'][-1]['mmse'] = 0
        request['visits'][-1]['moca'] = 22.123456
        result = run_workflow(request, score_java_synthetic_payload)
        report = decode(render_course_csv(request, result))
        self.assertIsNone(report['sources']['visits'][0]['mmse'])
        self.assertEqual(report['sources']['visits'][-1]['mmse'], 0)
        self.assertEqual(report['sources']['visits'][-1]['moca'], 22.123456)
        self.assertIs(report['modelExecutedByReport'], False)

    def test_export_does_not_modify_inputs_or_execute_models_or_network(self):
        request, result = pair()
        before = copy.deepcopy((request, result))
        with (patch('python_service.deepagent_contract.run_workflow', side_effect=AssertionError('model')),
              patch('socket.socket', side_effect=AssertionError('network'))):
            render_course_csv(request, result)
        self.assertEqual((request, result), before)

    def test_deterministic_bytes_and_unchanged_html_json_report_values(self):
        request, result = pair()
        a = render_course_csv(request, result)
        b = render_course_csv(dict(reversed(list(request.items()))), dict(reversed(list(result.items()))))
        self.assertEqual(a, b)
        self.assertTrue(a.endswith('\r\n'))
        self.assertEqual(decode(a), build_course_report(request, result))

    def test_invalid_or_real_inputs_rejected_before_csv(self):
        for value in ('REAL_RETROSPECTIVE_RESEARCH', None):
            request, result = pair()
            result['dataSource'] = value
            with self.assertRaises(CourseReportError):
                render_course_csv(request, result)
        request, result = pair()
        result['riskScore'] = float('nan')
        with self.assertRaises(CourseReportError):
            render_course_csv(request, result)

    def test_cli_csv_uses_new_target_only_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / 'report.csv'
            command = [sys.executable, '-B', '-m', 'python_service.course_report',
                       '--request', 'contracts/examples/deepagent-v1.request.example.json',
                       '--result', 'contracts/examples/deepagent-v1.response.example.json',
                       '--output', str(output), '--format', 'csv']
            run = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)
            original = output.read_bytes()
            self.assertEqual(decode(original.decode('utf-8'))['riskScore'], .8304)
            run = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
            self.assertNotEqual(run.returncode, 0)
            self.assertEqual(output.read_bytes(), original)


if __name__ == '__main__':
    unittest.main()
