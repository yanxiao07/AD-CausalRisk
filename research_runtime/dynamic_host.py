"""Dynamic research table host: one trusted model, many frozen snapshots.

The runId is looked up in a host-owned directory.  The incoming request is
compared with the frozen request digest, and the agent is rebuilt from the
bundle's trusted context; no model path or context is accepted from the wire.

Run ``python -m research_runtime.dynamic_host --help`` for the local demo CLI.
"""
import argparse
import json
import os
from copy import deepcopy
from dataclasses import dataclass
from hashlib import sha256
from http.server import HTTPServer
from pathlib import Path

from python_service.course_protocol import CourseProtocolError, decode_json
from python_service.deepagent_contract import RUN_ID_RE
from .dynamic_snapshot import (DATA_SCOPES, SnapshotDirectory,
                               DynamicSnapshotError, load_bundle, verify_bundle,
                               write_bundle)
from .inference import load_final_model
from .input_admission import _sha, _token
from .lock_digest import lock_sha256
from .research_table_agent import ResearchAgentError, ResearchTableAgent
from .research_table_protocol import digest
from .runtime_source import runtime_source_sha256
from .table_assessment import TableAssessment


MAXIMUM_AUTHORIZATION_BYTES = 4096
CONSTRUCTED_HTTP_REFERENCES = {"CONSTRUCTED-HTTP-PREDICT", "CONSTRUCTED-HTTP-EXPLAIN"}


@dataclass(frozen=True)
class ControlledExecutionAuthorization:
    run_id: str
    snapshot_id: int
    subject_id: int
    request_sha256: str
    input_sha256: str
    context_sha256: str
    prediction_ref: str
    explanation_ref: str

    @classmethod
    def from_file(cls, path, snapshot_directory):
        """Read one operator-managed grant outside the HTTP registration directory."""
        try:
            path = Path(path).resolve(strict=True)
            if path.is_relative_to(Path(snapshot_directory).resolve()) or not path.is_file():
                raise ValueError
            with path.open("rb") as stream:
                raw = stream.read(MAXIMUM_AUTHORIZATION_BYTES + 1)
            if len(raw) > MAXIMUM_AUTHORIZATION_BYTES:
                raise ValueError
            grant = decode_json(raw)
            fields = {"runId", "snapshotId", "subjectId", "requestSha256", "inputSha256",
                      "contextSha256", "predictionAuthorizationRef", "explanationAuthorizationRef"}
            if type(grant) is not dict or set(grant) != fields:
                raise ValueError
            run_id = grant["runId"]
            snapshot_id, subject_id = grant["snapshotId"], grant["subjectId"]
            request = grant["requestSha256"]
            inputs, context = grant["inputSha256"], grant["contextSha256"]
            prediction, explanation = grant["predictionAuthorizationRef"], grant["explanationAuthorizationRef"]
            if (type(run_id) is not str or not RUN_ID_RE.fullmatch(run_id)
                    or type(snapshot_id) is not int or snapshot_id <= 0
                    or type(subject_id) is not int or subject_id <= 0
                    or not all(_sha(value) for value in (request, inputs, context))
                    or not all(_token(ref) for ref in (prediction, explanation))
                    or prediction in CONSTRUCTED_HTTP_REFERENCES
                    or explanation in CONSTRUCTED_HTTP_REFERENCES):
                raise ValueError
        except (CourseProtocolError, OSError, ValueError, RecursionError):
            raise ValueError("CONTROLLED_EXECUTION_AUTHORIZATION_INVALID") from None
        return cls(run_id, snapshot_id, subject_id, request, inputs, context, prediction, explanation)

    def references_for(self, snapshot):
        if (snapshot.data_scope == "CONTROLLED_RESEARCH"
                and self.run_id == snapshot.run_id and self.snapshot_id == snapshot.snapshot_id
                and self.subject_id == snapshot.subject_id
                and self.request_sha256 == snapshot.request_sha256
                and self.input_sha256 == snapshot.input_sha256
                and self.context_sha256 == snapshot.context_sha256):
            return (self.request_sha256, self.prediction_ref, self.explanation_ref)
        return None


