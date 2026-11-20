"""Dynamic host resolution tests; model deserialisation is replaced by a stub."""
from hashlib import sha256
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase, mock
from http.server import HTTPServer
from threading import Thread
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import json
import os
import sys

from research_runtime.dynamic_host import DynamicResearchHost
from research_runtime.dynamic_snapshot import DynamicSnapshotError, bundle_from_parts, write_bundle
from research_runtime.examples.input_admission_constructed import constructed_pair
from research_runtime.examples.research_table_http import MODEL_STATUS_ROUTE, handler
from research_runtime.research_table_protocol import CONTRACT, ResearchAgentError


RUN_ID = "run-dynamic-0123456789abcdef"
DIRECTORY = Path(".tmp") / "dynamic-host-tests"
RUNTIME_SHA = "e" * 64


class StubAssessment:
    def __init__(self, model, *, verified_identity):
        self.model_identity = verified_identity


def lock_sha():
    return sha256((Path("research_runtime") / "requirements.lock").read_bytes()).hexdigest()


def parts(run_id=RUN_ID):
    fixture, context = constructed_pair("seven", "logistic")
    model = context["model"]
    model.update(manifestSha256="a" * 64, artifactSha256="b" * 64,
                 trainingPlanSha256="c" * 64, runtimeSourceSha256=RUNTIME_SHA,
                 softwareLockSha256=lock_sha())
    record = fixture["records"][0]
    request = dict(contractVersion=CONTRACT, runId=run_id,
                   modelKey="trained-seven-logistic", subjectCode=record["subjectCode"],
                   dataVersion=fixture["dataVersion"], indexDate=fixture["indexDate"],
                   availabilityPolicy=fixture["availabilityPolicy"],
                   predictionWindowMonths=24, features=record["features"])
    verified = dict(manifestSha256="a" * 64, artifactSha256="b" * 64,
                    trainingPlanSha256="c" * 64)
    return request, model, context, verified


class DynamicHostTests(TestCase):
    def setUp(self):
        DIRECTORY.mkdir(parents=True, exist_ok=True)
        for entry in DIRECTORY.iterdir():
            if entry.is_file():
                entry.unlink()
        request, model, context, verified = parts()
        self.request, self.model, self.context = request, model, context
        self.verified = verified
        self.bundle = bundle_from_parts(run_id=RUN_ID, request=request, model=model,
                                        context=context, snapshot_id=3, subject_id=203)

    def tearDown(self):
        for entry in DIRECTORY.iterdir():
            if entry.is_file():
                entry.unlink()

    def host(self):
        with mock.patch("research_runtime.dynamic_host.load_final_model",
                        return_value=({"model": self.model}, self.verified)), \
                mock.patch("research_runtime.dynamic_host.runtime_source_sha256",
                           return_value=RUNTIME_SHA), \
                mock.patch("research_runtime.dynamic_host.TableAssessment", StubAssessment):
            return DynamicResearchHost("unused.json", "0" * 64, DIRECTORY)

    def test_known_run_builds_a_bound_agent(self):
        write_bundle(DIRECTORY / "one.json", self.bundle)
        agent = self.host().resolve(RUN_ID, self.request)
        self.assertEqual(agent._subject_code, self.request["subjectCode"])
        self.assertEqual(agent._model_key, "trained-seven-logistic")

    def test_unknown_run_is_rejected(self):
        with self.assertRaises(ResearchAgentError) as caught:
            self.host().resolve("run-unknown", self.request)
        self.assertEqual(caught.exception.code, "RUN_ID_INVALID")

    def test_model_status_reports_only_verified_models_to_authorized_callers(self):
        host = self.host()
        with HTTPServer(("127.0.0.1", 0), handler(host.resolve, b"Bearer model-test", models=host.model_status)) as server:
            worker = Thread(target=server.serve_forever, daemon=True)
            worker.start()
            endpoint = f"http://127.0.0.1:{server.server_port}{MODEL_STATUS_ROUTE}"
            try:
                with self.assertRaises(HTTPError) as unauthorized:
                    urlopen(endpoint, timeout=2)
                self.assertEqual(unauthorized.exception.code, 401)
                with urlopen(Request(endpoint, headers={"Authorization": "Bearer model-test"}), timeout=2) as response:
                    payload = json.load(response)
                self.assertEqual(payload, {"models": [{"specification": "seven", "family": "logistic",
                    "manifestSha256": "a" * 64, "artifactSha256": "b" * 64}]})
                self.assertEqual(host.run_ids, ())
            finally:
                server.shutdown()
                worker.join(timeout=2)

    def test_request_digest_mismatch_is_rejected(self):
        write_bundle(DIRECTORY / "one.json", self.bundle)
        changed = dict(self.request, indexDate="2020-01-03")
        with self.assertRaises(ResearchAgentError) as caught:
            self.host().resolve(RUN_ID, changed)
        self.assertEqual(caught.exception.code, "SNAPSHOT_BINDING_MISMATCH")

    def test_foreign_model_identity_is_rejected(self):
        self.model["manifestSha256"] = "f" * 64
        self.context["model"] = self.model
        # The bundle remains internally consistent; the host's verified identity differs.
        write_bundle(DIRECTORY / "one.json",
                     bundle_from_parts(run_id=RUN_ID, request=self.request,
                                       model=self.model, context=self.context))
        with self.assertRaises(ResearchAgentError) as caught:
            self.host().resolve(RUN_ID, self.request)
        self.assertEqual(caught.exception.code, "HOST_BINDING_INVALID")

    def test_register_is_idempotent_for_the_same_bundle(self):
        host = self.host()
        first = host.register(self.bundle)
        second = host.register(self.bundle)
        self.assertEqual(first["status"], "SYNCED")
        self.assertFalse(first["idempotent"])
        self.assertEqual(second["status"], "SYNCED")
        self.assertTrue(second["idempotent"])
        self.assertIn(RUN_ID, [item["runId"] for item in host.describe()])

    def test_register_rejects_a_changed_bundle_for_the_same_run(self):
        host = self.host()
        host.register(self.bundle)
        changed = dict(self.request, indexDate="2020-01-04")
        context = dict(self.context, indexDate="2020-01-04")
        conflicting = bundle_from_parts(run_id=RUN_ID, request=changed, model=self.model,
                                       context=context)
        with self.assertRaises(DynamicSnapshotError) as caught:
            host.register(conflicting)
        self.assertEqual(str(caught.exception), "RUN_ID_CONFLICT")


