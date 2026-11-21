"""One-shot in-memory research dispatch; no readers, loaders, fit or persistence.

Callers independently control source evidence, artifact verification and access
authorization. This module checks agreement with those inputs; it supplies no
proof of their authenticity or authority. Acceptance so far is constructed only.
"""
from copy import deepcopy
import hashlib
import hmac
import json
import math
from threading import Lock, RLock

from .input_admission import validate_snapshot, _snapshot, _model, _token, _sha


SCHEMA = 'research-inmemory-inference-v1'
PREDICTOR_SCHEMA = 'research-inmemory-predictor-v1'


class InferenceError(ValueError):
    """Fixed error code; never quote input values or callback exception text."""


def _fail(code):
    raise InferenceError(code) from None


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'),
                      ensure_ascii=False, allow_nan=False).encode('utf-8')


def _binding(value):
    # Ephemeral capability binding, not returned, logged or persisted.
    return hashlib.sha256(_canonical(value)).hexdigest()


class _OneShotPermit:
    __slots__ = ('_request', '_context', '_lock', '_state', '_calls')

    def __init__(self, request, context):
        self._request = _binding(request)
        self._context = _binding(context)
        self._lock = Lock()
        self._state = 'READY'
        self._calls = 0

    @property
    def state(self):
        with self._lock:
            return self._state

    @property
    def prediction_calls(self):
        with self._lock:
            return self._calls

    def _mark_call(self):
        with self._lock:
            if self._state != 'CLAIMED' or self._calls:
                _fail('PERMIT_CALL_BUDGET')
            self._calls = 1

    def _claim(self, request, context):
        with self._lock:
            if self._state != 'READY':
                _fail('PERMIT_ALREADY_USED')
            if (not hmac.compare_digest(self._request, _binding(request))
                    or not hmac.compare_digest(self._context, _binding(context))):
                _fail('PERMIT_SNAPSHOT_MISMATCH')
            self._state = 'CLAIMED'

    def _finish(self, success):
        with self._lock:
            self._state = 'COMPLETED' if success else 'FAILED'

    def __reduce_ex__(self, protocol):
        _fail('IN_MEMORY_CAPABILITY_ONLY')

    def __repr__(self):
        return '<ResearchPermit state='+self.state+'>'


def authorize_once(request, *, trusted_context, authorization_ref,
                   authorization_granted=False):
    """Bind an independently authorized local call to one exact snapshot.

    The trusted host, not request JSON or metadata agreement, grants this. A
    true flag/reference is the host's assertion, not an authorization service.
    No reference or personal digest is retained in returned results.
    """
    if authorization_granted is not True or not _token(authorization_ref):
        _fail('INDEPENDENT_EXECUTION_PERMISSION_REQUIRED')
    request = _snapshot(request, 'REQUEST_FORMAT')
    context = _snapshot(trusted_context, 'CONTEXT_FORMAT')
    report = validate_snapshot(request, trusted_context=context)
    if report['status'] != 'METADATA_CONSISTENT':
        _fail('ADMISSION_BLOCKED')
    return _OneShotPermit(request, context)


class BoundPredictor:
    """Caller-verified in-memory predictor with an independently pinned identity.

    ``state_token`` must fingerprint all relevant parameters and preprocessing.
    A constant/incorrect token cannot prove unchanged model state. Neither this
    class nor its callbacks constitute a sandbox or an artifact authenticity check.
    """
    __slots__ = ('_identity', '_predict', '_state_token', '_baseline', '_lock')

    def __init__(self, identity, *, predict, state_token):
        identity = _snapshot(identity, 'REQUEST_FORMAT')
        _model(identity)
        if not callable(predict) or not callable(state_token):
            _fail('PREDICTOR_CALLBACK_REQUIRED')
        self._identity = _canonical(identity)
        self._predict = predict
        self._state_token = state_token
        self._lock = RLock()
        self._baseline = self._state()

    @property
    def identity(self):
        return json.loads(self._identity)

    def _state(self):
        try:
            value = self._state_token()
        except Exception:
            _fail('PREDICTOR_STATE_CHECK_FAILED')
        if not _sha(value):
            _fail('PREDICTOR_STATE_TOKEN_REQUIRED')
        return value

    def __reduce_ex__(self, protocol):
        _fail('IN_MEMORY_CAPABILITY_ONLY')

    def __repr__(self):
        return '<BoundResearchPredictor>'


def _checked_result(value, identity, rows):
    try:
        result = _snapshot(value, 'REQUEST_FORMAT')
    except Exception:
        _fail('PREDICTOR_RESULT_FORMAT')
    if set(result) != {'schemaVersion', 'model', 'predictionWindowMonths', 'predictionPolicy', 'records'}:
        _fail('PREDICTOR_RESULT_FIELDS')
    if (result['schemaVersion'] != PREDICTOR_SCHEMA or result['model'] != identity
            or type(result['predictionWindowMonths']) is not int or result['predictionWindowMonths'] != 24
            or result['predictionPolicy'] != identity['predictionPolicy']):
        _fail('PREDICTOR_RESULT_IDENTITY')
    predictions = result['records']
    if type(predictions) is not list or len(predictions) != len(rows):
        _fail('PREDICTOR_RESULT_COUNT')
    for prediction, row in zip(predictions, rows):
        if type(prediction) is not dict or set(prediction) != {'subject_id', 'ad_cif_24m', 'other_dementia_cif_24m'}:
            _fail('PREDICTOR_RECORD_FIELDS')
        if prediction['subject_id'] != row['subject_id']:
            _fail('PREDICTOR_SUBJECT_ORDER')
        values = [prediction['ad_cif_24m'], prediction['other_dementia_cif_24m']]
        try:
            valid = all(type(v) in (int, float) and math.isfinite(v) and 0 <= v <= 1 for v in values)
        except OverflowError:
            valid = False
        if not valid or sum(values) > 1+1e-12:
            _fail('INVALID_TWO_CAUSE_CIF')
    return predictions