class DynamicResearchHost:
    def __init__(self, manifest_path, trusted_manifest_sha256, snapshot_directory, *, additional_models=(),
                 controlled_authorization_file=None):
        self._controlled_authorization = (None if controlled_authorization_file is None else
            ControlledExecutionAuthorization.from_file(controlled_authorization_file, snapshot_directory))
        bundle, verified = load_final_model(
            manifest_path, trusted_manifest_sha256, "seven", "logistic")
        self._models = {("seven", "logistic"): (bundle, verified)}
        for specification, family, manifest, pin in additional_models:
            key = (specification, family)
            if key in self._models:
                raise ValueError("DUPLICATE_TABLE_MODEL")
            self._models[key] = load_final_model(manifest, pin, specification, family)
        self._runtime_source_sha256 = runtime_source_sha256()
        self._software_lock_sha256 = lock_sha256()
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

    def model_status(self):
        return [dict(specification=specification, family=family,
                     manifestSha256=verified["manifestSha256"], artifactSha256=verified["artifactSha256"])
                for (specification, family), (_, verified) in sorted(self._models.items())]

    def _verify_model(self, model):
        key = (model["specification"], model["family"])
        if key not in self._models:
            raise ValueError("TABLE_MODEL_NOT_CONFIGURED")
        bundle, verified = self._models[key]
        for key in ("manifestSha256", "artifactSha256", "trainingPlanSha256"):
            if model.get(key) != verified[key]:
                raise ValueError("MODEL_IDENTITY_MISMATCH")
        # runtimeSourceSha256 belongs to the trained model's provenance, not to
        # the host checkout.  The host attests its own code separately (status
        # endpoint) and must not reject a model whose training runtime differs.
        if model.get("softwareLockSha256") != self._software_lock_sha256:
            raise ValueError("SOFTWARE_LOCK_MISMATCH")
        return bundle

    def _assessment(self, snapshot):
        key = digest(snapshot.model)
        assessment = self._assessments.get(key)
        if assessment is None:
            model_bundle = self._verify_model(snapshot.model)
            assessment = TableAssessment(model_bundle["model"],
                                         verified_identity=snapshot.model)
            self._assessments[key] = assessment
        return assessment

    def register(self, bundle):
        """Accept constructed bundles from the Java registration endpoint."""
        verified = verify_bundle(bundle)
        if verified.data_scope == "CONTROLLED_RESEARCH":
            raise DynamicSnapshotError("CONTROLLED_SNAPSHOT_REQUIRES_HOST_PROVISIONING")
        try:
            self._verify_model(verified.model)
        except ValueError as error:
            raise DynamicSnapshotError(str(error)) from None
        target = self._snapshots.directory / f"{verified.run_id}.json"
        try:
            write_bundle(target, bundle)
        except FileExistsError:
            existing = load_bundle(target)
            if (existing.request_sha256, existing.input_sha256,
                    existing.context_sha256, existing.data_scope, existing.snapshot_id,
                    existing.subject_id) == (verified.request_sha256, verified.input_sha256,
                    verified.context_sha256, verified.data_scope, verified.snapshot_id,
                    verified.subject_id):
                return dict(runId=verified.run_id, snapshotId=verified.snapshot_id,
                            status="SYNCED", idempotent=True)
            raise DynamicSnapshotError("RUN_ID_CONFLICT")
        except OSError:
            raise DynamicSnapshotError("SNAPSHOT_DIRECTORY_UNAVAILABLE") from None
        self._snapshots.reload()
        return dict(runId=verified.run_id, snapshotId=verified.snapshot_id,
                    status="SYNCED", idempotent=False)

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
            assessment = self._assessment(snapshot)
            if snapshot.data_scope not in DATA_SCOPES:
                raise ValueError("DATA_SCOPE_MISMATCH")
            return ResearchTableAgent(
                model_key=snapshot.request["modelKey"],
                subject_code=snapshot.request["subjectCode"],
                assessment=assessment, trusted_context=deepcopy(snapshot.context),
                data_scope=snapshot.data_scope,
                controlled_execution=(None if self._controlled_authorization is None else
                                      self._controlled_authorization.references_for(snapshot)))
        except ResearchAgentError:
            raise
        except Exception:
            raise ResearchAgentError("HOST_BINDING_INVALID", run_id) from None


