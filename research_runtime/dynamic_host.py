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
from hashlib import sha256
from http.server import HTTPServer
from pathlib import Path

from .dynamic_snapshot import (DATA_SCOPES, SnapshotDirectory,
                               DynamicSnapshotError, load_bundle, verify_bundle,
                               write_bundle)
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

    def register(self, bundle):
        """Validate and persist one host-owned bundle; idempotent by runId."""
        verified = verify_bundle(bundle)
        try:
            self._verify_model(verified.model)
        except ValueError as error:
            raise DynamicSnapshotError(str(error)) from None
        target = self._snapshots.directory / f"{verified.run_id}.json"
        if target.exists():
            existing = load_bundle(target)
            if (existing.request_sha256, existing.input_sha256,
                    existing.context_sha256) == (verified.request_sha256,
                                                 verified.input_sha256,
                                                 verified.context_sha256):
                return dict(runId=verified.run_id, snapshotId=verified.snapshot_id,
                            status="SYNCED", idempotent=True)
            raise DynamicSnapshotError("RUN_ID_CONFLICT")
        write_bundle(target, bundle)
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
    parser.add_argument("--snapshot-dir", type=Path,
                        default=Path("runtime/dynamic-snapshots"))
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
        host = DynamicResearchHost(manifest, trusted, snapshot_dir)
    except (ValueError, OSError, KeyError, TypeError) as error:
        parser.exit(1, f"research host configuration unavailable: {error}\n")

    from research_runtime.examples.research_table_http import handler
    server = HTTPServer((args.host, args.port), handler(host.resolve, ("Bearer " + token).encode(), host.register))
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
