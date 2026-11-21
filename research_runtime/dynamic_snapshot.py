"""Host-owned snapshot bundles for dynamic research table runs.

A bundle freezes the exact wire request, trusted admission context and model
identity that a runId is allowed to execute.  The host never accepts a model
path, a context, feature values or a model identity from the request itself.
"""
from dataclasses import dataclass
from hashlib import sha256
import json
import os
from pathlib import Path
from tempfile import NamedTemporaryFile

from python_service.deepagent_contract import RUN_ID_RE
from .input_admission import (AdmissionError, CONTEXT_SCHEMA, SCHEMA,
    _context, _snapshot, _token, validate_snapshot)
from .research_table_protocol import CONTRACT, REQUEST_FIELDS, digest


BUNDLE_SCHEMA = "research-dynamic-snapshot-v1"
BUNDLE_FIELDS = {"schemaVersion", "runId", "request", "model", "context",
                 "requestSha256", "inputSha256", "contextSha256"}
OPTIONAL_BUNDLE_FIELDS = {"snapshotId", "subjectId", "dataScope", "createdAt"}
DATA_SCOPES = ("CONSTRUCTED_VALIDATION", "CONTROLLED_RESEARCH")
MAXIMUM_BUNDLE_BYTES = 2 * 1024 * 1024


class DynamicSnapshotError(ValueError):
    """A bundle cannot be trusted; the message is a stable local code."""


def _fail(code):
    raise DynamicSnapshotError(code) from None


def _unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            _fail("BUNDLE_DUPLICATE_KEY")
        result[key] = value
    return result


def _reject_constant(_):
    _fail("BUNDLE_NON_FINITE_NUMBER")


def admission_core(request, model):
    """The canonical admission core used by Java and by the Python agent."""
    return dict(schemaVersion=SCHEMA, purpose="OFFLINE_RESEARCH_ADMISSION",
                dataVersion=request["dataVersion"], indexDate=request["indexDate"],
                availabilityPolicy=request["availabilityPolicy"],
                predictionWindowMonths=request["predictionWindowMonths"],
                model=model,
                records=[dict(subjectCode=request["subjectCode"],
                              features=request["features"])])


def _request(request):
    if type(request) is not dict or set(request) != REQUEST_FIELDS:
        _fail("BUNDLE_REQUEST_FIELDS")
    if request.get("contractVersion") != CONTRACT:
        _fail("BUNDLE_CONTRACT")
    run_id = request.get("runId")
    if type(run_id) is not str or not RUN_ID_RE.fullmatch(run_id):
        _fail("BUNDLE_RUN_ID")
    if not _token(request.get("modelKey")) or not _token(request.get("subjectCode")):
        _fail("BUNDLE_BINDING_TOKEN")
    if request.get("predictionWindowMonths") != 24:
        _fail("BUNDLE_WINDOW")
    return request


def verify_bundle(bundle, *, path=None):
    """Validate and freeze one bundle; never consult the request at runtime."""
    where = str(path) if path is not None else "<bundle>"
    try:
        if type(bundle) is not dict:
            _fail("BUNDLE_FORMAT")
        if not BUNDLE_FIELDS <= set(bundle) or set(bundle) - BUNDLE_FIELDS - OPTIONAL_BUNDLE_FIELDS:
            _fail("BUNDLE_FIELDS")
        if bundle["schemaVersion"] != BUNDLE_SCHEMA:
            _fail("BUNDLE_SCHEMA")
        request = _request(bundle["request"])
        run_id = bundle["runId"]
        if type(run_id) is not str or not RUN_ID_RE.fullmatch(run_id) or request["runId"] != run_id:
            _fail("BUNDLE_RUN_ID")
        context = _snapshot(bundle["context"], "CONTEXT_FORMAT")
        _context(context)
        if context["schemaVersion"] != CONTEXT_SCHEMA:
            _fail("BUNDLE_CONTEXT_SCHEMA")
        model = _snapshot(bundle["model"], "MODEL_FORMAT")
        if model != context["model"]:
            _fail("BUNDLE_MODEL_CONTEXT_MISMATCH")
        for key in ("dataVersion", "indexDate", "availabilityPolicy"):
            if request[key] != context[key]:
                _fail("BUNDLE_REQUEST_CONTEXT_MISMATCH")
        if model["family"] not in ("logistic", "random_forest", "xgboost"):
            _fail("BUNDLE_MODEL")
        core = admission_core(request, model)
        try:
            validate_snapshot(core, trusted_context=context)
        except AdmissionError:
            _fail("BUNDLE_SNAPSHOT_INVALID")
        if digest(request) != bundle["requestSha256"]:
            _fail("BUNDLE_REQUEST_DIGEST")
        if digest(core) != bundle["inputSha256"]:
            _fail("BUNDLE_INPUT_DIGEST")
        if digest(context) != bundle["contextSha256"]:
            _fail("BUNDLE_CONTEXT_DIGEST")
        scope = bundle.get("dataScope", "CONSTRUCTED_VALIDATION")
        if scope not in DATA_SCOPES:
            _fail("BUNDLE_DATA_SCOPE")
        snapshot_id = bundle.get("snapshotId")
        if snapshot_id is not None and (type(snapshot_id) is not int or snapshot_id <= 0):
            _fail("BUNDLE_SNAPSHOT_ID")
        subject_id = bundle.get("subjectId")
        if subject_id is not None and (type(subject_id) is not int or subject_id <= 0):
            _fail("BUNDLE_SUBJECT_ID")
        created_at = bundle.get("createdAt")
        if created_at is not None and type(created_at) is not str:
            _fail("BUNDLE_CREATED_AT")
    except DynamicSnapshotError as error:
        raise DynamicSnapshotError(f"{where}: {error}") from None
    except (KeyError, TypeError, ValueError) as error:
        raise DynamicSnapshotError(f"{where}: BUNDLE_FORMAT") from None
    return SnapshotBundle(
        run_id=run_id, request=request, model=model, context=context,
        data_scope=scope, request_sha256=bundle["requestSha256"],
        input_sha256=bundle["inputSha256"], context_sha256=bundle["contextSha256"],
        snapshot_id=snapshot_id, subject_id=subject_id, path=path)


