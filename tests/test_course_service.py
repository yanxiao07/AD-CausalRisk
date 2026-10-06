import copy
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import threading
import unittest
from urllib.request import Request,urlopen
from urllib.error import HTTPError

from python_service.server import AgentServer,RunStore
from python_service.deepagent_contract import run_workflow,AgentContractError
from python_service.demo_scoring import score_java_synthetic_payload

ROOT=Path(__file__).resolve().parents[1]


def fixture():return json.loads((ROOT/'contracts/examples/deepagent-v1.request.example.json').read_text(encoding='utf-8'))


class WorkflowTests(unittest.TestCase):
    def test_original_example_is_reproduced(self):
        expected=json.loads((ROOT/'contracts/examples/deepagent-v1.response.example.json').read_text(encoding='utf-8'))
        actual=run_workflow(fixture(),score_java_synthetic_payload)
        for row in actual['stages']:row['durationMs']=0
        self.assertEqual(actual,expected)
    def test_blocked_has_no_score_and_only_executed_stages(self):
        body=fixture();body['visits']=[]
        result=RunStore().create(body)
        self.assertEqual(result['status'],'BLOCKED');self.assertIsNone(result['riskScore'])
        self.assertEqual(result['riskLevel'],'UNAVAILABLE')
        self.assertEqual([s['stage'] for s in result['stages']],['DATA_FUSION'])
    def test_missing_modalities_warn(self):
        body=fixture();body['features']=[]
        result=RunStore().create(body);self.assertEqual(result['qualityStatus'],'WARN')
        self.assertTrue(result['missingModalities']);self.assertEqual(result['dataSource'],'SYNTHETIC_DEMO')
    def test_post_origin_feature_is_excluded(self):
        body=fixture();body['features'].append({'featureName':'APOE_E4_COUNT','featureValue':2,'recordedAt':'2026-02-01','unit':'count'})
        result=RunStore().create(body)
        self.assertEqual(result['riskScore'],RunStore().create(fixture())['riskScore'])
        self.assertEqual(result['stages'][0]['summary']['excludedPostIndexFeatures'],1)
    def test_conflicting_same_date_blocks(self):
        body=fixture();body['features'].append({**body['features'][0],'featureValue':2})
        self.assertIsNone(RunStore().create(body)['riskScore'])
    def test_non_demo_identifiers_and_versions_rejected(self):
        for key,value in [('subjectCode','REAL-001'),('dataVersion','research-v1'),('modelVersion','trained-model'),('contractVersion','v0')]:
            body=fixture();body[key]=value
            with self.subTest(key=key),self.assertRaises(AgentContractError):RunStore().create(body)
    def test_out_of_range_and_invalid_units_rejected(self):
        for field,value in [('mmse',31),('cdrSb',-1),('moca',True)]:
            body=fixture();body['visits'][0][field]=value
            with self.subTest(field=field),self.assertRaises(AgentContractError):RunStore().create(body)
        body=fixture();body['features'][0]['unit']='mg'
        with self.assertRaises(AgentContractError):RunStore().create(body)
    def test_future_and_non_iso_dates_rejected(self):
        for value in ['2999-01-01','20250101']:
            body=fixture();body['visits'][0]['visitDate']=value
            with self.subTest(value=value),self.assertRaises(AgentContractError):RunStore().create(body)
    def test_duplicate_same_request_and_concurrency_share_cached_result(self):
        store=RunStore()
        with ThreadPoolExecutor(max_workers=8) as pool:results=list(pool.map(lambda _:store.create(fixture()),range(16)))
        self.assertTrue(all(x==results[0] for x in results));self.assertEqual(len(store.runs),1)
        results[0]['warnings'].append('mutated')
        self.assertNotIn('mutated',store.get(fixture()['runId'])['warnings'])
    def test_conflicting_run_id_is_409(self):
        store=RunStore();store.create(fixture());body=fixture();body['predictionWindowMonths']=12
        with self.assertRaises(AgentContractError) as context:store.create(body)
        self.assertEqual(context.exception.status,409)
    def test_restart_does_not_pretend_persistence(self):
        store=RunStore();store.create(fixture())
        with self.assertRaises(AgentContractError) as context:RunStore().get(fixture()['runId'])
        self.assertEqual(context.exception.status,404)
    def test_capacity_blocks_new_records_but_allows_replay(self):
        store=RunStore(capacity=1);expected=store.create(fixture())
        self.assertEqual(store.create(fixture()),expected)
        body=fixture();body['runId']='run-second'
        with self.assertRaises(AgentContractError) as context:store.create(body)
        self.assertEqual(context.exception.status,503)


class HttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server=AgentServer(('127.0.0.1',0));cls.thread=threading.Thread(target=cls.server.serve_forever,daemon=True);cls.thread.start()
        cls.base=f'http://127.0.0.1:{cls.server.server_port}'
    @classmethod
    def tearDownClass(cls):cls.server.shutdown();cls.server.server_close();cls.thread.join()
    def request(self,path,body=None,content_type='application/json',method=None):
        data=body if isinstance(body,bytes) else json.dumps(body,ensure_ascii=False).encode() if body is not None else None
        req=Request(self.base+path,data=data,headers={'Content-Type':content_type,'X-Trace-Id':'test-trace'},method=method)
        try:response=urlopen(req,timeout=5)
        except HTTPError as exc:response=exc
        with response:return response.status,json.loads(response.read()),dict(response.headers)
    def test_health_declares_synthetic_nonpersistent_mode(self):
        status,body,_=self.request('/api/health')
        self.assertEqual(status,200);self.assertEqual(body['serviceMode'],'COURSE_SYNTHETIC')
        self.assertFalse(body['researchEvidenceLoaded']);self.assertFalse(body['persistentRunStore'])
    def test_http_create_lookup_replay_conflict_and_schema(self):
        from jsonschema import Draft202012Validator,FormatChecker
        from referencing import Registry,Resource
        spec=json.loads((ROOT/'contracts/python-agent.openapi.json').read_text(encoding='utf-8'))
        def validator(name):
            root={'$schema':'https://json-schema.org/draft/2020-12/schema',**spec}
            registry=Registry().with_resource('urn:course-contract',Resource.from_contents(root))
            return Draft202012Validator({'$ref':'urn:course-contract#/components/schemas/'+name},registry=registry,format_checker=FormatChecker())
        payload=fixture();payload['runId']='run-http-success'
        validator('AgentRequest').validate(payload)
        status,body,headers=self.request('/api/agent/v1/runs',payload)
        self.assertEqual(status,200);validator('AgentResult').validate(body)
        self.assertEqual(headers['X-Trace-Id'],'test-trace')
        self.assertEqual(self.request('/api/agent/v1/runs/run-http-success')[1],body)
        self.assertEqual(self.request('/api/agent/v1/runs',payload)[1],body)
        payload['predictionWindowMonths']=12
        status,error,_=self.request('/api/agent/v1/runs',payload)
        self.assertEqual(status,409);validator('AgentError').validate(error)
    def test_http_blocked_result(self):
        from jsonschema import Draft202012Validator
        body=fixture();body.update(runId='run-http-blocked',visits=[])
        status,result,_=self.request('/api/agent/v1/runs',body)
        self.assertEqual(status,200);self.assertIsNone(result['riskScore']);self.assertEqual(result['qualityStatus'],'BLOCKED')
        spec=json.loads((ROOT/'contracts/python-agent.openapi.json').read_text(encoding='utf-8'))
        Draft202012Validator(spec['components']['schemas']['AgentResult']).validate(result)
    def test_non_24_month_window_returns_blocked_not_a_fabricated_score(self):
        body=fixture();body.update(runId='run-http-other-window',predictionWindowMonths=12)
        status,result,_=self.request('/api/agent/v1/runs',body)
        self.assertEqual(status,200);self.assertEqual(result['status'],'BLOCKED');self.assertIsNone(result['riskScore'])
        self.assertTrue(any('24' in warning for warning in result['warnings']))
    def test_malformed_json_and_nonfinite_values(self):
        for payload in [b'{',b'[]',b'{"runId":"a","runId":"b"}',b'{"x":NaN}']:
            with self.subTest(payload=payload):self.assertEqual(self.request('/api/agent/v1/runs',payload)[0],400)
    def test_content_type_and_body_size(self):
        self.assertEqual(self.request('/api/agent/v1/runs',b'{}','text/plain')[0],415)
        self.assertEqual(self.request('/api/agent/v1/runs',b' '*(128*1024+1))[0],413)
    def test_unknown_routes_and_runs_are_not_research_endpoints(self):
        self.assertEqual(self.request('/api/research/predict')[0],404)
        self.assertEqual(self.request('/api/agent/v1/runs/run-unknown')[0],404)
        self.assertEqual(self.request('/../../data/nacc/raw')[0],404)
    def test_browser_direct_call_not_enabled(self):
        status,body,headers=self.request('/api/agent/v1/runs',method='OPTIONS')
        self.assertEqual(status,405);self.assertNotIn('Access-Control-Allow-Origin',headers)


if __name__=='__main__':unittest.main()