def run_in_memory(request, *, trusted_context, predictor=None, permit=None):
    """Validate and dispatch once; return no scores for blocked/unpermitted calls.

    Success returns individual predictions only in memory to the authorized host.
    Do not log or persist them without separately applicable authorization.
    Metadata report predictionAllowed stays false; dispatch uses its own permit.
    """
    request = _snapshot(request, 'REQUEST_FORMAT')
    context = _snapshot(trusted_context, 'CONTEXT_FORMAT')
    admission = validate_snapshot(request, trusted_context=context)
    result = dict(schemaVersion=SCHEMA, status='BLOCKED', admission=admission,
                  dataVersion=request['dataVersion'], indexDate=request['indexDate'],
                  availabilityPolicy=request['availabilityPolicy'], predictionWindowMonths=24,
                  model=deepcopy(admission['model']), predictionCalls=0, predictions=None,
                  executionPermissionUsed=False, clinicalUse=False,
                  sourceAuthenticityCertified=False, historicalAvailabilityCertified=False,
                  individualCausalEffectIdentified=False, defaultModelChanged=False)
    if admission['status'] != 'METADATA_CONSISTENT':
        return result
    if permit is None:
        result['status'] = 'PERMISSION_REQUIRED'
        return result
    if type(permit) is not _OneShotPermit:
        _fail('IN_MEMORY_PERMIT_REQUIRED')
    permit._claim(request, context)
    success = False
    try:
        if type(predictor) is not BoundPredictor:
            _fail('BOUND_PREDICTOR_REQUIRED')
        with predictor._lock:
            if predictor._identity != _canonical(admission['model']):
                _fail('PREDICTOR_MODEL_IDENTITY')
            if predictor._state() != predictor._baseline:
                _fail('PREDICTOR_STATE_CHANGED')
            rows = [{'subject_id': row['subjectCode'], **{
                name: row['features'][name]['value'] for name in admission['featureOrder']}}
                for row in request['records']]
            before = _canonical(rows)
            permit._mark_call()
            try:
                raw = predictor._predict(rows)
            except Exception:
                _fail('PREDICTOR_CALL_FAILED')
            try:
                same = _canonical(rows) == before
            except (ValueError, TypeError, OverflowError, RecursionError):
                same = False
            if not same:
                _fail('PREDICTOR_MUTATED_INPUT')
            if predictor._state() != predictor._baseline:
                _fail('PREDICTOR_STATE_CHANGED')
            predictions = _checked_result(raw, admission['model'], rows)
            result.update(status='COMPLETED', predictionCalls=1,
                          predictions=predictions, executionPermissionUsed=True)
        success = True
        return result
    finally:
        permit._finish(success)


def bind_existing_model(model, *, verified_identity, state_token):
    """Bridge an already independently verified model to original predict_cif.

    No archive/manifest is opened and no model is fitted. This does not verify
    the given model against artifact hashes; the caller must do that beforehand.
    """
    identity = _snapshot(verified_identity, 'REQUEST_FORMAT')
    _model(identity)

    def predict(rows):
        from types import SimpleNamespace
        import numpy as np
        import pandas as pd
        from threadpoolctl import threadpool_limits
        from .discrete import predict_cif
        columns = identity['features']
        frame = pd.DataFrame(rows, columns=['subject_id', *columns])
        for row in rows:
            for name in columns:
                value = row[name]
                if type(value) is int and int(float(value)) != value:
                    _fail('NUMERIC_REPRESENTATION_LOSS')
        frame = frame.astype({name: 'float64' for name in columns})
        try:
            labels = np.asarray(model.named_steps['hazards'].classes_)
        except Exception:
            _fail('THREE_CAUSE_CLASSES_REQUIRED')
        if (labels.dtype.kind not in 'iu' or labels.shape != (3,)
                or sorted(labels.tolist()) != [0, 1, 2]):
            _fail('THREE_CAUSE_CLASSES_REQUIRED')

        class CheckedModel:
            named_steps = {'hazards': SimpleNamespace(classes_=labels.copy())}

            def predict_proba(self, grid):
                before = grid.copy(deep=True)
                values = np.asarray(model.predict_proba(grid))
                if not grid.equals(before):
                    _fail('PREDICTOR_MUTATED_GRID')
                if (values.dtype.kind not in 'fi' or values.shape != (len(grid), 3)
                        or not np.isfinite(values).all() or (values < 0).any() or (values > 1).any()):
                    _fail('INVALID_THREE_CAUSE_HAZARDS')
                # XGBoost returns float32 probabilities. Check row sums at the
                # estimator precision and preserve its raw outputs for CIF and attribution.
                tolerance = max(1e-12, 4 * np.finfo(values.dtype).eps) if values.dtype.kind == 'f' else 1e-12
                if not np.allclose(values.sum(1, dtype=np.float64), 1., atol=tolerance, rtol=0):
                    _fail('INVALID_THREE_CAUSE_HAZARDS')
                return values

        with threadpool_limits(limits=1):
            output = predict_cif(CheckedModel(), frame, list(columns))
        return dict(schemaVersion=PREDICTOR_SCHEMA, model=deepcopy(identity),
                    predictionWindowMonths=24, predictionPolicy=identity['predictionPolicy'],
                    records=output.to_dict(orient='records'))

    return BoundPredictor(identity, predict=predict, state_token=state_token)
