"""Loopback HTTP host for the constructed research table interoperability cases."""

import argparse
from hmac import compare_digest
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import re

from python_service.course_protocol import CourseProtocolError, decode_json, identity_encoding, utf8_json_media
from research_runtime.input_admission import POLICY, SCHEMA
from research_runtime.research_table_agent import ResearchAgentError
from research_runtime.research_table_protocol import digest
from research_runtime.tests.test_research_table_agent import configured


ROUTE = "/api/research-table/v1/assessments"


def cases():
    agents = {}
    manifest = {}
    for label, spec in (("seven", "seven"), ("ten", "multimodal_values"), ("blocked", "seven")):
        agent, request, _, context = configured(spec, unknown_qc=label == "blocked")
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


def handler(agents, token):
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
            run = body.get("runId")
            if not isinstance(run, str) or run not in agents:
                raise ResearchAgentError("RUN_ID_INVALID")
            result = agents[run].handle_json(raw, authorization_granted=True,
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
    args = parser.parse_args()
    token = os.environ.get("RESEARCH_TABLE_TEST_TOKEN", "")
    if not re.fullmatch(r"[A-Za-z0-9._~+/-]+=*", token):
        parser.error("Set RESEARCH_TABLE_TEST_TOKEN to a valid bearer token")
    agents, manifest = cases()
    with HTTPServer(("127.0.0.1", args.port), handler(agents, ("Bearer " + token).encode())) as server:
        with args.manifest.open("x", encoding="utf-8") as stream:
            json.dump(dict(port=server.server_port, cases=manifest), stream, ensure_ascii=False)
        server.serve_forever()


if __name__ == "__main__":
    main()