def main():
    """Local one-command host used by the desktop start script.

    This keeps the research host on loopback, requires the trusted model
    manifest, and serves the frozen snapshot bundles in ``--snapshot-dir``.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=16301)
    parser.add_argument("--manifest", type=Path, required=True,
                        help="Trusted Model_Manifest.json")
    parser.add_argument("--artifact", type=Path,
                        help="final__logistic.joblib (must match the manifest)")
    parser.add_argument("--trusted-manifest-sha256", required=True,
                        help="Independently obtained SHA-256 of the manifest; never derived locally")
    parser.add_argument("--table-model", action="append", nargs=4, default=[],
                        metavar=("SPEC", "FAMILY", "MANIFEST", "SHA256"),
                        help="Explicitly configure an additional pinned table model")
    parser.add_argument("--snapshot-dir", type=Path,
                        default=Path("runtime/dynamic-snapshots"))
    parser.add_argument("--controlled-authorization-file", type=Path,
                        help="Optional operator-managed grant for one frozen controlled run")
    parser.add_argument("--token",
                        help="Bearer token required by the host (or RESEARCH_TABLE_TEST_TOKEN)")
    parser.add_argument("--allow-public", action="store_true",
                        help="DANGER: allow binding to a non-loopback address")
    args = parser.parse_args()

    manifest = args.manifest.resolve()
    if not manifest.is_file():
        parser.exit(2, f"manifest not found: {manifest}\n")
    trusted = args.trusted_manifest_sha256.strip().lower()
    if len(trusted) != 64 or any(character not in "0123456789abcdef" for character in trusted):
        parser.exit(2, "trusted manifest SHA-256 must be 64 lowercase hex characters\n")
    if sha256(manifest.read_bytes()).hexdigest() != trusted:
        parser.exit(2, "manifest SHA-256 does not match --trusted-manifest-sha256\n")
    if args.artifact is not None:
        artifact = args.artifact.resolve()
        if not artifact.is_file():
            parser.exit(2, f"artifact not found: {artifact}\n")
    host_address = args.host.strip()
    loopback = host_address in {"127.0.0.1", "localhost", "::1"}
    if not loopback and not args.allow_public:
        parser.exit(2, "refusing to bind a non-loopback address without --allow-public\n")
    snapshot_dir = args.snapshot_dir.resolve()
    snapshot_dir.mkdir(parents=True, exist_ok=True)
    token = (args.token or os.environ.get("RESEARCH_TABLE_TEST_TOKEN") or "").strip()
    if not token:
        parser.exit(2, "a Bearer token is required (--token or RESEARCH_TABLE_TEST_TOKEN)\n")

    try:
        host = DynamicResearchHost(manifest, trusted, snapshot_dir, additional_models=args.table_model,
                                   controlled_authorization_file=args.controlled_authorization_file)
    except (ValueError, OSError, KeyError, TypeError) as error:
        parser.exit(1, f"research host configuration unavailable: {error}\n")

    from research_runtime.examples.research_table_http import handler
    server = HTTPServer((args.host, args.port), handler(host.resolve, ("Bearer " + token).encode(), host.register,
                                                       host.model_status))
    handshake = snapshot_dir.parent / "dynamic-host-handshake.json"
    if handshake.exists():
        handshake.unlink()
    handshake.parent.mkdir(parents=True, exist_ok=True)
    with handshake.open("x", encoding="utf-8") as stream:
        json.dump(dict(host=args.host, port=server.server_port, mode="dynamic",
                       snapshots=host.describe(), handshake=str(handshake)), stream,
                  ensure_ascii=False, indent=2)
    print(json.dumps(dict(mode="dynamic", host=args.host, port=server.server_port,
                          snapshots=host.describe(), handshake=str(handshake)),
                     ensure_ascii=False), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
