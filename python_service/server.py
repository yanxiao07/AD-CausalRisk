"""Local synthetic Agent HTTP boundary for independently developed Java clients."""
from __future__ import annotations
import argparse
import copy
from dataclasses import dataclass, field
from datetime import date,datetime,timezone
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
import re
import socket
import threading
import time
from urllib.parse import urlsplit,unquote
from uuid import uuid4

from .deepagent_contract import (CONTRACT_VERSION,WORKFLOW_VERSION,MODEL_VERSION,STAGES,
    AgentContractError,request_snapshot,request_identity_digest,run_workflow)
from .demo_scoring import ContractError,score_java_synthetic_payload
from .course_protocol import CourseContract,validate_course_result,utf8_json_media,identity_encoding

MAX_BODY=128*1024
REJECT_DRAIN_BYTES=MAX_BODY*2
REJECT_DRAIN_SECONDS=0.1
HTTP_API_VERSION='course-agent-http-v1'


def _execution_failed():
    return AgentContractError('工作流执行或结果校验失败，未保存可用结果',500,'ASSESSMENT_FAILED')


@dataclass
class _PendingRun:
    digest: str
    done: threading.Event = field(default_factory=threading.Event)
    result: dict | None = None

    def wait_result(self):
        # This is the original synchronous POST, not another workflow attempt.
        self.done.wait()
        if self.result is None:
            raise _execution_failed()
        return copy.deepcopy(self.result)


class RunStore:
    """Volatile completed results and bounded, per-run execution reservations."""

    def __init__(self,capacity=1000):
        self.capacity=capacity
        self.runs={}
        self._pending={}
        self.lock=threading.RLock()

    def create(self,payload):
        payload=request_snapshot(payload)
        digest=request_identity_digest(payload)
        run_id=payload['runId']
        with self.lock:
            cached=self.runs.get(run_id)
            pending=self._pending.get(run_id)
            if cached is not None:
                old,result=cached
                if old!=digest:
                    raise AgentContractError('runId已用于不同请求',409,'RUN_ID_CONFLICT')
            elif pending is not None:
                if pending.digest!=digest:
                    raise AgentContractError('runId已用于不同请求',409,'RUN_ID_CONFLICT')
            else:
                if len(self.runs)+len(self._pending)>=self.capacity:
                    raise AgentContractError('运行缓存已满；历史结果请从业务服务查询',503,'RUN_CAPACITY_REACHED')
                flight=_PendingRun(digest)
                self._pending[run_id]=flight
        # No computation, result validation, copying or waiting holds the
        # store lock. Unrelated POSTs and completed GETs remain independent.
        if cached is not None:
            return copy.deepcopy(result)
        if pending is not None:
            return pending.wait_result()
        try:
            # Take ownership before validation, so the exact checked object
            # becomes the cache entry rather than a later copy of tool state.
            result=copy.deepcopy(run_workflow(payload,score_java_synthetic_payload))
            validate_course_result(result,payload,CourseContract())
        except BaseException as error:
            # Wake every existing follower even on process/thread interruption.
            # Failed executions occupy no cache slot and followers never retry.
            with self.lock:
                del self._pending[run_id]
                flight.done.set()
            if not isinstance(error,Exception):
                raise
            raise _execution_failed() from None
        with self.lock:
            self.runs[run_id]=(digest,result)
            flight.result=result
            del self._pending[run_id]
            flight.done.set()
        return copy.deepcopy(result)

    def get(self,run_id):
        with self.lock:
            if run_id not in self.runs:
                raise AgentContractError('未找到该运行记录；运行缓存会在服务重启后清空，历史结果请从业务服务查询',404,'RUN_NOT_FOUND')
            result=self.runs[run_id][1]
        return copy.deepcopy(result)


class AgentServer(ThreadingHTTPServer):
    daemon_threads=True
    def __init__(self,address,store=None):
        self.store=store or RunStore()
        super().__init__(address,AgentHandler)