@dataclass(frozen=True)
class SnapshotBundle:
    run_id: str
    request: dict
    model: dict
    context: dict
    data_scope: str
    request_sha256: str
    input_sha256: str
    context_sha256: str
    snapshot_id: int | None = None
    subject_id: int | None = None
    path: Path | None = None


def bundle_from_parts(*, run_id, request, model, context, snapshot_id=None,
                      subject_id=None, data_scope="CONSTRUCTED_VALIDATION",
                      created_at=None):
    """Build a bundle with the contract's canonical digests, then verify it."""
    request = _snapshot(request, "REQUEST_FORMAT")
    model = _snapshot(model, "MODEL_FORMAT")
    context = _snapshot(context, "CONTEXT_FORMAT")
    request["runId"] = run_id
    bundle = dict(schemaVersion=BUNDLE_SCHEMA, runId=run_id, request=request,
                  model=model, context=context,
                  requestSha256=digest(request),
                  inputSha256=digest(admission_core(request, model)),
                  contextSha256=digest(context), dataScope=data_scope)
    if snapshot_id is not None:
        bundle["snapshotId"] = snapshot_id
    if subject_id is not None:
        bundle["subjectId"] = subject_id
    if created_at is not None:
        bundle["createdAt"] = created_at
    verify_bundle(bundle)
    return bundle


def load_bundle(path):
    path = Path(path)
    try:
        with path.open("rb") as stream:
            raw = stream.read(MAXIMUM_BUNDLE_BYTES + 1)
    except OSError:
        _fail("SNAPSHOT_DIRECTORY_UNAVAILABLE")
    if len(raw) > MAXIMUM_BUNDLE_BYTES:
        raise DynamicSnapshotError(f"{path.name}: BUNDLE_TOO_LARGE")
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_pairs,
                           parse_constant=_reject_constant)
    except DynamicSnapshotError as error:
        raise DynamicSnapshotError(f"{path.name}: {error}") from None
    except (UnicodeError, ValueError, RecursionError):
        raise DynamicSnapshotError(f"{path.name}: BUNDLE_JSON_INVALID") from None
    return verify_bundle(value, path=path)


def write_bundle(path, bundle, *, replace=False):
    """Publish a verified bundle atomically, preserving existing bindings by default."""
    verify_bundle(bundle)
    raw = (json.dumps(bundle, ensure_ascii=False, allow_nan=False, sort_keys=True,
                      separators=(",", ":")) + "\n").encode("utf-8")
    if len(raw) > MAXIMUM_BUNDLE_BYTES:
        _fail("BUNDLE_TOO_LARGE")
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with NamedTemporaryFile(dir=path.parent, suffix=".tmp", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(raw)
        if replace:
            temporary.replace(path)
        else:
            os.link(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


class SnapshotDirectory:
    """Read-only directory of bundles, keyed by the host-owned runId."""

    def __init__(self, directory):
        self.directory = Path(directory).resolve(strict=True)
        if not self.directory.is_dir():
            raise DynamicSnapshotError("SNAPSHOT_DIRECTORY_INVALID")
        self._bundles = {}
        self._signature = None
        self.reload()

    def _scan(self):
        try:
            return tuple((item.name, item.stat().st_size, item.stat().st_mtime_ns)
                         for item in sorted(self.directory.glob("*.json")))
        except OSError:
            raise DynamicSnapshotError("SNAPSHOT_DIRECTORY_UNAVAILABLE") from None

    def reload(self):
        signature = self._scan()
        bundles = {}
        for item in sorted(self.directory.glob("*.json")):
            bundle = load_bundle(item)
            if bundle.run_id in bundles:
                raise DynamicSnapshotError(f"DUPLICATE_RUN_ID {bundle.run_id}")
            bundles[bundle.run_id] = bundle
        self._bundles = bundles
        self._signature = signature
        return self

    def resolve(self, run_id):
        if self._scan() != self._signature:
            self.reload()
        return self._bundles.get(run_id)

    @property
    def run_ids(self):
        if self._scan() != self._signature:
            self.reload()
        return tuple(sorted(self._bundles))
