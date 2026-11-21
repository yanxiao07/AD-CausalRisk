"""Product response text, exact legacy acceptance and real loopback transport."""
import copy
import http.client
import json
from pathlib import Path
import threading
import unittest

from python_service.course_client import CourseClient
from python_service.course_protocol import CourseContract, CourseProtocolError, validate_course_result
from python_service.course_report import build_course_report
from python_service.deepagent_contract import run_workflow
from python_service.demo_scoring import score_java_synthetic_payload
from python_service.server import AgentServer, RunStore

ROOT = Path(__file__).resolve().parents[1]
NEW = 'CDR-SB 信号在当前排序中贡献上移'
OLD = 'CDR-SB 信号在当前演示排序中贡献上移'


def request():
    return json.loads((ROOT / 'contracts/examples/deepagent-v1.request.example.json').read_text(encoding='utf-8'))


def cdr(result):
    return next(row for row in result['explanations'] if row['feature'] == 'CDR_SB')


class ProductResponseCopyTests(unittest.TestCase):
    def test_new_result_uses_product_copy_and_preserves_original_score_and_scope(self):
        payload = request()
        result = run_workflow(payload, score_java_synthetic_payload)
        self.assertEqual(cdr(result)['message'], NEW)
        self.assertEqual(result['riskScore'], .8304)
        self.assertEqual(result['dataSource'], 'SYNTHETIC_DEMO')
        self.assertEqual(result['modelVersion'], payload['modelVersion'])
        self.assertNotIn('演示排序', json.dumps(result, ensure_ascii=False))

    def test_legacy_saved_text_is_accepted_without_rewriting_the_original(self):
        payload = request()
        result = run_workflow(payload, score_java_synthetic_payload)
        cdr(result)['message'] = OLD
        before = copy.deepcopy(result)
        self.assertIs(validate_course_result(result, payload, CourseContract()), result)
        self.assertEqual(result, before)

    def test_other_text_and_causal_claims_still_rejected(self):
        payload = request()
        for message in (NEW + '。', 'CDR-SB 已证明个体病因', OLD.replace('贡献上移', '治疗有效')):
            with self.subTest(message=message):
                result = run_workflow(payload, score_java_synthetic_payload)
                cdr(result)['message'] = message
                with self.assertRaises(CourseProtocolError) as error:
                    validate_course_result(result, payload, CourseContract())
                self.assertEqual(error.exception.code, 'OUTPUT_EXPLANATION_TEXT')

    def test_report_reads_both_current_and_legacy_text_without_changing_it(self):
        payload = request()
        result = run_workflow(payload, score_java_synthetic_payload)
        for message in (OLD, NEW):
            cdr(result)['message'] = message
            report = build_course_report(payload, result)
            self.assertEqual(cdr(report)['message'], message)
            self.assertEqual(report['riskScore'], .8304)
            self.assertFalse(report['clinicalValidityEstablished'])

    def test_actual_http_client_and_cache_errors_use_product_copy(self):
        server = AgentServer(('127.0.0.1', 0), RunStore(capacity=2))
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        def wire(method, path, payload=None):
            connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=5)
            try:
                body = None if payload is None else json.dumps(payload).encode('utf-8')
                connection.request(method, path, body, {'Content-Type': 'application/json'})
                response = connection.getresponse()
                return response.status, json.loads(response.read())
            finally:
                connection.close()
        try:
            client = CourseClient(f'http://127.0.0.1:{server.server_port}')
            self.assertEqual(client.health()['serviceMode'], 'COURSE_SYNTHETIC')
            payload = request()
            result = client.create(payload)
            self.assertEqual(cdr(result)['message'], NEW)
            self.assertEqual(client.result(payload['runId'], expected_request=payload), result)
            blocked = dict(payload, runId='run-product-copy-blocked', visits=[])
            status, result = wire('POST', '/api/agent/v1/runs', blocked)
            self.assertEqual(status, 200)
            self.assertIsNone(result['riskScore'])
            status, error = wire('GET', '/api/agent/v1/runs/run-product-copy-missing')
            self.assertEqual((status, error['code']), (404, 'RUN_NOT_FOUND'))
            self.assertIn('服务重启后清空', error['message'])
            self.assertNotIn('演示', error['message'])
            status, error = wire('POST', '/api/agent/v1/runs', dict(payload, runId='run-product-copy-full'))
            self.assertEqual((status, error['code']), (503, 'RUN_CAPACITY_REACHED'))
            self.assertIn('业务服务查询', error['message'])
            self.assertNotIn('演示', error['message'])
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=5)
        self.assertFalse(worker.is_alive())


if __name__ == '__main__':
    unittest.main()