class AgentHandler(BaseHTTPRequestHandler):
    server:AgentServer
    def log_message(self,format,*args):pass
    def setup(self):
        super().setup();self.connection.settimeout(10)
    def send_json(self,payload,status=200):
        body=json.dumps(payload,ensure_ascii=False,allow_nan=False,separators=(',',':')).encode()
        self.send_response(status);self.send_header('Content-Type','application/json; charset=utf-8')
        self.send_header('Content-Length',str(len(body)));self.send_header('Cache-Control','no-store')
        self.send_header('X-Trace-Id',self.trace_id);self.send_header('Connection','close')
        self.end_headers();self.wfile.write(body);self.wfile.flush()
    def initialize_trace(self):
        supplied=self.headers.get('X-Trace-Id','')
        self.trace_id=supplied if re.fullmatch(r'[A-Za-z0-9._-]{1,64}',supplied) else uuid4().hex
    def failure(self,error):
        self.close_connection=True
        try:
            self.send_json({'code':error.code,'message':str(error),'traceId':self.trace_id,
                            'timestamp':datetime.now(timezone.utc).isoformat()},error.status)
            # Deliver the complete error before discarding unread rejected bytes.
            # Reading here never parses JSON, validates a payload or creates a run.
            self.connection.shutdown(socket.SHUT_WR)
            remaining=getattr(self,'unread_body',None)
            budget=REJECT_DRAIN_BYTES if remaining is None else min(remaining,REJECT_DRAIN_BYTES)
            deadline=time.monotonic()+REJECT_DRAIN_SECONDS
            while budget>0:
                timeout=deadline-time.monotonic()
                if timeout<=0:break
                self.connection.settimeout(timeout)
                discarded=self.rfile.read1(min(budget,8192))
                if not discarded:break
                budget-=len(discarded)
        except (OSError,TimeoutError,ValueError):
            pass  # Disconnected peers cannot receive a second error response.
    def do_GET(self):
        self.initialize_trace();path=unquote(urlsplit(self.path).path)
        try:
            if path=='/api/health':
                self.send_json({'status':'ok','serviceMode':'COURSE_SYNTHETIC','httpApiVersion':HTTP_API_VERSION,
                    'contractVersion':CONTRACT_VERSION,'workflowVersion':WORKFLOW_VERSION,'modelVersion':MODEL_VERSION,
                    'dataSource':'SYNTHETIC_DEMO','synchronousExecution':True,'persistentRunStore':False,
                    'researchEvidenceLoaded':False,'individualResearchModelDeployed':False,'stages':list(STAGES)})
            elif path.startswith('/api/agent/v1/runs/'):
                run_id=path.removeprefix('/api/agent/v1/runs/')
                if not re.fullmatch(r'run-[A-Za-z0-9][A-Za-z0-9-]{0,63}',run_id):raise AgentContractError('runId格式无效')
                self.send_json(self.server.store.get(run_id))
            else:raise AgentContractError('接口不存在',404,'ROUTE_NOT_FOUND')
        except AgentContractError as error:self.failure(error)
    def do_POST(self):
        self.initialize_trace()
        lengths=self.headers.get_all('Content-Length',[])
        self.unread_body=None
        if len(lengths)==1 and re.fullmatch(r'[0-9]{1,12}',lengths[0]):self.unread_body=int(lengths[0])
        try:
            if urlsplit(self.path).path!='/api/agent/v1/runs':raise AgentContractError('接口不存在',404,'ROUTE_NOT_FOUND')
            if not utf8_json_media(self.headers) or not identity_encoding(self.headers):raise AgentContractError('请求必须为UTF-8 application/json且不使用内容压缩',415,'UNSUPPORTED_MEDIA_TYPE')
            if self.headers.get_all('Transfer-Encoding',[]):raise AgentContractError('仅支持带Content-Length的JSON请求')
            if len(lengths)!=1:raise AgentContractError('需要唯一Content-Length')
            length=lengths[0]
            if not re.fullmatch(r'[0-9]+',length):raise AgentContractError('Content-Length无效')
            if len(length)>12:raise AgentContractError('请求超过128KiB',413,'PAYLOAD_TOO_LARGE')
            size=int(length)
            if size<1:raise AgentContractError('JSON请求不能为空')
            if size>MAX_BODY:raise AgentContractError('请求超过128KiB',413,'PAYLOAD_TOO_LARGE')
            def bad_constant(value):raise ValueError('JSON常量无效')
            def unique_object(pairs):
                result={}
                for key,value in pairs:
                    if key in result:raise ValueError('JSON键重复')
                    result[key]=value
                return result
            raw=self.rfile.read(size);self.unread_body=max(0,size-len(raw))
            if len(raw)!=size:raise AgentContractError('请求体长度与Content-Length不一致')
            payload=json.loads(raw.decode('utf-8'),parse_constant=bad_constant,object_pairs_hook=unique_object)
            if not isinstance(payload,dict):raise AgentContractError('JSON请求必须是对象')
        except AgentContractError as error:self.failure(error);return
        except ContractError as error:self.failure(AgentContractError(str(error),error.status,'VALIDATION_400'));return
        except (UnicodeDecodeError,ValueError,RecursionError):self.failure(AgentContractError('JSON或输入字段无效'));return
        except (TimeoutError,OSError):self.failure(AgentContractError('请求读取超时',408,'REQUEST_TIMEOUT'));return
        except Exception:self.failure(AgentContractError('评估执行失败，未生成可用结果',500,'ASSESSMENT_FAILED'));return
        try:result=self.server.store.create(payload)
        except AgentContractError as error:self.failure(error);return
        except ContractError as error:self.failure(AgentContractError(str(error),error.status,'VALIDATION_400'));return
        except Exception:self.failure(AgentContractError('评估执行失败，未生成可用结果',500,'ASSESSMENT_FAILED'));return
        try:self.send_json(result)
        except (OSError,TimeoutError):self.close_connection=True
    def do_OPTIONS(self):
        self.initialize_trace();self.failure(AgentContractError('前端应调用Java业务API；此服务不开放跨域调用',405,'METHOD_NOT_ALLOWED'))


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--host',default='127.0.0.1',choices=['127.0.0.1'])
    parser.add_argument('--port',type=int,default=8091);args=parser.parse_args()
    with AgentServer((args.host,args.port)) as server:
        print(json.dumps({'serviceMode':'COURSE_SYNTHETIC','url':f'http://{args.host}:{server.server_port}','persistentRunStore':False}),flush=True)
        try:server.serve_forever()
        except KeyboardInterrupt:pass


if __name__=='__main__':main()
