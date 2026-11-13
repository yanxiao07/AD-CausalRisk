"""Portable synthetic Java-client fault lab, vectors and actual HTTP acceptance.

Separate test harness; never changes the production AgentServer or shared API.
Only owned ephemeral loopback servers are started by this module.
"""
import argparse
import copy
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import socket
import subprocess
import sys
import threading
from .course_client import CourseClient, CourseClientError
from .course_protocol import CourseContract, decode_json, validate_course_result
from .course_recovery import reconcile_course_run
from .deepagent_contract import run_workflow
from .demo_scoring import score_java_synthetic_payload
from .server import AgentServer, AgentHandler, RunStore

ROOT = Path(__file__).resolve().parents[1]
SCENARIOS = ('ok', 'error500', 'capacity503', 'wrong-run', 'wrong-version',
             'blocked-zero', 'malformed-json', 'wrong-trace', 'redirect',
             'commit-drop', 'commit-delay', 'wrong-health')
FAULT_EXPECTATIONS = {'error500': ('ASSESSMENT_FAILED', 500, False),
                      'capacity503': ('RUN_CAPACITY_REACHED', 503, False),
                      'wrong-run': ('RESULT_IDENTITY', 200, True),
                      'wrong-version': ('WIRE_SCHEMA_MISMATCH', 200, True),
                      'blocked-zero': ('WIRE_SCHEMA_MISMATCH', 200, True),
                      'malformed-json': ('INVALID_RESPONSE_JSON', 200, True),
                      'wrong-trace': ('RESPONSE_TRACE', 200, True),
                      'redirect': ('REDIRECT_REFUSED', 302, True),
                      'commit-drop': ('LOCAL_TRANSPORT_FAILED', None, True),
                      'commit-delay': ('LOCAL_TRANSPORT_FAILED', None, True),
                      'wrong-health': ('SERVICE_CONTRACT_MISMATCH', None, False)}
SOURCE_FILES = ('contracts/python-agent.openapi.json', 'python_service/server.py', 'python_service/course_clock.py', 'python_service/course_fusion.py', 'python_service/course_graph.py',
                'python_service/deepagent_contract.py', 'python_service/demo_scoring.py',
                'python_service/course_protocol.py', 'python_service/course_client.py',
                'python_service/course_recovery.py', 'python_service/course_interop.py',
                'contracts/examples/deepagent-v1.request.example.json')


def source_hashes():
    return {name: hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in SOURCE_FILES}


def vector_requests():
    original = decode_json((ROOT/'contracts/examples/deepagent-v1.request.example.json').read_bytes())
    cases = []
    for name in ('completed', 'blocked-window', 'blocked-cognition', 'warn-missing', 'fractional', 'zero'):
        request = copy.deepcopy(original); request['runId'] = 'run-interop-'+name
        if name == 'blocked-window': request['predictionWindowMonths'] = 12
        elif name == 'blocked-cognition': request['visits'] = []
        elif name == 'warn-missing': request['features'] = []
        elif name == 'fractional':
            request['visits'][-1].update(mmse=25.44, moca=22.123456, cdrSb=.55)
        elif name == 'zero': request['visits'][-1].update(mmse=0, moca=0, cdrSb=0)
        cases.append((name, request))
    return cases


def build_interop_pack():
    cases = []
    contract = CourseContract()
    for name, request in vector_requests():
        result = run_workflow(request, score_java_synthetic_payload)
        validate_course_result(result, request, contract)
        for stage in result['stages']: stage.pop('durationMs')
        cases.append({'id': name, 'request': request, 'expectedCore': result})
    return {'packVersion': 'course-interop-v1', 'scope': 'COURSE_SYNTHETIC_INTEROP',
            'contractSha256': contract.sha256, 'sourceSha256': source_hashes(),
            'cases': cases, 'faultScenarios': list(SCENARIOS),
            'faultExpectations': [{'scenario': name, 'pythonClientCode': values[0],
                                   'httpStatus': values[1], 'submissionOutcomeUnknown': values[2],
                                   'automaticResubmissionAllowed': False} for name, values in FAULT_EXPECTATIONS.items()],
            'comparisonExcludedFields': ['stages[*].durationMs'],
            'notes': ['expectedCore is a comparison projection, not an AgentResult DTO.',
                      'Golden cores share the unchanged course workflow; not independent algorithm validation.',
                      'Durations must be finite and nonnegative in real responses; compare original replay durations exactly.',
                      'Java owns authorization, business idempotency, persisted history and audit.'],
            'javaIntegrationValidated': False, 'clinicalValidityAssessed': False}


