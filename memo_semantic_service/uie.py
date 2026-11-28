"""uie-nano 的懒加载封装。

只在第一次抽取时加载模型，原因有两个：服务启动不该因为模型下载卡住；没有模型时健康
检查仍能回答「未就绪」，Java 侧据此直接退回规则抽取。

模型路径可以用环境变量覆盖（`MEMO_UIE_MODEL_DIR`），指向本地已下载的模型目录，避免
运行期联网。默认的 `uie-nano` 名字交给 Taskflow 自己解析。

加载和推理都固定在一个常驻工作线程里。这不是为了省事，而是实测踩出来的：Paddle 的
静态图推理依赖线程本地状态，模型在 A 线程构建、在 B 线程推理时，`Executor.run` 会拿
回静态变量而不是 numpy 数组，报 `'Tensor' object has no attribute 'numpy'`。HTTP 服务
每个请求一个新线程，所以必须把这两步钉在同一个线程上，否则第一波请求之后就全线 503。
"""
from __future__ import annotations

import os
import queue
import threading

from .spans import LABELS, SCHEMA

MODEL_NAME = 'uie-nano'
MAX_TEXT_LENGTH = 500


class ModelUnavailable(RuntimeError):
    """模型加载或推理不可用。调用方据此返回 503，让 Java 退回规则抽取。"""


class UieExtractor:
    """懒加载抽取器：模型加载与推理都在同一个常驻线程里串行执行。"""

    def __init__(self, model_dir=None, schema=None):
        self.model_dir = model_dir or os.environ.get('MEMO_UIE_MODEL_DIR') or MODEL_NAME
        self.schema = schema or SCHEMA
        self._taskflow = None
        self._queue = queue.Queue()
        self._worker = None
        self._worker_lock = threading.Lock()
        self._ready = threading.Event()

    def ready(self):
        return self._ready.is_set()

    def _ensure_worker(self):
        if self._worker is not None and self._worker.is_alive():
            return
        with self._worker_lock:
            if self._worker is not None and self._worker.is_alive():
                return
            self._worker = threading.Thread(target=self._loop, name='uie-worker', daemon=True)
            self._worker.start()

    def _loop(self):
        while True:
            text, reply = self._queue.get()
            try:
                reply['result'] = self._infer(text)
            except Exception as failure:
                reply['error'] = failure
            finally:
                reply['done'].set()

    def _infer(self, text):
        taskflow = self._load()
        return taskflow(text)

    def _load(self):
        if self._taskflow is not None:
            return self._taskflow
        try:
            from paddlenlp import Taskflow
        except Exception as failure:
            raise ModelUnavailable(f'paddlenlp 不可用：{failure}') from failure
        try:
            self._taskflow = Taskflow(
                'information_extraction',
                schema=self.schema,
                model=self.model_dir,
                device_id=-1,
            )
        except Exception as failure:
            raise ModelUnavailable(f'uie 模型加载失败：{failure}') from failure
        self._ready.set()
        return self._taskflow

    def extract(self, text, timeout=None):
        """返回 Taskflow 原始结构；校验交给 spans.normalize。"""
        if not text or not text.strip():
            return []
        if len(text) > MAX_TEXT_LENGTH:
            # 超长文本不截断：截断会切掉半句，抽出来的片段反而对不上原文。
            raise ValueError('text 过长')
        self._ensure_worker()
        reply = {'done': threading.Event(), 'result': None, 'error': None}
        self._queue.put((text, reply))
        if not reply['done'].wait(timeout):
            raise ModelUnavailable('uie 推理超时')
        if reply['error'] is not None:
            raise ModelUnavailable(f'uie 推理失败：{reply["error"]}') from reply['error']
        return reply['result']


def supported_labels():
    return LABELS