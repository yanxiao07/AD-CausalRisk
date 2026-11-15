"""Loopback HTTP host for the constructed research table interoperability cases.

Static modes are test fixtures.  ``--snapshot-dir`` is the dynamic host: the
runId is resolved against host-owned frozen bundles, never against a fixed
in-process table, and the agent is rebuilt from the bundle for every request.
"""

import argparse
from hmac import compare_digest
from hashlib import sha256
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import re

from python_service.course_protocol import CourseProtocolError, decode_json, identity_encoding, utf8_json_media
from python_service.deepagent_contract import RUN_ID_RE
from research_runtime.input_admission import POLICY, SCHEMA
from research_runtime.research_table_agent import ResearchAgentError, ResearchTableAgent
from research_runtime.research_table_protocol import digest


ROUTE = "/api/research-table/v1/assessments"


def cases(trained_manifest=None, trusted_sha256=None):
    agents = {}
    manifest = {}
    if trained_manifest is None:
        from research_runtime.tests.test_research_table_agent import configured

        prepared = [(label, *configured(spec, unknown_qc=label == "blocked"))
                    for label, spec in (("seven", "seven"), ("ten", "multimodal_values"), ("blocked", "seven"))]
    else:
        prepared = [("seven", *trained_case(trained_manifest, trusted_sha256))]
    for label, agent, request, _, context in prepared:
        if trained_manifest is None:
            request["runId"] = "run-constructed-http-" + label
        model = context["model"]
        core = dict(schemaVersion=SCHEMA, purpose="OFFLINE_RESEARCH_ADMISSION",
                    dataVersion=request["dataVersion"], indexDate=request["indexDate"],
                    availabilityPolicy=request["availabilityPolicy"], predictionWindowMonths=24,
                    model=model, records=[dict(subjectCode=request["subjectCode"], features=request["features"])])
        manifest[label] = dict(request=request, model=model, requestSha256=digest(request),
                               inputSha256=digest(core), contextSha256=digest(context))
        agents[request["runId"]] = agent
    return agents, manifest


def trained_case(manifest, trusted_sha256):
    from research_runtime.examples.input_admission_constructed import constructed_pair
    from research_runtime.inference import load_final_model
    from research_runtime.research_table_protocol import CONTRACT
    from research_runtime.runtime_source import runtime_source_sha256
    from research_runtime.table_assessment import TableAssessment

    bundle, verified = load_final_model(manifest, trusted_sha256, "seven", "logistic")
    fixture, context = constructed_pair("seven", "logistic")
    identity = context["model"]
    identity.update({name: verified[name] for name in ("manifestSha256", "artifactSha256", "trainingPlanSha256")})
    identity.update(runtimeSourceSha256=runtime_source_sha256(),
                    softwareLockSha256=sha256((Path(__file__).resolve().parents[1]
                                                / "requirements.lock").read_bytes()).hexdigest())
    record = fixture["records"][0]
    request = dict(contractVersion=CONTRACT, runId="",
                   modelKey="trained-seven-logistic", subjectCode=record["subjectCode"],
                   dataVersion=fixture["dataVersion"], indexDate=fixture["indexDate"],
                   availabilityPolicy=fixture["availabilityPolicy"], predictionWindowMonths=24,
                   features=record["features"])
    # The fixture runId is derived from its frozen request, not fixed in code.
    request["runId"] = "run-trained-" + digest({key: value for key, value in request.items()
                                                if key != "runId"})[:16]
    assessment = TableAssessment(bundle["model"], verified_identity=identity)
    agent = ResearchTableAgent(model_key=request["modelKey"], subject_code=record["subjectCode"],
                               assessment=assessment, trusted_context=context,
                               data_scope="CONSTRUCTED_VALIDATION")
    return agent, request, assessment, context


def static_resolver(agents):
    def resolve(run, body):
        if run not in agents:
            raise ResearchAgentError("RUN_ID_INVALID")
        return agents[run]
    return resolve