class LabStore(RunStore):
    def __init__(self, scenario):
        super().__init__(capacity=0 if scenario == 'capacity503' else 1000)
        self.scenario = scenario; self.create_calls = 0

    def create(self, payload):
        with self.lock:
            self.create_calls += 1
            if self.scenario == 'error500': raise RuntimeError('CONSTRUCTED_FAULT_NOT_FOR_CLIENT')
            return super().create(payload)


class LabHandler(AgentHandler):
    def end_headers(self):
        self.send_header('X-Course-Fault-Lab', 'synthetic-test-only')
        super().end_headers()

    def do_POST(self):
        with self.server.store.lock: self.server.post_calls += 1
        super().do_POST()

    def send_json(self, payload, status=200):
        scenario = self.server.scenario
        payload = copy.deepcopy(payload)
        if self.command == 'GET' and self.path == '/api/health' and scenario == 'wrong-health':
            payload['serviceMode'] = 'FAULT_LAB_WRONG_SERVICE'
        if self.command == 'POST' and status == 200:
            if scenario == 'commit-drop':
                self.close_connection = True
                try: self.connection.shutdown(socket.SHUT_RDWR)
                except OSError: pass
                return
            if scenario == 'commit-delay': self.server.release_response.wait(3)
            elif scenario == 'wrong-run': payload['runId'] = 'run-fault-unrelated'
            elif scenario == 'wrong-version': payload['workflowVersion'] = 'fault-workflow'
            elif scenario == 'blocked-zero':
                payload['riskScore'] = 0; payload['riskLevel'] = 'LOW'
            elif scenario == 'wrong-trace': self.trace_id = 'fault-trace-unrelated'
            elif scenario in ('malformed-json', 'redirect'):
                raw = b'{' if scenario == 'malformed-json' else b'{}'
                self.send_response(200 if scenario == 'malformed-json' else 302)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(raw)))
                self.send_header('X-Trace-Id', self.trace_id)
                self.send_header('Connection', 'close')
                if scenario == 'redirect': self.send_header('Location', 'http://example.invalid/')
                self.end_headers(); self.wfile.write(raw); self.wfile.flush(); return
        try: super().send_json(payload, status)
        except (OSError, TimeoutError): self.close_connection = True


class FaultLabServer(AgentServer):
    def __init__(self, scenario):
        if scenario not in SCENARIOS: raise ValueError('FAULT_SCENARIO')
        self.scenario = scenario; self.release_response = threading.Event(); self.post_calls = 0
        super().__init__(('127.0.0.1', 0), LabStore(scenario))
        self.RequestHandlerClass = LabHandler


@contextmanager
def owned_lab(scenario='ok'):
    server = FaultLabServer(scenario)
    thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=.05), daemon=True)
    thread.start()
    try: yield 'http://127.0.0.1:'+str(server.server_port), server
    finally:
        server.release_response.set(); server.shutdown(); server.server_close(); thread.join(timeout=5)