class DynamicHostCliTests(TestCase):
    def test_refuses_to_start_without_an_independent_manifest_digest(self):
        from research_runtime import dynamic_host

        with TemporaryDirectory() as directory:
            manifest = Path(directory) / "manifest.json"
            manifest.write_text("{}", encoding="utf-8")
            with mock.patch.object(sys, "argv", [
                    "dynamic_host", "--manifest", str(manifest), "--token", "token"]):
                with self.assertRaises(SystemExit) as caught:
                    dynamic_host.main()
        self.assertEqual(caught.exception.code, 2)

    def test_refuses_non_loopback_binding_without_explicit_opt_in(self):
        from research_runtime import dynamic_host

        with TemporaryDirectory() as directory:
            manifest = Path(directory) / "manifest.json"
            manifest.write_text("{}", encoding="utf-8")
            digest = sha256(manifest.read_bytes()).hexdigest()
            with mock.patch.object(sys, "argv", [
                    "dynamic_host", "--manifest", str(manifest),
                    "--trusted-manifest-sha256", digest,
                    "--token", "token", "--host", "0.0.0.0"]):
                with self.assertRaises(SystemExit) as caught:
                    dynamic_host.main()
        self.assertEqual(caught.exception.code, 2)

    def test_refuses_to_start_without_a_token(self):
        from research_runtime import dynamic_host

        with TemporaryDirectory() as directory:
            manifest = Path(directory) / "manifest.json"
            manifest.write_text("{}", encoding="utf-8")
            digest = sha256(manifest.read_bytes()).hexdigest()
            with mock.patch.dict(os.environ, {"RESEARCH_TABLE_TEST_TOKEN": ""}), \
                    mock.patch.object(sys, "argv", [
                        "dynamic_host", "--manifest", str(manifest),
                        "--trusted-manifest-sha256", digest]):
                with self.assertRaises(SystemExit) as caught:
                    dynamic_host.main()
        self.assertEqual(caught.exception.code, 2)


if __name__ == "__main__":
    import unittest
    unittest.main()
