"""记忆卡片语义抽取的本地 HTTP 边界（仅标准库）。

接口与 Java 侧 `MemoSemanticClient` 对齐：

  POST /api/memo/v1/extract   {"text": "..."}
  -> {"spans": [{"label": "TIME", "text": "下周三上午", "start": 0}]}

  GET /healthz
  -> {"status": "ok", "model": "uie-nano", "ready": true}

失败语义刻意做简单：任何不可用都返回 5xx，Java 侧把它当成「这次不用模型」，退回规则
抽取继续出卡。卡片生成不能因为可选模型挂掉而失败，所以这里不做重试、不做部分结果兜底。
"""
from __future__ import annotations

import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .spans import normalize
from .uie import UieExtractor, ModelUnavailable, supported_labels

MAX_BODY = 4 * 1024
API_PREFIX = '/api/memo/v1'
JSON_MEDIA = 'application/json; charset=utf-8'


def _size_or_zero(length):
    """把 Content-Length 转成可排空的字节数；缺失或非法一律当 0。"""
    try:
        return max(0, int(length))
    except (TypeError, ValueError):
        return 0


class MemoHandler(BaseHTTPRequestHandler):
    server_version = 'memo-semantic/1'
    extractor = None

    def log_message(self, fmt, *args):
        return

    def _send(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', JSON_MEDIA)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Connection', 'close')
        self.close_connection = True
        self.end_headers()
        self.wfile.write(body)

    def _drain(self, size):
        """把没读的请求体读掉再回错误码。

        Windows 上带着未读数据关闭 socket 会发 RST，客户端可能连响应一起丢掉，表现为
        `ConnectionAbortedError: WinError 10053`。所以拒绝请求前先把 body 排空，最多读到
        64KB 就停——真的超大请求不值得为它一直读下去。
        """
        remaining = min(size, MAX_BODY * 16)
        while remaining > 0:
            chunk = self.rfile.read(min(remaining, 4096))
            if not chunk:
                break
            remaining -= len(chunk)

    def do_GET(self):
        if self.path == '/healthz':
            self._send(200, {
                'status': 'ok',
                'model': getattr(self.extractor, 'model_dir', ''),
                'ready': bool(getattr(self.extractor, 'ready', lambda: False)()),
                'labels': list(supported_labels()),
            })
            return
        self._send(404, {'errorCode': 'NOT_FOUND'})

    def do_POST(self):
        length = self.headers.get('Content-Length')
        if self.path != API_PREFIX + '/extract':
            # 未知路径也要先排空请求体，否则客户端会丢掉这条 404（见 _drain 的说明）。
            self._drain(_size_or_zero(length))
            self._send(404, {'errorCode': 'NOT_FOUND'})
            return
        if length is None:
            self._send(411, {'errorCode': 'LENGTH_REQUIRED'})
            return
        try:
            size = int(length)
        except ValueError:
            self._drain(MAX_BODY)
            self._send(400, {'errorCode': 'BAD_REQUEST'})
            return
        if size <= 0 or size > MAX_BODY:
            self._drain(size)
            self._send(413, {'errorCode': 'PAYLOAD_TOO_LARGE'})
            return
        try:
            payload = json.loads(self.rfile.read(size).decode('utf-8'))
        except Exception:
            self._send(400, {'errorCode': 'BAD_REQUEST'})
            return
        text = payload.get('text') if isinstance(payload, dict) else None
        if not isinstance(text, str) or not text.strip():
            self._send(400, {'errorCode': 'BAD_REQUEST'})
            return
        try:
            raw = self.extractor.extract(text)
        except ValueError as too_long:
            self._send(400, {'errorCode': 'TEXT_TOO_LONG', 'message': str(too_long)})
            return
        except ModelUnavailable as unavailable:
            self._send(503, {'errorCode': 'MODEL_UNAVAILABLE', 'message': str(unavailable)})
            return
        except Exception as failure:
            self._send(500, {'errorCode': 'EXTRACTION_FAILED', 'message': str(failure)})
            return
        self._send(200, {'spans': normalize(raw, text)})


def build(host, port, extractor):
    handler = type('BoundMemoHandler', (MemoHandler,), {'extractor': extractor})
    return ThreadingHTTPServer((host, port), handler)


def main(argv=None):
    parser = argparse.ArgumentParser(description='记忆卡片 UIE 字段抽取服务')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8092)
    parser.add_argument('--model-dir', default=None)
    args = parser.parse_args(argv)
    server = build(args.host, args.port, UieExtractor(model_dir=args.model_dir))
    print(f'memo semantic service listening on http://{args.host}:{args.port}', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()