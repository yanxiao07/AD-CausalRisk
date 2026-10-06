"""Local synthetic Agent HTTP boundary for independently developed Java clients."""
from __future__ import annotations
import argparse
import copy
from datetime import date,datetime,timezone
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
import re
import threading
from urllib.parse import urlsplit,unquote
from uuid import uuid4

from .deepagent_contract import (CONTRACT_VERSION,WORKFLOW_VERSION,MODEL_VERSION,STAGES,
    AgentContractError,validate_request,payload_digest,run_workflow)
from .demo_scoring import ContractError,score_java_synthetic_payload

MAX_BODY=128*1024
HTTP_API_VERSION='course-agent-http-v1'


class RunStore:
    def __init__(self,capacity=1000):
        self.capacity=capacity;self.runs={};self.lock=threading.RLock()
    def create(self,payload):
        validate_request(payload)
        # Wire dates use the ISO calendar form declared in OpenAPI.
        for row,key in [(x,'visitDate') for x in payload['visits']]+[(x,'recordedAt') for x in payload['features']]:
            if not re.fullmatch(r'\d{4}-\d{2}-\d{2}',row[key]):raise AgentContractError('日期应为YYYY-MM-DD')
        digest=payload_digest(payload);run_id=payload['runId']
        with self.lock:
            if run_id in self.runs:
                old,result=self.runs[run_id]
                if old!=digest:raise AgentContractError('runId已用于不同请求',409,'RUN_ID_CONFLICT')
                return copy.deepcopy(result)
            if len(self.runs)>=self.capacity:raise AgentContractError('本机演示缓存已满，请由Java保存历史结果',503,'RUN_CAPACITY_REACHED')
            result=run_workflow(payload,score_java_synthetic_payload)
            self.runs[run_id]=(digest,result)
            return copy.deepcopy(result)
    def get(self,run_id):
        with self.lock:
            if run_id not in self.runs:raise AgentContractError('runId不存在；演示缓存重启后清空',404,'RUN_NOT_FOUND')
            return copy.deepcopy(self.runs[run_id][1])


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
        self.send_header('X-Trace-Id',self.trace_id);self.end_headers();self.wfile.write(body)
    def initialize_trace(self):
        supplied=self.headers.get('X-Trace-Id','')
        self.trace_id=supplied if re.fullmatch(r'[A-Za-z0-9._-]{1,64}',supplied) else uuid4().hex
    def failure(self,error):
        self.send_json({'code':error.code,'message':str(error),'traceId':self.trace_id,
                        'timestamp':datetime.now(timezone.utc).isoformat()},error.status)
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
        try:
            if urlsplit(self.path).path!='/api/agent/v1/runs':raise AgentContractError('接口不存在',404,'ROUTE_NOT_FOUND')
            if self.headers.get_content_type()!='application/json':raise AgentContractError('请求必须为application/json',415,'UNSUPPORTED_MEDIA_TYPE')
            if self.headers.get('Transfer-Encoding'):raise AgentContractError('仅支持带Content-Length的JSON请求')
            length=self.headers.get('Content-Length','')
            if not length.isdecimal():raise AgentContractError('Content-Length无效')
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
            payload=json.loads(self.rfile.read(size).decode('utf-8'),parse_constant=bad_constant,object_pairs_hook=unique_object)
            if not isinstance(payload,dict):raise AgentContractError('JSON请求必须是对象')
            self.send_json(self.server.store.create(payload))
        except AgentContractError as error:self.failure(error)
        except ContractError as error:self.failure(AgentContractError(str(error),error.status,'VALIDATION_400'))
        except (UnicodeDecodeError,ValueError,RecursionError):self.failure(AgentContractError('JSON或输入字段无效'))
        except (TimeoutError,OSError):self.failure(AgentContractError('请求读取超时',408,'REQUEST_TIMEOUT'))
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
