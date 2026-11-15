"""Dynamic host resolution tests; model deserialisation is replaced by a stub."""
from hashlib import sha256
from pathlib import Path
from unittest import TestCase, mock

from research_runtime.dynamic_host import DynamicResearchHost
from research_runtime.dynamic_snapshot import bundle_from_parts, write_bundle
from research_runtime.examples.input_admission_constructed import constructed_pair
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


if __name__ == "__main__":
    import unittest
    unittest.main()