@contextmanager
def original_process(port=0):
    """Owned original server process; no existing service is stopped or reused."""
    process = subprocess.Popen([sys.executable, '-B', '-m', 'python_service.server',
                                '--legacy-contract-test', '--port', str(port)],
                               cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
                               creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    startup = []; ready = threading.Event()
    def read_startup():
        startup.append(process.stdout.readline()); ready.set()
    reader = threading.Thread(target=read_startup, daemon=True); reader.start()
    try:
        if not ready.wait(10) or not startup or not startup[0]: raise RuntimeError('SERVER_START_FAILED')
        yield json.loads(startup[0])['url']
    finally:
        if process.poll() is None: process.terminate()
        try: process.wait(timeout=5)
        except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
        reader.join(timeout=1); process.stdout.close()


def run_interop_acceptance(*, observe=None):
    checks = []; observations = []
    pack = build_interop_pack()
    def seen(name, value):
        if observe: observe(name, value)
    with owned_lab() as (url, server):
        client = CourseClient(url)
        for case in pack['cases']:
            request = case['request']; result = client.create(request)
            seen('AgentRequest', request); seen('AgentResult', result)
            core = copy.deepcopy(result)
            for stage in core['stages']: stage.pop('durationMs')
            assert core == case['expectedCore']
            assert client.create(dict(reversed(list(request.items())))) == result
            assert reconcile_course_run(client, request)['result'] == result
        assert server.post_calls == 12 and len(server.store.runs) == 6
        checks.extend(['six_exported_vectors_actual_http_match_cores',
                       'semantic_key_order_replays_keep_original_durations',
                       'read_only_reconciliation_does_not_submit'])
        request = pack['cases'][0]['request']; changed = copy.deepcopy(request)
        changed['predictionWindowMonths'] = 12
        try: client.create(changed)
        except CourseClientError as error: assert error.code == 'RUN_ID_CONFLICT' and error.http_status == 409
        else: raise AssertionError('CONFLICT_ACCEPTED')
        assert reconcile_course_run(client, request)['lookupStatus'] == 'FOUND'
        checks.append('409_conflict_keeps_original_result')
    for scenario, wanted in FAULT_EXPECTATIONS.items():
        request = copy.deepcopy(pack['cases'][1 if scenario == 'blocked-zero' else 0]['request'])
        with owned_lab(scenario) as (url, server):
            client = CourseClient(url, timeout=.5)
            try: client.create(request)
            except CourseClientError as error:
                assert (error.code, error.http_status, error.request_outcome_unknown) == wanted, scenario
                observations.append({'scenario': scenario, 'code': error.code,
                                     'httpStatus': error.http_status, 'outcomeUnknown': error.request_outcome_unknown})
            else: raise AssertionError('FAULT_ACCEPTED')
            assert server.post_calls == (0 if scenario == 'wrong-health' else 1)
            if scenario in ('commit-drop', 'commit-delay'):
                assert len(server.store.runs) == 1
                recovered = reconcile_course_run(client, request)
                assert recovered['lookupStatus'] == 'FOUND' and recovered['result']['runId'] == request['runId']
                seen('AgentResult', recovered['result'])
                assert recovered['resubmissionAuthorized'] is False and server.post_calls == 1
            elif scenario in ('error500', 'capacity503', 'wrong-health'):
                assert not server.store.runs
            checks.append('actual_fault_'+scenario)
    request = pack['cases'][0]['request']
    with original_process() as url:
        client = CourseClient(url); result = client.create(request)
        assert client.result(request['runId']) == result
        port = client.port
    with original_process(port) as restarted:
        assert restarted == url
        report = reconcile_course_run(CourseClient(restarted), request)
        assert report['lookupStatus'] == 'CACHE_MISS' and report['result'] is None
        assert report['executionOccurred'] is None and report['resubmissionAuthorized'] is False
        again = reconcile_course_run(CourseClient(restarted), request)
        assert again['lookupStatus'] == 'CACHE_MISS'
    checks.append('actual_original_process_restart_same_port_loses_cache_without_resubmission')
    return {'status': 'PASSED', 'passed': len(checks), 'checks': checks, 'faultObservations': observations,
            'checkedAtUtc': datetime.now(timezone.utc).isoformat(), 'sourceSha256': source_hashes(),
            'exportedVectors': 6, 'ownedEphemeralServersClosed': True, 'originalServerProcessesRestarted': True,
            'originalApiOrScoringChanged': False, 'live8091Or8084Modified': False,
            'javaIntegrationValidated': False, 'clinicalValidityAssessed': False,
            'restrictedRecordsRead': False, 'newFits': 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--self-test', action='store_true')
    mode.add_argument('--export', type=Path, metavar='NEW_JSON_FILE')
    mode.add_argument('--serve', choices=SCENARIOS)
    args = parser.parse_args()
    try:
        if args.export:
            pack = build_interop_pack()
            with args.export.open('x', encoding='utf-8', newline='\n') as stream:
                json.dump(pack, stream, ensure_ascii=False, indent=2, allow_nan=False)
            print(json.dumps({'status': 'CREATED', 'vectors': len(pack['cases']), 'networkRequests': 0}))
        elif args.self_test: print(json.dumps(run_interop_acceptance(), ensure_ascii=False))
        else:
            with FaultLabServer(args.serve) as server:
                print(json.dumps({'scope': 'SYNTHETIC_FAULT_LAB_NOT_PRODUCTION', 'scenario': args.serve,
                                  'url': 'http://127.0.0.1:'+str(server.server_port), 'persistentRunStore': False}), flush=True)
                try: server.serve_forever(poll_interval=.05)
                except KeyboardInterrupt: pass
                finally: server.release_response.set()
        return 0
    except Exception as error:
        print(json.dumps({'status': 'FAILED', 'code': getattr(error, 'code', 'INTEROP_FAILED'),
                          'errorType': type(error).__name__})); return 1


if __name__ == '__main__': raise SystemExit(main())