def handler(resolve, token):
    class ResearchHandler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def log_message(self, *_):
            pass

        def do_POST(self):
            if self.path != ROUTE:
                self.send_error(404)
                return
            try:
                try:
                    result = self.assess()
                except ResearchAgentError as error:
                    self.respond(error.http_status, error.response())
                else:
                    self.respond(200, result)
            except (TimeoutError, ConnectionError):
                self.close_connection = True

        def assess(self):
            supplied = self.headers.get_all("Authorization", [])
            if len(supplied) != 1 or not compare_digest(supplied[0].encode(), token):
                raise ResearchAgentError("INDEPENDENT_EXECUTION_PERMISSION_REQUIRED")
            lengths = self.headers.get_all("Content-Length", [])
            if (self.headers.get("Transfer-Encoding") is not None or len(lengths) != 1
                    or not re.fullmatch(r"[0-9]{1,10}", lengths[0])
                    or not utf8_json_media(self.headers) or not identity_encoding(self.headers)):
                raise ResearchAgentError("REQUEST_JSON_INVALID")
            length = int(lengths[0])
            if length > POLICY["maximumJSONBytes"]:
                raise ResearchAgentError("REQUEST_TOO_LARGE")
            raw = self.rfile.read(length)
            try:
                if len(raw) != length:
                    raise ValueError
                body = decode_json(raw)
            except (CourseProtocolError, ValueError, RecursionError, UnicodeError):
                raise ResearchAgentError("REQUEST_JSON_INVALID") from None
            if type(body) is not dict:
                raise ResearchAgentError("REQUEST_FIELDS_INVALID")
            run = body.get("runId")
            if not isinstance(run, str) or not RUN_ID_RE.fullmatch(run):
                raise ResearchAgentError("RUN_ID_INVALID")
            agent = resolve(run, body)
            result = agent.handle_json(raw, authorization_granted=True,
                prediction_authorization_ref="CONSTRUCTED-HTTP-PREDICT",
                explanation_authorization_ref="CONSTRUCTED-HTTP-EXPLAIN")
            print(json.dumps(dict(runId=run, status=result["status"], **result["computation"])), flush=True)
            return result

        def respond(self, status, payload):
            body = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    return ResearchHandler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--manifest", type=Path, required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--trained-manifest", type=Path)
    mode.add_argument("--contract-test", action="store_true")
    parser.add_argument("--trusted-manifest-sha256")
    parser.add_argument("--snapshot-dir", type=Path,
                        help="Host-owned frozen runId bundle directory (dynamic mode).")
    args = parser.parse_args()
    if bool(args.trained_manifest) != bool(args.trusted_manifest_sha256):
        parser.error("The trained model requires its trusted manifest SHA-256.")
    if args.snapshot_dir is not None and args.contract_test:
        parser.error("Dynamic snapshot mode requires the trained model manifest.")
    token = os.environ.get("RESEARCH_TABLE_TEST_TOKEN", "")
    if not re.fullmatch(r"[A-Za-z0-9._~+/-]+=*", token):
        parser.error("Set RESEARCH_TABLE_TEST_TOKEN to a valid bearer token")
    try:
        if args.snapshot_dir is not None:
            from research_runtime.dynamic_host import DynamicResearchHost

            host = DynamicResearchHost(args.trained_manifest, args.trusted_manifest_sha256,
                                       args.snapshot_dir)
            resolve = host.resolve
            manifest = dict(mode="dynamic", cases={}, snapshots=host.describe())
        else:
            agents, manifest = cases(args.trained_manifest, args.trusted_manifest_sha256)
            resolve = static_resolver(agents)
            manifest = dict(mode="trained" if args.trained_manifest else "contract-test", cases=manifest)
    except (ValueError, OSError, KeyError, TypeError):
        parser.exit(1, "Research model or assessment configuration unavailable.\n")
    with HTTPServer(("127.0.0.1", args.port), handler(resolve, ("Bearer " + token).encode())) as server:
        with args.manifest.open("x", encoding="utf-8") as stream:
            json.dump(dict(port=server.server_port, **manifest), stream, ensure_ascii=False)
        server.serve_forever()


if __name__ == "__main__":
    main()
