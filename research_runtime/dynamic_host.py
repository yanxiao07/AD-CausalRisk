"""Dynamic research table host: one trusted model, many frozen snapshots.

The runId is looked up in a host-owned directory.  The incoming request is
compared with the frozen request digest, and the agent is rebuilt from the
bundle's trusted context; no model path or context is accepted from the wire.
"""
from copy import deepcopy
from hashlib import sha256
from pathlib import Path

from .dynamic_snapshot import (DATA_SCOPES, SnapshotDirectory,
                               DynamicSnapshotError, verify_bundle)
from .inference import load_final_model
from .research_table_agent import ResearchAgentError, ResearchTableAgent
from .research_table_protocol import digest
from .runtime_source import runtime_source_sha256
from .table_assessment import TableAssessment


class DynamicResearchHost:
    def __init__(self, manifest_path, trusted_manifest_sha256, snapshot_directory):
        bundle, verified = load_final_model(
            manifest_path, trusted_manifest_sha256, "seven", "logistic")
        self._bundle = bundle
        self._verified = verified
        self._runtime_source_sha256 = runtime_source_sha256()
        self._software_lock_sha256 = sha256(
            (Path(__file__).resolve().parent / "requirements.lock").read_bytes()).hexdigest()
        self._snapshots = SnapshotDirectory(snapshot_directory)
        self._assessments = {}

    @property
    def run_ids(self):
        return self._snapshots.run_ids

    def describe(self):
        values = []
        for run_id in self._snapshots.run_ids:
            bundle = self._snapshots.resolve(run_id)
            values.append(dict(runId=run_id, snapshotId=bundle.snapshot_id,
                               subjectId=bundle.subject_id,
                               dataScope=bundle.data_scope))
        return values

    def _verify_model(self, model):
        for key in ("manifestSha256", "artifactSha256", "trainingPlanSha256"):
            if model.get(key) != self._verified[key]:
                raise ValueError("MODEL_IDENTITY_MISMATCH")
        # runtimeSourceSha256 belongs to the trained model's provenance, not to
        # the host checkout.  The host attests its own code separately (status
        # endpoint) and must not reject a model whose training runtime differs.
        if model.get("softwareLockSha256") != self._software_lock_sha256:
            raise ValueError("SOFTWARE_LOCK_MISMATCH")
        if (model.get("family") != "logistic" or model.get("specification") != "seven"
                or model.get("role") != "FULL_COHORT_SEVEN_VARIABLE_RESEARCH_MODEL"
                or model.get("predictionPolicy") != "raw_uncalibrated_24month_CIF"):
            raise ValueError("MODEL_BINDING_MISMATCH")

    def _assessment(self, snapshot):
        key = digest(snapshot.model)
        assessment = self._assessments.get(key)
        if assessment is None:
            assessment = TableAssessment(self._bundle["model"],
                                         verified_identity=snapshot.model)
            self._assessments[key] = assessment
        return assessment

    def resolve(self, run_id, body):
        """Return a freshly bound agent, or raise a stable protocol error."""
        if type(run_id) is not str:
            raise ResearchAgentError("RUN_ID_INVALID")
        try:
            snapshot = self._snapshots.resolve(run_id)
        except DynamicSnapshotError:
            raise ResearchAgentError("HOST_BINDING_INVALID") from None
        if snapshot is None:
            raise ResearchAgentError("RUN_ID_INVALID")
        if digest(body) != snapshot.request_sha256:
            raise ResearchAgentError("SNAPSHOT_BINDING_MISMATCH", run_id)
        try:
            self._verify_model(snapshot.model)
            assessment = self._assessment(snapshot)
            if snapshot.data_scope not in DATA_SCOPES:
                raise ValueError("DATA_SCOPE_MISMATCH")
            return ResearchTableAgent(
                model_key=snapshot.request["modelKey"],
                subject_code=snapshot.request["subjectCode"],
                assessment=assessment, trusted_context=deepcopy(snapshot.context),
                data_scope=snapshot.data_scope)
        except ResearchAgentError:
            raise
        except Exception:
            raise ResearchAgentError("HOST_BINDING_INVALID", run_id) from None
