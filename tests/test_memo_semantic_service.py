"""UIE 片段校验与本地 HTTP 边界的测试（不依赖 paddlepaddle）。

模型本身不在这组测试里跑：加载模型需要额外依赖和下载，跑不动就整组跳过，等于没测。
这里钉住的是「模型说什么都不能直接信」这条边界——片段必须能在原文里找到、同字段的
嵌套片段要合并、超长要整段丢弃，以及服务不可用时必须明确返回 5xx 让 Java 退回规则。
"""
import json
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener

from memo_semantic_service import server
from memo_semantic_service.spans import flatten, normalize
from memo_semantic_service.uie import ModelUnavailable

HTTP = build_opener(ProxyHandler({}))


def _raw(*pairs):
    """构造 Taskflow 形状的原始输出：{label: [{text, start, end}]}。"""
    result = []
    for label, text in pairs:
        result.append({label: [{'text': text, 'start': 0, 'end': len(text)}]})
    return result


class FakeExtractor:
    model_dir = 'fake'

    def __init__(self, raw=None, failure=None):
        self.raw = raw or []
        self.failure = failure

    def ready(self):
        return self.failure is None

    def extract(self, text):
        if self.failure is not None:
            raise self.failure
        return self.raw


class NormalizeTests(unittest.TestCase):
    def test_spans_absent_from_the_source_are_dropped(self):
        kept = normalize(_raw(('TIME', '下周三'), ('PLACE', '急诊室')), '下周三去社区医院抽血')
        self.assertEqual([item['text'] for item in kept], ['下周三'])

    def test_nested_spans_of_the_same_label_keep_the_longer_one(self):
        kept = normalize(_raw(('TIME', '下周三'), ('TIME', '下周三上午九点')),
                         '下周三上午九点去社区医院抽血')
        self.assertEqual(len(kept), 1)
        self.assertEqual(kept[0]['text'], '下周三上午九点')

    def test_spans_are_ordered_by_their_position_in_the_source(self):
        kept = normalize(_raw(('ITEM', '医保卡'), ('TIME', '明天')), '明天去复诊要带医保卡')
        self.assertEqual([item['label'] for item in kept], ['TIME', 'ITEM'])
        self.assertEqual(kept[0]['start'], 0)

    def test_overlong_and_too_short_spans_are_discarded_whole(self):
        long_item = '医保卡、病历本、化验单、三个月内的全部检查报告单原件、出院小结复印件以及所有既往用药清单原件'
        kept = normalize(_raw(('ITEM', '卡'), ('ITEM', long_item)), '带' + long_item)
        self.assertEqual(kept, [])

    def test_unknown_labels_and_malformed_entries_are_ignored(self):
        raw = [{'TIME': [{'text': '明天', 'start': 0, 'end': 2}]}, {'MOOD': [{'text': '明天'}]}, 'nonsense', None]
        kept = normalize(raw, '明天去药房取药')
        self.assertEqual([item['text'] for item in kept], ['明天'])

    def test_flatten_ignores_values_that_are_not_dicts(self):
        self.assertEqual(list(flatten([{'TIME': ['明天']}])), [])

    def test_result_is_capped(self):
        source = ''.join(f'第{i}项、' for i in range(1, 13))
        raw = _raw(*[('ITEM', f'第{i}项') for i in range(1, 13)])
        self.assertEqual(len(normalize(raw, source)), 8)


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.httpd = server.build('127.0.0.1', 0, FakeExtractor())
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.base = f'http://127.0.0.1:{self.httpd.server_address[1]}'

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=5)

    def post(self, body, path='/api/memo/v1/extract'):
        data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode('utf-8')
        request = Request(self.base + path, data=data, method='POST',
                          headers={'Content-Type': 'application/json'})
        with HTTP.open(request, timeout=5) as response:
            return response.status, json.loads(response.read().decode('utf-8'))

    def get(self, path='/healthz'):
        with HTTP.open(self.base + path, timeout=5) as response:
            return response.status, json.loads(response.read().decode('utf-8'))

    def test_health_reports_the_model_name_and_readiness(self):
        status, payload = self.get()
        self.assertEqual(status, 200)
        self.assertEqual(payload['status'], 'ok')
        self.assertEqual(payload['model'], 'fake')
        self.assertTrue(payload['ready'])

    def test_extract_returns_only_spans_present_in_the_source(self):
        self.httpd.RequestHandlerClass.extractor = FakeExtractor(
            _raw(('TIME', '下周三上午九点'), ('PLACE', '急诊室')))
        status, payload = self.post({'text': '下周三上午九点去社区医院抽血'})
        self.assertEqual(status, 200)
        self.assertEqual(payload['spans'],
                         [{'label': 'TIME', 'text': '下周三上午九点', 'start': 0}])

    def test_blank_text_is_rejected(self):
        with self.assertRaises(HTTPError) as caught:
            self.post({'text': '   '})
        self.assertEqual(caught.exception.code, 400)

    def test_malformed_json_is_rejected(self):
        with self.assertRaises(HTTPError) as caught:
            self.post(b'{oops')
        self.assertEqual(caught.exception.code, 400)

    def test_oversized_body_is_rejected_before_parsing(self):
        with self.assertRaises(HTTPError) as caught:
            self.post({'text': 'x' * (server.MAX_BODY + 1)})
        self.assertEqual(caught.exception.code, 413)

    def test_unavailable_model_answers_503_so_the_caller_can_fall_back(self):
        self.httpd.RequestHandlerClass.extractor = FakeExtractor(failure=ModelUnavailable('未安装'))
        with self.assertRaises(HTTPError) as caught:
            self.post({'text': '明天上午去药房取药'})
        self.assertEqual(caught.exception.code, 503)
        payload = json.loads(caught.exception.read().decode('utf-8'))
        self.assertEqual(payload['errorCode'], 'MODEL_UNAVAILABLE')

    def test_unknown_path_is_not_found(self):
        with self.assertRaises(HTTPError) as caught:
            self.post({'text': '明天'}, path='/api/memo/v1/nope')
        self.assertEqual(caught.exception.code, 404)

    def test_server_builds_a_threading_http_server(self):
        self.assertIsInstance(self.httpd, ThreadingHTTPServer)


if __name__ == '__main__':
    unittest.main()